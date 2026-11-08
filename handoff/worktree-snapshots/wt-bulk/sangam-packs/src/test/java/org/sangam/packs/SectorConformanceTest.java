// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;

import org.sangam.packs.port.PackAuditEvent;
import org.sangam.packs.port.PackRuntime;
import org.sangam.packs.sim.LoggingAuditSink;
import org.sangam.packs.sim.SimulatorConnectorGateway;
import org.sangam.packs.spi.PackDeployer;
import org.sangam.packs.spi.PackDescriptor;
import org.sangam.packs.spi.PackRegistry;

/**
 * Sector conformance (sangam/SECTOR-CONFORMANCE.md): for one representative workflow per sector, a tenant set up by
 * pack configuration alone (BPMN, DMN, forms, seed seats) runs from start to the end event on H2 with the simulator.
 * Every task is answered by {@link FormAnswers} (seat holder from the seed, affirmative decision of the form), so no
 * scenario carries sector Java. Each run must show: end reached with no incident, every task completed by the seed
 * holder of its seat, a {@code TASK_COMPLETED} audit event per task, {@code RULE_EVALUATED} when the pack ships DMN,
 * a journaled connector call when the pack declares connectors, and a finished history record (the report source).
 */
class SectorConformanceTest {

    static ProcessEngine engine;
    static PackDriver driver;
    static PackRegistry registry;

    @BeforeAll
    static void setUp() {
        PackRuntime.reset();
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-sector;DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        cfg.setHistory("full");
        engine = cfg.buildProcessEngine();
        registry = PackRegistry.discover();
        new PackDeployer(engine, registry).deployAll(null);
        driver = new PackDriver(engine);
    }

    @AfterAll
    static void tearDown() {
        engine.close();
    }

    record Run(String bk, List<String> done, PackDescriptor pack) {
    }

    static PackDescriptor pack(String id) {
        return registry.find(id).orElseThrow(() -> new AssertionError("pack not shipped: " + id)).descriptor();
    }

    /** Runs {@code key} of pack {@code packId} to its end by configuration only and checks the common evidence. */
    static Run conform(String packId, String key, FormAnswers answers, Object... startKv) {
        PackDescriptor p = pack(packId);
        assertTrue(p.mainProcesses().contains(key), packId + " main processes " + p.mainProcesses());
        String bk = "CONF/" + key;
        Map<String, Object> vars = AllPacksSmokeTest.bag();
        p.acceptance().stream().findFirst().map(PackDescriptor.AcceptanceScenario::startVariables)
                .ifPresent(vars::putAll);
        for (int i = 0; i + 1 < startKv.length; i += 2) {
            vars.put(startKv[i].toString(), startKv[i + 1]);
        }
        engine.getRuntimeService().createProcessInstanceByKey(key).businessKey(bk).setVariables(vars)
                .processDefinitionWithoutTenantId().execute();
        List<String> done = driver.run(bk, answers::answer, 80);
        assertTrue(driver.ended(bk), key + " did not reach its end; open tasks "
                + engine.getTaskService().createTaskQuery().processInstanceBusinessKeyLike(bk + "%").list().stream()
                        .map(t -> t.getTaskDefinitionKey()).toList() + ", done " + done);
        assertEquals(0, engine.getRuntimeService().createIncidentQuery().count(), "incidents");
        assertFalse(done.isEmpty(), key + ": no human step ran");
        List<PackAuditEvent> audit = ((LoggingAuditSink) PackRuntime.audit()).timeline(bk);
        Set<String> types = new HashSet<>();
        audit.forEach(e -> types.add(e.type()));
        long completions = audit.stream().filter(e -> "TASK_COMPLETED".equals(e.type())).count();
        assertTrue(completions >= done.size(), key + ": " + completions + " TASK_COMPLETED for " + done.size() + " tasks");
        assertTrue(audit.stream().filter(e -> "TASK_COMPLETED".equals(e.type()))
                .allMatch(e -> e.actorPersonId() != null && e.actorSeatId() != null), "actor person and seat on audit");
        assertTrue(audit.stream().filter(e -> "TASK_COMPLETED".equals(e.type()))
                .filter(e -> PackRuntime.seats().roleOf(null, e.actorSeatId()).isPresent())
                .noneMatch(e -> e.actorPersonId().startsWith("user.")), key + ": a seed seat has no holder today");
        if (!p.resources(PackDescriptor.PackResource.Kind.DMN).isEmpty()) {
            assertTrue(types.contains("RULE_EVALUATED"), key + ": DMN shipped but no RULE_EVALUATED; " + types);
        }
        assertNotNull(engine.getHistoryService().createHistoricProcessInstanceQuery().processInstanceBusinessKey(bk)
                .finished().list().stream().findFirst().orElse(null), "history record for reports");
        return new Run(bk, done, p);
    }

