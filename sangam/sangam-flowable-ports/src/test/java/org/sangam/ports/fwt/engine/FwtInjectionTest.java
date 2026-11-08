// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.fwt.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.task.Task;
import org.sangam.ports.dynamic.CrossInstanceStateChangeService;
import org.sangam.ports.dynamic.DynamicInjectionService;
import org.sangam.ports.dynamic.DynamicInjectionService.UserTaskSpec;
import org.sangam.ports.support.PortsProblem;

/** FW-15 / FW-33 / FW-34 scenarios on DynamicInjectionService (FWT-034..038, FWT-054). */
class FwtInjectionTest {

    static final String HOST = FwtEngine.defs("""
              <process id="host" isExecutable="true">
                <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="hostTask"/>
                <userTask id="hostTask"/><sequenceFlow id="f2" sourceRef="hostTask" targetRef="e"/>
                <endEvent id="e"/>
              </process>""");

    FwtEngine f;
    DynamicInjectionService inj;

    @BeforeEach
    void setUp() {
        f = new FwtEngine();
        inj = new DynamicInjectionService(f.engine, FwtEngine.D, f.plugin.links(), DynamicInjectionService.InjectionPolicy.ALLOW_ALL);
    }

    @AfterEach
    void tearDown() {
        f.close();
    }

    private String startHost() {
        f.deploy("host.bpmn", HOST);
        return f.engine.getRuntimeService().startProcessInstanceByKey("host").getId();
    }

    private long deployments() {
        return f.engine.getRepositoryService().createDeploymentQuery().count();
    }

    // FWT-034
    @Test
    void fwt034_expressionInAssigneeRefusedNothingDeployed() {
        String pid = startHost();
        long before = deployments();
        PortsProblem p = assertThrows(PortsProblem.class, () -> inj.injectUserTask(pid,
                new UserTaskSpec("t", "${execution.setVariable('x',1)}", null, null, null), "u"));
        assertEquals("EXPRESSION_NOT_ALLOWED", p.code());
        assertEquals(422, p.status());
        assertEquals(before, deployments(), "nothing deployed");
        assertNull(f.engine.getRuntimeService().getVariable(pid, "x"));
        assertEquals(0, f.count("SELECT COUNT(*) FROM DYN_INJECTION"));
    }

    // FWT-036
    @Test
    void fwt036_invalidFragmentsRefused() {
        String pid = startHost();
        String twoStarts = FwtEngine.defs("""
                <process id="frag" isExecutable="true"><startEvent id="a"/><startEvent id="b"/>
                <sequenceFlow id="x" sourceRef="a" targetRef="e"/><sequenceFlow id="y" sourceRef="b" targetRef="e"/><endEvent id="e"/></process>""");
        String noEnd = FwtEngine.defs("""
                <process id="frag" isExecutable="true"><startEvent id="a"/><sequenceFlow id="x" sourceRef="a" targetRef="t"/><userTask id="t"/></process>""");
        String messageRef = FwtEngine.defs("""
                <message id="m" name="m"/>
                <process id="frag" isExecutable="true"><startEvent id="a"/><sequenceFlow id="x" sourceRef="a" targetRef="r"/>
                <receiveTask id="r" messageRef="m"/><sequenceFlow id="y" sourceRef="r" targetRef="e"/><endEvent id="e"/></process>""");
        long before = deployments();
        for (String frag : List.of(twoStarts, noEnd, messageRef)) {
            PortsProblem p = assertThrows(PortsProblem.class, () -> inj.injectEmbeddedSubProcess(pid, "x", f.bytes(frag), "u"));
            assertEquals("FRAGMENT_INVALID", p.code());
            assertEquals(422, p.status());
        }
        assertEquals(before, deployments());
    }

