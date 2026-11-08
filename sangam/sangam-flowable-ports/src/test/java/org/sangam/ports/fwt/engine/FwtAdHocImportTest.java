// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.fwt.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.OptimisticLockingException;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.externaltask.LockedExternalTask;
import org.operaton.bpm.engine.task.Task;
import org.sangam.ports.adhoc.AdHocSubProcessService;
import org.sangam.ports.modelimport.FlowableModelImporter;
import org.sangam.ports.support.EngineTx;

/** Ad-hoc (FW-09, FW-35) and imported-model execution (FW-12, FW-13): FWT-014, 015, 016, 039, 053. */
class FwtAdHocImportTest {

    FwtEngine f;

    @BeforeEach
    void setUp() {
        f = new FwtEngine();
    }

    @AfterEach
    void tearDown() {
        f.close();
    }

    private List<String> taskKeys(String pid) {
        return f.engine.getTaskService().createTaskQuery().processInstanceId(pid).list().stream()
                .map(Task::getTaskDefinitionKey).sorted().toList();
    }

    static final String COMP_ADHOC = FwtEngine.defs("""
              <process id="ah" isExecutable="true">
                <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="sub"/>
                <adHocSubProcess id="sub" ordering="Parallel">
                  <serviceTask id="done" operaton:expression="${true}"/>
                  <boundaryEvent id="db" attachedToRef="done"><compensateEventDefinition/></boundaryEvent>
                  <userTask id="undoDone" isForCompensation="true"/>
                  <userTask id="running"/>
                  <boundaryEvent id="rb" attachedToRef="running"><compensateEventDefinition/></boundaryEvent>
                  <userTask id="undoRunning" isForCompensation="true"/>
                  <association id="da" sourceRef="db" targetRef="undoDone"/>
                  <association id="ra" sourceRef="rb" targetRef="undoRunning"/>
                </adHocSubProcess>
                <sequenceFlow id="f2" sourceRef="sub" targetRef="th"/>
                <intermediateThrowEvent id="th"><compensateEventDefinition/></intermediateThrowEvent>
                <sequenceFlow id="f3" sourceRef="th" targetRef="e"/><endEvent id="e"/>
              </process>""");

    // FWT-014 (H2 only)
    @Test
    void fwt014_adHocUserTaskCompensationHandlerCreated() {
        f.deploy("ah.bpmn", COMP_ADHOC);
        String pid = f.engine.getRuntimeService().startProcessInstanceByKey("ah").getId();
        AdHocSubProcessService ah = new AdHocSubProcessService(f.engine);
        ah.executeActivity(pid, null, "done");
        ah.complete(pid, null, false);
        assertEquals(List.of("undoDone"), taskKeys(pid), "handler task created, no FK error");
    }

    // FWT-015
    @Test
    void fwt015_cancelRemainingCompensatesOnlyCompleted() {
        f.deploy("ah.bpmn", COMP_ADHOC);
        String pid = f.engine.getRuntimeService().startProcessInstanceByKey("ah").getId();
        AdHocSubProcessService ah = new AdHocSubProcessService(f.engine);
        ah.executeActivity(pid, null, "done");
        ah.executeActivity(pid, null, "running");
        assertEquals(List.of("running"), taskKeys(pid));
        ah.complete(pid, null, true);
        assertEquals(List.of("undoDone"), taskKeys(pid), "only the completed inner activity is compensated");
    }

