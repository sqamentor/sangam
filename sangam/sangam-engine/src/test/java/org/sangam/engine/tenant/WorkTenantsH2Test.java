// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.operaton.bpm.engine.runtime.Job;
import org.sangam.core.engine.AuditSink;
import org.sangam.core.engine.IncidentSink;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.core.tenant.TenantRefusal;
import org.sangam.core.tenant.TenantResolutionException;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.SangamEngineFactory;
import org.sangam.engine.SangamEngineSettings;
import org.sangam.engine.job.JobIdentitySupport;

/**
 * Decision 144 (owner 197) at the engine sites: a tenant-owned job runs as its engine tenant and a differing
 * {@code sangamTenantId} variable refuses it; an untenanted job of an unregistered deployment is not executed and its
 * incident goes to the system audit scope only with {@code TENANT_UNRESOLVED}; the same job of a registered shared
 * deployment runs as the system audit scope; an untenanted batch's seed job carries its source deployment and follows
 * the same rule (decision 330); the engine start refuses a missing or customer-tenant scope. H2 only;
 * Oracle and SQL Server NOT_RUN. Written, NOT_RUN.
 */
class WorkTenantsH2Test {

    static final String SCOPE = "system:platform";
    static final List<SecurityIdentity> SEEN = new CopyOnWriteArrayList<>();

    /** Records the job identity the delegate runs under. */
    public static final class Capture implements JavaDelegate {
        @Override
        public void execute(DelegateExecution execution) {
            SEEN.add(JobIdentitySupport.current().orElse(null));
        }
    }

