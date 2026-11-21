// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Register E-31(b): the timer at valid-to of an acting posting (SANGAM.md J-P1-E06 "K7 at action and a timer at
 * valid-to", AJ-04 step 4 {@code DELEGATION_EXPIRED}). Every {@code interval} it runs
 * {@code PostingService.expireDue}, which writes one {@code DELEGATION_EXPIRED} for each additional charge or leave
 * substitution whose valid-to has passed and marks the posting as notified in the same transaction.
 *
 * <p>The authority itself never depends on this timer: a seat is refused from valid-to on by the K7 check of every
 * request ({@code PostingService.holdersAt} at database time). The sweep is written once per posting whichever node
 * runs it (only the sweep that flips {@code expiry_notified} writes the event), so it needs no leader.
 */
public final class PostingExpiryScheduler implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(PostingExpiryScheduler.class);
    public static final Duration DEFAULT_INTERVAL = Duration.ofMinutes(1);

    private final IntSupplier sweep;
    private final Duration interval;
    private ScheduledExecutorService executor;
    private volatile boolean running;

    /**
     * @param sweep    one sweep in its own transaction; answers the number of events written
     * @param interval time between two sweeps; null, zero or negative: {@link #DEFAULT_INTERVAL}
     */
    public PostingExpiryScheduler(IntSupplier sweep, Duration interval) {
        this.sweep = Objects.requireNonNull(sweep, "sweep");
        this.interval = interval == null || interval.isNegative() || interval.isZero() ? DEFAULT_INTERVAL : interval;
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
            Thread t = new Thread(r, "sangam-posting-expiry");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(this::tick, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
        running = true;
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        // as the other maintenance schedulers: after the integration services, before web traffic stops
        return Integer.MAX_VALUE - 90;
    }

    /** One sweep; a failure is logged and the next tick tries again (nothing is marked without its event). */
    int tick() {
        try {
            int n = sweep.getAsInt();
            if (n > 0) {
                LOG.info("acting postings past valid-to: {} DELEGATION_EXPIRED written", n);
            }
            return n;
        } catch (RuntimeException e) {
            LOG.warn("posting expiry sweep failed: {}", e.toString());
            return 0;
        }
    }
}
