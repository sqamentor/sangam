// SPDX-License-Identifier: Apache-2.0
package org.sangam.assembly.integration;

import java.time.Duration;
import java.util.Objects;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

import org.sangam.assembly.host.HostScheduler;
import org.sangam.assembly.host.Lifecycle;
import org.sangam.assembly.host.SangamProperties;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.PendingAudit;
import org.sangam.core.layer.PendingAuditDrainer;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.relay.LeaderElection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The two periodic sweeps the assembly itself schedules (owner decision 315): they run on the host's scheduling seam
 * ({@link HostScheduler}), not on executors of their own. What runs and how often is decided here, with the keys and
 * defaults the Spring host reads; the host only supplies the timer. A sweep that fails is logged and runs again at its
 * next turn, whatever the host scheduler does with a failing task.
 *
 * <ul>
 *   <li><b>Pending audit</b> (SL-133): {@link #pendingAudit} builds the drainer without a sweep thread of its own and
 *       schedules its sweep every {@value #PENDING_SWEEP_INTERVAL} (default 30 s; 0: no sweep).</li>
 *   <li><b>Idempotency cleanup</b>: {@link #idempotencyCleanup} runs the store's cleanup every
 *       {@value #CLEANUP_MINUTES} minutes (default 60, first run after {@link #CLEANUP_FIRST_RUN}) on the node that
 *       holds the {@value #IDEMPOTENCY_CLEANUP} leadership.</li>
 * </ul>
 */
public final class IntegrationSweeps {

    private static final Logger LOG = LoggerFactory.getLogger(IntegrationSweeps.class);

    public static final String PENDING_WORKERS = "sangam.audit.pending.workers";
    public static final String PENDING_SWEEP_INTERVAL = "sangam.audit.pending.sweep-interval";
    public static final String PENDING_SWEEP_MIN_AGE = "sangam.audit.pending.sweep-min-age";
    public static final String PENDING_SWEEP_BATCH = "sangam.audit.pending.sweep-batch";

    /** Task and component name of the pending-audit sweep. */
    public static final String PENDING_AUDIT_SWEEP = "sangam-pending-audit-sweep";

    public static final String CLEANUP_MINUTES = "sangam.rest.idempotency-cleanup-minutes";

    /** Task, component and leadership name of the idempotency cleanup (the Spring host's leadership name). */
    public static final String IDEMPOTENCY_CLEANUP = "sangam-idempotency-cleanup";

    /** The first cleanup runs this long after the start. */
    public static final Duration CLEANUP_FIRST_RUN = Duration.ofMinutes(5);

    /** Before the integration services ({@link IntegrationLifecycles#PHASE}): started first, stopped after them. */
    static final int PENDING_AUDIT_PHASE = IntegrationLifecycles.PHASE - 10;

    /** After the integration services: started last, stopped first. */
    static final int CLEANUP_PHASE = Integer.MAX_VALUE;

    private IntegrationSweeps() {
    }

    /** The pending-audit settings as configured (the interval is the configured one, also when a host schedules it). */
    public static PendingAuditDrainer.Settings pendingAuditSettings(SangamProperties properties) {
        PendingAuditDrainer.Settings d = PendingAuditDrainer.Settings.defaults();
        Duration interval = properties.getDuration(PENDING_SWEEP_INTERVAL, d.sweepInterval());
        if (interval.isNegative()) {
            throw new IllegalArgumentException(PENDING_SWEEP_INTERVAL + " must not be negative: " + interval);
        }
        return new PendingAuditDrainer.Settings(properties.getInt(PENDING_WORKERS, d.workers()), interval,
                properties.getDuration(PENDING_SWEEP_MIN_AGE, d.sweepMinAge()),
                properties.getInt(PENDING_SWEEP_BATCH, d.sweepBatch()));
    }

    /**
     * The pending-audit drainer with its sweep on {@code scheduler}: the drainer keeps its drain workers (decision
     * 315: services keep their executors) but has no sweep thread; the component schedules the sweep at start, cancels
     * it at stop and then closes the drainer.
     */
    public static PendingAuditSweep pendingAudit(PendingAudit pending, SangamProperties properties,
                                                 HostScheduler scheduler) {
        Objects.requireNonNull(pending, "pending");
        Objects.requireNonNull(scheduler, "scheduler");
        PendingAuditDrainer.Settings s = pendingAuditSettings(properties);
        PendingAuditDrainer drainer = new PendingAuditDrainer(pending,
                new PendingAuditDrainer.Settings(s.workers(), Duration.ZERO, s.sweepMinAge(), s.sweepBatch()));
        return new PendingAuditSweep(drainer, scheduler, s.sweepInterval());
    }

    /** The drainer and the runtime component that sweeps it on the host scheduler. */
    public static final class PendingAuditSweep implements Lifecycle {

        private final PendingAuditDrainer drainer;
        private final HostScheduler scheduler;
        private final Duration interval;
        private HostScheduler.Scheduled scheduled;

        private PendingAuditSweep(PendingAuditDrainer drainer, HostScheduler scheduler, Duration interval) {
            this.drainer = drainer;
            this.scheduler = scheduler;
            this.interval = interval;
        }

        /** The drainer callers signal after they committed pending rows. */
        public PendingAuditDrainer drainer() {
            return drainer;
        }

        /** The configured sweep interval ({@code Duration.ZERO}: no sweep). */
        public Duration interval() {
            return interval;
        }

        @Override
        public String name() {
            return PENDING_AUDIT_SWEEP;
        }

        @Override
        public int phase() {
            return PENDING_AUDIT_PHASE;
        }

        @Override
        public synchronized void start() {
            if (!interval.isZero() && scheduled == null) {
                scheduled = scheduler.scheduleWithFixedDelay(PENDING_AUDIT_SWEEP, interval, interval, this::sweep);
            }
        }

        private void sweep() {
            try {
                int n = drainer.sweep();
                if (n > 0) {
                    LOG.info("pending audit sweep appended {} event(s)", n);
                }
            } catch (RuntimeException e) {
                LOG.warn("pending audit sweep failed; retried at the next sweep", e);
            }
        }

        @Override
        public synchronized void stop(Duration drain) {
            if (scheduled != null) {
                scheduled.cancel();
                scheduled = null;
            }
            drainer.close();
        }
    }

    /** The configured cleanup period in minutes; anything below one minute is refused with the key named. */
    public static long cleanupMinutes(SangamProperties properties) {
        long minutes = properties.getLong(CLEANUP_MINUTES, 60L);
        if (minutes < 1) {
            throw new IllegalArgumentException(CLEANUP_MINUTES + " must be at least 1: " + minutes);
        }
        return minutes;
    }

    /** How long the cleanup leadership is held: twice the period, at least three minutes. */
    public static Duration leaderTtl(long minutes) {
        return Duration.ofMinutes(Math.max(3, minutes * 2));
    }

    /**
     * The idempotency cleanup on {@code scheduler}: every {@value #CLEANUP_MINUTES} minutes, on the leader only,
     * {@code cleanup} compacts expired responses and deletes expired keys (it returns the rows it touched).
     *
     * @param newTransactions independent short transactions for the leadership row
     */
    public static Lifecycle idempotencyCleanup(HostScheduler scheduler, SangamProperties properties, Dialect dialect,
                                               Transactions newTransactions, String node, IntSupplier cleanup) {
        long minutes = cleanupMinutes(properties);
        LeaderElection leader = new LeaderElection(dialect, newTransactions, node, leaderTtl(minutes));
        return idempotencyCleanup(scheduler, minutes, () -> leader.tryAcquire(IDEMPOTENCY_CLEANUP), cleanup);
    }

    /** As above, with the leadership as a function: a negative epoch means this node is not the leader. */
    static Lifecycle idempotencyCleanup(HostScheduler scheduler, long minutes, LongSupplier leaderEpoch,
                                        IntSupplier cleanup) {
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(leaderEpoch, "leaderEpoch");
        Objects.requireNonNull(cleanup, "cleanup");
        Runnable tick = () -> {
            try {
                if (leaderEpoch.getAsLong() >= 0) {
                    LOG.info("idempotency cleanup touched {} rows", cleanup.getAsInt());
                }
            } catch (RuntimeException e) {
                LOG.warn("idempotency cleanup failed: {}", e.toString());
            }
        };
        HostScheduler.Scheduled[] scheduled = new HostScheduler.Scheduled[1];
        return Lifecycle.of(IDEMPOTENCY_CLEANUP, CLEANUP_PHASE, () -> {
            synchronized (scheduled) {
                if (scheduled[0] == null) {
                    scheduled[0] = scheduler.scheduleWithFixedDelay(IDEMPOTENCY_CLEANUP, CLEANUP_FIRST_RUN,
                            Duration.ofMinutes(minutes), tick);
                }
            }
        }, drain -> {
            synchronized (scheduled) {
                if (scheduled[0] != null) {
                    scheduled[0].cancel();
                    scheduled[0] = null;
                }
            }
        });
    }
}
