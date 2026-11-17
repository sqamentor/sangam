// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.adhoc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.task.Task;
import org.sangam.ports.PortsH2Engine;

/**
 * P-6 standard loop (BPMN 2.0.2 §10.3.8). Fail-first: before P-6 the validator refused the model (D-52), and plain
 * Operaton ran the activity once and dropped the loop.
 */
class StandardLoopH2Test {

    static String model(String key, String loop) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" targetNamespace="t">
                  <process id="%s" isExecutable="true">
                    <startEvent id="s"/>
                    <sequenceFlow id="f1" sourceRef="s" targetRef="work"/>
                    <userTask id="work">%s</userTask>
                    <sequenceFlow id="f2" sourceRef="work" targetRef="after"/>
                    <userTask id="after"/>
                    <sequenceFlow id="f3" sourceRef="after" targetRef="e"/>
                    <endEvent id="e"/>
                  </process>
                </definitions>
                """.formatted(key, loop);
    }

    private PortsH2Engine h2;

    @BeforeEach
    void setUp() {
        h2 = PortsH2Engine.start(null);
    }

    @AfterEach
    void tearDown() {
        h2.close();
        h2 = null;
    }

    private void deploy(String key, String loop) {
        h2.engine.getRepositoryService().createDeployment().addString(key + ".bpmn", model(key, loop)).deploy();
    }

    private List<Task> tasks(String pid) {
        return h2.engine.getTaskService().createTaskQuery().processInstanceId(pid).list();
    }

    /** Completes "work" until it is gone; returns the loopCounter seen by each iteration. */
    private List<Object> drive(String pid, Map<String, Object> varsPerCompletion) {
        java.util.ArrayList<Object> counters = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) {
            Task t = h2.engine.getTaskService().createTaskQuery().processInstanceId(pid).taskDefinitionKey("work").singleResult();
            if (t == null) {
                break;
            }
            counters.add(h2.engine.getTaskService().getVariable(t.getId(), "loopCounter"));
            h2.engine.getTaskService().complete(t.getId(), varsPerCompletion);
        }
        return counters;
    }

    @Test
    void doWhileRepeatsWhileTheConditionHoldsAndExposesLoopCounter() {
        deploy("dowhile", "<standardLoopCharacteristics><loopCondition xsi:type=\"tFormalExpression\">${loopCounter &lt; 3}</loopCondition></standardLoopCharacteristics>");
        String pid = h2.engine.getRuntimeService().startProcessInstanceByKey("dowhile").getId();
        assertEquals(List.of(0, 1, 2, 3), drive(pid, Map.of()), "do-while: runs, then tests loopCounter (completed iterations)");
        assertEquals(List.of("after"), tasks(pid).stream().map(Task::getTaskDefinitionKey).toList());
    }

    @Test
    void doWhileRunsOnceEvenWhenTheConditionIsFalse() {
        deploy("once", "<standardLoopCharacteristics><loopCondition xsi:type=\"tFormalExpression\">${false}</loopCondition></standardLoopCharacteristics>");
        String pid = h2.engine.getRuntimeService().startProcessInstanceByKey("once").getId();
        assertEquals(List.of(0), drive(pid, Map.of()));
        assertEquals(List.of("after"), tasks(pid).stream().map(Task::getTaskDefinitionKey).toList());
    }

    @Test
    void testBeforeSkipsTheActivityWhenFalseAtEntry() {
        deploy("whileloop", "<standardLoopCharacteristics testBefore=\"true\"><loopCondition xsi:type=\"tFormalExpression\">${more}</loopCondition></standardLoopCharacteristics>");
        String pid = h2.engine.getRuntimeService().startProcessInstanceByKey("whileloop", Map.of("more", false)).getId();
        assertEquals(List.of("after"), tasks(pid).stream().map(Task::getTaskDefinitionKey).toList(), "while: never ran");
    }

    @Test
    void conditionReadsProcessDataSetByTheIteration() {
        deploy("data", "<standardLoopCharacteristics testBefore=\"true\"><loopCondition xsi:type=\"tFormalExpression\">${more}</loopCondition></standardLoopCharacteristics>");
        String pid = h2.engine.getRuntimeService().startProcessInstanceByKey("data", Map.of("more", true)).getId();
        for (int i = 0; i < 2; i++) {
            Task t = h2.engine.getTaskService().createTaskQuery().processInstanceId(pid).taskDefinitionKey("work").singleResult();
            h2.engine.getTaskService().complete(t.getId(), Map.of("more", true));
        }
        assertTrue(tasks(pid).stream().anyMatch(t -> t.getTaskDefinitionKey().equals("work")), "still looping while more=true");
        Task t = h2.engine.getTaskService().createTaskQuery().processInstanceId(pid).taskDefinitionKey("work").singleResult();
        assertEquals(2, h2.engine.getTaskService().getVariable(t.getId(), "loopCounter"));
        h2.engine.getTaskService().complete(t.getId(), Map.of("more", false));
        assertEquals(List.of("after"), tasks(pid).stream().map(Task::getTaskDefinitionKey).toList());
    }

    @Test
    void loopMaximumCapsTheIterations() {
        deploy("capped", "<standardLoopCharacteristics loopMaximum=\"2\"><loopCondition xsi:type=\"tFormalExpression\">${true}</loopCondition></standardLoopCharacteristics>");
        String pid = h2.engine.getRuntimeService().startProcessInstanceByKey("capped").getId();
        assertEquals(List.of(0, 1), drive(pid, Map.of()));
        assertEquals(List.of("after"), tasks(pid).stream().map(Task::getTaskDefinitionKey).toList());
    }

    @Test
    void loopMaximumZeroWithTestBeforeNeverRuns() {
        deploy("zero", "<standardLoopCharacteristics testBefore=\"true\" loopMaximum=\"0\"/>");
        String pid = h2.engine.getRuntimeService().startProcessInstanceByKey("zero").getId();
        assertEquals(List.of("after"), tasks(pid).stream().map(Task::getTaskDefinitionKey).toList());
    }

    @Test
    void aNonBooleanConditionFailsTheStep() {
        deploy("strict", "<standardLoopCharacteristics><loopCondition xsi:type=\"tFormalExpression\">${'yes'}</loopCondition></standardLoopCharacteristics>");
        String pid = h2.engine.getRuntimeService().startProcessInstanceByKey("strict").getId();
        Task t = tasks(pid).get(0);
        ProcessEngineException ex = assertThrows(ProcessEngineException.class, () -> h2.engine.getTaskService().complete(t.getId()));
        assertTrue(ex.getMessage().contains("SANGAM-LOOP-CONDITION"), ex.getMessage());
    }

    @Test
    void aLoopThatCanNeverEndIsRefusedAtDeployment() {
        ProcessEngineException ex = assertThrows(ProcessEngineException.class,
                () -> deploy("endless", "<standardLoopCharacteristics/>"));
        assertTrue(ex.getMessage().contains("loopCondition or a loopMaximum"), ex.getMessage());
    }

    @Test
    void theLoopBodyIsASequentialLoopInTheActivityInstanceTree() {
        deploy("tree", "<standardLoopCharacteristics loopMaximum=\"2\"/>");
        String pid = h2.engine.getRuntimeService().startProcessInstanceByKey("tree").getId();
        var tree = h2.engine.getRuntimeService().getActivityInstance(pid);
        assertEquals("work#multiInstanceBody", tree.getChildActivityInstances()[0].getActivityId());
        assertEquals("work", tree.getChildActivityInstances()[0].getChildActivityInstances()[0].getActivityId());
    }
}
