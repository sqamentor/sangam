// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.ProcessEngineException;
import org.sangam.core.engine.TaskSuspensionEvents;

/**
 * SL-45, decision 331, item 3 of round 3: an immediate suspension-state change addressed BY KEY (suspend or activate
 * process definitions by key, and the job-definition twins), issued by an identity of tenant B, never changes tenant
 * A's definitions, instances, tasks or job definitions, and never reports tenant A's tasks as changed.
 *
 * <p>Operaton's {@code TenantCommandChecker} has no check for a change by key ({@code checkUpdateProcessDefinitionByKey}
 * and the job-definition twins are empty), but every by-key UPDATE carries the tenant check in its statement
 * (Operaton 2.2 mappings {@code ProcessDefinition.xml updateProcessDefinitionSuspensionStateByParameters},
 * {@code JobDefinition.xml}, {@code Execution.xml}, {@code Job.xml}, {@code ExternalTask.xml}, {@code Task.xml}:
 * {@code queryTenantCheckWithPrefix} inside the {@code processDefinitionKey} branch), so the caller's tenants bound
 * the rows. This test is the proof, through the engine API Sangam's adapters and {@code /engine-rest} use (the REST
 * filter {@code EngineRestTenantFilter} sets exactly this authentication: the token's principal and its one tenant).
 *
 * <p>Sangam's own task-suspension report ({@code TenantScopedCreation.Tasks}, SG-03(c)) read the tasks a by-key
 * UPDATE would change with the tenant check off, so it reported tenant A's tasks as suspended or activated when B's
 * by-key change had changed none of them (events and the task lifecycle of tenant A). The report now reads with the
 * same tenant check as the UPDATE.
 */
class SuspensionByKeyTenantH2Test {

