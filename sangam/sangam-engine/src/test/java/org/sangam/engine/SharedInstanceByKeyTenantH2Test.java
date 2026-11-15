// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.operaton.bpm.engine.impl.persistence.entity.SuspensionState;
import org.sangam.engine.deploy.TenantIsolationException;

/**
 * Decision 364 (extends decision 355): an identity of one tenant cannot suspend or activate the process instances,
 * tasks and external tasks of tenantless (shared) instances by process definition key. Operaton's
 * {@code AbstractSetProcessInstanceStateCmd} by key has no command check
 * ({@code TenantCommandChecker#checkUpdateProcessInstanceSuspensionStateByProcessDefinitionKey} is empty, javap 2.2.0)
 * and its three UPDATEs (executions, tasks, external tasks) carry the caller's tenant check, which also matches rows
 * without a tenant, so tenant B's by-key call suspended the shared instances of that key. The call is now refused by
 * name ({@code TENANT_ISOLATION_VIOLATION}) at Sangam's seam and nothing changes; the caller's own instances (a key only
 * it has, or a call naming its tenant) and platform code are unchanged.
 */
class SharedInstanceByKeyTenantH2Test {

    /** A parallel fork: every instance waits at a user task and an external task. */
    static String bpmn(String key) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="t">
                  <process id="KEY" isExecutable="true" operaton:historyTimeToLive="30">
                    <startEvent id="start"/><sequenceFlow id="f0" sourceRef="start" targetRef="fork"/>
                    <parallelGateway id="fork"/>
                    <sequenceFlow id="f1" sourceRef="fork" targetRef="review"/><userTask id="review"/>
                    <sequenceFlow id="f2" sourceRef="fork" targetRef="work"/>
                    <serviceTask id="work" operaton:type="external" operaton:topic="t364"/>
                    <sequenceFlow id="f3" sourceRef="review" targetRef="join"/>
                    <sequenceFlow id="f4" sourceRef="work" targetRef="join"/>
                    <parallelGateway id="join"/><sequenceFlow id="f5" sourceRef="join" targetRef="end"/><endEvent id="end"/>
                  </process>
                </definitions>
                """.replace("KEY", key);
    }

    /** Only a shared (tenantless) definition, with a tenantless instance. */
    static final String SHARED = "shared364";
    /** A shared definition with a tenantless instance, and tenant B's own definition with B's instance. */
    static final String MIXED = "mixed364";
    /** Only tenant B has it. */
    static final String OWN = "own364";

    /** The instance ids: key + "/" + tenant ("-" for the tenantless one). */
    private void withInstances(BiConsumer<ProcessEngine, Map<String, String>> test) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:s364" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamEngineSettings s = new SangamEngineSettings();
        s.setSystemAuditScope("system:platform"); // decision 144: required at engine start
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true");
        s.setJobExecutorActivate(false);
        try (OperatonEngineAdapter a = SangamEngineFactory.build(ds, s, "h2")) {
            ProcessEngine engine = a.processEngine();
            Map<String, String> ids = new HashMap<>();
            for (String key : List.of(SHARED, MIXED)) {
                // platform code (no tenant identity) deploys and starts the shared definitions
                engine.getRepositoryService().createDeployment().name(key).addString(key + ".bpmn", bpmn(key)).deploy();
                ids.put(key + "/-", engine.getRuntimeService().createProcessInstanceByKey(key)
                        .processDefinitionWithoutTenantId().execute().getId());
            }
            for (String key : List.of(MIXED, OWN)) {
                a.deploy("B", key, Map.of(key + ".bpmn", bpmn(key).getBytes(StandardCharsets.UTF_8)));
                ids.put(key + "/B", a.startProcess("B", key, "BK-" + key, Map.of()));
            }
            assertEquals(null, engine.getRuntimeService().createProcessInstanceQuery()
                    .processInstanceId(ids.get(SHARED + "/-")).singleResult().getTenantId(), "the shared instance has no tenant");
            test.accept(engine, ids);
        }
    }

    /** Runs {@code change} as an identity of tenant {@code tenant}. */
    static void as(ProcessEngine engine, String tenant, Executable change) throws Throwable {
        engine.getIdentityService().setAuthentication("user-" + tenant, List.of(), List.of(tenant));
        try {
            change.execute();
        } finally {
            engine.getIdentityService().clearAuthentication();
        }
    }

    static void refusedAs(ProcessEngine engine, String tenant, Executable change, String what) {
        TenantIsolationException e = assertThrows(TenantIsolationException.class, () -> as(engine, tenant, change), what);
        assertTrue(e.getMessage().contains("shared"), e.getMessage());
        assertTrue(e.getMessage().contains("by key"), e.getMessage());
    }

    /** Instance, its user task and its external task: all suspended (true), all active (false), else a failure. */
    static boolean suspended(ProcessEngine engine, String instanceId) {
        boolean instance = engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(instanceId)
                .singleResult().isSuspended();
        boolean task = engine.getTaskService().createTaskQuery().processInstanceId(instanceId).singleResult().isSuspended();
        boolean external = engine.getExternalTaskService().createExternalTaskQuery().processInstanceId(instanceId)
                .singleResult().isSuspended();
        assertEquals(List.of(instance, instance), List.of(task, external),
                "instance, task and external task of " + instanceId + " share one state");
        return instance;
    }

    @Test
    void aTenantCannotSuspendOrActivateSharedInstancesByKey() {
        withInstances((engine, ids) -> {
            var runtime = engine.getRuntimeService();
            refusedAs(engine, "B", () -> runtime.suspendProcessInstanceByProcessDefinitionKey(SHARED), "by key");
            refusedAs(engine, "B", () -> runtime.updateProcessInstanceSuspensionState().byProcessDefinitionKey(SHARED)
                    .suspend(), "the builder by key");
            refusedAs(engine, "B", () -> runtime.updateProcessInstanceSuspensionState().byProcessDefinitionKey(SHARED)
                    .processDefinitionWithoutTenantId().suspend(), "the builder, without tenant");
            refusedAs(engine, "B", () -> runtime.suspendProcessInstanceByProcessDefinitionKey(MIXED),
                    "a key with a shared instance and the caller's own");
            assertFalse(suspended(engine, ids.get(SHARED + "/-")), "the shared instance, task and external task");
            assertFalse(suspended(engine, ids.get(MIXED + "/-")));
            assertFalse(suspended(engine, ids.get(MIXED + "/B")), "the command rolled back as a whole");

            runtime.suspendProcessInstanceByProcessDefinitionKey(SHARED); // platform code may
            assertTrue(suspended(engine, ids.get(SHARED + "/-")));
            refusedAs(engine, "B", () -> runtime.activateProcessInstanceByProcessDefinitionKey(SHARED), "activate");
            refusedAs(engine, "B", () -> runtime.updateProcessInstanceSuspensionState().byProcessDefinitionKey(SHARED)
                    .processDefinitionWithoutTenantId().activate(), "activate, builder without tenant");
            assertTrue(suspended(engine, ids.get(SHARED + "/-")), "still suspended");
            runtime.activateProcessInstanceByProcessDefinitionKey(SHARED); // platform code may
            assertFalse(suspended(engine, ids.get(SHARED + "/-")));
        });
    }

    @Test
    void theCallersOwnInstancesByKeyAreUnchanged() {
        withInstances((engine, ids) -> {
            var runtime = engine.getRuntimeService();
            assertDoesNotThrow(() -> as(engine, "B", () -> runtime.updateProcessInstanceSuspensionState()
                    .byProcessDefinitionKey(MIXED).processDefinitionTenantId("B").suspend()));
            assertTrue(suspended(engine, ids.get(MIXED + "/B")), "by key and the caller's own tenant");
            assertFalse(suspended(engine, ids.get(MIXED + "/-")), "the shared instance is not touched");
            assertDoesNotThrow(() -> as(engine, "B", () -> runtime.updateProcessInstanceSuspensionState()
                    .byProcessDefinitionKey(MIXED).processDefinitionTenantId("B").activate()));
            assertFalse(suspended(engine, ids.get(MIXED + "/B")));

            assertDoesNotThrow(() -> as(engine, "B", () -> runtime.suspendProcessInstanceByProcessDefinitionKey(OWN)));
            assertTrue(suspended(engine, ids.get(OWN + "/B")), "a key only the caller's tenant has");
            assertDoesNotThrow(() -> as(engine, "B", () -> runtime.activateProcessInstanceByProcessDefinitionKey(OWN)));
            assertFalse(suspended(engine, ids.get(OWN + "/B")));
            assertFalse(suspended(engine, ids.get(SHARED + "/-")));
        });
    }

    /**
     * Each of the three by-key UPDATEs refuses on its own (the executions run first in Operaton's command, so the API
     * test above reaches only that one): the seam on the task and external-task managers is not a dead branch.
     */
    @Test
    void eachByKeySeamRefusesSharedRowsOnItsOwn() {
        withInstances((engine, ids) -> {
            ProcessEngineConfigurationImpl cfg = (ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration();
            SuspensionState s = SuspensionState.SUSPENDED;
            Map<String, java.util.function.Consumer<CommandContext>> refused = new java.util.LinkedHashMap<>();
            refused.put("executions by key", cc -> cc.getExecutionManager()
                    .updateExecutionSuspensionStateByProcessDefinitionKey(SHARED, s));
            refused.put("executions without tenant", cc -> cc.getExecutionManager()
                    .updateExecutionSuspensionStateByProcessDefinitionKeyAndTenantId(SHARED, null, s));
            refused.put("tasks by key", cc -> cc.getTaskManager().updateTaskSuspensionStateByProcessDefinitionKey(SHARED, s));
            refused.put("tasks without tenant", cc -> cc.getTaskManager()
                    .updateTaskSuspensionStateByProcessDefinitionKeyAndTenantId(SHARED, null, s));
            refused.put("external tasks by key", cc -> cc.getExternalTaskManager()
                    .updateExternalTaskSuspensionStateByProcessDefinitionKey(SHARED, s));
            refused.put("external tasks without tenant", cc -> cc.getExternalTaskManager()
                    .updateExternalTaskSuspensionStateByProcessDefinitionKeyAndTenantId(SHARED, null, s));
            refused.forEach((what, call) -> refusedAs(engine, "B",
                    () -> cfg.getCommandExecutorTxRequired().execute(cc -> {
                        call.accept(cc);
                        return null;
                    }), what));
            assertFalse(suspended(engine, ids.get(SHARED + "/-")), "nothing changed");

            assertDoesNotThrow(() -> as(engine, "B", () -> cfg.getCommandExecutorTxRequired().execute(cc -> {
                cc.getExecutionManager().updateExecutionSuspensionStateByProcessDefinitionKeyAndTenantId(MIXED, "B", s);
                cc.getTaskManager().updateTaskSuspensionStateByProcessDefinitionKeyAndTenantId(MIXED, "B", s);
                cc.getExternalTaskManager().updateExternalTaskSuspensionStateByProcessDefinitionKeyAndTenantId(MIXED, "B", s);
                return null;
            })));
            assertTrue(suspended(engine, ids.get(MIXED + "/B")), "the caller's own tenant named");
            assertFalse(suspended(engine, ids.get(MIXED + "/-")));
        });
    }
}
