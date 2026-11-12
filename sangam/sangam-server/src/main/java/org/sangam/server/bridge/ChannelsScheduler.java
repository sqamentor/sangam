// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.sangam.integration.relay.LeaderElection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/** Leader-elected poll loop for inbound mail, polled channels and the channel runtime. One failed task does not stop the rest. */
public final class ChannelsScheduler implements SmartLifecycle {

    public static final String LEADER = "sangam-channels-scheduler";
    private static final Logger LOG = LoggerFactory.getLogger(ChannelsScheduler.class);

    /** One unit of polling. */
    public interface Task {
        void run() throws Exception;
    }

    public record NamedTask(String name, Task task) {
    }

    /**
     * Spaces a task out to at most once per {@code period} on this node (daily retention inside the poll loop). The next run
     * is due {@code period} after a success and {@code retry} after a failure, which is rethrown so the tick logs it. The
     * state is in memory: a new leader runs a due task once more, so wrapped tasks must be repeatable.
     */
    public static final class Spaced {
        private final Duration period;
        private final Duration retry;
        private final Clock clock;
        private final Map<String, Instant> due = new ConcurrentHashMap<>();

        public Spaced(Duration period, Duration retry, Clock clock) {
            if (period.isNegative() || period.isZero() || retry.isNegative() || retry.isZero()) {
                throw new IllegalArgumentException("period and retry must be positive");
            }
            this.period = period;
            this.retry = retry;
            this.clock = clock;
        }

        public Task wrap(String key, Task task) {
            return () -> {
                Instant now = clock.instant();
                Instant next = due.get(key);
                if (next != null && now.isBefore(next)) {
                    return;
                }
                try {
                    task.run();
                    due.put(key, now.plus(period));
                } catch (Exception e) {
                    due.put(key, now.plus(retry));
                    throw e;
                }
            };
        }
    }

    private final LeaderElection leader;
    private final java.util.function.Supplier<List<NamedTask>> tasks;
    private final long periodSeconds;
    private ScheduledExecutorService executor;
    private volatile boolean running;

    public ChannelsScheduler(LeaderElection leader, java.util.function.Supplier<List<NamedTask>> tasks, long periodSeconds) {
        this.leader = leader;
        this.tasks = tasks;
        this.periodSeconds = periodSeconds;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, LEADER);
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(this::tick, 30, periodSeconds, TimeUnit.SECONDS);
        running = true;
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (executor != null) {
            executor.shutdownNow();
        }
        try {
            leader.release(LEADER);
        } catch (RuntimeException e) {
            LOG.debug("leader release failed: {}", e.toString());
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void tick() {
        try {
            if (leader.tryAcquire(LEADER) < 0) {
                return;
            }
            for (NamedTask t : tasks.get()) {
                try {
                    t.task().run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    LOG.warn("channel task {} failed: {}", t.name(), e.toString());
                }
            }
        } catch (RuntimeException e) {
            LOG.warn("channels tick failed: {}", e.toString());
        }
    }
}
