// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.impl.jobexecutor.AcquiredJobs;
import org.operaton.bpm.engine.runtime.Job;
import org.sangam.core.layer.Dialect;
import org.sangam.ports.PortsH2Engine;
import org.sangam.ports.jobs.FilteredAcquireJobsCmd.JobClass;

/** FC-24 split acquisition: timers vs other jobs, FW-36 exclusive rule, global acquire lock. */
class FilteredAcquireJobsCmdH2Test {

    private static final String MODELS = """
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="t">
              <process id="timerP" isExecutable="true">
                <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="tm"/>
                <intermediateCatchEvent id="tm"><timerEventDefinition><timeDate>2000-01-01T00:00:00Z</timeDate></timerEventDefinition></intermediateCatchEvent>
                <sequenceFlow id="f2" sourceRef="tm" targetRef="u"/><userTask id="u"/>
              </process>
              <process id="asyncP" isExecutable="true">
                <startEvent id="s2"/><sequenceFlow id="g1" sourceRef="s2" targetRef="fork"/>
                <parallelGateway id="fork"/>
                <sequenceFlow id="fa" sourceRef="fork" targetRef="a"/><sequenceFlow id="fb" sourceRef="fork" targetRef="b"/>
                <userTask id="a" operaton:asyncBefore="true"/><userTask id="b" operaton:asyncBefore="true"/>
              </process>
            </definitions>""";

    private PortsH2Engine h2;

    @BeforeEach
    void setUp() {
        h2 = PortsH2Engine.start(null);
        h2.engine.getRepositoryService().createDeployment().addString("m.bpmn", MODELS).deploy();
    }

    @AfterEach
    void tearDown() {
        h2.close();
    }

    private Set<String> acquire(JobClass cls, String globalLock) {
        var cfg = h2.configuration();
        AcquiredJobs acquired = cfg.getCommandExecutorTxRequired()
                .execute(new FilteredAcquireJobsCmd(cfg.getJobExecutor(), 10, cls, Dialect.POSTGRESQL, globalLock));
        return acquired.getJobIdBatches().stream().flatMap(List::stream).collect(Collectors.toSet());
    }

    private Set<String> ids(String type) {
        return h2.engine.getManagementService().createJobQuery().list().stream()
                .filter(j -> type.equals(((org.operaton.bpm.engine.impl.persistence.entity.JobEntity) j).getType()))
                .map(Job::getId).collect(Collectors.toSet());
    }

    @Test
    void timerAndAsyncExecutorsAcquireDisjointJobClasses() {
        h2.engine.getRuntimeService().startProcessInstanceByKey("timerP");
        h2.engine.getRuntimeService().startProcessInstanceByKey("asyncP");
        Set<String> timers = ids("timer");
        Set<String> messages = ids("message");
        assertEquals(1, timers.size());
        assertEquals(2, messages.size());
        assertEquals(timers, acquire(JobClass.TIMERS, null));
        assertEquals(messages, acquire(JobClass.NON_TIMERS, "acq"), "global acquire lock path");
        assertTrue(acquire(JobClass.NON_TIMERS, null).isEmpty(), "locked jobs are not acquired twice");
    }

    @Test
    void exclusiveJobIsNotAcquiredWhileASiblingHoldsALiveLock() throws Exception {
        String pi = h2.engine.getRuntimeService().startProcessInstanceByKey("asyncP").getId();
        List<String> jobs = h2.engine.getManagementService().createJobQuery().processInstanceId(pi).list().stream().map(Job::getId).sorted().toList();
        try (var c = h2.connection(); var ps = c.prepareStatement(
                "UPDATE ACT_RU_JOB SET LOCK_OWNER_ = 'other-node', LOCK_EXP_TIME_ = ? WHERE ID_ = ?")) {
            ps.setTimestamp(1, new java.sql.Timestamp(System.currentTimeMillis() + 3_600_000));
            ps.setString(2, jobs.get(0));
            ps.executeUpdate();
        }
        assertTrue(acquire(JobClass.NON_TIMERS, null).isEmpty(), "FW-36: sibling exclusive job waits");
    }
}