    // FWT-037
    @Test
    void fwt037_twentyFirstInjectionAndSuspendedInstanceRefused() {
        String pid = startHost();
        for (int i = 0; i < DynamicInjectionService.MAX_INJECTIONS_PER_INSTANCE; i++) {
            inj.injectUserTask(pid, new UserTaskSpec("t" + i, null, null, null, null), "u");
        }
        PortsProblem p = assertThrows(PortsProblem.class, () -> inj.injectUserTask(pid, new UserTaskSpec("t21", null, null, null, null), "u"));
        assertEquals("LIMIT_EXCEEDED", p.code());
        assertEquals(20, f.count("SELECT COUNT(*) FROM DYN_INJECTION WHERE process_instance_id = ?", pid));

        String pid2 = f.engine.getRuntimeService().startProcessInstanceByKey("host").getId();
        f.engine.getRuntimeService().suspendProcessInstanceById(pid2);
        // IllegalStateException is mapped to 409 INVALID_STATE by sangam-server PortsErrors
        assertThrows(IllegalStateException.class, () -> inj.injectUserTask(pid2, new UserTaskSpec("s", null, null, null, null), "u"));
        assertEquals(0, f.count("SELECT COUNT(*) FROM DYN_INJECTION WHERE process_instance_id = ?", pid2));
    }

    // FWT-035
    @Test
    void fwt035_fragmentCompensationStaysInsideFragment() {
        String host = FwtEngine.defs("""
              <process id="host" isExecutable="true">
                <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="A"/>
                <serviceTask id="A" operaton:expression="${true}"/>
                <boundaryEvent id="hb" attachedToRef="A"><compensateEventDefinition/></boundaryEvent>
                <userTask id="hostComp" isForCompensation="true"/>
                <sequenceFlow id="f2" sourceRef="A" targetRef="wait"/>
                <userTask id="wait"/><sequenceFlow id="f3" sourceRef="wait" targetRef="e"/><endEvent id="e"/>
                <association id="ha" sourceRef="hb" targetRef="hostComp"/>
              </process>""");
        f.deploy("host.bpmn", host);
        String pid = f.engine.getRuntimeService().startProcessInstanceByKey("host").getId();
        String frag = FwtEngine.defs("""
              <process id="frag" isExecutable="true">
                <startEvent id="s"/><sequenceFlow id="g1" sourceRef="s" targetRef="A"/>
                <serviceTask id="A" operaton:expression="${true}"/>
                <boundaryEvent id="fb" attachedToRef="A"><compensateEventDefinition/></boundaryEvent>
                <userTask id="fragComp" isForCompensation="true"/>
                <sequenceFlow id="g2" sourceRef="A" targetRef="th"/>
                <intermediateThrowEvent id="th"><compensateEventDefinition activityRef="A"/></intermediateThrowEvent>
                <sequenceFlow id="g3" sourceRef="th" targetRef="fe"/><endEvent id="fe"/>
                <association id="fa" sourceRef="fb" targetRef="fragComp"/>
              </process>""");
        inj.injectEmbeddedSubProcess(pid, "frag", f.bytes(frag), "u");
        List<String> keys = f.engine.getTaskService().createTaskQuery().processInstanceId(pid).list().stream()
                .map(Task::getTaskDefinitionKey).toList();
        assertEquals(1, keys.stream().filter(k -> k.endsWith("_fragComp")).count(), "fragment task compensated: " + keys);
        assertEquals(0, keys.stream().filter(k -> k.equals("hostComp")).count(), "host task with the same id not compensated: " + keys);
    }

    // FWT-038
    @Test
    void fwt038_injectionWithEventScopeExecutionThenCompensate() {
        String host = FwtEngine.defs("""
              <process id="host" isExecutable="true">
                <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="sp"/>
                <subProcess id="sp">
                  <startEvent id="ss"/><sequenceFlow id="i1" sourceRef="ss" targetRef="B"/>
                  <serviceTask id="B" operaton:expression="${true}"/>
                  <boundaryEvent id="bb" attachedToRef="B"><compensateEventDefinition/></boundaryEvent>
                  <userTask id="undoB" isForCompensation="true"/>
                  <sequenceFlow id="i2" sourceRef="B" targetRef="se"/><endEvent id="se"/>
                  <association id="ba" sourceRef="bb" targetRef="undoB"/>
                </subProcess>
                <sequenceFlow id="f2" sourceRef="sp" targetRef="wait"/>
                <userTask id="wait"/><sequenceFlow id="f3" sourceRef="wait" targetRef="th"/>
                <intermediateThrowEvent id="th"><compensateEventDefinition/></intermediateThrowEvent>
                <sequenceFlow id="f4" sourceRef="th" targetRef="e"/><endEvent id="e"/>
              </process>""");
        f.deploy("host.bpmn", host);
        String pid = f.engine.getRuntimeService().startProcessInstanceByKey("host").getId();
        String defBefore = f.engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(pid).singleResult().getProcessDefinitionId();
        long depsBefore = deployments();
        boolean migrated;
        try {
            inj.injectUserTask(pid, new UserTaskSpec("extra", null, null, null, null), "u");
            migrated = true;
        } catch (RuntimeException e) {
            migrated = false;
            // rolled back cleanly: same definition, no derived deployment, no audit row
            assertEquals(defBefore, f.engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(pid).singleResult()
                    .getProcessDefinitionId());
            assertEquals(depsBefore, deployments());
            assertEquals(0, f.count("SELECT COUNT(*) FROM DYN_INJECTION"));
        }
        System.out.println("FWT-038 migration " + (migrated ? "succeeded" : "rolled back"));
        Task wait = f.engine.getTaskService().createTaskQuery().processInstanceId(pid).taskDefinitionKey("wait").singleResult();
        assertNotNull(wait);
        f.engine.getTaskService().complete(wait.getId());
        assertEquals(1, f.engine.getTaskService().createTaskQuery().processInstanceId(pid).taskDefinitionKey("undoB").count(),
                "compensation still runs");
    }

