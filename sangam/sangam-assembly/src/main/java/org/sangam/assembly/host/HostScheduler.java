// SPDX-License-Identifier: Apache-2.0
package org.sangam.assembly.host;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scheduling seam (design note section 10.1). The assembly decides what runs and how often; the host only supplies the
 * timer. Cluster correctness never comes from the scheduler: partition leases and fencing epochs make a task that fires
 * twice, or on two nodes, execute once. A host without a scheduler of its own uses {@link #standalone(String)}.
 */
public interface HostScheduler {

    /** Runs {@code task} after {@code initialDelay}, then {@code delay} after each run ends. */
    Scheduled scheduleWithFixedDelay(String name, Duration initialDelay, Duration delay, Runnable task);

    /** A scheduled task. */
    interface Scheduled {
        void cancel();
    }

    /** The assembly's own scheduler: one timer thread, each run on that thread, a failing run logged and retried. */
    static Standalone standalone(String name) {
        return new Standalone(name);
    }

    /** {@link HostScheduler} on a single-thread {@link ScheduledExecutorService}; it is a {@link Lifecycle} too. */
    final class Standalone implements HostScheduler, Lifecycle {

        private static final Logger LOG = LoggerFactory.getLogger(Standalone.class);

        private final String name;
        private final ScheduledThreadPoolExecutor executor;

        Standalone(String name) {
            this.name = Objects.requireNonNull(name, "name");
            this.executor = new ScheduledThreadPoolExecutor(1, r -> {
                Thread t = new Thread(r, name);
                t.setDaemon(true);
                return t;
            });
            this.executor.setRemoveOnCancelPolicy(true);
        }

        @Override
        public Scheduled scheduleWithFixedDelay(String task, Duration initialDelay, Duration delay, Runnable run) {
            Objects.requireNonNull(run, "run");
            if (initialDelay.isNegative() || delay.isNegative() || delay.isZero()) {
                throw new IllegalArgumentException(task + ": the initial delay must not be negative and the delay"
                        + " must be positive");
            }
            ScheduledFuture<?> f = executor.scheduleWithFixedDelay(() -> {
                try {
                    run.run();
                } catch (RuntimeException e) {
                    // a failed run must not cancel the schedule (the executor would drop every later run)
                    LOG.warn("scheduled task {} failed; it runs again after {}", task, delay, e);
                }
            }, initialDelay.toNanos(), delay.toNanos(), TimeUnit.NANOSECONDS);
            return () -> f.cancel(false);
        }

        @Override
        public String name() {
            return name;
        }

        /** Last to stop: the components that schedule on it stop before it. */
        @Override
        public int phase() {
            return Integer.MIN_VALUE;
        }

        @Override
        public void start() {
            // the executor accepts work from construction on; nothing else to start
        }

        @Override
        public void stop(Duration drain) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(drain.toNanos(), TimeUnit.NANOSECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        public boolean isShutdown() {
            return executor.isShutdown();
        }
    }
}
