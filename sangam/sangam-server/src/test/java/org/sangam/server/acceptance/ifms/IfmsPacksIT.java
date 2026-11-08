// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.ifms;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.history.HistoricVariableInstance;
import org.operaton.bpm.engine.runtime.Job;
import org.operaton.bpm.engine.task.IdentityLink;
import org.operaton.bpm.engine.task.Task;
import org.sangam.core.layer.AuditChain;
import org.sangam.server.acceptance.AbstractAcceptanceIT;
import org.sangam.server.bridge.PacksTenantDeployer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * IFMS's first workflows (SD-434) as the generic packs of {@code sangam-packs}, deployed for a fresh tenant through
 * the product pack deployer and run on the Tier-1 database: budget formulation, distribution and re-appropriation
 * chains, and the ESS requests GPF, leave and pension. Each case is driven like a user would: every open task is
 * answered by a distinct person holding the task's seat, with the approving decision of the generic core blocks.
 * Proof per case: it ends CLOSED with no incident, the approval chain ran (each step a different person), and the
 * pack audit trail has the case's records. This class knows no state or department: the domain lives in the packs.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IfmsPacksIT extends AbstractAcceptanceIT {

    static final String TENANT = "accifms" + UUID.randomUUID().toString().substring(0, 6);

    static final Set<String> PACKS = Set.of("sangam.core", "gov.ifms.budget-formulation", "gov.ifms.budget-allocation",
            "gov.ifms.reappropriation", "gov.ifms.gpf", "gov.ifms.bill", "gov.ifms.pao-chain", "gov.leave", "gov.pension");

    @Autowired
    ProcessEngine engine;
    @Autowired
    PacksTenantDeployer packs;
    @Autowired
    DataSource dataSource;
    @Autowired
    ObjectProvider<AuditChain> auditChain;

    @BeforeAll
    void deployPacks() {
        packs.select(TENANT, PACKS);
        Map<String, String> deployed = packs.deploy(TENANT);
        System.out.println("[acceptance] ifms packs deployed for " + TENANT + ": " + deployed.keySet());
        assertThat(deployed).isNotEmpty();
    }

    // ------------------------------------------------------------------ driver

    /** Approving answers of the generic core blocks and the IFMS pack tasks (the packs' own vocabulary). */
    static final Map<String, Object> APPROVE = Map.of(
            "scrutinyDecision", "accept", "sanctionDecision", "approve", "recDecision", "recommend",
            "faDecision", "faConcur", "referralDecision", "faConcur", "objDecision", "accept",
            "serviceVerdict", "verified", "lifeDecision", "proceed", "remarks", "Seen; in order.");

    record Run(String pid, List<String> steps, List<String> persons, Map<String, Object> finalVars) {
    }

    private String start(String key, String bk, Map<String, Object> vars) {
        return engine.getRuntimeService().createProcessInstanceByKey(key).businessKey(bk).processDefinitionTenantId(TENANT)
                .setVariables(vars).execute().getId();
    }

    private Run drive(String key, String bk, Map<String, Object> vars) throws Exception {
        String pid = start(key, bk, vars);
        List<String> steps = new ArrayList<>();
        List<String> persons = new ArrayList<>();
        long end = System.currentTimeMillis() + 240_000;
        int idle = 0;
        while (System.currentTimeMillis() < end) {
            if (engine.getHistoryService().createHistoricProcessInstanceQuery().processInstanceId(pid).singleResult()
                    .getEndTime() != null) {
                break;
            }
            boolean moved = runJobs(bk);
            List<Task> tasks = engine.getTaskService().createTaskQuery().tenantIdIn(TENANT)
                    .processInstanceBusinessKeyLike(bk + "%").list();
            for (Task t : tasks) {
                String seat = engine.getTaskService().getIdentityLinksForTask(t.getId()).stream()
                        .map(IdentityLink::getGroupId).filter(java.util.Objects::nonNull).findFirst().orElse("none");
                String person = "acc.ifms." + t.getTaskDefinitionKey() + "." + steps.size();
                Map<String, Object> local = new HashMap<>();
                local.put("actingPerson", person);
                local.put("actingSeat", seat);
                engine.getTaskService().setVariablesLocal(t.getId(), local);
                engine.getTaskService().complete(t.getId(), new HashMap<>(APPROVE));
                steps.add(t.getTaskDefinitionKey());
                persons.add(person);
                moved = true;
            }
            if (!moved) {
                if (++idle > 120) {
                    break;
                }
                Thread.sleep(500);
            } else {
                idle = 0;
            }
        }
        Map<String, Object> fin = new LinkedHashMap<>();
        for (HistoricVariableInstance v : engine.getHistoryService().createHistoricVariableInstanceQuery()
                .processInstanceId(pid).list()) {
            fin.put(v.getName(), v.getValue());
        }
        return new Run(pid, steps, persons, fin);
    }

    private boolean runJobs(String bk) {
        boolean ran = false;
        Date now = new Date();
        for (Job j : engine.getManagementService().createJobQuery().tenantIdIn(TENANT).executable().list()) {
            if (j.getDuedate() != null && j.getDuedate().after(now)) {
                continue;
            }
            try {
                engine.getManagementService().executeJob(j.getId());
                ran = true;
            } catch (RuntimeException e) {
                // the server's job executor may have taken it; a real failure leaves an incident (asserted below)
            }
        }
        return ran;
    }

    private void assertClosed(String what, Run r) {
        String incidents = engine.getRuntimeService().createIncidentQuery().processInstanceId(r.pid()).list().stream()
                .map(i -> i.getActivityId() + ": " + i.getIncidentMessage()).toList().toString();
        String report = "[acceptance] " + what + " steps=" + r.steps() + " caseState=" + r.finalVars().get("caseState")
                + " incidents=" + incidents + " active=" + engine.getRuntimeService().getActiveActivityIds(r.pid());
        boolean ended = engine.getHistoryService().createHistoricProcessInstanceQuery().processInstanceId(r.pid())
                .singleResult().getEndTime() != null;
        System.out.println(report + " ended=" + ended);
        assertThat(ended).as(report).isTrue();
        assertThat(incidents).as(report).isEqualTo("[]");
        assertThat(r.finalVars().get("caseState")).as(report).isEqualTo("CLOSED");
        assertThat(r.steps()).as("a human approval chain ran: " + report).isNotEmpty();
        assertThat(Set.copyOf(r.persons())).as("each step by a different person").hasSize(r.persons().size());
        assertThat(auditRows(r)).as("pack audit trail of the case").isPositive();
    }

    /** Rows of the case in the product audit chain (the packs' {@code Vars.audit} sink), by business key. */
    private long auditRows(Run r) {
        Object bk = engine.getHistoryService().createHistoricProcessInstanceQuery().processInstanceId(r.pid())
                .singleResult().getBusinessKey();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM AUDIT_EVENT WHERE tenant_id = ? AND chain_id = ?")) {
            ps.setString(1, TENANT);
            ps.setString(2, String.valueOf(bk));
            long n;
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                n = rs.getLong(1);
            }
            AuditChain ac = auditChain.getIfAvailable();
            if (ac != null && n > 0) {
                AuditChain.VerifyResult v = ac.verify(c, TENANT, String.valueOf(bk));
                assertThat(v.ok()).as("audit chain of " + bk + " verifies: " + v.reason()).isTrue();
            }
            return n;
        } catch (Exception e) {
            throw new AssertionError("audit query failed: " + e.getMessage(), e);
        }
    }

    private static String bk(String p) {
        return p + "/" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static Date date(int plusDays) {
        return Date.from(LocalDate.now().plusDays(plusDays).atStartOfDay(ZoneId.of("UTC")).toInstant());
    }

    // ------------------------------------------------------------------ 1. budget

    @Test
    void budgetFormulation() throws Exception {
        Run r = drive("sangam-ifms-budget-formulation", bk("BF"), new HashMap<>(Map.of("fiscalYear", "2027-28",
                "headOfAccount", "2052-00-090-01", "beAmount", 12_500_000L, "reAmount", 12_000_000L,
                "justification", "Establishment estimates", "initiator", "acc.ifms.maker", "actingSeat", "DDO-1")));
        assertClosed("budget formulation", r);
    }

    @Test
    void budgetDistribution() throws Exception {
        Run r = drive("sangam-ifms-budget-allocation", bk("BA"), new HashMap<>(Map.of("fiscalYear", "2026-27",
                "stage", "RELEASE", "headOfAccount", "2052-00-090-01", "holder", "DDO-1", "amount", 2_500_000L,
                "initiator", "acc.ifms.maker", "actingSeat", "DDO-1")));
        assertClosed("budget distribution", r);
    }

    @Test
    void reappropriation() throws Exception {
        Run r = drive("sangam-ifms-reappropriation", bk("RA"), new HashMap<>(Map.of("fiscalYear", "2026-27",
                "fromHead", "2052-00-090-01", "toHead", "2052-00-090-02", "amount", 150_000L, "objectGroup", "OTHER_15CR",
                "justification", "Savings in salaries", "initiator", "acc.ifms.maker", "actingSeat", "DDO-1")));
        assertClosed("re-appropriation", r);
    }

    // ------------------------------------------------------------------ 2. ESS

    @Test
    void gpfAdvance() throws Exception {
        Run r = drive("sangam-ifms-gpf", bk("GPF"), new HashMap<>(Map.of("gpfAccount", "GPF-123", "gpfPurpose", "ADVANCE",
                "amount", 50_000L, "gpfBalance", 400_000L, "qualifyingService", 12.0, "employeeId", "E-1001",
                "initiator", "acc.ifms.employee", "actingSeat", "DDO-1")));
        assertClosed("GPF advance", r);
    }

    @Test
    void leave() throws Exception {
        Run r = drive("sangam-leave", bk("LV"), new HashMap<>(Map.of("employeeId", "E-1001", "leaveType", "EL",
                "leaveFrom", date(1), "leaveTo", date(3), "leaveDays", 3, "initiator", "acc.ifms.employee",
                "actingSeat", "DDO-1")));
        assertClosed("leave", r);
    }

    @Test
    void pensionSanctionAndStatus() throws Exception {
        Run r = drive("sangam-pension-sanction", bk("PEN"), new HashMap<>(Map.of("employeeId", "E-1001",
                "employeeName", "A Pensioner", "retirementDate", date(60), "qualifyingService", 33.0, "lastPay", 9_000_000L,
                "bankAccount", "XXXX5678", "ifsc", "SBIN0000001", "initiator", "acc.ifms.employee", "actingSeat", "DDO-1")));
        assertClosed("pension sanction", r);
    }
}
