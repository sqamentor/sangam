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
import org.sangam.engine.trace.EngineTracing;
import org.sangam.engine.trace.JobSpanScope;
import org.sangam.engine.trace.JobTraceContexts;
import org.sangam.engine.tx.CommandState;

/**
 * Operaton's {@link ExecuteJobsRunnable} with Sangam's per-job options around each job; with no option the plain
 * Operaton runnable is used instead (see {@link SangamJobExecutor#getExecuteJobsRunnable}).
 * <ul>
 *   <li><b>M-054 per-type bulkhead</b> ({@link JobBulkheads}): the job's handler type is read in a short read-only
 *       command, then a permit is taken on this executor thread outside any transaction. When no permit is free in
 *       time, or the executor stops while the job waits, the job is handed back through Operaton's own
 *       {@code unlockJob} ({@code UnlockJobCmd}: lock owner and expiry cleared, retries untouched); the run loop then
 *       signals the acquisition ({@code jobWasAdded}) as it always does at its end (javap, Operaton 2.2.0).</li>
 *   <li><b>M-062 job span with its link</b>: with trace links on, the same read command also loads the creating trace
 *       context ({@code JOB_TRACE_CONTEXT}); the job runs inside a {@link EngineTracing.Kind#JOB} root span named after
 *       its handler type (bounded) and linked to that context. {@link JobSpanScope} makes the job's
 *       {@code ExecuteJobsCmd} a child span. Every tracer call is guarded; a tracing failure never affects the job.</li>
 * </ul>
 * The job itself always runs exactly as Operaton runs it ({@code super.executeJob}: {@code ExecuteJobsCmd} through
 * {@code ExecuteJobHelper}, failed-job handling unchanged).
 */
public class SangamExecuteJobsRunnable extends ExecuteJobsRunnable {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(SangamExecuteJobsRunnable.class);

    private final JobBulkheads bulkheads;
    private final EngineTracing tracing;

    /**
     * @param bulkheadsOrNull the bulkheads ({@code null}: none)
     * @param tracingOrNull   the tracer for linked job spans ({@code null}: no job span here)
     */
    public SangamExecuteJobsRunnable(List<String> jobIds, ProcessEngineImpl processEngine, JobBulkheads bulkheadsOrNull,
                                     EngineTracing tracingOrNull) {
        super(jobIds, processEngine);
        this.bulkheads = bulkheadsOrNull;
        this.tracing = tracingOrNull;
    }

    @Override
    protected void executeJob(String jobId, CommandExecutor commandExecutor, JobFailureCollector jobFailureCollector) {
        JobFacts facts = commandExecutor.execute(new ReadJobFacts(jobId, tracing != null));
        JobBulkheads.Permit permit = null;
        if (bulkheads != null && bulkheads.limits(facts.handlerType())) {
            permit = bulkheads.acquire(facts.handlerType(), () -> jobExecutor.isActive());
            if (permit == null) {
                LOG.debug("M-054: bulkhead of job type {} full; job {} handed back unexecuted", facts.handlerType(), jobId);
                try {
                    unlockJob(jobId, commandExecutor);
                } catch (RuntimeException e) {
                    // as Operaton's run loop: the lock then simply expires (lockTimeInMillis), the job is not lost
                    LOG.warn("M-054: could not hand back job {} (its lock expires on its own): {}", jobId, e.toString());
                }
                return;
            }
        }
        EngineTracing.Span span = tracing == null ? null : startSpan(facts);
        if (span != null) {
            JobSpanScope.enter();
        }
        try {
            super.executeJob(jobId, commandExecutor, jobFailureCollector);
        } catch (RuntimeException | Error e) {
            if (span != null) {
                guarded(() -> span.error(e));
            }
            throw e;
        } finally {
            if (span != null) {
                JobSpanScope.exit();
                guarded(span::end);
            }
            if (permit != null) {
                permit.release();
            }
        }
    }

    private EngineTracing.Span startSpan(JobFacts facts) {
        try {
            return tracing.start(facts.handlerType() == null ? "job" : facts.handlerType(), EngineTracing.Kind.JOB,
                    facts.link());
        } catch (RuntimeException e) {
            LOG.debug("M-062: tracing start() failed", e);
            return null;
        }
    }

    private static void guarded(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            LOG.debug("M-062: tracing call failed", e);
        }
    }

    /** What the runnable needs to know about a job before running it; all {@code null} when the job is gone. */
    record JobFacts(String handlerType, EngineTracing.TraceContext link) {
    }

    /** Read-only: the job's stored handler type and, when asked, its stored creating trace context. */
    static final class ReadJobFacts implements Command<JobFacts> {
        private final String jobId;
        private final boolean link;

        ReadJobFacts(String jobId, boolean link) {
            this.jobId = jobId;
            this.link = link;
        }

        @Override
        public JobFacts execute(CommandContext commandContext) {
            JobEntity job = commandContext.getJobManager().findJobById(jobId);
            if (job == null) {
                return new JobFacts(null, null);
            }
            EngineTracing.TraceContext ctx = link
                    ? JobTraceContexts.read(CommandState.connection(commandContext), jobId).orElse(null) : null;
            return new JobFacts(job.getJobHandlerType(), ctx);
        }
    }
}
