// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.externaltask.LockedExternalTask;
import org.operaton.bpm.engine.impl.RuntimeServiceImpl;
import org.operaton.bpm.engine.impl.ServiceImpl;
import org.operaton.bpm.engine.task.Task;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.SangamEngineFactory;
import org.sangam.engine.SangamEngineSettings;

/**
 * Owner spec SEQ-PARALLEL point 9 / increment I-1: a caller cannot write or remove the engine-owned multi-instance
 * variables ({@code nrOfInstances}, {@code nrOfActiveInstances}, {@code nrOfCompletedInstances}, {@code loopCounter})
 * through any public engine service or builder, while the engine itself keeps driving them. Before the fix each write
 * below succeeds and, for the counter case, changes when the multi-instance body completes.
 */
class EngineOwnedVariablesH2Test {

    private static final String MI = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="eo">
              <process id="eo" isExecutable="true">
                <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="vote"/>
                <userTask id="vote">
                  <multiInstanceLoopCharacteristics isSequential="false">
                    <loopCardinality>3</loopCardinality>
                    <completionCondition>${nrOfCompletedInstances >= 3}</completionCondition>
                  </multiInstanceLoopCharacteristics>
                </userTask>
                <sequenceFlow id="f2" sourceRef="vote" targetRef="after"/>
                <userTask id="after"/>
                <sequenceFlow id="f3" sourceRef="after" targetRef="e"/><endEvent id="e"/>
              </process>
              <process id="ext" isExecutable="true">
                <startEvent id="xs"/><sequenceFlow id="x1" sourceRef="xs" targetRef="work"/>
                <serviceTask id="work" operaton:type="external" operaton:topic="eo-work">
                  <multiInstanceLoopCharacteristics isSequential="true"><loopCardinality>2</loopCardinality>
                  </multiInstanceLoopCharacteristics>
                </serviceTask>
                <sequenceFlow id="x2" sourceRef="work" targetRef="xe"/><endEvent id="xe"/>
              </process>
            </definitions>
            """;

    private OperatonEngineAdapter adapter;
    private ProcessEngine pe;

    @BeforeEach
    void start() {
        SangamEngineSettings s = new SangamEngineSettings();
        s.setSystemAuditScope("system:platform");
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true");
        s.setApplyEnvironment(false);
        s.setJobExecutorActivate(false);
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:eo" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        adapter = SangamEngineFactory.build(ds, s, "h2");
        adapter.deploy("t1", "eo-" + UUID.randomUUID(), Map.of("eo.bpmn", MI.getBytes(StandardCharsets.UTF_8)));
        pe = adapter.processEngine();
    }

    @AfterEach
    void stop() {
        if (adapter != null) {
            adapter.close();
        }
        adapter = null;
        pe = null;
    }

    private String startVote() {
        return adapter.startProcess("t1", "eo", null, Map.of());
    }

    private List<Task> votes(String pid) {
        return pe.getTaskService().createTaskQuery().processInstanceId(pid).taskDefinitionKey("vote").list();
    }

    /** The execution that holds {@code name} locally (the multi-instance body scope or an instance), or null. */
    private String holder(String pid, String name) {
        for (var e : pe.getRuntimeService().createExecutionQuery().processInstanceId(pid).list()) {
            if (pe.getRuntimeService().getVariableLocal(e.getId(), name) != null) {
                return e.getId();
            }
        }
        return null;
    }

    private Object local(String pid, String name) {
        String h = holder(pid, name);
        return h == null ? null : pe.getRuntimeService().getVariableLocal(h, name);
    }

    private static void refused(String what, Runnable write) {
        ProcessEngineException e = assertThrows(ProcessEngineException.class, write::run, what + " must be refused");
        assertTrue(e.getMessage().contains("422 ENGINE_OWNED_VARIABLE"), what + ": " + e.getMessage());
    }

    private static Map<String, Object> vars(String name, Object value) {
        Map<String, Object> m = new HashMap<>();
        m.put(name, value);
        m.put("ordinary", "ok");
        return m;
    }

    @Test
    void runtimeServiceWritesAreRefused() {
        String pid = startVote();
        String exec = votes(pid).getFirst().getExecutionId();
        var rs = pe.getRuntimeService();
        refused("setVariable", () -> rs.setVariable(pid, "nrOfCompletedInstances", 3));
        String body = holder(pid, "nrOfCompletedInstances");
        refused("setVariableLocal on the body", () -> rs.setVariableLocal(body, "nrOfCompletedInstances", 3));
        refused("setVariableLocal", () -> rs.setVariableLocal(exec, "loopCounter", 7));
        refused("setVariables", () -> rs.setVariables(pid, vars("nrOfInstances", 1)));
        refused("setVariablesLocal", () -> rs.setVariablesLocal(exec, vars("nrOfActiveInstances", 0)));
        refused("setVariablesAsync", () -> rs.setVariablesAsync(List.of(pid), vars("loopCounter", 1)));
        refused("removeVariable", () -> rs.removeVariable(pid, "nrOfInstances"));
        refused("removeVariableLocal", () -> rs.removeVariableLocal(exec, "loopCounter"));
        refused("removeVariables", () -> rs.removeVariables(pid, List.of("nrOfCompletedInstances")));
        refused("updateVariables (engine-rest PATCH path)",
                () -> ((RuntimeServiceImpl) rs).updateVariables(pid, vars("nrOfCompletedInstances", 3), List.of()));
        refused("updateVariables deletion", () -> ((RuntimeServiceImpl) rs).updateVariables(pid, Map.of(),
                List.of("nrOfInstances")));
        refused("start with variables", () -> rs.startProcessInstanceByKey("eo", vars("loopCounter", 0)));
        refused("instantiation builder", () -> rs.createProcessInstanceByKey("eo").setVariable("nrOfInstances", 1));
        refused("instantiation builder map", () -> rs.createProcessInstanceByKey("eo")
                .setVariables(vars("nrOfInstances", 1)));
        refused("modification builder", () -> rs.createProcessInstanceModification(pid).startBeforeActivity("after")
                .setVariable("nrOfCompletedInstances", 3));
        refused("message correlation", () -> rs.createMessageCorrelation("m").setVariable("loopCounter", 1));
        refused("message correlation local", () -> rs.createMessageCorrelation("m")
                .setVariablesLocal(vars("loopCounter", 1)));
        refused("message correlation async", () -> rs.createMessageCorrelationAsync("m")
                .setVariables(vars("nrOfInstances", 1)));
        refused("correlateMessage map", () -> rs.correlateMessage("m", "bk", vars("loopCounter", 1)));
        refused("signal builder", () -> rs.createSignalEvent("sig").setVariables(vars("nrOfInstances", 1)));
        refused("signalEventReceived", () -> rs.signalEventReceived("sig", vars("nrOfInstances", 1)));
        refused("condition evaluation", () -> rs.createConditionEvaluation().setVariable("loopCounter", 1));
        // nothing above changed the instance: three votes still open, the counters are the engine's
        assertEquals(3, votes(pid).size());
        assertEquals(0, local(pid, "nrOfCompletedInstances"));
    }

    @Test
    void taskAndFormWritesAreRefused() {
        String pid = startVote();
        Task t = votes(pid).getFirst();
        var ts = pe.getTaskService();
        refused("complete", () -> ts.complete(t.getId(), vars("nrOfCompletedInstances", 3)));
        refused("completeWithVariablesInReturn",
                () -> ts.completeWithVariablesInReturn(t.getId(), vars("loopCounter", 9), false));
        refused("task setVariable", () -> ts.setVariable(t.getId(), "nrOfCompletedInstances", 3));
        refused("task setVariableLocal", () -> ts.setVariableLocal(t.getId(), "loopCounter", 3));
        refused("task setVariables", () -> ts.setVariables(t.getId(), vars("nrOfInstances", 1)));
        refused("task removeVariable", () -> ts.removeVariable(t.getId(), "loopCounter"));
        refused("task handleBpmnError", () -> ts.handleBpmnError(t.getId(), "E", "m", vars("loopCounter", 1)));
        refused("form submit", () -> pe.getFormService().submitTaskForm(t.getId(), vars("nrOfCompletedInstances", 3)));
        // a normal completion still works and the engine still counts
        ts.complete(t.getId(), Map.of("ordinary", "ok"));
        assertEquals(1, local(pid, "nrOfCompletedInstances"));
        assertEquals(2, votes(pid).size());
    }

    @Test
    void tamperingCannotShortCutTheMultiInstanceBody() {
        String pid = startVote();
        Consumer<Runnable> tryIt = r -> {
            try {
                r.run();
            } catch (ProcessEngineException expected) {
                // refused after the fix
            }
        };
        String body = holder(pid, "nrOfCompletedInstances");
        tryIt.accept(() -> pe.getRuntimeService().setVariableLocal(body, "nrOfCompletedInstances", 2));
        pe.getTaskService().complete(votes(pid).getFirst().getId());
        // before the fix the forged counter made one vote complete a three-vote body
        assertEquals(2, votes(pid).size(), "one vote cannot complete a three-vote body");
    }

    @Test
    void externalTaskWritesAreRefused() {
        String pid = adapter.startProcess("t1", "ext", null, Map.of());
        LockedExternalTask w = pe.getExternalTaskService().fetchAndLock(1, "w1").topic("eo-work", 60_000).execute()
                .getFirst();
        var xs = pe.getExternalTaskService();
        refused("worker complete", () -> xs.complete(w.getId(), "w1", vars("loopCounter", 5)));
        refused("worker complete local", () -> xs.complete(w.getId(), "w1", Map.of(), vars("nrOfInstances", 1)));
        refused("worker failure", () -> xs.handleFailure(w.getId(), "w1", "x", "d", 1, 0L, vars("loopCounter", 5),
                Map.of()));
        refused("worker bpmn error", () -> xs.handleBpmnError(w.getId(), "w1", "E", "m", vars("loopCounter", 5)));
        xs.complete(w.getId(), "w1", Map.of("ordinary", "ok"));
        assertEquals(1, local(pid, "loopCounter"), "the engine still drives loopCounter");
    }

    @Test
    void theServicesStayOperatonImplementations() {
        // I-26 and engine-rest cast to ServiceImpl / RuntimeServiceImpl
        assertTrue(pe.getRuntimeService() instanceof ServiceImpl);
        assertTrue(pe.getRuntimeService() instanceof RuntimeServiceImpl);
        assertTrue(pe.getTaskService() instanceof ServiceImpl);
        assertTrue(pe.getFormService() instanceof ServiceImpl);
        assertTrue(pe.getExternalTaskService() instanceof ServiceImpl);
        assertTrue(pe.getRuntimeService() instanceof EngineOwnedServices.Runtime, "I-1 guard installed");
    }
}
