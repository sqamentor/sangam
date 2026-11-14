// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.incident;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.OptimisticLockingException;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.delegate.BpmnError;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.operaton.bpm.engine.runtime.Incident;
import org.operaton.bpm.engine.runtime.Job;
import org.sangam.core.engine.AuditSink;
import org.sangam.core.layer.Jdbc;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.SangamEngineFactory;
import org.sangam.engine.SangamEngineSettings;
import org.sangam.engine.tenant.WorkTenants;

/**
 * Owner decisions 299 to 303: {@code INCIDENT_OPENED} exactly once per incident the engine opens, in the same command,
 * under the resolved attribution on the root chain (else {@code incident:<id>}); beside {@code TENANT_UNRESOLVED} and
 * {@code ROUTING_INCIDENT} (both events); every {@code errorClass} branch. H2 only; Oracle and SQL Server NOT_RUN.
 * Written, NOT_RUN.
 */
class IncidentOpenedH2Test {

    static final String SCOPE = "system:platform";

    /**
     * Always fails. (A BpmnError thrown here would not open an incident: Operaton unwraps it from the cause chain,
     * {@code BpmnExceptionHandler.checkIfCauseOfExceptionIsBpmnError}, and handles it as a BPMN error, javap 2.2.0; so
     * the business branch is covered by {@link #everyErrorClassBranch} on the recorded failure.)
     */
    public static final class Failing implements JavaDelegate {
        @Override
        public void execute(DelegateExecution execution) {
            throw new IllegalStateException("delegate failed");
        }
    }

    private static final String JOB_MODEL = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="t">
              <process id="io" isExecutable="true">
                <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="work"/>
                <serviceTask id="work" operaton:asyncBefore="true"
                             operaton:class="org.sangam.engine.incident.IncidentOpenedH2Test$Failing"/>
                <sequenceFlow id="f2" sourceRef="work" targetRef="e"/><endEvent id="e"/>
              </process>
            </definitions>
            """;

    private static final String TASK_MODEL = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="t">
              <process id="iot" isExecutable="true">
                <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="ext"/>
                <serviceTask id="ext" operaton:type="external" operaton:topic="io-topic"/>
                <sequenceFlow id="f2" sourceRef="ext" targetRef="e"/><endEvent id="e"/>
              </process>
            </definitions>
            """;

