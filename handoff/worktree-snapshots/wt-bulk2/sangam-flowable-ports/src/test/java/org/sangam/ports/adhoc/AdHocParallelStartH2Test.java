// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.adhoc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.engine.task.Task;

/**
 * Regression: starting a second inner activity of a parallel ad-hoc sub-process failed with "Cannot create concurrent
 * execution for ConcurrentExecution[..]" (E11 gap-adhoc-subprocess on the server). Model = the Stage B GAP model.
 */
class AdHocParallelStartH2Test {

    private static final String MODEL = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" targetNamespace="t">
              <process id="adhoc" isExecutable="true">
                <startEvent id="s"/>
                <sequenceFlow id="f1" sourceRef="s" targetRef="sub"/>
                <adHocSubProcess id="sub" ordering="Parallel" cancelRemainingInstances="true">
                  <userTask id="taskA"/>
                  <userTask id="taskB"/>
                  <completionCondition xsi:type="tFormalExpression">${execution.getVariable('adhocDone') == true}</completionCondition>
                </adHocSubProcess>
                <sequenceFlow id="f2" sourceRef="sub" targetRef="probe"/>
                <userTask id="probe"/>
                <sequenceFlow id="f3" sourceRef="probe" targetRef="e"/>
                <endEvent id="e"/>
              </process>
            </definitions>
            """;

    private ProcessEngine engine;

    @BeforeEach
    void setUp() {
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:adhoc" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        cfg.setJobExecutorActivate(false);
        cfg.setEnforceHistoryTimeToLive(false);
        cfg.setBpmnParseFactory(new SangamBpmnParseFactory());
        engine = cfg.buildProcessEngine();
        engine.getRepositoryService().createDeployment().addString("adhoc.bpmn", MODEL).deploy();
    }

    @AfterEach
    void tearDown() {
        engine.close();
    }

    private List<String> taskKeys(String pid) {
        return engine.getTaskService().createTaskQuery().processInstanceId(pid).list().stream()
                .map(Task::getTaskDefinitionKey).sorted().toList();
    }

    @Test
    void parallelAdHocStartsTwoInnerActivitiesAndCompletesByCondition() {
        String pid = engine.getRuntimeService().startProcessInstanceByKey("adhoc").getId();
        AdHocSubProcessService adHoc = new AdHocSubProcessService(engine);
        List<AdHocSubProcessService.EnabledActivity> enabled = adHoc.getEnabledActivities(pid);
        assertEquals(List.of("taskA", "taskB"), enabled.stream().map(AdHocSubProcessService.EnabledActivity::activityId)
                .sorted().toList());
        String scope = enabled.get(0).adHocActivityInstanceId();
        adHoc.executeActivity(pid, scope, "taskA");
        adHoc.executeActivity(pid, scope, "taskB");
        assertEquals(List.of("taskA", "taskB"), taskKeys(pid));

        Task a = engine.getTaskService().createTaskQuery().processInstanceId(pid).taskDefinitionKey("taskA").singleResult();
        engine.getTaskService().complete(a.getId(), Map.of("adhocDone", true));
        assertEquals(List.of("probe"), taskKeys(pid), "condition true: B cancelled, probe reached");
        assertTrue(engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(pid).count() == 1);
    }
}
