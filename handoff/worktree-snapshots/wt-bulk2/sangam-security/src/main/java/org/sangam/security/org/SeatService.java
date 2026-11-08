// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.org;

import org.sangam.core.layer.Jdbc;
import org.sangam.core.security.SangamSecurityException;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.security.audit.SecurityAuditService;
import org.sangam.security.audit.SecurityEventType;
import org.sangam.security.dialect.SecurityDialect;
import org.sangam.security.policy.AuthorizationService;
import org.sangam.security.policy.Permission;
import org.sangam.security.policy.ResourceRegistry;
import org.sangam.security.support.Sql;

import java.sql.Connection;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Seats (posts) and their effective-dated placement in the hierarchy (IX.5 rule 1, AJ-02). A seat is a designation in
 * an office; tasks are assigned to seats, never to fixed people.
 */
public final class SeatService {

    /** Port: does the seat still have open work or references (AJ-02 step 4)? */
    @FunctionalInterface
    public interface OpenWorkProbe {
        boolean hasOpenWork(Connection c, String tenant, String seatId);
    }

    /** Default probe: an open seat-task grant (written by the seat task listener) means an open task. */
    public static final OpenWorkProbe SEAT_TASK_GRANTS = (c, tenant, seatId) -> Jdbc.one(c,
            "SELECT COUNT(*) FROM AUTH_GRANT_SOURCE WHERE tenant_id=? AND principal_id=? AND source_id LIKE 'task:%'",
            rs -> rs.getLong(1), tenant, SecurityIdentity.SEAT + seatId).orElse(0L) > 0;

    private final SecurityDialect dialect;
    private final AuthorizationService authz;
    private final SecurityAuditService audit;
    private final OrgUnitService units;
    private final OpenWorkProbe openWork;

    public SeatService(SecurityDialect dialect, AuthorizationService authz, SecurityAuditService audit, OrgUnitService units,
                       OpenWorkProbe openWork) {
        this.dialect = dialect;
        this.authz = authz;
        this.audit = audit;
        this.units = units;
        this.openWork = openWork == null ? SEAT_TASK_GRANTS : openWork;
    }

    public OrgModel.Seat createSeat(Connection c, SecurityIdentity actor, String seatId, String designation, String unitId,
                                    int rankNo, boolean pool, String escalationSeatId, Instant validFrom) {
        authz.require(c, actor, ResourceRegistry.SEAT, Permission.ADMIN, ResourceRegistry.ANY_ID);
        String t = actor.tenantId();
        Instant from = validFrom == null ? Sql.dbNow(c, dialect) : validFrom;
        units.unitAt(c, t, unitId, from).orElseThrow(() ->
                new SangamSecurityException.AdminConflict("UNKNOWN_UNIT", "unit " + unitId + " not found"));
        // seat ids are never reused: the key includes abolished seats
        if (!Sql.insertIfAbsent(c, dialect, "INSERT INTO SEC_SEAT(tenant_id, seat_id, designation, rank_no, pool_flag,"
                        + " escalation_seat_id, status, created_at, abolished_at) VALUES (?,?,?,?,?,?,'ACTIVE',?,NULL)",
                t, seatId, designation, rankNo, pool ? 1 : 0, escalationSeatId, Sql.dbNow(c, dialect))) {
            throw new SangamSecurityException.AdminConflict("SEAT_EXISTS", "seat id " + seatId + " exists or was used before");
        }
        Jdbc.update(c, "INSERT INTO SEC_SEAT_PLACEMENT(tenant_id, seat_id, unit_id, valid_from, valid_to) VALUES (?,?,?,?,NULL)",
                t, seatId, unitId, from);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("seat_id", seatId);
        p.put("designation", designation);
        p.put("unit_id", unitId);
        p.put("pool", pool);
        audit.security(c, actor, SecurityEventType.SEAT_CREATED, p);
        return seatAt(c, t, seatId, from).orElseThrow();
    }

    /** Moves a seat to another unit from {@code effectiveFrom}; open tasks stay with the seat (AJ-02 step 3). */
    public void moveSeat(Connection c, SecurityIdentity actor, String seatId, String newUnitId, Instant effectiveFrom) {
        authz.require(c, actor, ResourceRegistry.SEAT, Permission.ADMIN, seatId);
        String t = actor.tenantId();
        Jdbc.update(c, "UPDATE SEC_SEAT_PLACEMENT SET valid_to=? WHERE tenant_id=? AND seat_id=? AND valid_from < ?"
                + " AND (valid_to IS NULL OR valid_to > ?)", effectiveFrom, t, seatId, effectiveFrom, effectiveFrom);
        Jdbc.update(c, "DELETE FROM SEC_SEAT_PLACEMENT WHERE tenant_id=? AND seat_id=? AND valid_from >= ?", t, seatId, effectiveFrom);
        Jdbc.update(c, "INSERT INTO SEC_SEAT_PLACEMENT(tenant_id, seat_id, unit_id, valid_from, valid_to) VALUES (?,?,?,?,NULL)",
                t, seatId, newUnitId, effectiveFrom);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("seat_id", seatId);
        p.put("unit_id", newUnitId);
        p.put("effective_from", effectiveFrom);
        audit.security(c, actor, SecurityEventType.SEAT_MOVED, p);
    }

