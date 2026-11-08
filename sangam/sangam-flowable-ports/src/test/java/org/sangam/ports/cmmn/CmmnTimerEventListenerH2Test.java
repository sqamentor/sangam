// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.cmmn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.impl.util.ClockUtil;
import org.operaton.bpm.engine.runtime.CaseExecution;
import org.operaton.bpm.engine.runtime.Job;
import org.sangam.ports.PortsH2Engine;

/** FC-55: CMMN timer / user event listeners (SL-12 regression: the timer listener never fired). */
class CmmnTimerEventListenerH2Test {

    private static final String TIMER_CASE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/CMMN/20151109/MODEL" targetNamespace="t" id="d">
              <case id="timerCase">
                <casePlanModel id="plan">
                  <planItem id="PI_work" definitionRef="work"/>
                  <planItem id="PI_timer" definitionRef="timer"/>
                  <planItem id="PI_escalate" definitionRef="escalate">
                    <entryCriterion id="ec" sentryRef="s"/>
                  </planItem>
                  <sentry id="s">
                    <planItemOnPart id="op" sourceRef="PI_timer"><standardEvent>occur</standardEvent></planItemOnPart>
                  </sentry>
                  <humanTask id="work" name="Work"/>
                  <timerEventListener id="timer">
                    <timerExpression id="te">P21D</timerExpression>
                  </timerEventListener>
                  <humanTask id="escalate" name="Escalated"/>
                </casePlanModel>
              </case>
            </definitions>
            """;

    private static final String USER_CASE = TIMER_CASE.replace("timerCase", "userCase")
            .replace("<timerEventListener id=\"timer\">\n        <timerExpression id=\"te\">P21D</timerExpression>\n      </timerEventListener>",
                    "<userEventListener id=\"timer\"/>");

    private PortsH2Engine h2;
    private long t0;

    @BeforeEach
    void setUp() {
        t0 = 1_800_000_000_000L;
        ClockUtil.setCurrentTime(new Date(t0));
        h2 = PortsH2Engine.start(null);
        h2.engine.getRepositoryService().createDeployment().addString("timer.cmmn", TIMER_CASE).deploy();
    }

    @AfterEach
    void tearDown() {
        h2.close();
    }

    /** Task count by plan item id (a CMMN task's definition key is its plan item id). */
    private long tasks(String caseId, String planItemSuffix) {
        String key = "PI_" + planItemSuffix;
        return h2.engine.getTaskService().createTaskQuery().caseInstanceId(caseId).list().stream()
                .filter(t -> key.equals(t.getTaskDefinitionKey())).count();
    }

    @Test
    void timerListenerSchedulesAJobAndOccursWhenDue() {
        String c = h2.engine.getCaseService().createCaseInstanceByKey("timerCase").getId();
        List<Job> jobs = h2.engine.getManagementService().createJobQuery().list();
        assertEquals(1, jobs.size(), "one timer job for the listener");
        assertEquals(t0 + 21L * 24 * 3600 * 1000, jobs.get(0).getDuedate().getTime(), "due after P21D");
        assertEquals(0, tasks(c, "escalate"));

        ClockUtil.setCurrentTime(new Date(t0 + 20L * 24 * 3600 * 1000));
        assertEquals(0, h2.executeDueJobs(), "not due on day 20");

        ClockUtil.setCurrentTime(new Date(t0 + 22L * 24 * 3600 * 1000));
        assertEquals(1, h2.executeDueJobs());
        assertEquals(1, tasks(c, "escalate"), "occur satisfied the sentry");
        assertEquals(1, tasks(c, "work"));
        assertEquals(0, h2.engine.getManagementService().createJobQuery().count());
    }

    @Test
    void timerJobOfAnEndedCaseDoesNothing() {
        String c = h2.engine.getCaseService().createCaseInstanceByKey("timerCase").getId();
        h2.engine.getCaseService().terminateCaseExecution(c);
        ClockUtil.setCurrentTime(new Date(t0 + 22L * 24 * 3600 * 1000));
        h2.executeDueJobs();
        assertEquals(0, tasks(c, "escalate"));
    }

    @Test
    void userListenerOccursThroughTheService() {
        assertTrue(USER_CASE.contains("userEventListener"), "fixture rewrite applied");
        h2.engine.getRepositoryService().createDeployment().addString("user.cmmn", USER_CASE).deploy();
        String c = h2.engine.getCaseService().createCaseInstanceByKey("userCase").getId();
        assertEquals(0, h2.engine.getManagementService().createJobQuery().count());
        CmmnEventListenerService svc = new CmmnEventListenerService(h2.engine);
        List<CaseExecution> listeners = svc.availableEventListeners(c);
        assertEquals(1, listeners.size());
        svc.occur(listeners.get(0).getId());
        assertEquals(1, tasks(c, "escalate"));
        assertThrows(ProcessEngineException.class, () -> svc.occur(listeners.get(0).getId()), "occurs once");
        String work = h2.engine.getCaseService().createCaseExecutionQuery().caseInstanceId(c).activityId("PI_work").singleResult().getId();
        assertThrows(ProcessEngineException.class, () -> svc.occur(work), "not an event listener");
    }
}
