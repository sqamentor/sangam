// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.batch.Batch;
import org.operaton.bpm.engine.impl.util.ClockUtil;
import org.operaton.bpm.engine.runtime.Job;
import org.sangam.core.engine.TaskSuspensionEvents;

/**
 * SG-03(c): every way Operaton suspends or activates tasks ends in a bulk {@code TaskManager} UPDATE that fires no task
 * listener, so {@code task.suspend} / {@code task.activate} never fired. {@code TenantScopedCreation.Tasks} (the one
 * Sangam TaskManager) now reports exactly the tasks each UPDATE changes. Each path is covered: one instance, a
 * definition with its instances, a definition key, a batch, a delayed (timer) definition change, and a repeat that
 * changes nothing (no second event). Without the overrides every count here is zero.
 */
class TaskSuspensionEventsH2Test {

    static final String BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="t">
              <process id="susp" isExecutable="true" operaton:historyTimeToLive="30">
                <startEvent id="start"/><sequenceFlow id="f0" sourceRef="start" targetRef="review"/>
                <userTask id="review"/><sequenceFlow id="f1" sourceRef="review" targetRef="end"/><endEvent id="end"/>
              </process>
            </definitions>
            """;

    record Event(String taskId, boolean suspended) {
    }

    final List<Event> events = new ArrayList<>();

    @AfterEach
    void resetClock() {
        ClockUtil.reset();
    }

    record Engine(ProcessEngine engine, List<String> instances) {
        String definitionId() {
            return engine.getRepositoryService().createProcessDefinitionQuery().processDefinitionKey("susp").singleResult()
                    .getId();
        }

        String taskOf(String pi) {
            return engine.getTaskService().createTaskQuery().processInstanceId(pi).singleResult().getId();
        }

        void runJobs() {
            for (int round = 0; round < 10; round++) {
                List<Job> due = engine.getManagementService().createJobQuery().executable().list();
                if (due.isEmpty()) {
                    return;
                }
                due.forEach(j -> engine.getManagementService().executeJob(j.getId()));
            }
        }
    }

    /** The tenant of each reported task, and the tenant of the job identity the report was made under (null: no job). */
    final List<String> eventTenants = new ArrayList<>();
    final List<String> ranAs = new ArrayList<>();

    /** A Sangam engine with two instances of tenant {@code t} waiting in a user task, and a recording listener. */
    private void withEngine(Consumer<Engine> test) {
        withAdapter(a -> {
            a.deploy("t", "susp", Map.of("susp.bpmn", BPMN.getBytes(StandardCharsets.UTF_8)));
            List<String> pis = List.of(a.startProcess("t", "susp", "BK-1", Map.of()),
                    a.startProcess("t", "susp", "BK-2", Map.of()));
            test.accept(new Engine(a.processEngine(), pis));
        });
    }

    /** A Sangam engine with a recording suspension listener and nothing deployed. */
    private void withAdapter(Consumer<OperatonEngineAdapter> test) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:s" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamEngineSettings s = new SangamEngineSettings();
        s.setSystemAuditScope(SCOPE); // decision 144: required at engine start
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true");
        s.setJobExecutorActivate(false);
        try (OperatonEngineAdapter a = SangamEngineFactory.build(ds, s, "h2")) {
            ProcessEngine engine = a.processEngine();
            TaskSuspensionEvents.Listener l = (tasks, suspended) -> tasks.forEach(t -> {
                events.add(new Event(t.taskId(), suspended));
                eventTenants.add(t.tenantId());
                ranAs.add(org.sangam.engine.job.JobIdentitySupport.current()
                        .map(org.sangam.core.security.SecurityIdentity::tenantId).orElse(null));
            });
            TaskSuspensionEvents.register(engine.getName(), l);
            try {
                test.accept(a);
            } finally {
                TaskSuspensionEvents.unregister(engine.getName(), l);
            }
        }
    }

    static final String SCOPE = "system:platform";

    static org.sangam.core.security.SecurityIdentity deployer() {
        return new org.sangam.core.security.SecurityIdentity("platform", "ops-1", "ops-1",
                java.util.Set.of(org.sangam.engine.tenant.SharedDeployments.PLATFORM_MARKER,
                        org.sangam.engine.tenant.SharedDeployments.DEPLOYER_GROUP), java.util.Set.of(),
                java.util.Set.of(), java.util.Set.of(), "OIDC", null, null, false, Map.of());
    }

    static Date inAnHour() {
        return new Date(ClockUtil.getCurrentTime().getTime() + 3_600_000L);
    }

    /** The one delayed state change timer of the engine. */
    static Job timer(ProcessEngine engine) {
        return engine.getManagementService().createJobQuery().timers().singleResult();
    }

    /** Runs the timer once and returns its refusal. */
    static RuntimeException refused(ProcessEngine engine) {
        String id = timer(engine).getId();
        RuntimeException e = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> engine.getManagementService().executeJob(id));
        StringBuilder all = new StringBuilder();
        for (Throwable c = e; c != null; c = c.getCause() == c ? null : c.getCause()) {
            all.append(c.getMessage()).append('\n');
        }
        assertTrue(all.toString().contains(org.sangam.core.tenant.TenantRefusal.MISSING_ENGINE_TENANT.code()),
                all.toString());
        return e;
    }

    static boolean suspended(ProcessEngine engine, String tenant) {
        var q = engine.getRepositoryService().createProcessDefinitionQuery().processDefinitionKey("susp");
        return (tenant == null ? q.withoutTenantId() : q.tenantIdIn(tenant)).singleResult().isSuspended();
    }

    @Test
    void oneInstanceSuspendedAndActivatedGivesOneEventEachForItsTask() {
        withEngine(e -> {
            String pi = e.instances().get(0);
            String task = e.taskOf(pi);
            e.engine().getRuntimeService().suspendProcessInstanceById(pi);
            assertEquals(List.of(new Event(task, true)), events);
            e.engine().getRuntimeService().activateProcessInstanceById(pi);
            assertEquals(List.of(new Event(task, true), new Event(task, false)), events);
        });
    }

    @Test
    void aDefinitionWithItsInstancesGivesOneEventPerTask() {
        withEngine(e -> {
            e.engine().getRepositoryService().suspendProcessDefinitionById(e.definitionId(), true, null);
            assertEquals(2, events.size(), events.toString());
            assertTrue(events.stream().allMatch(Event::suspended));
            events.clear();
            e.engine().getRepositoryService().activateProcessDefinitionByKey("susp", true, null);
            assertEquals(2, events.size(), events.toString());
            assertTrue(events.stream().noneMatch(Event::suspended));
        });
    }

    @Test
    void aBatchGivesOneEventPerTask() {
        withEngine(e -> {
            // SL-45 / decision 144: a batch over a tenant's instances is created by an identity of that tenant, so its
            // jobs carry the tenant (a batch of no tenant over tenant-owned instances is refused when its jobs run)
            e.engine().getIdentityService().setAuthentication("operator", List.of(), List.of("t"));
            Batch b;
            try {
                b = e.engine().getRuntimeService().updateProcessInstanceSuspensionState()
                        .byProcessInstanceIds(e.instances()).suspendAsync();
            } finally {
                e.engine().getIdentityService().clearAuthentication();
            }
            assertEquals(0, events.size(), "nothing before the batch jobs run");
            e.runJobs();
            assertEquals(2, events.size(), events.toString());
            assertTrue(events.stream().allMatch(Event::suspended));
            assertTrue(b.getId() != null);
        });
    }

    @Test
    void aDelayedDefinitionChangeEmitsWhenItsTimerRuns() {
        withEngine(e -> {
            Date at = new Date(ClockUtil.getCurrentTime().getTime() + 3_600_000L);
            e.engine().getRepositoryService().suspendProcessDefinitionById(e.definitionId(), true, at);
            e.runJobs();
            assertEquals(0, events.size(), "not before the date");
            ClockUtil.setCurrentTime(new Date(at.getTime() + 1000));
            e.runJobs();
            assertEquals(2, events.size(), events.toString());
        });
    }

    /**
     * Decision 331: the engine's delayed state change timer carries the tenant of the definition it will change, from
     * the moment it is scheduled, so it runs as that tenant (Operaton creates it with the deployment and no tenant, and
     * such a job of a tenant's deployment is refused, decision 197).
     */
    @Test
    void aDelayedSuspendTimerCarriesItsDefinitionsTenantAndRunsAsIt() {
        withEngine(e -> {
            Date at = inAnHour();
            e.engine().getRepositoryService().suspendProcessDefinitionById(e.definitionId(), true, at);
            Job timer = timer(e.engine());
            assertEquals("t", timer.getTenantId(), "stamped when scheduled");
            assertEquals("suspend-processdefinition",
                    ((org.operaton.bpm.engine.impl.persistence.entity.JobEntity) timer).getJobHandlerType());
            assertTrue(timer.getDeploymentId() != null, "Operaton's deployment stays on the timer");
            ClockUtil.setCurrentTime(new Date(at.getTime() + 1000));
            e.runJobs();
            assertEquals(2, events.size(), events.toString());
            assertTrue(events.stream().allMatch(Event::suspended));
            assertEquals(List.of("t", "t"), ranAs, "the timer ran as the definition's tenant");
            assertTrue(suspended(e.engine(), "t"));
        });
    }

    @Test
    void aDelayedActivationOfATenantOwnedDefinitionRunsAndEmits() {
        withEngine(e -> {
            e.engine().getRepositoryService().suspendProcessDefinitionById(e.definitionId(), true, null);
            events.clear();
            ranAs.clear();
            Date at = inAnHour();
            e.engine().getRepositoryService().activateProcessDefinitionById(e.definitionId(), true, at);
            assertEquals("t", timer(e.engine()).getTenantId());
            e.runJobs();
            assertEquals(0, events.size(), "not before the date");
            ClockUtil.setCurrentTime(new Date(at.getTime() + 1000));
            e.runJobs();
            assertEquals(2, events.size(), events.toString());
            assertTrue(events.stream().noneMatch(Event::suspended));
            assertEquals(List.of("t", "t"), ranAs);
            assertTrue(!suspended(e.engine(), "t"));
        });
    }

    @Test
    void aDelayedChangeByKeyAndTenantBelongsToThatTenant() {
        withEngine(e -> {
            Date at = inAnHour();
            e.engine().getRepositoryService().updateProcessDefinitionSuspensionState().byProcessDefinitionKey("susp")
                    .processDefinitionTenantId("t").includeProcessInstances(true).executionDate(at).suspend();
            assertEquals("t", timer(e.engine()).getTenantId());
            ClockUtil.setCurrentTime(new Date(at.getTime() + 1000));
            e.runJobs();
            assertEquals(2, events.size(), events.toString());
            assertEquals(List.of("t", "t"), ranAs);
        });
    }

    /** An untenanted definition behaves as before: no tenant on the timer; decision 197 decides whether it runs. */
    @Test
    void aDelayedChangeOfAnUntenantedDefinitionIsRefusedUnlessItsDeploymentIsARegisteredSharedOne() {
        withAdapter(a -> {
            ProcessEngine engine = a.processEngine();
            String dep = a.deploy(null, "susp", Map.of("susp.bpmn", BPMN.getBytes(StandardCharsets.UTF_8)));
            a.startProcess(null, "susp", "BK-1", Map.of());
            a.startProcess(null, "susp", "BK-2", Map.of());
            String def = engine.getRepositoryService().createProcessDefinitionQuery().processDefinitionKey("susp")
                    .singleResult().getId();
            Date at = inAnHour();
            engine.getRepositoryService().suspendProcessDefinitionById(def, true, at);
            Job timer = timer(engine);
            org.junit.jupiter.api.Assertions.assertNull(timer.getTenantId(), "no tenant is invented for a shared definition");
            assertEquals(dep, timer.getDeploymentId());
            ClockUtil.setCurrentTime(new Date(at.getTime() + 1000));
            refused(engine);
            assertEquals(0, events.size(), "refused: nothing changed");
            assertTrue(!suspended(engine, null));

            new org.sangam.engine.tenant.SharedDeployments(a).register(deployer(), dep, "platform housekeeping");
            engine.getManagementService().executeJob(timer(engine).getId());
            assertEquals(2, events.size(), events.toString());
            assertEquals(List.of(SCOPE, SCOPE), ranAs, "a registered shared deployment's timer runs in the system scope");
            assertTrue(suspended(engine, null));
        });
    }

    /** Two tenants with the same key: a timer scheduled for tenant A changes A only and never runs as B. */
    @Test
    void aTimerScheduledForOneTenantNeverRunsAsAnother() {
        withAdapter(a -> {
            ProcessEngine engine = a.processEngine();
            for (String tenant : List.of("A", "B")) {
                a.deploy(tenant, "susp", Map.of("susp.bpmn", BPMN.getBytes(StandardCharsets.UTF_8)));
                a.startProcess(tenant, "susp", "BK-" + tenant, Map.of());
            }
            var repo = engine.getRepositoryService();
            String defA = repo.createProcessDefinitionQuery().processDefinitionKey("susp").tenantIdIn("A").singleResult()
                    .getId();
            Date at = inAnHour();
            repo.suspendProcessDefinitionById(defA, true, at);
            assertEquals("A", timer(engine).getTenantId());
            ClockUtil.setCurrentTime(new Date(at.getTime() + 1000));
            engine.getManagementService().executeJob(timer(engine).getId());
            assertEquals(List.of("A"), eventTenants, "only tenant A's task");
            assertEquals(List.of("A"), ranAs, "as tenant A");
            assertTrue(suspended(engine, "A"));
            assertTrue(!suspended(engine, "B"), "tenant B's definition of the same key is untouched");
            assertEquals(1, engine.getTaskService().createTaskQuery().tenantIdIn("B").active().count());

            // by key alone the change addresses both tenants: no one tenant owns it, so the timer has none and is
            // refused when it comes due (fail closed); nothing changes for either tenant
            events.clear();
            eventTenants.clear();
            ranAs.clear();
            ClockUtil.reset();
            Date later = inAnHour();
            repo.activateProcessDefinitionByKey("susp", true, later);
            org.junit.jupiter.api.Assertions.assertNull(timer(engine).getTenantId());
            ClockUtil.setCurrentTime(new Date(later.getTime() + 1000));
            refused(engine);
            assertEquals(0, events.size());
            assertTrue(suspended(engine, "A"), "still suspended");
        });
    }

    /**
     * Operaton has no tenant check for a state change by key, so an identity of tenant B can schedule one for tenant
     * A's definitions. Its timer gets no tenant and is refused when it comes due: a timer scheduled by B never runs as
     * A. By definition id Operaton itself refuses B at once.
     */
    @Test
    void aTimerScheduledByAnotherTenantsIdentityGetsNoTenantAndIsRefused() {
        withAdapter(a -> {
            ProcessEngine engine = a.processEngine();
            for (String tenant : List.of("A", "B")) {
                a.deploy(tenant, "susp", Map.of("susp.bpmn", BPMN.getBytes(StandardCharsets.UTF_8)));
                a.startProcess(tenant, "susp", "BK-" + tenant, Map.of());
            }
            var repo = engine.getRepositoryService();
            String defA = repo.createProcessDefinitionQuery().processDefinitionKey("susp").tenantIdIn("A").singleResult()
                    .getId();
            Date at = inAnHour();
            engine.getIdentityService().setAuthentication("user-b", List.of(), List.of("B"));
            try {
                org.junit.jupiter.api.Assertions.assertThrows(org.operaton.bpm.engine.ProcessEngineException.class,
                        () -> repo.suspendProcessDefinitionById(defA, true, at), "by id: refused by Operaton");
                repo.updateProcessDefinitionSuspensionState().byProcessDefinitionKey("susp")
                        .processDefinitionTenantId("A").includeProcessInstances(true).executionDate(at).suspend();
            } finally {
                engine.getIdentityService().clearAuthentication();
            }
            org.junit.jupiter.api.Assertions.assertNull(timer(engine).getTenantId(), "B's timer is not stamped with A");
            ClockUtil.setCurrentTime(new Date(at.getTime() + 1000));
            refused(engine);
            assertEquals(0, events.size());
            assertTrue(!suspended(engine, "A"), "tenant A's definition was not changed");

            // the same request by an identity of tenant A is A's own timer
            engine.getManagementService().deleteJob(timer(engine).getId());
            ClockUtil.reset();
            Date later = inAnHour();
            engine.getIdentityService().setAuthentication("user-a", List.of(), List.of("A"));
            try {
                repo.updateProcessDefinitionSuspensionState().byProcessDefinitionKey("susp")
                        .processDefinitionTenantId("A").includeProcessInstances(true).executionDate(later).suspend();
            } finally {
                engine.getIdentityService().clearAuthentication();
            }
            assertEquals("A", timer(engine).getTenantId());
            ClockUtil.setCurrentTime(new Date(later.getTime() + 1000));
            engine.getManagementService().executeJob(timer(engine).getId());
            assertEquals(List.of("A"), eventTenants);
            assertEquals(List.of("A"), ranAs);
        });
    }

    /** The job-definition twin of the same engine command family is stamped the same way. */
    @Test
    void aDelayedJobDefinitionChangeBelongsToTheDefinitionsTenant() {
        String async = BPMN.replace("id=\"susp\"", "id=\"jd\"").replace("<userTask id=\"review\"/>",
                "<userTask id=\"review\" operaton:asyncBefore=\"true\"/>");
        withAdapter(a -> {
            ProcessEngine engine = a.processEngine();
            a.deploy("t", "jd", Map.of("jd.bpmn", async.getBytes(StandardCharsets.UTF_8)));
            var mgmt = engine.getManagementService();
            String jobDefinition = mgmt.createJobDefinitionQuery().processDefinitionKey("jd").singleResult().getId();
            Date at = inAnHour();
            mgmt.suspendJobDefinitionById(jobDefinition, false, at);
            Job timer = timer(engine);
            assertEquals("t", timer.getTenantId());
            assertEquals("suspend-job-definition",
                    ((org.operaton.bpm.engine.impl.persistence.entity.JobEntity) timer).getJobHandlerType());
            ClockUtil.setCurrentTime(new Date(at.getTime() + 1000));
            mgmt.executeJob(timer.getId());
            assertTrue(mgmt.createJobDefinitionQuery().jobDefinitionId(jobDefinition).singleResult().isSuspended());
        });
    }

    @Test
    void aRepeatThatChangesNothingEmitsNothing() {
        withEngine(e -> {
            String pi = e.instances().get(0);
            e.engine().getRuntimeService().suspendProcessInstanceById(pi);
            e.engine().getRepositoryService().suspendProcessDefinitionById(e.definitionId(), true, null);
            // the definition suspension changes only the second instance's task: the first was already suspended
            assertEquals(2, events.size(), events.toString());
            assertEquals(2, events.stream().map(Event::taskId).distinct().count(), "each task exactly once");
            events.clear();
            e.engine().getRuntimeService().suspendProcessInstanceById(e.instances().get(1));
            assertEquals(0, events.size(), "already suspended: no event");
        });
    }
}
