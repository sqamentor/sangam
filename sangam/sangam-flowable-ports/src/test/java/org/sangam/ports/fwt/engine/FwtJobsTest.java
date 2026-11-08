// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.fwt.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.externaltask.ExternalTask;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.jobexecutor.AcquiredJobs;
import org.operaton.bpm.engine.impl.jobexecutor.DefaultJobExecutor;
import org.operaton.bpm.engine.impl.jobexecutor.JobExecutor;
import org.operaton.bpm.engine.impl.util.ClockUtil;
import org.operaton.bpm.engine.runtime.Job;
import org.sangam.core.engine.SangamClockHolder;
import org.sangam.ports.jobs.FilteredAcquireJobsCmd;
import org.sangam.ports.support.EngineTx;

/** Split executors / hand-off (FW-04, FW-08, FW-36, FW-37): FWT-008, 013, 040, 041 on H2. */
class FwtJobsTest {

    final List<FwtEngine> engines = new ArrayList<>();

    @AfterEach
    void tearDown() {
        engines.forEach(FwtEngine::close);
        ClockUtil.reset();
        SangamClockHolder.reset();
    }

    private static void engineClock() {
        SangamClockHolder.set(() -> ClockUtil.getCurrentTime().toInstant());
    }

    private static void advanceMinutes(long m) {
        ClockUtil.setCurrentTime(new Date(ClockUtil.getCurrentTime().getTime() + m * 60_000));
    }

    private FwtEngine split(String url, boolean schema) {
        FwtEngine f = new FwtEngine(url, schema, s -> {
            s.setSplitJobExecutors(true);
            s.setGlobalAcquireLock(true);   // H2 has no FOR UPDATE OF .. SKIP LOCKED; the global-lock query path is used
        }, c -> { });
        engines.add(f);
        return f;
    }

    private static List<String> acquire(FwtEngine f, boolean timers) {
        var split = f.plugin.splitJobExecutors();
        JobExecutor je = timers ? split.timerExecutor() : split.asyncExecutor();
        AcquiredJobs a = EngineTx.execute(f.engine, new FilteredAcquireJobsCmd(je, 50,
                timers ? FilteredAcquireJobsCmd.JobClass.TIMERS : FilteredAcquireJobsCmd.JobClass.NON_TIMERS, FwtEngine.D, timers ? "timers" : "async"));
        List<String> ids = new ArrayList<>();
        a.getJobIdBatches().forEach(ids::addAll);
        return ids;
    }

    static final String EXT_AND_ASYNC = FwtEngine.defs("""
              <process id="mix" isExecutable="true">
                <startEvent id="s"/><sequenceFlow id="f0" sourceRef="s" targetRef="fork"/><parallelGateway id="fork"/>
                <sequenceFlow id="f1" sourceRef="fork" targetRef="ext"/><serviceTask id="ext" operaton:type="external" operaton:topic="fwt013"/>
                <sequenceFlow id="f2" sourceRef="ext" targetRef="e1"/><endEvent id="e1"/>
                <sequenceFlow id="f3" sourceRef="fork" targetRef="as"/><serviceTask id="as" operaton:asyncBefore="true" operaton:expression="${true}"/>
                <sequenceFlow id="f4" sourceRef="as" targetRef="e2"/><endEvent id="e2"/>
              </process>""");