    private static final String ROUTING_MODEL = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" targetNamespace="t">
              <process id="ior" isExecutable="true">
                <startEvent id="s"/><sequenceFlow id="f0" sourceRef="s" targetRef="prep"/>
                <userTask id="prep"/><sequenceFlow id="f1" sourceRef="prep" targetRef="gw"/>
                <exclusiveGateway id="gw"/>
                <sequenceFlow id="fLow" sourceRef="gw" targetRef="low"><conditionExpression xsi:type="tFormalExpression">${amount &lt;= 100}</conditionExpression></sequenceFlow>
                <userTask id="low"/>
              </process>
            </definitions>
            """;

    record Audited(String tenant, String root, String type, Map<String, Object> payload) {
    }

    private final List<Audited> audited = new CopyOnWriteArrayList<>();
    /** The failure recorded on the command state when each incident's reconciliation row is written. */
    private final List<Object> recordedFailures = new CopyOnWriteArrayList<>();

    private OperatonEngineAdapter engine() {
        return engine(true);
    }

    /** @param recordingSink the test's incident sink; false: the default JdbcIncidentSink (SANGAM_ENGINE_INCIDENT) */
    private OperatonEngineAdapter engine(boolean recordingSink) {
        SangamEngineSettings s = new SangamEngineSettings();
        s.setSystemAuditScope(SCOPE);
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true");
        s.setApplyEnvironment(false);
        s.setDeployAllowedClasses(Map.of("*", Set.of(Failing.class.getName())));
        s.setAuditSink((AuditSink) (c, t, r, e, p) -> audited.add(new Audited(t, r, e, p)));
        if (recordingSink) s.setIncidentSink(new org.sangam.core.engine.IncidentSink() {
            @Override
            public void opened(java.sql.Connection connection, Incident incident) {
                Throwable f = org.sangam.engine.tx.CommandState.current().jobFailure();
                recordedFailures.add(f == null ? "none" : f);
            }

            @Override
            public void closed(java.sql.Connection connection, String incidentId, String reason) {
            }
        });
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:io" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        return SangamEngineFactory.build(ds, s, "h2");
    }

    private List<Audited> of(String type) {
        return audited.stream().filter(x -> type.equals(x.type())).toList();
    }

    private static void failUntilIncident(OperatonEngineAdapter a, String pid) {
        var mgmt = a.processEngine().getManagementService();
        for (int i = 0; i < 3; i++) {
            String id = mgmt.createJobQuery().processInstanceId(pid).singleResult().getId();
            assertThrows(RuntimeException.class, () -> mgmt.executeJob(id));
        }
    }

    private static Incident incident(OperatonEngineAdapter a, String pid) {
        List<Incident> list = a.processEngine().getRuntimeService().createIncidentQuery().processInstanceId(pid).list();
        assertEquals(1, list.size(), "one incident");
        return list.get(0);
    }

    private static final List<String> PAYLOAD_KEYS = List.of("incidentId", "incidentType", "processInstanceId",
            "rootProcessInstanceId", "executionId", "activityId", "processDefinitionId", "configuration", "errorClass",
            "message", "engineTenant", "resolvedScope", "resolutionReason");

    @Test
    void aFailedJobOpensExactlyOneIncidentOpenedUnderItsTenantOnTheRootChain() {
        try (OperatonEngineAdapter a = engine()) {
            a.deploy("t1", "io", Map.of("io.bpmn", JOB_MODEL.getBytes(StandardCharsets.UTF_8)));
            String pid = a.startProcess("t1", "io", "IO-1", Map.of());
            failUntilIncident(a, pid);
            Incident inc = incident(a, pid);
            List<Audited> opened = of(SangamIncidentHandler.OPENED_EVENT);
            assertEquals(1, opened.size(), "exactly one per incident: " + opened);
            Audited e = opened.get(0);
            assertEquals("t1", e.tenant());
            assertEquals(pid, e.root());
            assertEquals(PAYLOAD_KEYS, List.copyOf(e.payload().keySet()));
            assertEquals(inc.getId(), e.payload().get("incidentId"));
            assertEquals("failedJob", e.payload().get("incidentType"));
            assertEquals(pid, e.payload().get("rootProcessInstanceId"));
            assertEquals("work", e.payload().get("activityId"));
            assertEquals(IncidentErrorClass.UNKNOWN, e.payload().get("errorClass"));
            assertEquals("TENANT_OWNED", e.payload().get("resolutionReason"));
            assertTrue(of(WorkTenants.UNRESOLVED_EVENT).isEmpty());
            assertTrue(of(RoutingIncidentHandler.AUDIT_EVENT).isEmpty());
        }
    }

    @Test
    void theOriginalJobFailureReachesTheIncidentCommand() {
        try (OperatonEngineAdapter a = engine()) {
            a.deploy("t1", "io", Map.of("io.bpmn", JOB_MODEL.getBytes(StandardCharsets.UTF_8)));
            String pid = a.startProcess("t1", "io", "IO-2", Map.of());
            failUntilIncident(a, pid);
            assertEquals(1, recordedFailures.size());
            boolean delegateFailure = false;
            for (Throwable t = (Throwable) recordedFailures.get(0); t != null; t = t.getCause() == t ? null : t.getCause()) {
                delegateFailure |= t instanceof IllegalStateException && "delegate failed".equals(t.getMessage());
            }
            assertTrue(delegateFailure, "decision 302: errorClass is derived from the original failure "
                    + recordedFailures.get(0));
        }
    }

    @Test
    void retriesSetToZeroWithoutAFailureAreUnknown() {
        try (OperatonEngineAdapter a = engine()) {
            a.deploy("t1", "io", Map.of("io.bpmn", JOB_MODEL.getBytes(StandardCharsets.UTF_8)));
            String pid = a.startProcess("t1", "io", "IO-3", Map.of());
            var mgmt = a.processEngine().getManagementService();
            Job job = mgmt.createJobQuery().processInstanceId(pid).singleResult();
            mgmt.setJobRetries(job.getId(), 0); // an operator, no failure in this command
            List<Audited> opened = of(SangamIncidentHandler.OPENED_EVENT);
            assertEquals(1, opened.size());
            assertEquals(IncidentErrorClass.UNKNOWN, opened.get(0).payload().get("errorClass"));
            assertEquals(List.of("none"), recordedFailures, "no failure recorded in an operator's command");
        }
    }

    @Test
    void anUnresolvedTenantGetsBothEventsInTheSystemScope() {
        try (OperatonEngineAdapter a = engine()) {
            a.deploy(null, "io", Map.of("io.bpmn", JOB_MODEL.getBytes(StandardCharsets.UTF_8)));
            String pid = a.startProcess(null, "io", "IO-4", Map.of());
            failUntilIncident(a, pid);
            List<Audited> unresolved = of(WorkTenants.UNRESOLVED_EVENT);
            List<Audited> opened = of(SangamIncidentHandler.OPENED_EVENT);
            assertEquals(1, unresolved.size(), "decision 301: both events");
            assertEquals(1, opened.size());
            assertEquals(SCOPE, opened.get(0).tenant(), "decision 300: the resolved attribution");
            assertEquals(pid, opened.get(0).root());
            assertEquals("UNRESOLVED_TENANT", opened.get(0).payload().get("resolutionReason"));
            assertEquals(unresolved.get(0).payload().get("resourceIds") instanceof Map<?, ?> m ? m.get("incidentId")
                    : null, opened.get(0).payload().get("incidentId"), "the same incident");
        }
    }

    @Test
    void aFailedExternalTaskAndTheLeaseHoldOpenOneEachAsUnknown() {
        try (OperatonEngineAdapter a = engine()) {
            a.deploy("t1", "iot", Map.of("iot.bpmn", TASK_MODEL.getBytes(StandardCharsets.UTF_8)));
            String p1 = a.startProcess("t1", "iot", "IO-5", Map.of());
            String p2 = a.startProcess("t1", "iot", "IO-6", Map.of());
            var ext = a.processEngine().getExternalTaskService();
            var locked = ext.fetchAndLock(2, "w-1").topic("io-topic", 60_000L).execute();
            assertEquals(2, locked.size());
            for (var t : locked) {
                // the worker's final failure, and the A-10 hold LeaseExpiry raises the same way (retries 0)
                String message = t.getProcessInstanceId().equals(p1) ? "worker gave up"
                        : "LEASE_EXPIRED_HELD: lease 1 of w-1 expired";
                ext.handleFailure(t.getId(), "w-1", message, null, 0, 0L);
            }
            List<Audited> opened = of(SangamIncidentHandler.OPENED_EVENT);
            assertEquals(2, opened.size(), "one per incident");
            for (Audited e : opened) {
                assertEquals("failedExternalTask", e.payload().get("incidentType"));
                assertEquals(IncidentErrorClass.UNKNOWN, e.payload().get("errorClass"));
                assertEquals("t1", e.tenant());
            }
            assertEquals(Set.of(p1, p2), Set.of(opened.get(0).root(), opened.get(1).root()));
        }
    }

    @Test
    void aRoutingIncidentGetsBothEventsAsConfig() {
        try (OperatonEngineAdapter a = engine()) {
            a.deploy("t1", "ior", Map.of("ior.bpmn", ROUTING_MODEL.getBytes(StandardCharsets.UTF_8)));
            var engine = a.processEngine();
            String pid = a.startProcess("t1", "ior", "IO-7", Map.of("amount", 5_000L));
            engine.getTaskService().complete(engine.getTaskService().createTaskQuery().processInstanceId(pid)
                    .singleResult().getId());
            Incident inc = incident(a, pid);
            List<Audited> opened = of(SangamIncidentHandler.OPENED_EVENT);
            List<Audited> routing = of(RoutingIncidentHandler.AUDIT_EVENT);
            assertEquals(1, opened.size(), "decision 301: both events, each once");
            assertEquals(1, routing.size());
            assertEquals(IncidentErrorClass.CONFIG, opened.get(0).payload().get("errorClass"));
            assertEquals(inc.getId(), opened.get(0).payload().get("incidentId"));
            assertEquals(routing.get(0).root(), opened.get(0).root());
            assertEquals(routing.get(0).tenant(), opened.get(0).tenant());
        }
    }

    @Test
    void aRecoveryLoopIncidentWritesOneEventAndOneReconciliationRow() {
        try (OperatonEngineAdapter a = engine(false)) {
            a.deploy("t1", "ior", Map.of("ior.bpmn", ROUTING_MODEL.getBytes(StandardCharsets.UTF_8)));
            var engine = a.processEngine();
            String pid = a.startProcess("t1", "ior", "IO-8", Map.of("amount", 5L));
            String executionId = engine.getRuntimeService().createExecutionQuery().processInstanceId(pid)
                    .activityId("prep").singleResult().getId();
            // the call JobExecutorSetup's lease listener makes for a poison job (X.2.1.6)
            Incident inc = engine.getRuntimeService().createIncident(
                    org.sangam.engine.job.PartitionLeaseManager.RECOVERY_LOOP, executionId, "job-loop-1",
                    org.sangam.engine.job.PartitionLeaseManager.RECOVERY_LOOP + ": job job-loop-1 was recovered 6 times");
            List<Audited> opened = of(SangamIncidentHandler.OPENED_EVENT);
            assertEquals(1, opened.size(), "exactly one INCIDENT_OPENED: " + opened);
            Audited e = opened.get(0);
            assertEquals("t1", e.tenant());
            assertEquals(pid, e.root());
            assertEquals(inc.getId(), e.payload().get("incidentId"));
            assertEquals(org.sangam.engine.job.PartitionLeaseManager.RECOVERY_LOOP, e.payload().get("incidentType"));
            assertEquals(IncidentErrorClass.UNKNOWN, e.payload().get("errorClass"));
            int rows = a.inEngineTransaction(c -> Jdbc.one(c, "SELECT COUNT(*) FROM SANGAM_ENGINE_INCIDENT"
                    + " WHERE incident_id = ? AND incident_type = ? AND state = 'OPEN'", rs -> rs.getInt(1), inc.getId(),
                    org.sangam.engine.job.PartitionLeaseManager.RECOVERY_LOOP).orElse(0));
            assertEquals(1, rows, "exactly one reconciliation row");
        }
    }

    @Test
    void everyErrorClassBranch() {
        assertEquals(IncidentErrorClass.CONFIG, IncidentErrorClass.of(true, null));
        assertEquals(IncidentErrorClass.CONFIG, IncidentErrorClass.of(true, new BpmnError("E-1")), "routing first");
        assertEquals(IncidentErrorClass.UNKNOWN, IncidentErrorClass.of(false, null));
        assertEquals(IncidentErrorClass.RETRYABLE, IncidentErrorClass.of(false, new OptimisticLockingException("x")));
        assertEquals(IncidentErrorClass.RETRYABLE, IncidentErrorClass.of(false, new Jdbc.LayerSqlException("SELECT 1",
                new SQLException("deadlock detected", "40P01", 0))), "a PostgreSQL deadlock");
        assertEquals(IncidentErrorClass.BUSINESS, IncidentErrorClass.of(false, new BpmnError("E-1")));
        assertEquals(IncidentErrorClass.BUSINESS, IncidentErrorClass.of(false,
                new ProcessEngineException("wrapped", new BpmnError("E-2"))));
        assertEquals(IncidentErrorClass.UNKNOWN, IncidentErrorClass.of(false, new BpmnError(" ")), "a blank code proves nothing");
        assertEquals(IncidentErrorClass.UNKNOWN, IncidentErrorClass.of(false, new IllegalStateException("boom")));
    }
}