    private static final String MODEL = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="t">
              <process id="w144" isExecutable="true">
                <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="work"/>
                <serviceTask id="work" operaton:asyncBefore="true"
                             operaton:class="org.sangam.engine.tenant.WorkTenantsH2Test$Capture"/>
                <sequenceFlow id="f2" sourceRef="work" targetRef="e"/><endEvent id="e"/>
              </process>
            </definitions>
            """;

    record Audited(String tenant, String root, String type, Map<String, Object> payload) {
    }

    private final List<Audited> audited = new CopyOnWriteArrayList<>();
    private final List<IncidentSink.Incident> incidents = new CopyOnWriteArrayList<>();

    private OperatonEngineAdapter engine() {
        SangamEngineSettings s = new SangamEngineSettings();
        s.setSystemAuditScope(SCOPE);
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true");
        s.setApplyEnvironment(false);
        s.setDeployAllowedClasses(Map.of("*", Set.of(Capture.class.getName())));
        s.setAuditSink((AuditSink) (c, t, r, e, p) -> audited.add(new Audited(t, r, e, p)));
        s.setIncidentSink(new IncidentSink() {
            @Override
            public void opened(Connection connection, Incident incident) {
                incidents.add(incident);
            }

            @Override
            public void closed(Connection connection, String incidentId, String reason) {
            }
        });
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:w144" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        return SangamEngineFactory.build(ds, s, "h2");
    }

    static SecurityIdentity deployer() {
        return new SecurityIdentity("platform", "ops-1", "ops-1",
                Set.of(SharedDeployments.PLATFORM_MARKER, SharedDeployments.DEPLOYER_GROUP), Set.of(), Set.of(), Set.of(),
                "OIDC", null, null, false, Map.of());
    }

    private static Job jobOf(OperatonEngineAdapter a, String pid) {
        return a.processEngine().getManagementService().createJobQuery().processInstanceId(pid).singleResult();
    }

    /** Runs the job until its retries are used up; returns the last failure. */
    private static RuntimeException failUntilIncident(OperatonEngineAdapter a, String pid) {
        RuntimeException last = null;
        for (int i = 0; i < 3; i++) {
            Job j = jobOf(a, pid);
            String id = j.getId();
            last = assertThrows(RuntimeException.class, () -> a.processEngine().getManagementService().executeJob(id));
        }
        return last;
    }

    @Test
    void aTenantOwnedJobRunsAsItsEngineTenant() {
        SEEN.clear();
        try (OperatonEngineAdapter a = engine()) {
            a.deploy("t1", "w144", Map.of("w144.bpmn", MODEL.getBytes(StandardCharsets.UTF_8)));
            String pid = a.startProcess("t1", "w144", "B-1", Map.of());
            a.processEngine().getManagementService().executeJob(jobOf(a, pid).getId());
            assertEquals(1, SEEN.size());
            assertEquals("t1", SEEN.get(0).tenantId());
        }
    }

    @Test
    void aDifferingTenantVariableRefusesTheJob() {
        SEEN.clear();
        try (OperatonEngineAdapter a = engine()) {
            a.deploy("t1", "w144", Map.of("w144.bpmn", MODEL.getBytes(StandardCharsets.UTF_8)));
            String pid = a.startProcess("t1", "w144", "B-2", Map.of(JobIdentitySupport.TENANT_VARIABLE, "t2"));
            failUntilIncident(a, pid);
            assertTrue(SEEN.isEmpty(), "the job was not executed");
            assertEquals(1, incidents.size());
            assertEquals("t1", incidents.get(0).tenantId(), "the incident stays with the engine tenant");
        }
    }

    @Test
    void anUntenantedJobOfAnUnregisteredDeploymentFailsClosedToTheSystemScope() {
        SEEN.clear();
        try (OperatonEngineAdapter a = engine()) {
            String dep = a.deploy(null, "w144", Map.of("w144.bpmn", MODEL.getBytes(StandardCharsets.UTF_8)));
            String pid = a.startProcess(null, "w144", "B-3", Map.of());
            RuntimeException e = failUntilIncident(a, pid);
            assertTrue(SEEN.isEmpty(), "the job was not executed");
            assertTrue(String.valueOf(rootMessage(e)).contains(TenantRefusal.MISSING_ENGINE_TENANT.code())
                    || causeIs(e, TenantResolutionException.class), String.valueOf(rootMessage(e)));
            assertEquals(1, incidents.size());
            assertEquals(SCOPE, incidents.get(0).tenantId(), "never a customer tenant or the attribution default");
            Audited u = audited.stream().filter(x -> WorkTenants.UNRESOLVED_EVENT.equals(x.type())).findFirst().orElseThrow();
            assertEquals(SCOPE, u.tenant());
            assertNull(u.payload().get("engineTenant"));
            assertEquals(SCOPE, u.payload().get("resolvedScope"));
            assertEquals("UNRESOLVED_TENANT", u.payload().get("resolutionReason"));
            @SuppressWarnings("unchecked")
            Map<String, Object> ids = (Map<String, Object>) u.payload().get("resourceIds");
            assertEquals(dep, ids.get("deploymentId"));
            assertEquals(pid, ids.get("processInstanceId"));
            assertNotNull(u.payload().get("correlationId"));
        }
    }

    @Test
    void anUntenantedJobOfARegisteredSharedDeploymentRunsInTheSystemScope() {
        SEEN.clear();
        try (OperatonEngineAdapter a = engine()) {
            String dep = a.deploy(null, "w144", Map.of("w144.bpmn", MODEL.getBytes(StandardCharsets.UTF_8)));
            new SharedDeployments(a).register(deployer(), dep, "platform housekeeping");
            String pid = a.startProcess(null, "w144", "B-4", Map.of());
            a.processEngine().getManagementService().executeJob(jobOf(a, pid).getId());
            assertEquals(1, SEEN.size());
            assertEquals(SCOPE, SEEN.get(0).tenantId(), "attributed to the system audit scope");
            assertTrue(incidents.isEmpty());
        }
    }

    @Test
    void everyEngineExposesOnePartitionResizeEvenWithoutPartitioning() {
        try (OperatonEngineAdapter a = engine()) {
            org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl cfg =
                    (org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl) a.processEngine()
                            .getProcessEngineConfiguration();
            org.sangam.engine.SangamEngineComponents c = cfg.getProcessEnginePlugins().stream()
                    .filter(org.sangam.engine.SangamProcessEnginePlugin.class::isInstance)
                    .map(p -> ((org.sangam.engine.SangamProcessEnginePlugin) p).components()).findFirst().orElseThrow();
            assertNotNull(c.partitionResize(), "decision 146: built on every node, for the server bridge");
            org.sangam.engine.job.PartitionResize.ResizeException e = assertThrows(
                    org.sangam.engine.job.PartitionResize.ResizeException.class, () -> c.partitionResize().map());
            assertEquals(org.sangam.engine.job.PartitionResize.Code.NOT_INITIALISED, e.code(), "no executor map yet");
            assertThrows(IllegalStateException.class, () -> c.partitionResize(c.partitionResize()), "set once");
            assertEquals(SCOPE, c.workTenants().systemAuditScope());
        }
    }

    @Test
    void resolutionKeepsTheRawEngineTenantApartFromTheScope() {
        WorkTenants w = new WorkTenants(SCOPE);
        WorkTenants.Resolution own = w.resolve(null, "t1", null, null, false, "test");
        assertEquals(WorkTenants.Outcome.TENANT_OWNED, own.outcome());
        assertEquals("t1", own.attribution());
        WorkTenants.Resolution conflict = w.resolve(null, "t1", "t2", null, false, "test");
        assertTrue(conflict.unresolved());
        assertEquals(TenantRefusal.CONFLICTING_TENANT, conflict.refusal().refusal());
        assertThrows(TenantResolutionException.class, conflict::require);
        WorkTenants.Resolution maintenance = w.resolve(null, " ", null, null, true, "test");
        assertEquals(WorkTenants.Outcome.ENGINE_MAINTENANCE, maintenance.outcome(), "W4-B condition 3");
        assertEquals("ENGINE_MAINTENANCE", maintenance.auditFields().get("resolutionReason"));
        assertEquals(SCOPE, maintenance.attribution());
        assertEquals(" ", maintenance.auditFields().get("engineTenant"), "the raw engine tenant is kept");
        WorkTenants.Resolution tenantedMaintenance = w.resolve(null, "t1", null, null, true, "test");
        assertEquals(WorkTenants.Outcome.TENANT_OWNED, tenantedMaintenance.outcome(),
                "W4-B condition 2: a tenanted batch's seed and monitor jobs resolve to the batch tenant");
        assertEquals("t1", tenantedMaintenance.attribution());
        WorkTenants.Resolution none = w.resolve(null, null, "t1", null, false, "test");
        assertTrue(none.unresolved(), "a variable never substitutes for the engine tenant");
        assertEquals(TenantRefusal.MISSING_ENGINE_TENANT, none.refusal().refusal());
        assertSame(SCOPE, none.attribution());
    }

    @Test
    void theEngineStartRefusesAMissingOrCustomerScope() {
        assertThrows(IllegalStateException.class, () -> new WorkTenants(null));
        assertThrows(IllegalStateException.class, () -> new WorkTenants(" "));
        assertThrows(IllegalStateException.class, () -> new WorkTenants("tenant-a"));
        assertThrows(IllegalStateException.class, () -> new WorkTenants("-"));
        SangamEngineSettings s = new SangamEngineSettings();
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true");
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:w144n" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        RuntimeException e = assertThrows(RuntimeException.class, () -> SangamEngineFactory.build(ds, s, "h2").close());
        assertTrue(String.valueOf(rootMessage(e)).contains(WorkTenants.SETTING), String.valueOf(rootMessage(e)));
        assertFalse(WorkTenants.MAINTENANCE_JOB_TYPES.contains("timer-intermediate-transition"));
    }

    @Test
    void theMaintenanceSetIsOperatonsOwnHandlerTypes() {
        // W4-B ACK 2026-11-13T03:40:12Z condition 1: the set equals Operaton's TYPE constants (and their 2.2.0 values)
        assertEquals(Set.of(org.operaton.bpm.engine.impl.jobexecutor.historycleanup.HistoryCleanupJobHandler.TYPE,
                        org.operaton.bpm.engine.impl.batch.BatchSeedJobHandler.TYPE,
                        org.operaton.bpm.engine.impl.batch.BatchMonitorJobHandler.TYPE),
                WorkTenants.MAINTENANCE_JOB_TYPES);
        assertEquals(Set.of("history-cleanup", "batch-seed-job", "batch-monitor-job"), WorkTenants.MAINTENANCE_JOB_TYPES);
    }

    @Test
    void aTenantedBatchsSeedAndMonitorJobsBelongToTheBatchTenant() {
        // W4-B condition 2: Operaton's migration batch takes the source definition's tenant, its seed and monitor job
        // definitions take the batch tenant, and their jobs the job definition's tenant (javap 2.2.0)
        try (OperatonEngineAdapter a = engine()) {
            a.deploy("t1", "w144", Map.of("w144.bpmn", MODEL.getBytes(StandardCharsets.UTF_8)));
            a.deploy("t1", "w144", Map.of("w144.bpmn", MODEL.replace("isExecutable=\"true\"",
                    "isExecutable=\"true\" name=\"v2\"").getBytes(StandardCharsets.UTF_8)));
            var repo = a.processEngine().getRepositoryService();
            String v1 = repo.createProcessDefinitionQuery().processDefinitionKey("w144").tenantIdIn("t1")
                    .processDefinitionVersion(1).singleResult().getId();
            String v2 = repo.createProcessDefinitionQuery().processDefinitionKey("w144").tenantIdIn("t1")
                    .processDefinitionVersion(2).singleResult().getId();
            String pid = a.startProcess("t1", "w144", "B-5", Map.of());
            var runtime = a.processEngine().getRuntimeService();
            org.operaton.bpm.engine.batch.Batch batch = runtime.newMigration(runtime.createMigrationPlan(v1, v2)
                    .mapEqualActivities().build()).processInstanceIds(pid).executeAsync();
            assertEquals("t1", batch.getTenantId());
            var mgmt = a.processEngine().getManagementService();
            Job seed = mgmt.createJobQuery().jobDefinitionId(batch.getSeedJobDefinitionId()).singleResult();
            assertEquals("t1", seed.getTenantId());
            WorkTenants w = new WorkTenants(SCOPE);
            WorkTenants.Resolution r = w.resolve(null, seed.getTenantId(), null, null, true, "test");
            assertEquals(WorkTenants.Outcome.TENANT_OWNED, r.outcome());
            assertEquals("t1", r.attribution());
            mgmt.executeJob(seed.getId()); // runs as t1: creates the execution jobs and the monitor job
            Job monitor = mgmt.createJobQuery().jobDefinitionId(batch.getMonitorJobDefinitionId()).singleResult();
            assertEquals("t1", monitor.getTenantId());
            assertTrue(incidents.isEmpty());
        }
    }

    /** An untenanted migration batch over one untenanted instance, from version 1 to version 2 of {@code w144}. */
    private record UntenantedBatch(String sourceDeployment, String v2, String pid, org.operaton.bpm.engine.batch.Batch batch,
                                   Job seed) {
    }

    private static UntenantedBatch untenantedMigration(OperatonEngineAdapter a, String businessKey) {
        String source = a.deploy(null, "w144", Map.of("w144.bpmn", MODEL.getBytes(StandardCharsets.UTF_8)));
        String pid = a.startProcess(null, "w144", businessKey, Map.of()); // an instance of version 1
        a.deploy(null, "w144", Map.of("w144.bpmn", MODEL.replace("isExecutable=\"true\"",
                "isExecutable=\"true\" name=\"v2\"").getBytes(StandardCharsets.UTF_8)));
        var repo = a.processEngine().getRepositoryService();
        String v1 = repo.createProcessDefinitionQuery().processDefinitionKey("w144").withoutTenantId()
                .processDefinitionVersion(1).singleResult().getId();
        String v2 = repo.createProcessDefinitionQuery().processDefinitionKey("w144").withoutTenantId()
                .processDefinitionVersion(2).singleResult().getId();
        var runtime = a.processEngine().getRuntimeService();
        assertEquals(v1, runtime.createProcessInstanceQuery().processInstanceId(pid).singleResult()
                .getProcessDefinitionId());
        org.operaton.bpm.engine.batch.Batch batch = runtime.newMigration(runtime.createMigrationPlan(v1, v2)
                .mapEqualActivities().build()).processInstanceIds(pid).executeAsync();
        assertNull(batch.getTenantId());
        Job seed = a.processEngine().getManagementService().createJobQuery()
                .jobDefinitionId(batch.getSeedJobDefinitionId()).singleResult();
        assertNull(seed.getTenantId());
        assertNull(seed.getProcessInstanceId());
        assertNull(seed.getProcessDefinitionId());
        // Operaton 2.2 gives a batch's seed job the deployment of the batch's source definition, so the seed job is
        // not an engine maintenance job (those have no deployment): decision 197 decides by that deployment
        assertEquals(source, seed.getDeploymentId(), "the seed job carries its source deployment");
        assertFalse(WorkTenants.isMaintenance((org.operaton.bpm.engine.impl.persistence.entity.JobEntity) seed));
        return new UntenantedBatch(source, v2, pid, batch, seed);
    }

    /**
     * Decisions 197 and 330: an untenanted batch's seed job is not maintenance regardless of its deployment. It carries
     * the batch's source deployment and is refused while that deployment is not a registered shared deployment. (This
     * test used to expect a seed job without a deployment that runs as engine maintenance; the owner ruled that premise
     * wrong, decision 330.)
     */
    @Test
    void anUntenantedBatchsSeedJobOfAnUnregisteredDeploymentIsRefused() {
        try (OperatonEngineAdapter a = engine()) {
            UntenantedBatch b = untenantedMigration(a, "B-6");
            var mgmt = a.processEngine().getManagementService();
            RuntimeException e = assertThrows(RuntimeException.class, () -> mgmt.executeJob(b.seed().getId()));
            assertTrue(String.valueOf(rootMessage(e)).contains(TenantRefusal.MISSING_ENGINE_TENANT.code())
                    || causeIs(e, TenantResolutionException.class), String.valueOf(rootMessage(e)));
            assertEquals(0, mgmt.createJobQuery().jobDefinitionId(b.batch().getMonitorJobDefinitionId()).count(),
                    "the seed job did not run: no monitor job");
            assertEquals(0, mgmt.createJobQuery().jobDefinitionId(b.batch().getBatchJobDefinitionId()).count(),
                    "and no execution job");
            assertEquals(1, a.processEngine().getRuntimeService().createProcessInstanceQuery()
                    .processInstanceId(b.pid()).count(), "the instance is untouched");
            assertFalse(b.v2().equals(a.processEngine().getRuntimeService().createProcessInstanceQuery()
                    .processInstanceId(b.pid()).singleResult().getProcessDefinitionId()), "not migrated");
        }
    }

    /** Decisions 197 and 330: the same seed job runs once its source deployment is a registered shared deployment. */
    @Test
    void anUntenantedBatchsSeedJobOfARegisteredSharedDeploymentRuns() {
        try (OperatonEngineAdapter a = engine()) {
            UntenantedBatch b = untenantedMigration(a, "B-7");
            new SharedDeployments(a).register(deployer(), b.sourceDeployment(), "platform migration");
            var mgmt = a.processEngine().getManagementService();
            mgmt.executeJob(b.seed().getId());
            assertEquals(1, mgmt.createJobQuery().jobDefinitionId(b.batch().getMonitorJobDefinitionId()).count(),
                    "the seed job ran: the monitor job exists");
            Job execution = mgmt.createJobQuery().jobDefinitionId(b.batch().getBatchJobDefinitionId()).singleResult();
            assertEquals(b.sourceDeployment(), execution.getDeploymentId(), "the execution job carries it too");
            mgmt.executeJob(execution.getId());
            assertEquals(b.v2(), a.processEngine().getRuntimeService().createProcessInstanceQuery()
                    .processInstanceId(b.pid()).singleResult().getProcessDefinitionId(), "the batch migrated the instance");
            assertTrue(incidents.isEmpty());
            assertTrue(audited.stream().noneMatch(x -> WorkTenants.UNRESOLVED_EVENT.equals(x.type())));
        }
    }

    static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return c.getMessage();
    }

    static boolean causeIs(Throwable t, Class<?> type) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (type.isInstance(c)) {
                return true;
            }
        }
        return false;
    }
}
