// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.bpmn;

import org.sangam.core.api.ReadWork;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.engine.runtime.Job;
import org.operaton.bpm.engine.task.Task;
import org.sangam.compliance.ComplianceAudit;
import org.sangam.compliance.ComplianceSchema;
import org.sangam.compliance.SangamCompliance;
import org.sangam.compliance.accountability.ContactDirectory;
import org.sangam.compliance.breach.IncidentReportingService;
import org.sangam.compliance.config.ComplianceConfig;
import org.sangam.compliance.jurisdiction.JurisdictionRegistry;
import org.sangam.compliance.ports.CompliancePorts.IncidentAuthorityGateway;
import org.sangam.compliance.ports.CompliancePorts.WorkflowStarter;
import org.sangam.compliance.support.Sql;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;

/**
 * SANGAM.md OJ-06 step 8, coordinator ruling 2026-11-13, on the shipped model: the generated
 * {@code packs/compliance/sangam-incident-report.bpmn} runs in an engine against the compliance services on H2.
 * Completing the follow-up task {@code u_follow} towards closing without the root cause and the lessons learned is
 * refused at completion with {@code 422 INCIDENT_CLOSURE_INCOMPLETE}: the task stays open, the incident stays reported,
 * and no job (so no failed job) is created for the close step. With both, the incident closes and
 * {@code SEC_INCIDENT_CLOSED} carries them. Choosing a follow-up needs neither.
 */
class IncidentClosureProcessH2Test {

    static final Dialect D = Dialect.POSTGRESQL;
    static final String TENANT = "t1";
    static final String PROCESS = "sangam-incident-report";
    static final String ROOT_CAUSE = "unpatched web framework on the portal";
    static final String LESSONS = "monthly patch window; WAF rule review";

    String url;
    Connection keepAlive;
    ProcessEngine engine;
    SangamCompliance cmp;

    @BeforeEach
    void setUp() throws Exception {
        url = "jdbc:h2:mem:incclose" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        keepAlive = DriverManager.getConnection(url, "sa", "");
        Schema.create(keepAlive, D);
        ComplianceSchema.create(keepAlive, D);

        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:inccloseengine" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        cfg.setHistory("full");
        engine = cfg.buildProcessEngine();
        // the shipped, generated model, exactly as the pack deploys it
        engine.getRepositoryService().createDeployment().tenantId(TENANT)
                .addClasspathResource("packs/compliance/" + PROCESS + ".bpmn").deploy();

        Map<String, String> props = new HashMap<>();
        props.put("jurisdiction", "IN");
        props.put("organisation-name", "Test Department");
        props.put("seats.CISO", "SEAT-CISO");
        props.put("seats.CISO-HEAD", "SEAT-HEAD");
        ComplianceConfig.Provider config = t -> ComplianceConfig.fromProperties(JurisdictionRegistry.discover(), props);
        IncidentAuthorityGateway gateway = s -> "ACK-" + s.incidentId() + "-" + s.recipient();
        WorkflowStarter starter = (c, tenant, key, businessKey, variables) -> engine.getRuntimeService()
                .createProcessInstanceByKey(key).processDefinitionTenantId(tenant).businessKey(businessKey)
                .setVariables(variables).execute().getId();
        SangamCompliance.Ports ports = new SangamCompliance.Ports(null, null, null, null, null, null, null, null, null,
                starter, null, gateway, null, null, null, null, null);
        cmp = new SangamCompliance(D, tx(), config, ports, null, org.sangam.compliance.PrincipalKey.of(new byte[32]));
        ComplianceRuntime.install(cmp);
    }

    @AfterEach
    void tearDown() throws Exception {
        engine.close();
        keepAlive.close();
    }