    // FWT-054 (injection part)
    @Test
    void fwt054_claimedTaskKeepsIdentityDuringInjection() {
        String pid = startHost();
        Task t = f.engine.getTaskService().createTaskQuery().processInstanceId(pid).singleResult();
        f.engine.getTaskService().claim(t.getId(), "alice");
        inj.injectUserTask(pid, new UserTaskSpec("extra", null, null, null, null), "u");
        Task after = f.engine.getTaskService().createTaskQuery().processInstanceId(pid).taskDefinitionKey("hostTask").singleResult();
        assertEquals(t.getId(), after.getId());
        assertEquals("alice", after.getAssignee());
        assertEquals(2, f.engine.getTaskService().createTaskQuery().processInstanceId(pid).count());
    }

    // FWT-054 (cross-instance move part)
    @Test
    void fwt054_claimedTaskKeepsIdentityDuringCrossInstanceMove() {
        f.deploy("child.bpmn", FwtEngine.defs("""
              <process id="child" isExecutable="true"><startEvent id="s"/><sequenceFlow id="c1" sourceRef="s" targetRef="cT"/>
                <userTask id="cT"/><sequenceFlow id="c2" sourceRef="cT" targetRef="e"/><endEvent id="e"/></process>"""));
        f.deploy("parent.bpmn", FwtEngine.defs("""
              <process id="parent" isExecutable="true">
                <startEvent id="s"/><sequenceFlow id="p0" sourceRef="s" targetRef="fork"/>
                <parallelGateway id="fork"/>
                <sequenceFlow id="p1" sourceRef="fork" targetRef="call"/><callActivity id="call" calledElement="child"/>
                <sequenceFlow id="p2" sourceRef="fork" targetRef="pT"/><userTask id="pT"/>
                <sequenceFlow id="p3" sourceRef="call" targetRef="e1"/><endEvent id="e1"/>
                <sequenceFlow id="p4" sourceRef="pT" targetRef="e2"/><endEvent id="e2"/>
                <userTask id="pX"/><sequenceFlow id="p5" sourceRef="pX" targetRef="e3"/><endEvent id="e3"/>
              </process>"""));
        String parent = f.engine.getRuntimeService().startProcessInstanceByKey("parent").getId();
        Task pT = f.engine.getTaskService().createTaskQuery().processInstanceId(parent).taskDefinitionKey("pT").singleResult();
        f.engine.getTaskService().claim(pT.getId(), "alice");
        String child = f.engine.getRuntimeService().createProcessInstanceQuery().superProcessInstanceId(parent).singleResult().getId();
        String cTInstance = f.engine.getRuntimeService().getActivityInstance(child).getActivityInstances("cT")[0].getId();
        new CrossInstanceStateChangeService(f.engine).move(child, cTInstance, parent, "pX", null, "test");
        Task after = f.engine.getTaskService().createTaskQuery().processInstanceId(parent).taskDefinitionKey("pT").singleResult();
        assertEquals(pT.getId(), after.getId());
        assertEquals("alice", after.getAssignee());
        assertTrue(f.engine.getTaskService().createTaskQuery().processInstanceId(parent).taskDefinitionKey("pX").count() == 1);
    }
}