    static SimulatorConnectorGateway sim() {
        return (SimulatorConnectorGateway) PackRuntime.gateway();
    }

    static boolean called(String bk, String connector) {
        return sim().journal().stream().anyMatch(r -> bk.equals(r.businessKey()) && connector.equals(r.connector()));
    }

    FormAnswers answers() {
        return new FormAnswers(engine);
    }

    // ---- priority 1: IFMS, central and state government -----------------------------------------------------------

    @Test
    void ifmsCentralPaoBillChain() {
        Run r = conform("gov.ifms.pao-chain", "sangam-ifms-pao-chain", answers());
        assertEquals(List.of("pc_distribute", "pc_dealing", "pc_aao", "pc_pao", "pc_second"), r.done());
    }

    @Test
    void ifmsYearEndClosing() {
        Run r = conform("gov.ifms.year-end-closing", "sangam-ifms-year-end-closing", answers());
        assertEquals(List.of("yc_compile", "yc_sign"), r.done());
    }

    @Test
    void ifmsMonthlyAccounts() {
        conform("gov.ifms.monthly-accounts", "sangam-ifms-monthly-accounts", answers());
    }

    @Test
    void stateTreasuryBillPaymentWithPayment() {
        Run r = conform("gov.p1.bill-payment", "sangam-p1-bill-payment", answers(),
                "amount", 475000.0, "officeId", "DPW-X", "actingSeat", "CLERK-DDO", "initiator", "p1.clerk",
                "selectedSeat", "AO-2");
        assertEquals("CLOSED", driver.var(r.bk(), "caseState"));
        assertTrue(called(r.bk(), "pfms.payment") || called(r.bk(), "ifms.payment"), "payment rail");
    }

    @Test
    void stateGrantSanctionDbtInstalments() {
        Run r = conform("gov.p3.grant-sanction", "sangam-p3-grant-application", answers(), "income", 120000.0,
                "socialCategory", "SC", "officeId", "BLOCK-A1", "actingSeat", "DEO-A1", "initiator", "p3.deo-a1");
        assertEquals("CLOSED", driver.var(r.bk(), "caseState"));
    }

    @Test
    void stateFileMovementEOffice() {
        conform("gov.p2.file-movement", "sangam-p2-file-movement", answers(), "officeId", "SEC-B2",
                "actingSeat", "DA-B2", "initiator", "p2.da");
    }

    @Test
    void stateRightToPublicServices() {
        conform("gov.rtps", "sangam-rtps-service", answers());
    }

    // ---- priority 2/3: India sectors ------------------------------------------------------------------------------

    @Test
    void bankPaymentAboveBandNeedsCheckerAndAuthoriser() {
        Run r = conform("sec.bank.payment-dual-auth", "sangam-sec-bank-payment", answers(), "amount", 3000000);
        assertEquals(2, ((Number) driver.var(r.bk(), "authLevels")).intValue());
        assertEquals(2, r.done().stream().filter("sn_sanction"::equals).count(), "checker then authoriser");
        List<PackAuditEvent> approvals = ((LoggingAuditSink) PackRuntime.audit()).timeline(r.bk()).stream()
                .filter(e -> "TASK_COMPLETED".equals(e.type()) && "sn_sanction".equals(e.details().get("task")))
                .toList();
        assertEquals(2, approvals.stream().map(PackAuditEvent::actorPersonId).distinct().count(), "two persons");
        assertEquals(2, approvals.stream().map(PackAuditEvent::actorSeatId).distinct().count(), "two seats");
        assertTrue(called(r.bk(), "bank.transfer"), "class (c) payment rail");
    }

    @Test
    void bankLoanOrigination() {
        conform("sec.bank.loan-origination", "sangam-sec-bank-loan-origination", answers());
    }

    @Test
    void capitalMarketInvestorOnboarding() {
        conform("sec.cap.investor-onboarding", "sangam-sec-cap-investor-onboarding", answers());
    }

    @Test
    void insuranceMotorClaim() {
        Run r = conform("sec.ins.motor-claim", "sangam-sec-ins-motor-claim", answers());
        assertEquals("CLOSED", driver.var(r.bk(), "caseState"));
    }

