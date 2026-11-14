// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.ToIntFunction;

import org.sangam.integration.relay.LeaderElection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Decision 165 (W4-H REQUEST 01:24:24Z): leader-elected purge of expired DSC prepared state, on the pattern of
 * {@link ResourcesSweepScheduler}. One tick a minute renews the {@code INTEGRATION_LEADER} lease {@value #LEADER}; the
 * leader calls {@code DscPreparedStore.purgeExpired(now)} every {@code interval} (default 15 minutes). The purge deletes
 * only unused entries whose expiry has passed, so running it twice, or on two nodes during a leader change, is harmless.
 */
public final class DscPurgeScheduler implements SmartLifecycle {

    public static final String LEADER = "sangam-dsc-purge";
    private static final Logger LOG = LoggerFactory.getLogger(DscPurgeScheduler.class);

    /** The lease operations used here (LeaderElection in the server; a fake in tests). */
    public interface Leader {
        long tryAcquire(String name);

        void release(String name);

        static Leader of(LeaderElection election) {
            Objects.requireNonNull(election, "election");
            return new Leader() {
                @Override
                public long tryAcquire(String name) {
                    return election.tryAcquire(name);
                }

                @Override
                public void release(String name) {
                    election.release(name);
                }
            };
        }
    }

    private final ToIntFunction<Instant> purgeExpired;
    private final Leader leader;
    private final Duration interval;
    private final Clock clock;
    private ScheduledExecutorService executor;
    private volatile boolean running;
    private volatile Instant next = Instant.EPOCH;

    /**
     * @param purgeExpired {@code DscPreparedStore::purgeExpired}
     */
    public DscPurgeScheduler(ToIntFunction<Instant> purgeExpired, Leader leader, Duration interval, Clock clock) {
        this.purgeExpired = Objects.requireNonNull(purgeExpired, "purgeExpired");
        this.leader = Objects.requireNonNull(leader, "leader");
        this.interval = interval == null || interval.isNegative() || interval.isZero() ? Duration.ofMinutes(15) : interval;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public Duration interval() {
        return interval;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sangam-dsc-purge");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(this::tick, 50, 60, TimeUnit.SECONDS);
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

    @Override
    public int getPhase() {
        // After the integration services (MAX_VALUE - 100), before web traffic stops.
        return Integer.MAX_VALUE - 90;
    }

    /** One scheduler tick; returns the number of purged entries (0 when not the leader or not due). */
    int tick() {
        try {
            if (leader.tryAcquire(LEADER) < 0) {
                return 0;
            }
            Instant now = clock.instant();
            if (now.isBefore(next)) {
                return 0;
            }
            next = now.plus(interval);
            int n = purgeExpired.applyAsInt(now);
            if (n > 0) {
                LOG.info("DSC prepared state: {} expired entries purged", n);
            }
            return n;
        } catch (RuntimeException e) {
            LOG.warn("DSC purge tick failed: {}", e.toString());
            return 0;
        }
    }
}
