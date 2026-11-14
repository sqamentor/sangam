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

/**
 * M-054 (SANGAM.md §4.4, R5, C7): Operaton's {@link DefaultJobExecutor} with two opt-in options, both off by default.
 * With both off this class is not installed at all ({@code SangamProcessEnginePlugin} keeps a plain
 * {@code DefaultJobExecutor}).
 * <ul>
 *   <li><b>Virtual threads.</b> The pool is the same {@link ThreadPoolExecutor} Operaton builds (core and max pool
 *       size, keep-alive 0, an {@link ArrayBlockingQueue} of {@code queueSize}, {@code AbortPolicy}, so a full queue
 *       still goes to the rejected-jobs handler), with a virtual-thread factory instead of the default one.
 *       {@code DefaultJobExecutor.startExecutingJobs} builds its pool only when there is none or it is shut down
 *       (javap, Operaton 2.2.0), so the pool built here is the one it uses. Operaton's {@code Context} stacks are
 *       ThreadLocals; pool threads are long-lived, so they behave as on platform threads.</li>
 *   <li><b>Per-type bulkheads.</b> Each batch runs in a {@link BulkheadExecuteJobsRunnable}
 *       ({@link #getExecuteJobsRunnable}, also used by Operaton's rejected-jobs handler).</li>
 * </ul>
 * Everything else (acquisition, locking, failure handling, shutdown) is {@code DefaultJobExecutor}'s.
 */
public class SangamJobExecutor extends DefaultJobExecutor {

    private final boolean virtualThreads;
    private final JobBulkheads bulkheads;

    /**
     * @param virtualThreads pool threads are virtual threads
     * @param bulkheads      job handler type -> concurrent executions; empty or {@code null} = none
     * @param bulkheadWait   the longest a job waits for its bulkhead (see {@link JobBulkheads})
     * @throws IllegalArgumentException on an invalid bulkhead limit or wait
     */
    public SangamJobExecutor(boolean virtualThreads, Map<String, Integer> bulkheads, Duration bulkheadWait) {
        this.virtualThreads = virtualThreads;
        JobBulkheads b = new JobBulkheads(bulkheads, bulkheadWait);
        this.bulkheads = b.isEmpty() ? null : b;
    }

    public boolean isVirtualThreads() {
        return virtualThreads;
    }

    /** The bulkheads, or {@code null} when no job type has a limit. */
    public JobBulkheads bulkheads() {
        return bulkheads;
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
        return bulkheads == null ? super.getExecuteJobsRunnable(jobIds, processEngine)
                : new BulkheadExecuteJobsRunnable(jobIds, processEngine, bulkheads);
    }
}
