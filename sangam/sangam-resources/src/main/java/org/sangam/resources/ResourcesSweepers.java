// SPDX-License-Identifier: Apache-2.0
package org.sangam.resources;

import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.resources.booking.BookingModel;
import org.sangam.resources.queue.QueueService;
import org.sangam.resources.roster.RosterService;
import org.sangam.resources.support.Sql;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Scheduler-ready entry points: one method per sweeper, per tenant. Each call
 * <ol>
 *   <li>acquires the lease {@code (tenant, sweeper)} in {@code RES_SWEEP_LEASE} in a short transaction: insert-if-absent,
 *       or take over a lease that is expired or already ours; the {@code fencing} counter increments on every
 *       acquisition. If another node holds a live lease the call returns {@code ran=false} at once;</li>
 *   <li>runs the sweep in its own transaction, first re-checking under a row lock that the lease is still ours with the
 *       same fencing value (a node whose lease was taken over cannot act);</li>
 *   <li>releases the lease (only with the matching fencing value) and records the count.</li>
 * </ol>
 * Every sweep is idempotent (state-guarded updates, skip-locked claims), so running it on many nodes, repeatedly or
 * after a crash is safe; the lease only avoids duplicate work. Sweeps run as the tenant's system principal.
 *
 * <p>Schedule each method every minute or so per tenant ({@link #tenants} lists the tenants with resources data):
 * {@link #expireHolds}, {@link #markNoShows}, {@link #expireWaitlistOffers}, {@link #queueNoShows},
 * {@link #rosterApplyLeave}, {@link #rosterReconcileAttendance}, {@link #flagOverdueAllotments}; or {@link #runAll}.
 */
public final class ResourcesSweepers {

    public static final String EXPIRE_HOLDS = "expire-holds";
    public static final String NO_SHOWS = "booking-no-shows";
    public static final String WAITLIST_OFFERS = "waitlist-offers";
    public static final String QUEUE_NO_SHOWS = "queue-no-shows";
    public static final String ROSTER_LEAVE = "roster-apply-leave";
    public static final String ROSTER_ATTENDANCE = "roster-attendance";
    public static final String OVERDUE_ALLOTMENTS = "overdue-allotments";

    /** Outcome of one scheduled call. */
    public record SweepResult(String sweeper, String tenantId, boolean ran, int count, long fencing, String note) {
    }

    private final ResourcesContext ctx;
    private final Transactions tx;
    private final SangamResources res;
    private final String node;
    private final Duration leaseTtl;
    private final int batch;

    /**
     * @param node     a stable id of this node (host name + process id)
     * @param leaseTtl how long a lease protects a running sweep (longer than one sweep takes)
     * @param batch    rows per sweep call
     */
    public ResourcesSweepers(SangamResources res, Transactions tx, String node, Duration leaseTtl, int batch) {
        this.res = res;
        this.ctx = res.context();
        this.tx = tx;
        this.node = node;
        this.leaseTtl = leaseTtl == null ? Duration.ofMinutes(5) : leaseTtl;
        this.batch = batch <= 0 ? 200 : batch;
    }

    /** Tenants that have resources data (types or service points or roster periods). */
    public List<String> tenants() {
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT tenant_id FROM RES_TYPE UNION SELECT tenant_id FROM RES_SERVICE_POINT"
                + " UNION SELECT tenant_id FROM RES_ROSTER_PERIOD", rs -> rs.getString(1)));
    }

    // ------------------------------------------------------------------ the sweepers

    /** Lapsed holds and approval holds → EXPIRED, claims released, waitlist promoted. */
    public SweepResult expireHolds(String tenantId) {
        return run(tenantId, EXPIRE_HOLDS, (c, sys) -> res.bookings().expireHolds(c, sys, batch));
    }

    /** Confirmed bookings past the type's no-show grace → NO_SHOW, refund by policy. */
    public SweepResult markNoShows(String tenantId) {
        return run(tenantId, NO_SHOWS, (c, sys) -> res.bookings().markNoShows(c, sys, batch));
    }

    /** Waitlist offers whose hold lapsed → EXPIRED; confirmed offers → PROMOTED. */
    public SweepResult expireWaitlistOffers(String tenantId) {
        return run(tenantId, WAITLIST_OFFERS, (c, sys) -> res.waitlist().expireOffers(c, sys));
    }

    /** Called tokens not served within the service point's no-show window → NO_SHOW, for every active service point. */
    public SweepResult queueNoShows(String tenantId) {
        return run(tenantId, QUEUE_NO_SHOWS, (c, sys) -> {
            QueueService q = res.queue();
            int n = 0;
            for (String sp : Jdbc.query(c, "SELECT service_point_id FROM RES_SERVICE_POINT WHERE tenant_id=? AND status <> 'CLOSED'",
                    rs -> rs.getString(1), tenantId)) {
                n += q.noShowSweep(c, sys, sp);
            }
            return n;
        });
    }

    /** Duty overlapping approved leave → LEAVE, hours freed, seat link ended, cover offered (open periods with future duty). */
    public SweepResult rosterApplyLeave(String tenantId) {
        return run(tenantId, ROSTER_LEAVE, (c, sys) -> {
            RosterService r = res.roster();
            Instant now = ctx.now(c);
            int n = 0;
            for (String period : Jdbc.query(c, "SELECT DISTINCT p.period_id FROM RES_ROSTER_PERIOD p JOIN RES_ROSTER_ASSIGNMENT a"
                            + " ON a.tenant_id=p.tenant_id AND a.period_id=p.period_id WHERE p.tenant_id=? AND p.state <> 'CLOSED'"
                            + " AND a.ends_at > ?", rs -> rs.getString(1), tenantId, now)) {
                n += r.applyLeave(c, sys, period);
            }
            return n;
        });
    }

    /** Attendance reconciliation for published (and recently closed) periods whose duty has ended. */
    public SweepResult rosterReconcileAttendance(String tenantId) {
        return run(tenantId, ROSTER_ATTENDANCE, (c, sys) -> {
            RosterService r = res.roster();
            Instant now = ctx.now(c);
            Instant since = now.minus(Duration.ofDays(ctx.setting("attendanceLookbackDays", 7)));
            int n = 0;
            for (String period : Jdbc.query(c, "SELECT DISTINCT p.period_id FROM RES_ROSTER_PERIOD p JOIN RES_ROSTER_ASSIGNMENT a"
                            + " ON a.tenant_id=p.tenant_id AND a.period_id=p.period_id WHERE p.tenant_id=?"
                            + " AND p.state IN ('PUBLISHED','CLOSED') AND a.kind='DUTY' AND a.attendance IS NULL"
                            + " AND a.ends_at <= ? AND a.ends_at > ?", rs -> rs.getString(1), tenantId, now, since)) {
                n += r.reconcileAttendance(c, sys, period);
            }
            return n;
        });
    }

    /**
     * Active allotments past their end or vacate-due date: notifies the holder once and records {@code OVERDUE} in the
     * history (idempotent: an allotment with an OVERDUE event for its current end date is skipped). Eviction or penal
     * charges are left to the pack process that consumes the event.
     */
    public SweepResult flagOverdueAllotments(String tenantId) {
        return run(tenantId, OVERDUE_ALLOTMENTS, (c, sys) -> {
            int n = 0;
            for (BookingModel.Allotment a : res.allotments().overdueForSweep(c, tenantId, batch)) {
                String marker = String.valueOf(a.vacateDueAt() != null ? a.vacateDueAt() : a.validTo());
                long seen = Jdbc.one(c, "SELECT COUNT(*) FROM RES_EVENT WHERE tenant_id=? AND entity_type='ALLOTMENT'"
                                + " AND entity_id=? AND event_type='OVERDUE' AND reason=?", rs -> rs.getLong(1),
                        tenantId, a.allotmentId(), marker).orElse(0L);
                if (seen > 0) {
                    continue;
                }
                ctx.event(c, sys, "ALLOTMENT", a.allotmentId(), "OVERDUE", marker, null, Map.of("holderId", a.holderId()));
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("allotmentId", a.allotmentId());
                d.put("allotmentRef", a.allotmentRef());
                d.put("dueAt", marker);
                ctx.notify(c, tenantId, a.holderId(), "allotment.overdue", d);
                n++;
            }
            return n;
        });
    }

    /** Runs every sweeper once for a tenant (each under its own lease). */
    public List<SweepResult> runAll(String tenantId) {
        return List.of(expireHolds(tenantId), markNoShows(tenantId), expireWaitlistOffers(tenantId), queueNoShows(tenantId),
                rosterApplyLeave(tenantId), rosterReconcileAttendance(tenantId), flagOverdueAllotments(tenantId));
    }

    // ------------------------------------------------------------------ lease

    private SweepResult run(String tenantId, String sweeper, BiFunction<Connection, SecurityIdentity, Integer> work) {
        Long fencing = tx.inTransaction(c -> acquire(c, tenantId, sweeper));
        if (fencing == null) {
            return new SweepResult(sweeper, tenantId, false, 0, -1, "lease held by another node");
        }
        SecurityIdentity sys = SecurityIdentity.system(tenantId, "sweeper:" + sweeper);
        int count;
        try {
            count = tx.inTransaction(c -> {
                if (!stillMine(c, tenantId, sweeper, fencing)) {
                    return -1;
                }
                return work.apply(c, sys);
            });
        } catch (RuntimeException e) {
            tx.inTransaction(c -> release(c, tenantId, sweeper, fencing, null));
            throw e;
        }
        if (count < 0) {
            return new SweepResult(sweeper, tenantId, false, 0, fencing, "lease taken over before the sweep");
        }
        tx.inTransaction(c -> release(c, tenantId, sweeper, fencing, count));
        return new SweepResult(sweeper, tenantId, true, count, fencing, null);
    }

    /** @return the fencing value of the acquired lease, or null when another node holds a live one */
    private Long acquire(Connection c, String tenantId, String sweeper) {
        Instant now = Sql.dbNow(c, ctx.dialect());
        Instant until = now.plus(leaseTtl);
        if (ctx.dialect().insertIfAbsent(c, "INSERT INTO RES_SWEEP_LEASE(tenant_id, sweeper, owner_node, lease_until, fencing,"
                + " last_started_at) VALUES (?,?,?,?,1,?)", tenantId, sweeper, node, until, now)) {
            return 1L;
        }
        Object[] row = Jdbc.one(c, ctx.dialect().lockingSelect("owner_node, lease_until, fencing", "RES_SWEEP_LEASE",
                        "tenant_id=? AND sweeper=?"),
                rs -> new Object[]{rs.getString(1), Jdbc.instant(rs, "lease_until"), rs.getLong(3)}, tenantId, sweeper).orElseThrow();
        String owner = (String) row[0];
        Instant leaseUntil = (Instant) row[1];
        long f = (Long) row[2];
        if (!node.equals(owner) && leaseUntil.isAfter(now)) {
            return null;
        }
        Jdbc.update(c, "UPDATE RES_SWEEP_LEASE SET owner_node=?, lease_until=?, fencing=?, last_started_at=?"
                + " WHERE tenant_id=? AND sweeper=? AND fencing=?", node, until, f + 1, now, tenantId, sweeper, f);
        return f + 1;
    }

    private boolean stillMine(Connection c, String tenantId, String sweeper, long fencing) {
        Instant now = Sql.dbNow(c, ctx.dialect());
        return Jdbc.one(c, ctx.dialect().lockingSelect("owner_node, lease_until, fencing", "RES_SWEEP_LEASE",
                        "tenant_id=? AND sweeper=?"),
                rs -> node.equals(rs.getString(1)) && rs.getLong(3) == fencing && Jdbc.instant(rs, "lease_until").isAfter(now),
                tenantId, sweeper).orElse(false);
    }

    private Void release(Connection c, String tenantId, String sweeper, long fencing, Integer count) {
        Instant now = Sql.dbNow(c, ctx.dialect());
        Jdbc.update(c, "UPDATE RES_SWEEP_LEASE SET lease_until=?, last_finished_at=?, last_count=?"
                + " WHERE tenant_id=? AND sweeper=? AND owner_node=? AND fencing=?", now, now, count, tenantId, sweeper, node, fencing);
        return null;
    }
}