    Transactions tx() {
        return new Transactions() {
            @Override
            public <T> T inTransaction(Function<Connection, T> work) {
                try (Connection c = DriverManager.getConnection(url, "sa", "")) {
                    c.setAutoCommit(false);
                    try {
                        T r = work.apply(c);
                        c.commit();
                        return r;
                    } catch (RuntimeException e) {
                        c.rollback();
                        throw e;
                    }
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            }

            @Override
            public <T> T read(ReadWork<T> work) {
                try (Connection c = DriverManager.getConnection(url, "sa", "")) {
                    c.setAutoCommit(false);
                    try {
                        T r = work.read(c);
                        c.commit();
                        return r;
                    } catch (RuntimeException e) {
                        c.rollback();
                        throw e;
                    }
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
    }

    /** A registered and reported incident whose process waits at the follow-up task; returns {incidentId, processInstanceId}. */
    String[] reportedIncidentAtFollowUp() {
        IncidentReportingService.Incident incident = tx().inTransaction(c -> {
            cmp.contacts.set(c, TENANT, new ContactDirectory.Contact(IncidentReportingService.pocRole("CERT-IN"), "PoC Officer",
                    "Deputy CISO", "Test Department", "poc@example.gov.in", "0110000000", "9000000000", "Block A, New Delhi", true,
                    null), "admin-1");
            return cmp.incidents.register(c, TENANT, "CERT-IN", "x", null, Instant.now(), List.of("portal"), Map.of(),
                    "web application attack", null, "ciso-1");
        });
        String pi = engine.getRuntimeService().createProcessInstanceQuery().processInstanceBusinessKey(incident.incidentId())
                .singleResult().getId();
        Task prepare = task(pi, "u_prepare");
        assertEquals("SEAT-CISO", engine.getTaskService().getIdentityLinksForTask(prepare.getId()).get(0).getGroupId());
        engine.getTaskService().complete(prepare.getId(), Map.of("actionsTaken", "isolated the server", "sangamActor", "poc-1"));
        runJob(pi, "s_submit");
        assertEquals(IncidentReportingService.Status.REPORTED, status(incident.incidentId()));
        assertNotNull(task(pi, "u_follow"), "the process waits at the follow-up task");
        return new String[] {incident.incidentId(), pi};
    }

    Task task(String pi, String key) {
        return engine.getTaskService().createTaskQuery().processInstanceId(pi).taskDefinitionKey(key).singleResult();
    }

    List<Job> jobs(String pi, String activityId) {
        return engine.getManagementService().createJobQuery().processInstanceId(pi).activityId(activityId).list();
    }

    void runJob(String pi, String activityId) {
        List<Job> jobs = jobs(pi, activityId);
        assertEquals(1, jobs.size(), "one job at " + activityId);
        engine.getManagementService().executeJob(jobs.get(0).getId());
    }

    IncidentReportingService.Status status(String incidentId) {
        return tx().inTransaction(c -> cmp.incidents.get(c, TENANT, incidentId).status());
    }

    List<Map<String, Object>> closedEvents() {
        return tx().inTransaction(c -> Jdbc.query(c, "SELECT p.payload FROM AUDIT_PAYLOAD p JOIN AUDIT_EVENT e ON e.event_id=p.event_id"
                + " WHERE e.tenant_id=? AND e.chain_id=? AND e.event_type=? ORDER BY e.seq", rs -> Sql.map(rs.getString(1)), TENANT,
                ComplianceAudit.tenantChain(TENANT), ComplianceAudit.SEC_INCIDENT_CLOSED));
    }

    @Test
    void closingWithoutRootCauseAndLessonsIsRefusedAtCompletionAndNothingMoves() {
        String[] at = reportedIncidentAtFollowUp();
        String incidentId = at[0];
        String pi = at[1];
        List<Map<String, Object>> incomplete = List.of(
                Map.of("followUpNeeded", false),
                Map.of("followUpNeeded", false, "rootCause", ROOT_CAUSE),
                Map.of("followUpNeeded", false, "lessonsLearned", LESSONS),
                Map.of("followUpNeeded", false, "rootCause", " ", "lessonsLearned", LESSONS),
                Map.of("rootCause", ROOT_CAUSE, "lessonsLearned", ""));
        for (Map<String, Object> answer : incomplete) {
            String taskId = task(pi, "u_follow").getId();
            ProcessEngineException e = assertThrows(ProcessEngineException.class,
                    () -> engine.getTaskService().complete(taskId, answer), "refused at completion: " + answer);
            assertTrue(e.getMessage().contains("422 INCIDENT_CLOSURE_INCOMPLETE"), e.getMessage());
            assertTrue(e.getMessage().contains(incidentId), e.getMessage());

            assertEquals(taskId, task(pi, "u_follow").getId(), "the follow-up task is still open");
            assertTrue(jobs(pi, "s_close").isEmpty(), "no close job was created, so none can fail");
            assertEquals(0, engine.getManagementService().createJobQuery().processInstanceId(pi).withException().count(),
                    "no failed job");
            assertEquals(0, engine.getRuntimeService().createIncidentQuery().processInstanceId(pi).count(), "no engine incident");
            assertNull(engine.getRuntimeService().getVariable(pi, "followUpNeeded"), "the refused answer is not stored");
            assertEquals(IncidentReportingService.Status.REPORTED, status(incidentId), "the incident stays open (reported)");
            assertTrue(closedEvents().isEmpty(), "no SEC_INCIDENT_CLOSED");
        }
    }

    @Test
    void closingWithBothFieldsClosesTheIncidentAndTheClosureEventCarriesThem() {
        String[] at = reportedIncidentAtFollowUp();
        String incidentId = at[0];
        String pi = at[1];
        engine.getTaskService().complete(task(pi, "u_follow").getId(), Map.of("followUpNeeded", false, "rootCause", ROOT_CAUSE,
                "lessonsLearned", LESSONS, "sangamActor", "ciso-1"));
        runJob(pi, "s_close");

        assertNull(engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(pi).singleResult(),
                "the process ended");
        assertEquals(IncidentReportingService.Status.CLOSED, status(incidentId));
        List<Map<String, Object>> closed = closedEvents();
        assertEquals(1, closed.size());
        assertEquals(incidentId, closed.get(0).get("incident"));
        assertEquals(ROOT_CAUSE, closed.get(0).get("rootCause"));
        assertEquals(LESSONS, closed.get(0).get("lessonsLearned"));
        assertEquals("ciso-1", closed.get(0).get("actor"));
    }

    @Test
    void choosingAFollowUpNeedsNeitherField() {
        String[] at = reportedIncidentAtFollowUp();
        String pi = at[1];
        engine.getTaskService().complete(task(pi, "u_follow").getId(), Map.of("followUpNeeded", true));
        assertEquals(1, jobs(pi, "s_follow").size(), "the follow-up step is queued");
        assertTrue(jobs(pi, "s_close").isEmpty());
        assertEquals(IncidentReportingService.Status.REPORTED, status(at[0]));
    }
}
