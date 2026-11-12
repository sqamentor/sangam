// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.layer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs the {@link PendingAudit} drain (SL-133) off the caller's thread: {@link #signal} after a writer committed
 * pending rows of a root, plus a periodic sweep for rows whose signal was lost (crash, restart, a drain that timed out
 * on the root lock). A drain waits for the root's {@code ROOT_SEQ} lock on its own worker thread, so it can never block
 * the job thread whose transaction holds that lock.
 *
 * <p>Signals for one root coalesce: while a drain of the root runs, further signals only ask for one more pass after
 * it, so a burst of signals costs at most two drains.
 */
public final class PendingAuditDrainer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(PendingAuditDrainer.class);

    /**
     * @param workers       drain threads (each holds at most one pool connection while it waits for a root lock)
     * @param sweepInterval period of the sweep ({@code Duration.ZERO}: no sweep thread)
     * @param sweepMinAge   only rows at least this old are swept (younger ones still have a live signal)
     * @param sweepBatch    roots per sweep
     */
    public record Settings(int workers, Duration sweepInterval, Duration sweepMinAge, int sweepBatch) {
        public Settings {
            if (workers <= 0 || sweepBatch <= 0) {
                throw new IllegalArgumentException("workers and sweepBatch must be positive");
            }
            Objects.requireNonNull(sweepInterval, "sweepInterval");
            Objects.requireNonNull(sweepMinAge, "sweepMinAge");
        }

        /** Two workers, a sweep every 30 s of rows older than 10 s, 200 roots per sweep. */
        public static Settings defaults() {
            return new Settings(2, Duration.ofSeconds(30), Duration.ofSeconds(10), 200);
        }
    }

    private final PendingAudit pending;
    private final Settings settings;
    private final ExecutorService workers;
    private final ScheduledExecutorService sweeper;
    /** Roots with a drain queued or running: FALSE = running with no new request, TRUE = run (again). */
    private final Map<PendingAudit.ChainRef, Boolean> state = new ConcurrentHashMap<>();

    public PendingAuditDrainer(PendingAudit pending, Settings settings) {
        this.pending = Objects.requireNonNull(pending, "pending");
        this.settings = settings == null ? Settings.defaults() : settings;
        this.workers = Executors.newFixedThreadPool(this.settings.workers(), daemon("sangam-audit-drain-"));
        this.sweeper = this.settings.sweepInterval().isZero() ? null
                : Executors.newSingleThreadScheduledExecutor(daemon("sangam-audit-sweep-"));
    }

    public PendingAudit pending() {
        return pending;
    }

    /** Starts the periodic sweep (no-op without a sweep interval). */
    public PendingAuditDrainer start() {
        if (sweeper != null) {
            long ms = settings.sweepInterval().toMillis();
            sweeper.scheduleWithFixedDelay(this::sweepQuietly, ms, ms, TimeUnit.MILLISECONDS);
        }
        return this;
    }

    /** Asks for a drain of the root; returns at once. */
    public void signal(String tenant, String chainId) {
        PendingAudit.ChainRef key = new PendingAudit.ChainRef(tenant, chainId);
        if (state.put(key, Boolean.TRUE) == null) {
            try {
                workers.execute(() -> run(key));
            } catch (RejectedExecutionException e) {
                state.remove(key);   // shutting down: the next start's sweep drains the rows
            }
        }
    }

    private void run(PendingAudit.ChainRef key) {
        while (true) {
            state.put(key, Boolean.FALSE);   // consume the request; a signal from now on asks for another pass
            drainQuietly(key);
            if (state.remove(key, Boolean.FALSE)) {
                return;
            }
        }
    }

    /**
     * One sweep, synchronously: drains every root with rows at least {@code sweepMinAge} old.
     *
     * @return events appended
     */
    public int sweep() {
        return sweep(settings.sweepMinAge());
    }

    /** One sweep over rows at least {@code minAge} old ({@code Duration.ZERO}: every pending row). */
    public int sweep(Duration minAge) {
        int n = 0;
        for (PendingAudit.ChainRef ref : pending.due(minAge, settings.sweepBatch())) {
            n += drainQuietly(ref);
        }
        return n;
    }

    private void sweepQuietly() {
        try {
            int n = sweep();
            if (n > 0) {
                LOG.info("pending audit sweep appended {} event(s)", n);
            }
        } catch (RuntimeException e) {
            LOG.warn("pending audit sweep failed; retried at the next sweep", e);
        }
    }

    private int drainQuietly(PendingAudit.ChainRef ref) {
        try {
            return pending.drain(ref.tenantId(), ref.chainId());
        } catch (RuntimeException e) {
            LOG.warn("pending audit drain of root {} (tenant {}) failed; the sweep retries it", ref.chainId(), ref.tenantId(), e);
            try {
                pending.recordFailure(ref.tenantId(), ref.chainId(), e.getClass().getSimpleName() + ": " + e.getMessage());
            } catch (RuntimeException ignored) {
                // the database is unavailable: the rows stay as they are and the sweep retries
            }
            return 0;
        }
    }

    @Override
    public void close() {
        if (sweeper != null) {
            sweeper.shutdownNow();
        }
        workers.shutdown();
        try {
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
                workers.shutdownNow();
            }
        } catch (InterruptedException e) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static ThreadFactory daemon(String prefix) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
