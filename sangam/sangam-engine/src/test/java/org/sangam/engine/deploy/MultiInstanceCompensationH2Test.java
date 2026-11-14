// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.deploy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.externaltask.LockedExternalTask;
import org.operaton.bpm.engine.task.Task;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.SangamEngineFactory;
import org.sangam.engine.SangamEngineSettings;

/**
 * Decision 148 (FIO D-27) reproduction and regression: the compensation handler of a multi-instance activity runs in
 * the context of the instance it compensates ({@code loopCounter}, the element variable and instance locals), for
 * parallel and sequential bodies, with a completion condition (only completed instances are compensated), across an
 * engine restart, with isolated handler variables and exactly one handler run per completed instance. Before the fix
 * every handler saw {@code loopCounter = null} (E16a #7, D-TENDER); these assertions fail on that code.
 */
class MultiInstanceCompensationH2Test {

    private JdbcDataSource ds;

    private static String model(String id, String loop, String completion) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="d27">
                  <process id="%s" isExecutable="true">
                    <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="hold"/>
                    <userTask id="hold">
                      <multiInstanceLoopCharacteristics %s operaton:collection="${bidders}"
                          operaton:elementVariable="bidder">%s</multiInstanceLoopCharacteristics>
                    </userTask>
                    <boundaryEvent id="holdComp" attachedToRef="hold"><compensateEventDefinition/></boundaryEvent>
                    <userTask id="refund" isForCompensation="true"/>
                    <association id="a1" associationDirection="One" sourceRef="holdComp" targetRef="refund"/>
                    <sequenceFlow id="f2" sourceRef="hold" targetRef="decide"/>
                    <userTask id="decide"/>
                    <sequenceFlow id="f3" sourceRef="decide" targetRef="comp"/>
                    <intermediateThrowEvent id="comp"><compensateEventDefinition/></intermediateThrowEvent>
                    <sequenceFlow id="f4" sourceRef="comp" targetRef="e"/><endEvent id="e"/>
                  </process>
                </definitions>
                """.formatted(id, loop, completion);
    }

    private static final String EXTERNAL = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="d27">
              <process id="tender" isExecutable="true">
                <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="emd"/>
                <serviceTask id="emd" operaton:type="external" operaton:topic="emd-hold">
                  <multiInstanceLoopCharacteristics isSequential="false">
                    <loopCardinality>${bidCount}</loopCardinality>
                  </multiInstanceLoopCharacteristics>
                </serviceTask>
                <boundaryEvent id="emdComp" attachedToRef="emd"><compensateEventDefinition/></boundaryEvent>
                <serviceTask id="refund" isForCompensation="true" operaton:type="external" operaton:topic="emd-refund"/>
                <association id="a1" associationDirection="One" sourceRef="emdComp" targetRef="refund"/>
                <sequenceFlow id="f2" sourceRef="emd" targetRef="comp"/>
                <intermediateThrowEvent id="comp"><compensateEventDefinition/></intermediateThrowEvent>
                <sequenceFlow id="f3" sourceRef="comp" targetRef="e"/><endEvent id="e"/>
              </process>
            </definitions>
            """;

