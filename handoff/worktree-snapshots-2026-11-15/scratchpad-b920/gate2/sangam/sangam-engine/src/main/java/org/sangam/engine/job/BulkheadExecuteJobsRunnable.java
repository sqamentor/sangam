// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.job;

import java.util.List;

import org.operaton.bpm.engine.impl.ProcessEngineImpl;
import org.operaton.bpm.engine.impl.interceptor.Command;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.operaton.bpm.engine.impl.interceptor.CommandExecutor;
import org.operaton.bpm.engine.impl.jobexecutor.ExecuteJobsRunnable;
import org.operaton.bpm.engine.impl.jobexecutor.JobFailureCollector;
import org.operaton.bpm.engine.impl.persistence.entity.JobEntity;

/**
 * M-054: Operaton's {@link ExecuteJobsRunnable} with a per-type bulkhead ({@link JobBulkheads}) around each job. For a
 * type that has a limit, the job's handler type is read in its own short read-only command, then a permit is taken on
 * this executor thread (outside any transaction). The job then runs exactly as Operaton runs it
 * ({@code super.executeJob}: {@code ExecuteJobsCmd} through {@code ExecuteJobHelper}, failed-job handling unchanged).
 * When no permit is free in time, or the executor stops while the job waits, the job is handed back through
 * Operaton's own {@code unlockJob} ({@code UnlockJobCmd}: lock owner and expiry cleared, retries untouched); the run
 * loop then signals the acquisition ({@code jobWasAdded}) as it always does at its end (javap, Operaton 2.2.0).
 */
public class BulkheadExecuteJobsRunnable extends ExecuteJobsRunnable {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(BulkheadExecuteJobsRunnable.class);

    private final JobBulkheads bulkheads;

    public BulkheadExecuteJobsRunnable(List<String> jobIds, ProcessEngineImpl processEngine, JobBulkheads bulkheads) {
        super(jobIds, processEngine);
        this.bulkheads = bulkheads;
    }

    @Override
    protected void executeJob(String jobId, CommandExecutor commandExecutor, JobFailureCollector jobFailureCollector) {
        String type = commandExecutor.execute(new JobHandlerType(jobId));
        if (!bulkheads.limits(type)) {
            super.executeJob(jobId, commandExecutor, jobFailureCollector);
            return;
        }
        JobBulkheads.Permit permit = bulkheads.acquire(type, () -> jobExecutor.isActive());
        if (permit == null) {
            LOG.debug("M-054: bulkhead of job type {} full; job {} handed back unexecuted", type, jobId);
            try {
                unlockJob(jobId, commandExecutor);
            } catch (RuntimeException e) {
                // as Operaton's run loop: the lock then simply expires (lockTimeInMillis), the job is not lost
                LOG.warn("M-054: could not hand back job {} (its lock expires on its own): {}", jobId, e.toString());
            }
            return;
        }
        try {
            super.executeJob(jobId, commandExecutor, jobFailureCollector);
        } finally {
            permit.release();
        }
    }

    /** Read-only: the stored handler type of a job, or {@code null} when the job no longer exists. */
    static final class JobHandlerType implements Command<String> {
        private final String jobId;

        JobHandlerType(String jobId) {
            this.jobId = jobId;
        }

        @Override
        public String execute(CommandContext commandContext) {
            JobEntity job = commandContext.getJobManager().findJobById(jobId);
            return job == null ? null : job.getJobHandlerType();
        }
    }
}
