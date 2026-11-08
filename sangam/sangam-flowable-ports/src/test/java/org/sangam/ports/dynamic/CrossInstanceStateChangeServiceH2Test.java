// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.dynamic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.sangam.ports.PortsH2Engine;

/** FC-17 move across call-activity boundaries: same root and tenant only, atomic. */
class CrossInstanceStateChangeServiceH2Test {

    private static final String PARENT = """
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="t">
              <process id="parent" isExecutable="true">
                <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="call"/>
                <callActivity id="call" calledElement="child"/><sequenceFlow id="f2" sourceRef="call" targetRef="done"/>
                <userTask id="done"/>
                <userTask id="rework"/>
              </process>
            </definitions>""";

    private static final String CHILD = """
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="t">
              <process id="child" isExecutable="true">
                <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="work"/>
                <userTask id="work"/><sequenceFlow id="f2" sourceRef="work" targetRef="e"/><endEvent id="e"/>
              </process>
            </definitions>""";

    private PortsH2Engine h2;
    private CrossInstanceStateChangeService svc;

    @BeforeEach
    void setUp() {
        h2 = PortsH2Engine.start(null);
        h2.engine.getRepositoryService().createDeployment().addString("p.bpmn", PARENT).addString("c.bpmn", CHILD).deploy();
        h2.engine.getRepositoryService().createDeployment().addString("c.bpmn", CHILD).tenantId("t1").deploy();
        svc = new CrossInstanceStateChangeService(h2.engine);
    }

    @AfterEach
    void tearDown() {
        h2.close();
    }

    private ProcessInstance child(String parentId) {
        return h2.engine.getRuntimeService().createProcessInstanceQuery().superProcessInstanceId(parentId).singleResult();
    }

    private String activityInstance(String pid, String activityId) {
        return h2.engine.getRuntimeService().getActivityInstance(pid).getActivityInstances(activityId)[0].getId();
    }

    private long tasks(String pid, String key) {
        return h2.engine.getTaskService().createTaskQuery().processInstanceId(pid).taskDefinitionKey(key).count();
    }

    @Test
    void movesAWaitingChildActivityToTheParent() {
        String parent = h2.engine.getRuntimeService().startProcessInstanceByKey("parent").getId();
        ProcessInstance child = child(parent);
        svc.moveToParent(child.getId(), activityInstance(child.getId(), "work"), "rework", "wrong data");
        assertEquals(1, tasks(parent, "rework"), "target started in the parent");
        assertEquals(0, h2.engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(child.getId()).count(), "source cancelled");
    }

    @Test
    void refusesInstancesOfDifferentRootsOrTenantsAndKeepsBothUntouched() {
        String p1 = h2.engine.getRuntimeService().startProcessInstanceByKey("parent").getId();
        String p2 = h2.engine.getRuntimeService().startProcessInstanceByKey("parent").getId();
        ProcessInstance c1 = child(p1);
        assertThrows(ProcessEngineException.class,
                () -> svc.move(c1.getId(), activityInstance(c1.getId(), "work"), p2, "rework", null, "x"), "different roots");

        String other = h2.engine.getRuntimeService().createProcessInstanceByKey("child").processDefinitionTenantId("t1").execute().getId();
        assertThrows(ProcessEngineException.class,
                () -> svc.move(other, activityInstance(other, "work"), p1, "rework", null, "x"), "different tenants");

        assertThrows(ProcessEngineException.class, () -> svc.moveToParent(p1, activityInstance(c1.getId(), "work"), "rework", "x"),
                "a root instance has no parent");
        assertThrows(ProcessEngineException.class, () -> svc.move("missing", "ai", p1, "rework", null, "x"));

        assertEquals(0, tasks(p1, "rework"));
        assertEquals(0, tasks(p2, "rework"));
        assertEquals(1, tasks(c1.getId(), "work"));
        assertEquals(1, tasks(other, "work"));
    }

    @Test
    void failedStartLeavesTheSourceInPlace() {
        String parent = h2.engine.getRuntimeService().startProcessInstanceByKey("parent").getId();
        ProcessInstance child = child(parent);
        assertThrows(ProcessEngineException.class,
                () -> svc.moveToParent(child.getId(), activityInstance(child.getId(), "work"), "no-such-activity", "x"));
        assertEquals(1, tasks(child.getId(), "work"));
    }
}