    /** Abolishes a seat; blocked while it has open work (AJ-02 step 4). History is kept. */
    public void abolishSeat(Connection c, SecurityIdentity actor, String seatId, Instant at) {
        authz.require(c, actor, ResourceRegistry.SEAT, Permission.ADMIN, seatId);
        String t = actor.tenantId();
        if (openWork.hasOpenWork(c, t, seatId)) {
            throw new SangamSecurityException.AdminConflict("SEAT_HAS_OPEN_WORK", "seat " + seatId + " has open tasks");
        }
        long holders = Jdbc.one(c, "SELECT COUNT(*) FROM SEC_POSTING WHERE tenant_id=? AND seat_id=? AND revoked_at IS NULL AND "
                + "(valid_to IS NULL OR valid_to > ?)", rs -> rs.getLong(1), t, seatId, at).orElse(0L);
        if (holders > 0) {
            throw new SangamSecurityException.AdminConflict("SEAT_HAS_HOLDERS", "end the postings of seat " + seatId + " first");
        }
        Jdbc.update(c, "UPDATE SEC_SEAT SET status='ABOLISHED', abolished_at=? WHERE tenant_id=? AND seat_id=?", at, t, seatId);
        Jdbc.update(c, "UPDATE SEC_SEAT_PLACEMENT SET valid_to=? WHERE tenant_id=? AND seat_id=? AND valid_to IS NULL", at, t, seatId);
        audit.security(c, actor, SecurityEventType.SEAT_ABOLISHED, Map.of("seat_id", seatId, "at", at));
    }

    public Optional<OrgModel.Seat> seatAt(Connection c, String tenant, String seatId, Instant at) {
        return Jdbc.one(c, "SELECT s.seat_id, s.designation, s.rank_no, s.pool_flag, s.escalation_seat_id, s.status,"
                        + " (SELECT p.unit_id FROM SEC_SEAT_PLACEMENT p WHERE p.tenant_id=s.tenant_id AND p.seat_id=s.seat_id AND "
                        + Sql.validAt("p") + ") AS unit_id FROM SEC_SEAT s WHERE s.tenant_id=? AND s.seat_id=?",
                rs -> new OrgModel.Seat(tenant, rs.getString(1), rs.getString(2), rs.getInt(3), rs.getInt(4) == 1,
                        rs.getString(5), rs.getString(7), rs.getString(6)),
                at, at, tenant, seatId);
    }

    public OrgModel.Seat requireSeat(Connection c, String tenant, String seatId, Instant at) {
        return seatAt(c, tenant, seatId, at).filter(s -> "ACTIVE".equals(s.status())).orElseThrow(() ->
                new SangamSecurityException.AdminConflict("UNKNOWN_SEAT", "seat " + seatId + " is not active"));
    }

    /** Seats of a designation in a unit at {@code at} (routing "put up to" the designation in an office). */
    public List<String> seatsInUnit(Connection c, String tenant, String unitId, String designation, Instant at) {
        String base = "SELECT s.seat_id FROM SEC_SEAT s JOIN SEC_SEAT_PLACEMENT p ON p.tenant_id=s.tenant_id AND p.seat_id=s.seat_id"
                + " WHERE s.tenant_id=? AND p.unit_id=? AND s.status='ACTIVE' AND " + Sql.validAt("p");
        String order = " ORDER BY s.rank_no DESC, s.seat_id";
        if (designation == null) {
            return Jdbc.query(c, base + order, rs -> rs.getString(1), tenant, unitId, at, at);
        }
        return Jdbc.query(c, base + " AND s.designation=?" + order, rs -> rs.getString(1), tenant, unitId, at, at, designation);
    }

    /**
     * Routing by hierarchy level (IX.5 rule 2): the seat of {@code designation} in the unit of {@code levelType} above
     * {@code fromUnitId} at {@code at}.
     */
    public Optional<String> seatAtLevel(Connection c, String tenant, String fromUnitId, String levelType, String designation,
                                        Instant at) {
        return units.ancestorAtLevel(c, tenant, fromUnitId, levelType, at)
                .flatMap(u -> seatsInUnit(c, tenant, u, designation, at).stream().findFirst());
    }

    /** True when seat {@code higher} outranks {@code lower}: higher rank, or a seat of an ancestor unit (AJ-08 step 2). */
    public boolean isSenior(Connection c, String tenant, String higher, String lower, Instant at) {
        OrgModel.Seat h = seatAt(c, tenant, higher, at).orElse(null);
        OrgModel.Seat l = seatAt(c, tenant, lower, at).orElse(null);
        if (h == null || l == null || h.seatId().equals(l.seatId())) {
            return false;
        }
        if (h.unitId() != null && l.unitId() != null && !h.unitId().equals(l.unitId())
                && units.isAncestor(c, tenant, h.unitId(), l.unitId(), at)) {
            return true;
        }
        return h.unitId() != null && h.unitId().equals(l.unitId()) && h.rankNo() > l.rankNo();
    }
}
