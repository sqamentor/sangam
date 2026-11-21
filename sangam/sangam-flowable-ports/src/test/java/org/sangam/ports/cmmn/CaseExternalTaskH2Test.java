// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.cmmn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.impl.util.ClockUtil;
import org.operaton.bpm.engine.runtime.CaseExecution;
import org.sangam.ports.PortsH2Engine;
import org.sangam.ports.cmmn.CaseExternalTaskService.CaseExternalTask;
import org.sangam.ports.cmmn.CaseExternalTaskService.Topic;

/**
 * PATCHES.md P-5 / FLOWABLE-INTO-OPERATON FC-11 native: a CMMN {@code task} with {@code sangam:topic} is a case
 * external task, stored in the Sangam table {@code PORT_CASE_EXT_TASK} keyed to its case execution, with the
 * fetch-and-lock / complete / failure contract of the BPMN external task.
 */
class CaseExternalTaskH2Test {

    private static final String CASE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/CMMN/20151109/MODEL"
                         xmlns:sangam="http://sangam.org/schema/1.0/cmmn" targetNamespace="t" id="d">
              <case id="extCase">
                <casePlanModel id="plan" autoComplete="false">
                  <planItem id="PI_kyc" definitionRef="kyc"/>
                  <planItem id="PI_after" definitionRef="after">
                    <entryCriterion id="ec" sentryRef="s"/>
                  </planItem>
                  <sentry id="s">
                    <planItemOnPart id="op" sourceRef="PI_kyc"><standardEvent>complete</standardEvent></planItemOnPart>
                  </sentry>
                  <task id="kyc" name="KYC check" sangam:topic="kyc" sangam:priority="7"/>
                  <humanTask id="after" name="After KYC"/>
                </casePlanModel>
              </case>
            </definitions>
            """;

    private static final long T0 = 1_800_000_000_000L;

    private PortsH2Engine h2;
    private CaseExternalTaskService svc;

    @BeforeEach
    void setUp() {
        ClockUtil.setCurrentTime(new Date(T0));
        h2 = PortsH2Engine.start(null);
        h2.engine.getRepositoryService().createDeployment().addString("ext.cmmn", CASE).deploy();
        svc = new CaseExternalTaskService(h2.engine);
    }

    @AfterEach
    void tearDown() {
        ClockUtil.reset();
        h2.close();
    }

    private String start() {
        return h2.engine.getCaseService().createCaseInstanceByKey("extCase").getId();
    }

    private List<CaseExternalTask> fetch(String worker, String topic) {
        return svc.fetchAndLock(null, worker, 10, List.of(new Topic(topic, 60_000)));
    }

    @Test
    void activeCaseTaskIsFetchedAndCompletedByItsWorker() {
        String c = start();
        List<CaseExternalTask> got = fetch("w1", "kyc");
        assertEquals(1, got.size(), "one case external task for topic kyc");
        CaseExternalTask t = got.get(0);
        assertEquals(c, t.caseInstanceId());
        assertEquals("PI_kyc", t.activityId());
        assertEquals("w1", t.workerId());
        assertEquals(7, t.priority());
        assertEquals(T0 + 60_000, t.lockExpiration().toEpochMilli());
        assertTrue(fetch("w2", "kyc").isEmpty(), "locked task is not fetched by another worker");

        assertThrows(CaseExternalTaskService.CaseExternalTaskException.class,
                () -> svc.complete(t.id(), "w2", Map.of()), "only the lock owner completes");
        svc.complete(t.id(), "w1", Map.of("kycOk", true));

        assertEquals(Boolean.TRUE, h2.engine.getCaseService().getVariable(c, "kycOk"));
        assertNull(h2.engine.getCaseService().createCaseExecutionQuery().caseInstanceId(c).activityId("PI_kyc").singleResult(),
                "case execution of the external task completed");
        assertEquals(1, h2.engine.getTaskService().createTaskQuery().caseInstanceId(c).count(), "sentry fired");
        assertTrue(svc.list(null, c).isEmpty(), "row removed on completion");
    }

    @Test
    void expiredLockIsFetchedAgainAndFailureCountsDownRetries() {
        start();
        CaseExternalTask t = fetch("w1", "kyc").get(0);
        ClockUtil.setCurrentTime(new Date(T0 + 61_000));
        CaseExternalTask again = fetch("w2", "kyc").get(0);
        assertEquals(t.id(), again.id());
        assertEquals("w2", again.workerId());

        svc.handleFailure(again.id(), "w2", "service down", 0, 0);
        assertTrue(fetch("w3", "kyc").isEmpty(), "retries 0: not fetchable (incident)");
        CaseExternalTask failed = svc.list(null, again.caseInstanceId()).get(0);
        assertEquals(0, failed.retries());
        assertEquals("service down", failed.errorMessage());

        svc.setRetries(failed.id(), 2);
        assertEquals(1, fetch("w3", "kyc").size(), "retries restored: fetchable again");
    }

    @Test
    void terminatingTheCaseRemovesTheTask() {
        String c = start();
        assertEquals(1, svc.list(null, c).size());
        h2.engine.getCaseService().terminateCaseExecution(c);
        assertTrue(svc.list(null, c).isEmpty());
        assertTrue(fetch("w1", "kyc").isEmpty());
    }

    @Test
    void plainTaskWithoutTopicIsNotAnExternalTask() {
        String plain = CASE.replace("extCase", "plainCase").replace(" sangam:topic=\"kyc\" sangam:priority=\"7\"", "");
        h2.engine.getRepositoryService().createDeployment().addString("plain.cmmn", plain).deploy();
        String c = h2.engine.getCaseService().createCaseInstanceByKey("plainCase").getId();
        assertTrue(svc.list(null, c).isEmpty());
        CaseExecution e = h2.engine.getCaseService().createCaseExecutionQuery().caseInstanceId(c).activityId("PI_kyc").singleResult();
        assertNull(e, "a plain CMMN task completes on start (Operaton TaskActivityBehavior.performStart)");
        assertEquals(1, h2.engine.getTaskService().createTaskQuery().caseInstanceId(c).count(), "sentry fired");
    }
}
