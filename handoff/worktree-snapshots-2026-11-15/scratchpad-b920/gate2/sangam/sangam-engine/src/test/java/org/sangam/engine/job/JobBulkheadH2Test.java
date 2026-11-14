// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.impl.ProcessEngineImpl;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.context.Context;
import org.operaton.bpm.engine.impl.jobexecutor.AsyncContinuationJobHandler;
import org.operaton.bpm.engine.impl.jobexecutor.JobExecutorContext;
import org.operaton.bpm.engine.impl.jobexecutor.JobFailureCollector;
import org.operaton.bpm.engine.impl.persistence.entity.JobEntity;
import org.operaton.bpm.engine.runtime.Job;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.SangamEngineFactory;
import org.sangam.engine.SangamEngineSettings;

/**
 * M-054 on a real engine (H2): a job whose type is at its bulkhead limit is handed back unexecuted, with its retries
 * unchanged and its lock released, and runs normally once a permit is free. The job runs through
 * {@link BulkheadExecuteJobsRunnable#executeJob} as the job executor's run loop calls it (job executor context set).
 */
class JobBulkheadH2Test {

    private static final String MODEL = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="t">
              <process id="m054" isExecutable="true">
                <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="work"/>
                <serviceTask id="work" operaton:asyncBefore="true" operaton:expression="${true}"/>
                <sequenceFlow id="f2" sourceRef="work" targetRef="e"/><endEvent id="e"/>
              </process>
            </definitions>
            """;

    @Test
    void aFullBulkheadHandsTheJobBackAndAFreePermitRunsIt() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:m054b" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamEngineSettings s = new SangamEngineSettings();
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true");
        s.setApplyEnvironment(false);
        s.getJobBulkheads().put(AsyncContinuationJobHandler.TYPE, 1);
        s.setJobBulkheadWait(Duration.ZERO);
        try (OperatonEngineAdapter a = SangamEngineFactory.build(ds, s, "h2")) {
            ProcessEngineConfigurationImpl cfg = (ProcessEngineConfigurationImpl) a.processEngine().getProcessEngineConfiguration();
            SangamJobExecutor je = (SangamJobExecutor) cfg.getJobExecutor();
            a.deploy("t1", "m054", Map.of("m054.bpmn", MODEL.getBytes(StandardCharsets.UTF_8)));
            String pid = a.startProcess("t1", "m054", "M054-1", Map.of());
            Job job = a.processEngine().getManagementService().createJobQuery().processInstanceId(pid).singleResult();
            assertNotNull(job, "the async continuation waits as a job");
            assertEquals(AsyncContinuationJobHandler.TYPE, ((JobEntity) job).getJobHandlerType());
            int retries = job.getRetries();
            BulkheadExecuteJobsRunnable runnable = new BulkheadExecuteJobsRunnable(List.of(job.getId()),
                    (ProcessEngineImpl) a.processEngine(), je.bulkheads());

            JobBulkheads.Permit held = je.bulkheads().acquire(AsyncContinuationJobHandler.TYPE, () -> true);
            assertNotNull(held, "another execution of the type holds the only permit");
            runAsTheRunLoopDoes(runnable, cfg, job.getId());
            Job back = a.processEngine().getManagementService().createJobQuery().jobId(job.getId()).singleResult();
            assertNotNull(back, "not executed while the bulkhead was full");
            assertEquals(retries, back.getRetries(), "handing back never costs a retry");
            assertNull(((JobEntity) back).getLockOwner(), "the lock is released, so the job is acquirable again");
            assertEquals(1, a.processEngine().getRuntimeService().createProcessInstanceQuery().processInstanceId(pid).count());

            held.release();
            runAsTheRunLoopDoes(runnable, cfg, job.getId());
            assertEquals(0, a.processEngine().getManagementService().createJobQuery().jobId(job.getId()).count(),
                    "with a free permit the job runs");
            assertEquals(0, a.processEngine().getRuntimeService().createProcessInstanceQuery().processInstanceId(pid).count(),
                    "and the instance completes");
            assertEquals(1, je.bulkheads().available(AsyncContinuationJobHandler.TYPE), "the permit is returned");
        }
    }

    private static void runAsTheRunLoopDoes(BulkheadExecuteJobsRunnable runnable, ProcessEngineConfigurationImpl cfg,
                                            String jobId) {
        Context.setJobExecutorContext(new JobExecutorContext());
        try {
            runnable.executeJob(jobId, cfg.getCommandExecutorTxRequired(), new JobFailureCollector(jobId));
        } finally {
            Context.removeJobExecutorContext();
        }
    }
}