    /** One user task (instances wait there) and an asynchronous service task (one job definition per definition). */
    static String bpmn(String key) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="t">
                  <process id="KEY" isExecutable="true" operaton:historyTimeToLive="30">
                    <startEvent id="start"/><sequenceFlow id="f0" sourceRef="start" targetRef="review"/>
                    <userTask id="review"/><sequenceFlow id="f1" sourceRef="review" targetRef="later"/>
                    <serviceTask id="later" operaton:asyncBefore="true" operaton:expression="${true}"/>
                    <sequenceFlow id="f2" sourceRef="later" targetRef="end"/><endEvent id="end"/>
                  </process>
                </definitions>
                """.replace("KEY", key);
    }

    /** Only tenant A has it. */
    static final String ONLY_A = "onlyA";
    /** Tenants A and B both have it. */
    static final String BOTH = "both";

    final List<String> reportedTenants = new ArrayList<>();

    private void withTenants(Consumer<ProcessEngine> test) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:k" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamEngineSettings s = new SangamEngineSettings();
        s.setSystemAuditScope("system:platform"); // decision 144: required at engine start
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true");
        s.setJobExecutorActivate(false);
        try (OperatonEngineAdapter a = SangamEngineFactory.build(ds, s, "h2")) {
            a.deploy("A", ONLY_A, Map.of(ONLY_A + ".bpmn", bpmn(ONLY_A).getBytes(StandardCharsets.UTF_8)));
            a.startProcess("A", ONLY_A, "BK-A1", Map.of());
            for (String tenant : List.of("A", "B")) {
                a.deploy(tenant, BOTH, Map.of(BOTH + ".bpmn", bpmn(BOTH).getBytes(StandardCharsets.UTF_8)));
                a.startProcess(tenant, BOTH, "BK-" + tenant, Map.of());
            }
            ProcessEngine engine = a.processEngine();
            TaskSuspensionEvents.Listener l = (tasks, suspended) -> tasks.forEach(t -> reportedTenants.add(t.tenantId()));
            TaskSuspensionEvents.register(engine.getName(), l);
            try {
                test.accept(engine);
            } finally {
                TaskSuspensionEvents.unregister(engine.getName(), l);
            }
        }
    }

    /** Runs {@code change} as an identity of tenant {@code tenant}; a refusal by the engine is as good as no change. */
    static void as(ProcessEngine engine, String tenant, Runnable change) {
        engine.getIdentityService().setAuthentication("user-" + tenant, List.of(), List.of(tenant));
        try {
            change.run();
        } catch (ProcessEngineException refused) {
            // refused: nothing changed (asserted by the caller)
        } finally {
            engine.getIdentityService().clearAuthentication();
        }
    }

    static boolean definitionSuspended(ProcessEngine engine, String key, String tenant) {
        return engine.getRepositoryService().createProcessDefinitionQuery().processDefinitionKey(key).tenantIdIn(tenant)
                .singleResult().isSuspended();
    }

    static long suspendedInstances(ProcessEngine engine, String key, String tenant) {
        return engine.getRuntimeService().createProcessInstanceQuery().processDefinitionKey(key).tenantIdIn(tenant)
                .suspended().count();
    }

    static long suspendedTasks(ProcessEngine engine, String key, String tenant) {
        return engine.getTaskService().createTaskQuery().processDefinitionKey(key).tenantIdIn(tenant).suspended().count();
    }

    static boolean jobDefinitionSuspended(ProcessEngine engine, String key, String tenant) {
        return engine.getManagementService().createJobDefinitionQuery().processDefinitionKey(key).tenantIdIn(tenant)
                .singleResult().isSuspended();
    }

    /** Tenant A's definition, instance, task and job definition of {@code key} are in state {@code suspended}. */
    static void assertTenantA(ProcessEngine engine, String key, boolean suspended, String what) {
        assertEquals(suspended, definitionSuspended(engine, key, "A"), what + ": A's definition");
        assertEquals(suspended ? 1 : 0, suspendedInstances(engine, key, "A"), what + ": A's instance");
        assertEquals(suspended ? 1 : 0, suspendedTasks(engine, key, "A"), what + ": A's task");
    }

    @Test
    void tenantBSuspendingByKeyNeverChangesTenantA() {
        withTenants(engine -> {
            var repo = engine.getRepositoryService();
            as(engine, "B", () -> repo.suspendProcessDefinitionByKey(ONLY_A, true, null));
            assertTenantA(engine, ONLY_A, false, "a key only A has");
            as(engine, "B", () -> repo.updateProcessDefinitionSuspensionState().byProcessDefinitionKey(ONLY_A)
                    .includeProcessInstances(true).suspend());
            assertTenantA(engine, ONLY_A, false, "the builder, by key");
            as(engine, "B", () -> repo.updateProcessDefinitionSuspensionState().byProcessDefinitionKey(ONLY_A)
                    .processDefinitionTenantId("A").includeProcessInstances(true).suspend());
            assertTenantA(engine, ONLY_A, false, "the builder, by key and A's tenant id");
            assertEquals(List.of(), reportedTenants, "no task of A reported as suspended");

            as(engine, "B", () -> repo.suspendProcessDefinitionByKey(BOTH, true, null));
            assertTenantA(engine, BOTH, false, "a key both tenants have");
            assertTrue(definitionSuspended(engine, BOTH, "B"), "B's own definition is suspended");
            assertEquals(1, suspendedInstances(engine, BOTH, "B"));
            assertEquals(1, suspendedTasks(engine, BOTH, "B"));
            assertEquals(List.of("B"), reportedTenants, "only B's task is reported");
        });
    }

    @Test
    void tenantBActivatingByKeyNeverChangesTenantA() {
        withTenants(engine -> {
            var repo = engine.getRepositoryService();
            // platform code (no tenant identity) suspends everything first
            repo.suspendProcessDefinitionByKey(ONLY_A, true, null);
            repo.suspendProcessDefinitionByKey(BOTH, true, null);
            assertTenantA(engine, ONLY_A, true, "set-up");
            assertTenantA(engine, BOTH, true, "set-up");
            reportedTenants.clear();

            as(engine, "B", () -> repo.activateProcessDefinitionByKey(ONLY_A, true, null));
            assertTenantA(engine, ONLY_A, true, "a key only A has");
            as(engine, "B", () -> repo.updateProcessDefinitionSuspensionState().byProcessDefinitionKey(ONLY_A)
                    .processDefinitionTenantId("A").includeProcessInstances(true).activate());
            assertTenantA(engine, ONLY_A, true, "the builder, by key and A's tenant id");
            assertEquals(List.of(), reportedTenants, "no task of A reported as activated");

            as(engine, "B", () -> repo.activateProcessDefinitionByKey(BOTH, true, null));
            assertTenantA(engine, BOTH, true, "a key both tenants have");
            assertFalse(definitionSuspended(engine, BOTH, "B"), "B's own definition is active again");
            assertEquals(0, suspendedTasks(engine, BOTH, "B"));
            assertEquals(List.of("B"), reportedTenants, "only B's task is reported");
        });
    }

    @Test
    void tenantBChangingJobDefinitionsByKeyNeverChangesTenantA() {
        withTenants(engine -> {
            var mgmt = engine.getManagementService();
            as(engine, "B", () -> mgmt.suspendJobDefinitionByProcessDefinitionKey(ONLY_A, true, null));
            assertFalse(jobDefinitionSuspended(engine, ONLY_A, "A"), "a key only A has");
            as(engine, "B", () -> mgmt.updateJobDefinitionSuspensionState().byProcessDefinitionKey(ONLY_A)
                    .processDefinitionTenantId("A").includeJobs(true).suspend());
            assertFalse(jobDefinitionSuspended(engine, ONLY_A, "A"), "the builder, by key and A's tenant id");
            as(engine, "B", () -> mgmt.suspendJobDefinitionByProcessDefinitionKey(BOTH, true, null));
            assertFalse(jobDefinitionSuspended(engine, BOTH, "A"), "a key both tenants have");
            assertTrue(jobDefinitionSuspended(engine, BOTH, "B"), "B's own job definition");

            // activation: platform code suspends A's, then B activates by key
            mgmt.suspendJobDefinitionByProcessDefinitionKey(ONLY_A, true, null);
            mgmt.suspendJobDefinitionByProcessDefinitionKey(BOTH, true, null);
            as(engine, "B", () -> mgmt.activateJobDefinitionByProcessDefinitionKey(ONLY_A, true, null));
            assertTrue(jobDefinitionSuspended(engine, ONLY_A, "A"), "a key only A has");
            as(engine, "B", () -> mgmt.activateJobDefinitionByProcessDefinitionKey(BOTH, true, null));
            assertTrue(jobDefinitionSuspended(engine, BOTH, "A"), "a key both tenants have");
            assertFalse(jobDefinitionSuspended(engine, BOTH, "B"), "B's own job definition is active again");
        });
    }
}