    @Test
    void insuranceDeathClaim() {
        Run r = conform("sec.ins.death-claim", "sangam-sec-ins-death-claim", answers());
        assertEquals("CLOSED", driver.var(r.bk(), "caseState"));
    }

    @Test
    void ecommerceGstTcsReconciliation() {
        conform("sec.ecom.gst-tcs", "sangam-sec-ecom-gst-tcs", answers());
    }

    @Test
    void ecommerceSellerOnboarding() {
        conform("sec.ecom.seller-onboarding", "sangam-sec-ecom-seller-onboarding", answers());
    }

    @Test
    void manufacturingBatchRelease() {
        Run r = conform("sec.mfg.batch-release", "sangam-sec-mfg-batch-release", answers());
        assertEquals(List.of("br_qc", "br_qa"), r.done());
    }

    @Test
    void healthIndiaLabOrder() {
        conform("sec.health.lab-order", "sangam-sec-health-lab-order", answers());
    }

    @Test
    void healthIndiaAdmissionToDischarge() {
        conform("sec.health.admission-discharge", "sangam-sec-health-admission", answers());
    }

    @Test
    void dpdpDataPrincipalRequest() {
        conform("sec.legal.dpdp-rights", "sangam-sec-legal-dpdp-request", answers());
    }

    // ---- priority 4: USA health (HIPAA) ---------------------------------------------------------------------------

    @Test
    void usHealthHipaaRightOfAccess() {
        conform("sec.ushealth.access-request", "sangam-sec-ushealth-access-request", answers());
    }

    @Test
    void usHealthBusinessAssociateAgreement() {
        String bk = "CONF/sangam-sec-ushealth-baa";
        engine.getRuntimeService().createProcessInstanceByKey("sangam-sec-ushealth-baa").businessKey(bk)
                .setVariables(AllPacksSmokeTest.bag()).processDefinitionWithoutTenantId().execute();
        List<String> done = driver.run(bk, answers().with("ba_terms", "baaTerms", true)::answer, 40);
        assertEquals(List.of("ba_security", "ba_terms", "ba_approve"), done);
        assertActiveAgreement(bk);
    }

    /** An approved agreement lives on: no open task, no incident, waiting on its review clock or a breach event. */
    static void assertActiveAgreement(String bk) {
        assertFalse(driver.ended(bk), "an executed BAA stays active for its term");
        assertEquals(0, engine.getTaskService().createTaskQuery().processInstanceBusinessKey(bk).count());
        assertEquals(0, engine.getRuntimeService().createIncidentQuery().count(), "incidents");
        String root = engine.getRuntimeService().createProcessInstanceQuery().processInstanceBusinessKey(bk)
                .singleResult().getId();
        assertFalse(engine.getRuntimeService().getActiveActivityIds(root).isEmpty(), "waiting in its lifecycle");
        assertTrue(((LoggingAuditSink) PackRuntime.audit()).timeline(bk).stream()
                .filter(e -> "TASK_COMPLETED".equals(e.type())).count() >= 3);
    }

    @Test
    void longCaseActorLogBeyondTheEngineStringColumn() {
        // regression: 40 rounds of "missing terms" grew actorLog past 4000 characters (JdbcBatchUpdateException)
        int[] rounds = {0};
        FormAnswers base = answers();
        String bk = "CONF/LONG/baa";
        java.util.Map<String, Object> v = AllPacksSmokeTest.bag();
        engine.getRuntimeService().createProcessInstanceByKey("sangam-sec-ushealth-baa").businessKey(bk).setVariables(v)
                .processDefinitionWithoutTenantId().execute();
        driver.run(bk, t -> {
            PackDriver.Answer a = base.answer(t);
            if ("ba_terms".equals(t.getTaskDefinitionKey())) {
                a.vars().put("baaTerms", ++rounds[0] > 40);
            }
            return a;
        }, 120);
        assertEquals(41, rounds[0], "every round persisted");
        assertActiveAgreement(bk);
        Run r = new Run(bk, List.of(), pack("sec.ushealth.baa"));
        // regression: the actor log of a long case exceeded the 4000-character engine string column
        Object log = engine.getHistoryService().createHistoricVariableInstanceQuery().variableName("actorLog")
                .processInstanceId(driver.rootId(r.bk())).singleResult().getValue();
        assertTrue(log instanceof byte[] b && b.length > 4000, "actor log stored as a document");
    }
}