    // FWT-039
    @Test
    void fwt039_sequentialAdHocRaceExactlyOneStarts() throws Exception {
        f.deploy("seq.bpmn", FwtEngine.defs("""
              <process id="seq" isExecutable="true">
                <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="sub"/>
                <adHocSubProcess id="sub" ordering="Sequential"><userTask id="a"/><userTask id="b"/></adHocSubProcess>
                <sequenceFlow id="f2" sourceRef="sub" targetRef="e"/><endEvent id="e"/>
              </process>"""));
        String pid = f.engine.getRuntimeService().startProcessInstanceByKey("seq").getId();
        AdHocSubProcessService ah = new AdHocSubProcessService(f.engine);
        CountDownLatch checked = new CountDownLatch(1);
        CountDownLatch otherDone = new CountDownLatch(1);
        // thread 1: start "a" and hold its command open until thread 2 has started "b" (an interleaving of two
        // concurrent requests: both checks run before either commits)
        CompletableFuture<Void> t1 = CompletableFuture.runAsync(() -> EngineTx.execute(f.engine, cc -> {
            ah.executeActivity(pid, null, "a");
            checked.countDown();
            try {
                otherDone.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        if (!checked.await(30, TimeUnit.SECONDS)) {
            t1.get(1, TimeUnit.SECONDS);   // surfaces thread 1's failure
            throw new AssertionError("thread 1 did not start");
        }
        CompletableFuture<Void> t2 = CompletableFuture.runAsync(() -> ah.executeActivity(pid, null, "b"));
        Throwable second = null;
        try {
            t2.get(30, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            second = e.getCause();
        } finally {
            otherDone.countDown();
        }
        Throwable first = null;
        try {
            t1.get(30, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            first = e.getCause();
        }
        assertTrue((first == null) != (second == null), "exactly one start succeeds: first=" + first + " second=" + second);
        Throwable loser = first != null ? first : second;
        assertInstanceOf(OptimisticLockingException.class, loser, "loser gets an optimistic-locking conflict: " + loser);
        List<String> started = taskKeys(pid);
        assertEquals(1, started.size(), "exactly one inner activity started: " + started);
        String other = started.get(0).equals("a") ? "b" : "a";
        ProcessEngineException busy = assertThrows(ProcessEngineException.class, () -> ah.executeActivity(pid, null, other));
        assertTrue(busy.getMessage().contains("busy"), busy.getMessage());
    }

    // FWT-016
    @Test
    void fwt016_importedVariableListenerFiresOnChange() {
        String flowable = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="t">
                  <process id="vl" isExecutable="true">
                    <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="c"/>
                    <intermediateCatchEvent id="c">
                      <extensionElements><flowable:variableListenerEventDefinition variableName="approved"/></extensionElements>
                    </intermediateCatchEvent>
                    <sequenceFlow id="f2" sourceRef="c" targetRef="after"/>
                    <userTask id="after"/><sequenceFlow id="f3" sourceRef="after" targetRef="e"/><endEvent id="e"/>
                  </process>
                </definitions>""";
        FlowableModelImporter.Result r = FlowableModelImporter.convert(flowable.getBytes(StandardCharsets.UTF_8));
        assertTrue(r.importable(), String.valueOf(r.findings()));
        f.deploy("vl.bpmn", new String(r.converted(), StandardCharsets.UTF_8));
        String pid = f.engine.getRuntimeService().startProcessInstanceByKey("vl").getId();
        assertEquals(List.of(), taskKeys(pid));
        f.engine.getRuntimeService().setVariable(pid, "approved", true);   // no evaluate call
        assertEquals(List.of("after"), taskKeys(pid));
    }

    // FWT-053
    @Test
    void fwt053_importedParallelMiExternalWorkerAllFetched() {
        String flowable = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="t">
                  <process id="mi" isExecutable="true">
                    <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="w"/>
                    <serviceTask id="w" flowable:type="external-worker" flowable:topic="fwt053">
                      <multiInstanceLoopCharacteristics isSequential="false"><loopCardinality>10</loopCardinality></multiInstanceLoopCharacteristics>
                    </serviceTask>
                    <sequenceFlow id="f2" sourceRef="w" targetRef="e"/><endEvent id="e"/>
                  </process>
                </definitions>""";
        FlowableModelImporter.Result r = FlowableModelImporter.convert(flowable.getBytes(StandardCharsets.UTF_8));
        assertTrue(r.importable(), String.valueOf(r.findings()));
        f.deploy("mi.bpmn", new String(r.converted(), StandardCharsets.UTF_8));
        f.engine.getRuntimeService().startProcessInstanceByKey("mi");
        List<LockedExternalTask> got = f.engine.getExternalTaskService().fetchAndLock(10, "w1").topic("fwt053", 60_000).execute();
        assertEquals(10, got.size(), "all MI items fetched");
        assertNotNull(got.get(0).getId());
    }
}