    private OperatonEngineAdapter engine() {
        SangamEngineSettings s = new SangamEngineSettings();
        s.setSystemAuditScope("system:platform"); // decision 144: required at engine start
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true");
        s.setApplyEnvironment(false);
        s.setJobExecutorActivate(false);
        if (ds == null) {
            ds = new JdbcDataSource();
            ds.setURL("jdbc:h2:mem:d27" + UUID.randomUUID().toString().replace("-", "")
                    + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        }
        return SangamEngineFactory.build(ds, s, "h2");
    }

    private static void deploy(OperatonEngineAdapter a, String name, String xml) {
        a.deploy("t1", "d27-" + UUID.randomUUID(), Map.of(name, xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static Map<String, Object> bidders(String... names) {
        Map<String, Object> v = new HashMap<>();
        v.put("bidders", new ArrayList<>(List.of(names)));
        return v;
    }

    private static List<Task> tasks(ProcessEngine pe, String pid, String key) {
        return pe.getTaskService().createTaskQuery().processInstanceId(pid).taskDefinitionKey(key).list();
    }

    /** Completes every open {@code hold} task, putting an instance-local marker on its execution first. */
    private static void completeHolds(ProcessEngine pe, String pid, int howMany) {
        for (int i = 0; i < howMany; i++) {
            Task t = tasks(pe, pid, "hold").getFirst();
            Object bidder = pe.getRuntimeService().getVariableLocal(t.getExecutionId(), "bidder");
            pe.getRuntimeService().setVariableLocal(t.getExecutionId(), "receipt", "EMD-" + bidder);
            pe.getTaskService().complete(t.getId());
        }
    }

    /** loopCounter to bidder and receipt, as each open refund handler sees them. */
    private static Map<Integer, String> handlerContexts(ProcessEngine pe, String pid) {
        Map<Integer, String> seen = new TreeMap<>();
        for (Task t : tasks(pe, pid, "refund")) {
            Map<String, Object> v = pe.getTaskService().getVariables(t.getId());
            Object lc = v.get("loopCounter");
            assertTrue(lc instanceof Integer, "D-27: the handler sees the compensated instance's loopCounter: " + v);
            seen.put((Integer) lc, v.get("bidder") + "/" + v.get("receipt"));
        }
        return seen;
    }

    @Test
    void parallelHandlersEachSeeTheirOwnInstance() {
        try (OperatonEngineAdapter a = engine()) {
            deploy(a, "par.bpmn", model("par", "isSequential=\"false\"", ""));
            ProcessEngine pe = a.processEngine();
            String pid = a.startProcess("t1", "par", null, bidders("B1", "B2", "B3"));
            completeHolds(pe, pid, 3);
            pe.getTaskService().complete(tasks(pe, pid, "decide").getFirst().getId());
            assertEquals(Map.of(0, "B1/EMD-B1", 1, "B2/EMD-B2", 2, "B3/EMD-B3"), handlerContexts(pe, pid));
        }
    }

    @Test
    void sequentialHandlersEachSeeTheirOwnInstance() {
        try (OperatonEngineAdapter a = engine()) {
            deploy(a, "seq.bpmn", model("seq", "isSequential=\"true\"", ""));
            ProcessEngine pe = a.processEngine();
            String pid = a.startProcess("t1", "seq", null, bidders("B1", "B2", "B3"));
            completeHolds(pe, pid, 3);
            pe.getTaskService().complete(tasks(pe, pid, "decide").getFirst().getId());
            // SL-101: every completed instance of the one step is compensated (Operaton's handler for a body); the
            // sequential body overwrote loopCounter per instance, so each handler must still see its own instance
            assertEquals(Map.of(0, "B1/EMD-B1", 1, "B2/EMD-B2", 2, "B3/EMD-B3"), handlerContexts(pe, pid));
            for (Task t : tasks(pe, pid, "refund")) {
                pe.getTaskService().complete(t.getId());
            }
            assertTrue(pe.getRuntimeService().createProcessInstanceQuery().processInstanceId(pid).list().isEmpty());
        }
    }

    @Test
    void completionConditionCompensatesOnlyCompletedInstances() {
        try (OperatonEngineAdapter a = engine()) {
            deploy(a, "cc.bpmn", model("cc", "isSequential=\"false\"",
                    "<completionCondition>${nrOfCompletedInstances >= 2}</completionCondition>"));
            ProcessEngine pe = a.processEngine();
            String pid = a.startProcess("t1", "cc", null, bidders("B1", "B2", "B3"));
            completeHolds(pe, pid, 2);
            assertTrue(tasks(pe, pid, "hold").isEmpty(), "the third instance was cancelled by the condition");
            pe.getTaskService().complete(tasks(pe, pid, "decide").getFirst().getId());
            Map<Integer, String> seen = handlerContexts(pe, pid);
            assertEquals(2, seen.size(), "no handler for the cancelled instance: " + seen);
            assertTrue(seen.values().stream().allMatch(s -> s.endsWith("/EMD-" + s.substring(0, s.indexOf('/')))),
                    "each handler sees its own instance: " + seen);
        }
    }

    @Test
    void handlerVariablesAreIsolatedAndEachInstanceIsCompensatedOnce() {
        try (OperatonEngineAdapter a = engine()) {
            deploy(a, "iso.bpmn", model("iso", "isSequential=\"false\"", ""));
            ProcessEngine pe = a.processEngine();
            String pid = a.startProcess("t1", "iso", null, bidders("B1", "B2"));
            completeHolds(pe, pid, 2);
            pe.getTaskService().complete(tasks(pe, pid, "decide").getFirst().getId());
            List<Task> refunds = tasks(pe, pid, "refund");
            assertEquals(2, refunds.size());
            pe.getRuntimeService().setVariableLocal(refunds.getFirst().getExecutionId(), "receipt", "CHANGED");
            Map<String, Object> other = pe.getTaskService().getVariables(refunds.get(1).getId());
            assertTrue(!"CHANGED".equals(other.get("receipt")), "a handler's change does not leak to another: " + other);
            assertTrue(!pe.getRuntimeService().getVariables(pid).containsKey("receipt"),
                    "instance variables never reach the process scope");
            for (Task t : refunds) {
                pe.getTaskService().complete(t.getId());
            }
            assertEquals(2, pe.getHistoryService().createHistoricTaskInstanceQuery().processInstanceId(pid)
                    .taskDefinitionKey("refund").count(), "one handler run per completed instance");
            assertTrue(pe.getRuntimeService().createProcessInstanceQuery().processInstanceId(pid).list().isEmpty());
        }
    }

    @Test
    void theInstanceContextSurvivesAnEngineRestart() {
        String pid;
        try (OperatonEngineAdapter a = engine()) {
            deploy(a, "rst.bpmn", model("rst", "isSequential=\"false\"", ""));
            ProcessEngine pe = a.processEngine();
            pid = a.startProcess("t1", "rst", null, bidders("B1", "B2", "B3"));
            completeHolds(pe, pid, 3);
        }
        try (OperatonEngineAdapter b = engine()) {
            ProcessEngine pe = b.processEngine();
            pe.getTaskService().complete(tasks(pe, pid, "decide").getFirst().getId());
            assertEquals(Map.of(0, "B1/EMD-B1", 1, "B2/EMD-B2", 2, "B3/EMD-B3"), handlerContexts(pe, pid));
        }
    }

    /** A multi-instance embedded sub-process whose inner step has its own compensation handler (a scope inner activity). */
    private static final String SUBPROCESS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="d27">
              <process id="subMi" isExecutable="true">
                <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="perBidder"/>
                <subProcess id="perBidder">
                  <multiInstanceLoopCharacteristics isSequential="false" operaton:collection="${bidders}"
                      operaton:elementVariable="bidder"/>
                  <startEvent id="ss"/><sequenceFlow id="g1" sourceRef="ss" targetRef="hold"/>
                  <userTask id="hold"/>
                  <boundaryEvent id="holdComp" attachedToRef="hold"><compensateEventDefinition/></boundaryEvent>
                  <userTask id="refund" isForCompensation="true"/>
                  <association id="a1" associationDirection="One" sourceRef="holdComp" targetRef="refund"/>
                  <sequenceFlow id="g2" sourceRef="hold" targetRef="se"/><endEvent id="se"/>
                </subProcess>
                <sequenceFlow id="f2" sourceRef="perBidder" targetRef="decide"/>
                <userTask id="decide"/>
                <sequenceFlow id="f3" sourceRef="decide" targetRef="comp"/>
                <intermediateThrowEvent id="comp"><compensateEventDefinition/></intermediateThrowEvent>
                <sequenceFlow id="f4" sourceRef="comp" targetRef="e"/><endEvent id="e"/>
              </process>
            </definitions>
            """;

    /**
     * Decision 148 open point (2) of 2026-11-12T19:37:57Z: an embedded sub-process inside the multi-instance body, with
     * its own compensable child. The sub-process is a scope, so Operaton snapshots its scope execution; the context
     * adds the instance variables that live above it ({@code loopCounter}, the element variable), so each child
     * handler sees the instance it compensates and its own instance-local data.
     */
    @Test
    void anEmbeddedSubProcessBodyCompensatesEachChildInItsInstance() {
        try (OperatonEngineAdapter a = engine()) {
            deploy(a, "sub.bpmn", SUBPROCESS);
            ProcessEngine pe = a.processEngine();
            String pid = a.startProcess("t1", "subMi", null, bidders("B1", "B2", "B3"));
            for (int i = 0; i < 3; i++) {
                // the element variable lives on the instance execution above the sub-process scope: read it inherited
                Task t = tasks(pe, pid, "hold").getFirst();
                Object bidder = pe.getRuntimeService().getVariable(t.getExecutionId(), "bidder");
                pe.getRuntimeService().setVariableLocal(t.getExecutionId(), "receipt", "EMD-" + bidder);
                pe.getTaskService().complete(t.getId());
            }
            pe.getTaskService().complete(tasks(pe, pid, "decide").getFirst().getId());
            assertEquals(Map.of(0, "B1/EMD-B1", 1, "B2/EMD-B2", 2, "B3/EMD-B3"), handlerContexts(pe, pid));
            for (Task t : tasks(pe, pid, "refund")) {
                pe.getTaskService().complete(t.getId());
            }
            assertEquals(3, pe.getHistoryService().createHistoricTaskInstanceQuery().processInstanceId(pid)
                    .taskDefinitionKey("refund").count(), "one handler run per completed instance");
            assertTrue(pe.getRuntimeService().createProcessInstanceQuery().processInstanceId(pid).list().isEmpty());
        }
    }

    @Test
    void externalTaskHandlersOfTheTenderShapeSeeTheirInstanceAndRetryKeepsIt() {
        try (OperatonEngineAdapter a = engine()) {
            deploy(a, "tender.bpmn", EXTERNAL);
            ProcessEngine pe = a.processEngine();
            String pid = a.startProcess("t1", "tender", null, Map.of("bidCount", 3));
            List<LockedExternalTask> holds = pe.getExternalTaskService().fetchAndLock(10, "w")
                    .topic("emd-hold", 60_000).execute();
            assertEquals(3, holds.size());
            for (LockedExternalTask t : holds) {
                pe.getExternalTaskService().complete(t.getId(), "w");
            }
            List<LockedExternalTask> refunds = pe.getExternalTaskService().fetchAndLock(10, "w")
                    .topic("emd-refund", 60_000).execute();
            List<Integer> counters = new ArrayList<>();
            for (LockedExternalTask t : refunds) {
                counters.add((Integer) t.getVariables().get("loopCounter"));
            }
            counters.sort(null);
            assertEquals(List.of(0, 1, 2), counters, "each refund knows its EMD (no product ledger guess)");
            // a failed attempt with a retry keeps the same execution, so the same instance context
            LockedExternalTask first = refunds.getFirst();
            Object lc = first.getVariables().get("loopCounter");
            pe.getExternalTaskService().handleFailure(first.getId(), "w", "transient", 1, 0);
            List<LockedExternalTask> again = pe.getExternalTaskService().fetchAndLock(10, "w")
                    .topic("emd-refund", 60_000).execute();
            assertEquals(1, again.size());
            assertEquals(lc, again.getFirst().getVariables().get("loopCounter"));
            for (LockedExternalTask t : refunds.subList(1, refunds.size())) {
                pe.getExternalTaskService().complete(t.getId(), "w");
            }
            pe.getExternalTaskService().complete(again.getFirst().getId(), "w");
            assertTrue(pe.getRuntimeService().createProcessInstanceQuery().processInstanceId(pid).list().isEmpty());
        }
    }
}