    // FWT-013
    @Test
    void fwt013_expiredJobLockReacquiredExternalLeaseUntouched() {
        engineClock();
        ClockUtil.setCurrentTime(new Date());
        FwtEngine f = split("jdbc:h2:mem:fwt013" + System.nanoTime() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", true);
        f.deploy("mix.bpmn", EXT_AND_ASYNC);
        String pid = f.engine.getRuntimeService().startProcessInstanceByKey("mix").getId();
        var leased = f.engine.getExternalTaskService().fetchAndLock(1, "worker-A").topic("fwt013", 3_600_000).execute();
        assertEquals(1, leased.size());
        List<String> first = acquire(f, false);
        assertEquals(1, first.size());
        assertEquals(List.of(), acquire(f, false), "locked job is not re-acquired before expiry");
        advanceMinutes(10);   // job lock time is 5 min
        assertEquals(first, acquire(f, false), "expired job lock re-acquired");
        ExternalTask ext = f.engine.getExternalTaskService().createExternalTaskQuery().processInstanceId(pid).singleResult();
        assertEquals("worker-A", ext.getWorkerId(), "external-task lease untouched");
        assertEquals(leased.get(0).getLockExpirationTime(), ext.getLockExpirationTime());
    }

    // FWT-040
    @Test
    void fwt040_exclusiveJobsOfOneInstanceNeverLockedOnTwoNodes() {
        String url = "jdbc:h2:mem:fwt040" + System.nanoTime() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000";
        FwtEngine n1 = split(url, true);
        FwtEngine n2 = split(url, false);
        StringBuilder body = new StringBuilder("<process id=\"ten\" isExecutable=\"true\"><startEvent id=\"s\"/>"
                + "<sequenceFlow id=\"f0\" sourceRef=\"s\" targetRef=\"fork\"/><parallelGateway id=\"fork\"/>");
        for (int i = 0; i < 10; i++) {
            body.append("<sequenceFlow id=\"a").append(i).append("\" sourceRef=\"fork\" targetRef=\"t").append(i).append("\"/>")
                    .append("<serviceTask id=\"t").append(i).append("\" operaton:asyncBefore=\"true\" operaton:expression=\"${true}\"/>")
                    .append("<sequenceFlow id=\"b").append(i).append("\" sourceRef=\"t").append(i).append("\" targetRef=\"e").append(i).append("\"/>")
                    .append("<endEvent id=\"e").append(i).append("\"/>");
        }
        body.append("<sequenceFlow id=\"tf\" sourceRef=\"fork\" targetRef=\"tm\"/><intermediateCatchEvent id=\"tm\"><timerEventDefinition>"
                + "<timeDuration>PT0S</timeDuration></timerEventDefinition></intermediateCatchEvent>"
                + "<sequenceFlow id=\"tg\" sourceRef=\"tm\" targetRef=\"te\"/><endEvent id=\"te\"/></process>");
        n1.deploy("ten.bpmn", FwtEngine.defs(body.toString()));
        String pid = n1.engine.getRuntimeService().startProcessInstanceByKey("ten").getId();
        assertEquals(11, n1.engine.getManagementService().createJobQuery().processInstanceId(pid).count());

        List<String> node1 = acquire(n1, false);
        assertEquals(10, node1.size(), "node 1 acquires the async batch of the instance");
        List<String> node2Async = acquire(n2, false);
        List<String> node2Timers = acquire(n2, true);
        List<String> node1Timers = acquire(n1, true);
        assertEquals(List.of(), node2Async, "node 2 gets nothing");
        assertEquals(List.of(), node2Timers, "timer of the instance is exclusive: not acquirable while its async jobs are locked");
        assertEquals(List.of(), node1Timers, "not even by node 1's timer executor (other executor, other lock owner)");
        // never two lock owners on exclusive jobs of the instance
        long owners = n1.count("SELECT COUNT(DISTINCT LOCK_OWNER_) FROM ACT_RU_JOB WHERE PROCESS_INSTANCE_ID_ = ? AND EXCLUSIVE_ = TRUE AND LOCK_OWNER_ IS NOT NULL", pid);
        assertEquals(1, owners);
        for (String id : node1) {
            n1.engine.getManagementService().executeJob(id);   // node 1 runs its batch (no OLE)
        }
        // locks released with the jobs: the timer is now acquirable, exactly once
        List<String> t = acquire(n2, true);
        assertEquals(1, t.size());
        assertEquals(List.of(), acquire(n1, true));
    }

    /** A job executor whose single worker thread is busy and whose queue is full (saturated pool). */
    static final class SaturatedExecutor extends DefaultJobExecutor {
        final AtomicInteger rejected = new AtomicInteger();
        final CountDownLatch release = new CountDownLatch(1);

        SaturatedExecutor() {
            ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 1, TimeUnit.MINUTES, new SynchronousQueue<>());
            pool.execute(() -> {
                try {
                    release.await(5, TimeUnit.MINUTES);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            setThreadPoolExecutor(pool);
            setRejectedJobsHandler((ids, pe, je) -> rejected.incrementAndGet());
            isActive = true;   // running node, without the background acquisition thread
        }

        @Override
        public void jobWasAdded() {
            // no background acquisition thread on this node
        }

        @Override
        protected void startExecutingJobs() {
        }

        @Override
        protected void stopExecutingJobs() {
        }

        @Override
        public synchronized void shutdown() {
            release.countDown();
            getThreadPoolExecutor().shutdownNow();
            isActive = false;
        }
    }

    static final String ASYNC_NONEX = FwtEngine.defs("""
              <process id="ho" isExecutable="true">
                <startEvent id="s"/><sequenceFlow id="f0" sourceRef="s" targetRef="as"/>
                <serviceTask id="as" operaton:asyncBefore="true" operaton:exclusive="false" operaton:expression="${true}"/>
                <sequenceFlow id="f1" sourceRef="as" targetRef="w"/><userTask id="w"/>
                <sequenceFlow id="f2" sourceRef="w" targetRef="e"/><endEvent id="e"/>
              </process>""");

    // FWT-041
    @Test
    void fwt041_handOffSaturatedPoolAndDeploymentAware() {
        engineClock();
        ClockUtil.setCurrentTime(new Date());
        SaturatedExecutor sat = new SaturatedExecutor();
        FwtEngine f = new FwtEngine("jdbc:h2:mem:fwt041" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", true, s -> {
            s.setAsyncJobHandOff(true);
            s.setGlobalAcquireLock(true);
        }, c -> c.setJobExecutor(sat));
        engines.add(f);
        f.deploy("ho.bpmn", ASYNC_NONEX);
        String pid = f.engine.getRuntimeService().startProcessInstanceByKey("ho").getId();   // command succeeds
        Job j = f.engine.getManagementService().createJobQuery().processInstanceId(pid).singleResult();
        assertEquals(1, sat.rejected.get(), "hand-off rejected by the saturated pool");
        assertNotNull(j, "job committed");
        assertEquals(List.of(), acquire(f, false), "pre-locked for this node until expiry");
        advanceMinutes(10);
        List<String> again = acquire(f, false);
        assertEquals(List.of(j.getId()), again, "acquired after lock expiry");
        f.engine.getManagementService().executeJob(j.getId());
        assertEquals(1, f.engine.getTaskService().createTaskQuery().processInstanceId(pid).taskDefinitionKey("w").count(), "job ran");

        // deployment-aware engine: no hand-off at all
        SaturatedExecutor sat2 = new SaturatedExecutor();
        FwtEngine g = new FwtEngine("jdbc:h2:mem:fwt041b" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", true, s -> s.setAsyncJobHandOff(true),
                c -> {
                    c.setJobExecutor(sat2);
                    c.setJobExecutorDeploymentAware(true);
                });
        engines.add(g);
        g.deploy("ho.bpmn", ASYNC_NONEX);
        String pid2 = g.engine.getRuntimeService().startProcessInstanceByKey("ho").getId();
        assertEquals(0, sat2.rejected.get(), "no hand-off attempted");
        assertEquals(0, g.count("SELECT COUNT(*) FROM ACT_RU_JOB WHERE PROCESS_INSTANCE_ID_ = ? AND LOCK_OWNER_ IS NOT NULL", pid2),
                "job not pre-locked on a deployment-aware engine");
        assertEquals(1, g.engine.getManagementService().createJobQuery().processInstanceId(pid2).count());
    }

    // FWT-008
    @Test
    void fwt008_externalCompletionWhoseContinuationThrowsRollsBack() {
        FwtEngine f = new FwtEngine();
        engines.add(f);
        f.deploy("ext.bpmn", FwtEngine.defs("""
              <process id="ext" isExecutable="true">
                <startEvent id="s"/><sequenceFlow id="f0" sourceRef="s" targetRef="w"/>
                <serviceTask id="w" operaton:type="external" operaton:topic="fwt008"/>
                <sequenceFlow id="f1" sourceRef="w" targetRef="boom"/>
                <serviceTask id="boom" operaton:expression="${execution.getVariable('missing').length()}"/>
                <sequenceFlow id="f2" sourceRef="boom" targetRef="e"/><endEvent id="e"/>
              </process>"""));
        String pid = f.engine.getRuntimeService().startProcessInstanceByKey("ext").getId();
        var lt = f.engine.getExternalTaskService().fetchAndLock(1, "w1").topic("fwt008", 60_000).execute().get(0);
        RuntimeException e = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> f.engine.getExternalTaskService().complete(lt.getId(), "w1", Map.of("out", 1)));
        assertNotNull(e);
        ExternalTask still = f.engine.getExternalTaskService().createExternalTaskQuery().externalTaskId(lt.getId()).singleResult();
        assertNotNull(still, "whole completion rolled back: external task still exists");
        assertEquals("w1", still.getWorkerId(), "lease intact");
        assertEquals(lt.getLockExpirationTime(), still.getLockExpirationTime());
        assertNull(f.engine.getRuntimeService().getVariable(pid, "out"), "completion variables rolled back");
        assertTrue(f.engine.getRuntimeService().getActivityInstance(pid).getActivityInstances("w").length == 1);
        assertEquals(0, f.engine.getManagementService().createJobQuery().processInstanceId(pid).count(), "no async continuation left");
    }
}
