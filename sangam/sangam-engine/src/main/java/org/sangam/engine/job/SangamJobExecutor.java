// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.job;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.operaton.bpm.engine.impl.ProcessEngineImpl;
import org.operaton.bpm.engine.impl.jobexecutor.DefaultJobExecutor;
import org.sangam.engine.trace.EngineTracing;

/**
 * M-054 / M-062 (SANGAM.md §4.4, R5, C7, R13): Operaton's {@link DefaultJobExecutor} with opt-in options, all off by
 * default. With every option off this class is not installed at all ({@code SangamProcessEnginePlugin} keeps a plain
 * {@code DefaultJobExecutor}).
 * <ul>
 *   <li><b>Virtual threads.</b> The pool is the same {@link ThreadPoolExecutor} Operaton builds (core and max pool
 *       size, keep-alive 0, an {@link ArrayBlockingQueue} of {@code queueSize}, {@code AbortPolicy}, so a full queue
 *       still goes to the rejected-jobs handler), with a virtual-thread factory instead of the default one.
 *       {@code DefaultJobExecutor.startExecutingJobs} builds its pool only when there is none or it is shut down
 *       (javap, Operaton 2.2.0), so the pool built here is the one it uses. Operaton's {@code Context} stacks are
 *       ThreadLocals; pool threads are long-lived, so they behave as on platform threads.</li>
 *   <li><b>Per-type bulkheads</b> and <b>linked job spans</b>: each batch runs in a {@link SangamExecuteJobsRunnable}
 *       ({@link #getExecuteJobsRunnable}, also used by Operaton's rejected-jobs handler).</li>
 * </ul>
 * Everything else (acquisition, locking, failure handling, shutdown) is {@code DefaultJobExecutor}'s; partitioned
 * acquisition (M-056) is installed through {@code setAcquireJobsCmdFactory} on any executor.
 */
public class SangamJobExecutor extends DefaultJobExecutor {

    private final boolean virtualThreads;
    private final JobBulkheads bulkheads;
    private final EngineTracing linkedTracing;

    /** As {@link #SangamJobExecutor(boolean, Map, Duration, EngineTracing)} without linked job spans. */
    public SangamJobExecutor(boolean virtualThreads, Map<String, Integer> bulkheads, Duration bulkheadWait) {
        this(virtualThreads, bulkheads, bulkheadWait, null);
    }

    /**
     * @param virtualThreads pool threads are virtual threads
     * @param bulkheads      job handler type -> concurrent executions; empty or {@code null} = none
     * @param bulkheadWait   the longest a job waits for its bulkhead (see {@link JobBulkheads})
     * @param linkedTracing  the tracer that opens each job's root span linked to its stored creating context
     *                       ({@code JOB_TRACE_CONTEXT}); {@code null} = none (trace links off)
     * @throws IllegalArgumentException on an invalid bulkhead limit or wait
     */
    public SangamJobExecutor(boolean virtualThreads, Map<String, Integer> bulkheads, Duration bulkheadWait,
                             EngineTracing linkedTracing) {
        this.virtualThreads = virtualThreads;
        JobBulkheads b = new JobBulkheads(bulkheads, bulkheadWait);
        this.bulkheads = b.isEmpty() ? null : b;
        this.linkedTracing = linkedTracing == EngineTracing.NONE ? null : linkedTracing;
    }

    public boolean isVirtualThreads() {
        return virtualThreads;
    }

    /** The bulkheads, or {@code null} when no job type has a limit. */
    public JobBulkheads bulkheads() {
        return bulkheads;
    }

    /** The tracer of linked job spans, or {@code null}. */
    public EngineTracing linkedTracing() {
        return linkedTracing;
    }

    @Override
    protected void startExecutingJobs() {
        if (virtualThreads && (threadPoolExecutor == null || threadPoolExecutor.isShutdown())) {
            threadPoolExecutor = newVirtualThreadPool();
        }
        super.startExecutingJobs();
    }

    /** The pool Operaton's {@code DefaultJobExecutor} builds, on virtual threads. */
    ThreadPoolExecutor newVirtualThreadPool() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(corePoolSize, maxPoolSize, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueSize), Thread.ofVirtual().name("sangam-job-", 0).factory());
        pool.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        return pool;
    }

    @Override
    public Runnable getExecuteJobsRunnable(List<String> jobIds, ProcessEngineImpl processEngine) {
        return bulkheads == null && linkedTracing == null ? super.getExecuteJobsRunnable(jobIds, processEngine)
                : new SangamExecuteJobsRunnable(jobIds, processEngine, bulkheads, linkedTracing);
    }
}
