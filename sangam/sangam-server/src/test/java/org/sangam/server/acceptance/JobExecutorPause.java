// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance;

import java.time.Duration;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.jobexecutor.DefaultJobExecutor;
import org.operaton.bpm.engine.impl.jobexecutor.JobExecutor;
import org.operaton.bpm.engine.spring.components.jobexecutor.SpringJobExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * IFMSPACKS-EXEC-RACE (split out of SL-89): pauses the server's job executor for a test driver that runs due jobs on its
 * own thread, and returns only when no job of the executor is still running.
 *
 * <p><b>Root cause.</b> {@link JobExecutor#shutdown()} stops the acquisition thread and then calls
 * {@code stopExecutingJobs()}. Operaton's {@link DefaultJobExecutor} shuts its pool down and waits for it there, but the
 * Spring Boot host's {@link SpringJobExecutor} (the server's, running on the {@code operatonTaskExecutor}
 * {@link ThreadPoolTaskExecutor}) only stops the acquisition thread (javap, operaton-engine-spring 2.2.0). The batches it
 * had already handed to its task executor keep running after {@code shutdown()} returns, so a driver that started
 * executing jobs at once raced them on the same job ids (OptimisticLockingException, matrix-sqlserver.log). Pausing is
 * therefore shutdown plus draining the task executor's pool (every running and queued batch finishes, bounded by
 * {@link #DRAIN}); a task executor whose work cannot be drained fails closed.
 */
public final class JobExecutorPause implements AutoCloseable {

    /** The longest the pause waits for running batches: the wait {@code DefaultJobExecutor} itself gives its pool. */
    public static final Duration DRAIN = Duration.ofSeconds(60);

    private final JobExecutor executor;
    private final boolean wasActive;

    private JobExecutorPause(JobExecutor executor, boolean wasActive) {
        this.executor = executor;
        this.wasActive = wasActive;
    }

    /** Pauses the job executor of {@code engine} (a no-op when it has none or it is not running). */
    public static JobExecutorPause pause(ProcessEngine engine) {
        return pause(((ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration()).getJobExecutor(), DRAIN);
    }

    /** Pauses {@code executor}: shuts it down and waits up to {@code drain} until none of its jobs still runs. */
    public static JobExecutorPause pause(JobExecutor executor, Duration drain) {
        boolean active = executor != null && executor.isActive();
        if (active) {
            executor.shutdown();
            awaitIdle(executor, drain);
        }
        return new JobExecutorPause(executor, active);
    }

    /** Whether the executor ran before the pause (and is restarted by {@link #close()}). */
    public boolean wasActive() {
        return wasActive;
    }

    /** Restarts the executor when it ran before the pause. */
    @Override
    public void close() {
        if (wasActive) {
            if (executor instanceof SpringJobExecutor spring && spring.getTaskExecutor() instanceof ThreadPoolTaskExecutor pool
                    && pool.getThreadPoolExecutor().isShutdown()) {
                pool.initialize();
            }
            executor.start();
        }
    }

    private static void awaitIdle(JobExecutor executor, Duration drain) {
        if (!(executor instanceof SpringJobExecutor spring)) {
            if (executor instanceof DefaultJobExecutor) {
                return; // its stopExecutingJobs() already shut its pool down and waited for it
            }
            throw new IllegalStateException("IFMSPACKS-EXEC-RACE: cannot tell whether jobs of "
                    + executor.getClass().getName() + " still run after shutdown(); refusing to let the driver race it");
        }
        if (!(spring.getTaskExecutor() instanceof ThreadPoolTaskExecutor pool)) {
            throw new IllegalStateException("IFMSPACKS-EXEC-RACE: the job executor's task executor "
                    + (spring.getTaskExecutor() == null ? "null" : spring.getTaskExecutor().getClass().getName())
                    + " does not report its running work; refusing to let the driver race it");
        }
        // Counting active and queued work is only approximate (a worker between taking a batch from the queue and
        // running it is counted nowhere), so the pool is drained the way DefaultJobExecutor drains its own: an orderly
        // shutdown (running and queued batches finish, nothing is interrupted) and awaitTermination. close() gives the
        // task executor a fresh pool before the job executor starts again.
        java.util.concurrent.ThreadPoolExecutor running = pool.getThreadPoolExecutor();
        running.shutdown();
        try {
            if (!running.awaitTermination(drain.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("IFMSPACKS-EXEC-RACE: job batches still running " + drain
                        + " after the job executor was shut down (active " + running.getActiveCount() + ", queued "
                        + running.getQueue().size() + ")");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for job batches to finish", e);
        }
    }
}
