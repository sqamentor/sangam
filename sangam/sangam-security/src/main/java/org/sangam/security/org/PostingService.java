// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.org;

import org.sangam.core.layer.Jdbc;
import org.sangam.core.security.SangamSecurityException;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.core.security.SecurityPorts;
import org.sangam.security.audit.SecurityAuditService;
import org.sangam.security.audit.SecurityEventType;
import org.sangam.security.dialect.SecurityDialect;
import org.sangam.security.policy.AuthorizationService;
import org.sangam.security.policy.MembershipIndex;
import org.sangam.security.policy.Permission;
import org.sangam.security.policy.ResourceRegistry;
import org.sangam.security.support.Sql;

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Effective-dated assignments of persons to seats (IX.5 rules 1 and 4; AJ-04): postings, transfers, additional
 * charge, leave with substitute, vacancies, and the seat holder at the time of action (K7).
 *
 * <h2>Who acts for a seat at instant t</h2>
 * <ol>
 *   <li>Regular holders whose posting covers t and who are not on leave at t (not "acted for").</li>
 *   <li>Only if there is none (vacant, or holder on leave): leave substitutes and additional-charge holders whose
 *       posting covers t; they act <b>for</b> the seat and audit shows "acted for".</li>
 *   <li>No one: the seat is vacant; routing falls back to the escalation seat (AJ-04 step 6).</li>
 * </ol>
 * Each posting is mirrored as an effective-dated membership {@code user:p → seat:s} in {@code AUTH_MEMBERSHIP} (source
 * {@code posting:<id>}), so seat-based grants follow the posting dates and every change bumps {@code policy_version}.
 */
public final class PostingService implements SecurityPorts.SeatDirectory {

    private final SecurityDialect dialect;
    private final AuthorizationService authz;
    private final SecurityAuditService audit;
    private final SeatService seats;
    private final MembershipIndex memberships;

    public PostingService(SecurityDialect dialect, AuthorizationService authz, SecurityAuditService audit, SeatService seats,
                          MembershipIndex memberships) {
        this.dialect = dialect;
        this.authz = authz;
        this.audit = audit;
        this.seats = seats;
        this.memberships = memberships;
    }

    // ------------------------------------------------------------------ commands

    public OrgModel.Posting post(Connection c, SecurityIdentity actor, String seatId, String personId, OrgModel.PostingKind kind,
                                 Instant from, Instant to, String orderRef, BigDecimal powerCap) {
        authz.require(c, actor, ResourceRegistry.POSTING, Permission.ADMIN, seatId);
        String t = actor.tenantId();
        if (to != null && !to.isAfter(from)) {
            throw new SangamSecurityException.AdminConflict("INVALID_RANGE", "valid_to must be after valid_from");
        }
        seats.requireSeat(c, t, seatId, from);
        requirePerson(c, t, personId);
        if (kind == OrgModel.PostingKind.REGULAR && !seats.seatAt(c, t, seatId, from).orElseThrow().pool()) {
            long overlapping = Jdbc.one(c, "SELECT COUNT(*) FROM SEC_POSTING WHERE tenant_id=? AND seat_id=? AND kind='REGULAR'"
                            + " AND revoked_at IS NULL AND valid_from < ? AND (valid_to IS NULL OR valid_to > ?)",
                    rs -> rs.getLong(1), t, seatId, to == null ? Instant.parse("9999-12-31T00:00:00Z") : to, from).orElse(0L);
            if (overlapping > 0) {
                throw new SangamSecurityException.AdminConflict("SEAT_OCCUPIED",
                        "seat " + seatId + " already has a regular holder in that period (relieve first)");
            }
        }
        String id = Sql.newId();
        Jdbc.update(c, "INSERT INTO SEC_POSTING(tenant_id, posting_id, seat_id, person_id, kind, valid_from, valid_to, order_ref,"
                        + " power_cap, created_by, created_at, revoked_at, expiry_notified) VALUES (?,?,?,?,?,?,?,?,?,?,?,NULL,0)",
                t, id, seatId, personId, kind.name(), from, to, orderRef, Sql.decimal(powerCap), actor.personId(), Sql.dbNow(c, dialect));
        memberships.put(c, t, SecurityIdentity.USER + personId, SecurityIdentity.SEAT + seatId, "posting:" + id, from, to);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("posting_id", id);
        p.put("seat", seatId);
        p.put("person_id", personId);
        p.put("kind", kind.name());
        p.put("valid_from", from);
        p.put("valid_to", to);
        p.put("order_ref", orderRef);
        p.put("power_cap", powerCap);
        SecurityEventType type = switch (kind) {
            case REGULAR -> SecurityEventType.POSTING_EFFECTIVE;
            case ADDITIONAL_CHARGE -> SecurityEventType.ADDITIONAL_CHARGE_GRANTED;
            case LEAVE_SUBSTITUTE -> SecurityEventType.DELEGATION_GRANTED;
        };
        audit.security(c, actor, type, p);
        audit.security(c, actor, SecurityEventType.SEAT_HOLDER_CHANGED, p);
        return new OrgModel.Posting(t, id, seatId, personId, kind, from, to, orderRef, powerCap);
    }

    /** Ends a posting at {@code at} (relieving; open claims of the person on that seat lapse at that time). */
    public void endPosting(Connection c, SecurityIdentity actor, String postingId, Instant at) {
        String t = actor.tenantId();
        OrgModel.Posting p = posting(c, t, postingId).orElseThrow(() ->
                new SangamSecurityException.AdminConflict("UNKNOWN_POSTING", "posting " + postingId + " not found"));
        authz.require(c, actor, ResourceRegistry.POSTING, Permission.ADMIN, p.seatId());
        Instant end = p.validFrom().isAfter(at) ? p.validFrom() : at;
        Jdbc.update(c, "UPDATE SEC_POSTING SET valid_to=? WHERE tenant_id=? AND posting_id=? AND (valid_to IS NULL OR valid_to > ?)",
                end, t, postingId, end);
        memberships.end(c, t, "posting:" + postingId, end);
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("posting_id", postingId);
        e.put("seat", p.seatId());
        e.put("person_id", p.personId());
        e.put("valid_to", end);
        audit.security(c, actor, SecurityEventType.POSTING_ENDED, e);
        audit.security(c, actor, SecurityEventType.SEAT_HOLDER_CHANGED, e);
    }

    /** AJ-04 step 1 preview: the cases the person acted on (SoD still applies at the new seat) and open seat tasks. */
    public OrgModel.TransferPreview previewTransfer(Connection c, String tenant, String personId, String fromSeatId, String toSeatId) {
        List<String> cases = Jdbc.query(c, "SELECT DISTINCT case_id FROM SEC_CASE_ACTOR WHERE tenant_id=? AND person_id=? ORDER BY case_id",
                rs -> rs.getString(1), tenant, personId);
        List<String> tasks = Jdbc.query(c, "SELECT DISTINCT resource_id FROM AUTH_GRANT_SOURCE WHERE tenant_id=? AND principal_id=?"
                + " AND source_id LIKE 'task:%' ORDER BY resource_id", rs -> rs.getString(1), tenant, SecurityIdentity.SEAT + fromSeatId);
        return new OrgModel.TransferPreview(personId, fromSeatId, toSeatId, cases, tasks);
    }

    /** Transfer: relieved from {@code fromSeatId} at {@code relievedAt}, joins {@code toSeatId} at {@code joinsAt}. */
    public OrgModel.Posting transfer(Connection c, SecurityIdentity actor, String personId, String fromSeatId, Instant relievedAt,
                                     String toSeatId, Instant joinsAt, String orderRef) {
        String t = actor.tenantId();
        List<String> current = Jdbc.query(c, "SELECT posting_id FROM SEC_POSTING WHERE tenant_id=? AND person_id=? AND seat_id=?"
                        + " AND kind='REGULAR' AND revoked_at IS NULL AND valid_from <= ? AND (valid_to IS NULL OR valid_to > ?)",
                rs -> rs.getString(1), t, personId, fromSeatId, relievedAt, relievedAt);
        if (current.isEmpty()) {
            throw new SangamSecurityException.AdminConflict("NOT_POSTED", personId + " does not hold " + fromSeatId);
        }
        for (String pid : current) {
            endPosting(c, actor, pid, relievedAt);
        }
        return post(c, actor, toSeatId, personId, OrgModel.PostingKind.REGULAR, joinsAt, null, orderRef, null);
    }

    /** Additional charge of a seat for a date range (AJ-04 step 2). */
    public OrgModel.Posting grantAdditionalCharge(Connection c, SecurityIdentity actor, String seatId, String personId,
                                                  Instant from, Instant to, String orderRef, BigDecimal powerCap) {
        if (to == null) {
            throw new SangamSecurityException.AdminConflict("INVALID_RANGE", "an additional charge needs an end date");
        }
        return post(c, actor, seatId, personId, OrgModel.PostingKind.ADDITIONAL_CHARGE, from, to, orderRef, powerCap);
    }

    /**
     * Records leave and, optionally, a substitute for every seat the person regularly holds in that range (AJ-04 step 5).
     * The requester cannot approve their own leave; the date range is enforced by the substitute postings.
     */
    public OrgModel.Leave recordLeave(Connection c, SecurityIdentity approver, String personId, Instant from, Instant to,
                                      String substitutePersonId, String requestedBy) {
        authz.require(c, approver, ResourceRegistry.POSTING, Permission.ADMIN, ResourceRegistry.ANY_ID);
        String t = approver.tenantId();
        if (approver.personId().equals(requestedBy) || approver.personId().equals(personId)) {
            throw new SangamSecurityException.SodViolation("the requester cannot approve the leave substitution");
        }
        if (to == null || !to.isAfter(from)) {
            throw new SangamSecurityException.AdminConflict("INVALID_RANGE", "leave needs a valid date range");
        }
        String id = Sql.newId();
        Jdbc.update(c, "INSERT INTO SEC_LEAVE(tenant_id, leave_id, person_id, valid_from, valid_to, substitute_person_id,"
                        + " requested_by, approved_by, status, created_at) VALUES (?,?,?,?,?,?,?,?,'APPROVED',?)",
                t, id, personId, from, to, substitutePersonId, requestedBy, approver.personId(), Sql.dbNow(c, dialect));
        memberships.touch(c, t);   // bump policy_version: seat holders change at the leave boundaries (VI.4 rule 5)
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("leave_id", id);
        p.put("person_id", personId);
        p.put("valid_from", from);
        p.put("valid_to", to);
        p.put("substitute_person_id", substitutePersonId);
        audit.security(c, approver, SecurityEventType.LEAVE_RECORDED, p);
        if (substitutePersonId != null) {
            for (String seat : regularSeatsDuring(c, t, personId, from, to)) {
                post(c, approver, seat, substitutePersonId, OrgModel.PostingKind.LEAVE_SUBSTITUTE, from, to, "leave:" + id, null);
            }
        }
        return new OrgModel.Leave(t, id, personId, from, to, substitutePersonId, approver.personId());
    }

    /** A leave row with what a decision needs: who asked, its status (REQUESTED, APPROVED, REJECTED) and the seats. */
    public record LeaveRecord(OrgModel.Leave leave, String requestedBy, String status, List<String> seats, Instant createdAt) {
    }

    public static final String LEAVE_REQUESTED = "REQUESTED";
    public static final String LEAVE_APPROVED = "APPROVED";
    public static final String LEAVE_REJECTED = "REJECTED";

    /**
     * An officer asks for leave with a substitute (AJ-04 step 5, S14). Nothing takes effect: the row waits as
     * {@code REQUESTED} until another person decides it ({@link #decideLeave}); every reader of leave acts on
     * {@code APPROVED} rows only. Asking needs no admin permission, but the requester must regularly hold a seat in
     * that period and cannot be the substitute.
     */
    public OrgModel.Leave requestLeave(Connection c, SecurityIdentity requester, Instant from, Instant to,
                                       String substitutePersonId) {
        String t = requester.tenantId();
        String person = requester.personId();
        if (to == null || !to.isAfter(from)) {
            throw new SangamSecurityException.AdminConflict("INVALID_RANGE", "leave needs a valid date range");
        }
        if (substitutePersonId == null || substitutePersonId.equals(person)) {
            throw new SangamSecurityException.SodViolation("the officer on leave cannot be the substitute");
        }
        if (regularSeatsDuring(c, t, person, from, to).isEmpty()) {
            throw new SangamSecurityException.AdminConflict("NOT_POSTED", person + " holds no seat in that period");
        }
        requirePerson(c, t, substitutePersonId);
        String id = Sql.newId();
        Jdbc.update(c, "INSERT INTO SEC_LEAVE(tenant_id, leave_id, person_id, valid_from, valid_to, substitute_person_id,"
                        + " requested_by, approved_by, status, created_at) VALUES (?,?,?,?,?,?,?,NULL,'" + LEAVE_REQUESTED + "',?)",
                t, id, person, from, to, substitutePersonId, person, Sql.dbNow(c, dialect));
        return new OrgModel.Leave(t, id, person, from, to, substitutePersonId, null);
    }

    /**
     * The four-eyes decision on a requested leave (AJ-04 step 5: "requester cannot approve"). Approval makes the leave
     * effective exactly as {@link #recordLeave} does (policy version, {@code LEAVE_RECORDED}, substitute postings);
     * a rejection changes nothing but the row. Decided once: a second decision is {@code INVALID_TRANSITION}.
     */
    public LeaveRecord decideLeave(Connection c, SecurityIdentity approver, String leaveId, boolean approve) {
        authz.require(c, approver, ResourceRegistry.POSTING, Permission.ADMIN, ResourceRegistry.ANY_ID);
        String t = approver.tenantId();
        LeaveRecord r = leave(c, t, leaveId).orElseThrow(() ->
                new SangamSecurityException.AdminConflict("UNKNOWN_LEAVE", "leave request " + leaveId + " not found"));
        if (!LEAVE_REQUESTED.equals(r.status())) {
            throw new SangamSecurityException.AdminConflict("INVALID_TRANSITION",
                    "the leave request was already " + r.status().toLowerCase(java.util.Locale.ROOT));
        }
        OrgModel.Leave l = r.leave();
        if (approver.personId().equals(r.requestedBy()) || approver.personId().equals(l.personId())) {
            throw new SangamSecurityException.SodViolation("the requester cannot approve the leave substitution");
        }
        String status = approve ? LEAVE_APPROVED : LEAVE_REJECTED;
        int changed = Jdbc.update(c, "UPDATE SEC_LEAVE SET status=?, approved_by=? WHERE tenant_id=? AND leave_id=? AND status='"
                + LEAVE_REQUESTED + "'", status, approver.personId(), t, leaveId);
        if (changed != 1) {   // decided by another node in the meantime
            throw new SangamSecurityException.AdminConflict("INVALID_TRANSITION", "the leave request was already decided");
        }
        if (approve) {
            memberships.touch(c, t);   // as recordLeave: seat holders change at the leave boundaries (VI.4 rule 5)
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("leave_id", leaveId);
            p.put("person_id", l.personId());
            p.put("valid_from", l.validFrom());
            p.put("valid_to", l.validTo());
            p.put("substitute_person_id", l.substitutePersonId());
            p.put("requested_by", r.requestedBy());
            audit.security(c, approver, SecurityEventType.LEAVE_RECORDED, p);
            if (l.substitutePersonId() != null) {
                for (String seat : r.seats()) {
                    post(c, approver, seat, l.substitutePersonId(), OrgModel.PostingKind.LEAVE_SUBSTITUTE, l.validFrom(),
                            l.validTo(), "leave:" + leaveId, null);
                }
            }
        }
        return new LeaveRecord(new OrgModel.Leave(t, leaveId, l.personId(), l.validFrom(), l.validTo(), l.substitutePersonId(),
                approver.personId()), r.requestedBy(), status, r.seats(), r.createdAt());
    }

    /** A leave row by id, with the seats the person regularly holds in its period. */
    public Optional<LeaveRecord> leave(Connection c, String tenant, String leaveId) {
        return Jdbc.one(c, "SELECT person_id, valid_from, valid_to, substitute_person_id, requested_by, approved_by, status,"
                        + " created_at FROM SEC_LEAVE WHERE tenant_id=? AND leave_id=?",
                rs -> new Object[]{new OrgModel.Leave(tenant, leaveId, rs.getString(1), Jdbc.instant(rs, "valid_from"),
                        Jdbc.instant(rs, "valid_to"), rs.getString(4), rs.getString(6)), rs.getString(5), rs.getString(7),
                        Jdbc.instant(rs, "created_at")}, tenant, leaveId)
                .map(row -> {
                    OrgModel.Leave l = (OrgModel.Leave) row[0];
                    return new LeaveRecord(l, (String) row[1], (String) row[2],
                            regularSeatsDuring(c, tenant, l.personId(), l.validFrom(), l.validTo()), (Instant) row[3]);
                });
    }

    /**
     * The person whose regular posting to the seat is in force at {@code at}, on leave or not: the officer a
     * substitute acts for. Empty for a vacant seat.
     */
    public Optional<String> regularHolderAt(Connection c, String tenant, String seatId, Instant at) {
        return activePostings(c, tenant, "seat_id", seatId, at).stream()
                .filter(p -> p.kind() == OrgModel.PostingKind.REGULAR).map(OrgModel.Posting::personId).findFirst();
    }

    /**
     * Sweeper: emits {@code DELEGATION_EXPIRED} once for every acting posting that ended (AJ-04 step 4). Open forms of
     * the former holder fail the K3/K7 re-check at submit (CX-1); the task stays with the seat.
     */
    public int expireDue(Connection c, String tenant) {
        Instant now = Sql.dbNow(c, dialect);
        List<String[]> due = Jdbc.query(c, "SELECT posting_id, seat_id, person_id, kind FROM SEC_POSTING WHERE tenant_id=?"
                        + " AND kind <> 'REGULAR' AND valid_to IS NOT NULL AND valid_to <= ? AND expiry_notified = 0",
                rs -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)}, tenant, now);
        for (String[] d : due) {
            Jdbc.update(c, "UPDATE SEC_POSTING SET expiry_notified=1 WHERE tenant_id=? AND posting_id=?", tenant, d[0]);
            audit.security(c, SecurityIdentity.system(tenant, null), SecurityEventType.DELEGATION_EXPIRED,
                    Map.of("posting_id", d[0], "seat", d[1], "person_id", d[2], "kind", d[3]));
        }
        return due.size();
    }

    // ------------------------------------------------------------------ queries (SeatDirectory)

    @Override
    public List<SecurityPorts.SeatHolder> holdersAt(Connection c, String tenant, String seatId, Instant at) {
        List<OrgModel.Posting> active = activePostings(c, tenant, "seat_id", seatId, at);
        List<SecurityPorts.SeatHolder> regular = new ArrayList<>();
        List<SecurityPorts.SeatHolder> acting = new ArrayList<>();
        for (OrgModel.Posting p : active) {
            if (p.kind() == OrgModel.PostingKind.REGULAR) {
                if (!onLeave(c, tenant, p.personId(), at)) {
                    regular.add(holder(p));
                }
            } else if (!onLeave(c, tenant, p.personId(), at)) {
                acting.add(holder(p));
            }
        }
        return regular.isEmpty() ? acting : regular;
    }

    @Override
    public List<SecurityPorts.SeatHolder> seatsOf(Connection c, String tenant, String personId, Instant at) {
        List<SecurityPorts.SeatHolder> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (OrgModel.Posting p : activePostings(c, tenant, "person_id", personId, at)) {
            if (!seen.add(p.seatId())) {
                continue;
            }
            for (SecurityPorts.SeatHolder h : holdersAt(c, tenant, p.seatId(), at)) {
                if (h.personId().equals(personId)) {
                    out.add(h);
                }
            }
        }
        return out;
    }

    @Override
    public Optional<String> escalationSeat(Connection c, String tenant, String seatId, Instant at) {
        Optional<OrgModel.Seat> seat = seats.seatAt(c, tenant, seatId, at);
        if (seat.isEmpty()) {
            return Optional.empty();
        }
        if (seat.get().escalationSeatId() != null) {
            return Optional.of(seat.get().escalationSeatId());
        }
        if (seat.get().unitId() == null) {
            return Optional.empty();
        }
        // head seat of the seat's unit, else of the nearest ancestor unit with a head
        String unit = seat.get().unitId();
        for (int guard = 0; unit != null && guard < 64; guard++) {
            String u = unit;
            Optional<String> head = Jdbc.one(c, "SELECT head_seat_id FROM SEC_ORG_UNIT WHERE tenant_id=? AND unit_id=?"
                    + " AND head_seat_id IS NOT NULL", rs -> rs.getString(1), tenant, u);
            if (head.isPresent() && !head.get().equals(seatId)) {
                return head;
            }
            unit = Jdbc.one(c, "SELECT parent_id FROM SEC_ORG_UNIT_PARENT WHERE tenant_id=? AND unit_id=? AND parent_id IS NOT NULL AND "
                    + Sql.validAt(null), rs -> rs.getString(1), tenant, u, at, at).orElse(null);
        }
        return Optional.empty();
    }

    /** Vacant seats at {@code at}: active seats with no one able to act (O01 vacancy tiles, AJ-04 step 6). */
    public List<String> vacantSeats(Connection c, String tenant, Instant at) {
        List<String> out = new ArrayList<>();
        for (String s : Jdbc.query(c, "SELECT seat_id FROM SEC_SEAT WHERE tenant_id=? AND status='ACTIVE' ORDER BY seat_id",
                rs -> rs.getString(1), tenant)) {
            if (holdersAt(c, tenant, s, at).isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    public boolean canActFor(Connection c, String tenant, String personId, String seatId, Instant at) {
        return holdersAt(c, tenant, seatId, at).stream().anyMatch(h -> h.personId().equals(personId));
    }

    /** K7: the person must be able to act for the seat at the time of action. */
    public SecurityPorts.SeatHolder requireHolder(Connection c, String tenant, String personId, String seatId, Instant at) {
        return holdersAt(c, tenant, seatId, at).stream().filter(h -> h.personId().equals(personId)).findFirst()
                .orElseThrow(() -> new SangamSecurityException.AccessDenied("you no longer hold authority for seat " + seatId, 0));
    }

    public Optional<OrgModel.Posting> posting(Connection c, String tenant, String postingId) {
        return Jdbc.one(c, "SELECT posting_id, seat_id, person_id, kind, valid_from, valid_to, order_ref, power_cap FROM SEC_POSTING"
                + " WHERE tenant_id=? AND posting_id=?", rs -> map(tenant, rs), tenant, postingId);
    }

    // ------------------------------------------------------------------ internals

    private List<OrgModel.Posting> activePostings(Connection c, String tenant, String column, String value, Instant at) {
        return Jdbc.query(c, "SELECT posting_id, seat_id, person_id, kind, valid_from, valid_to, order_ref, power_cap FROM SEC_POSTING"
                        + " WHERE tenant_id=? AND " + column + "=? AND revoked_at IS NULL AND " + Sql.validAt(null)
                        + " ORDER BY valid_from, posting_id",
                rs -> map(tenant, rs), tenant, value, at, at);
    }

    private static OrgModel.Posting map(String tenant, java.sql.ResultSet rs) throws java.sql.SQLException {
        return new OrgModel.Posting(tenant, rs.getString(1), rs.getString(2), rs.getString(3),
                OrgModel.PostingKind.valueOf(rs.getString(4)), Jdbc.instant(rs, "valid_from"), Jdbc.instant(rs, "valid_to"),
                rs.getString(7), Sql.decimal(rs.getString(8)));
    }

    private boolean onLeave(Connection c, String tenant, String personId, Instant at) {
        return Jdbc.one(c, "SELECT COUNT(*) FROM SEC_LEAVE WHERE tenant_id=? AND person_id=? AND status='APPROVED' AND "
                + Sql.validAt(null), rs -> rs.getLong(1), tenant, personId, at, at).orElse(0L) > 0;
    }

    private List<String> regularSeatsDuring(Connection c, String tenant, String personId, Instant from, Instant to) {
        return Jdbc.query(c, "SELECT DISTINCT seat_id FROM SEC_POSTING WHERE tenant_id=? AND person_id=? AND kind='REGULAR'"
                        + " AND revoked_at IS NULL AND valid_from < ? AND (valid_to IS NULL OR valid_to > ?) ORDER BY seat_id",
                rs -> rs.getString(1), tenant, personId, to, from);
    }

    private void requirePerson(Connection c, String tenant, String personId) {
        long n = Jdbc.one(c, "SELECT COUNT(*) FROM SEC_PERSON WHERE tenant_id=? AND person_id=? AND status='ACTIVE'",
                rs -> rs.getLong(1), tenant, personId).orElse(0L);
        if (n == 0) {
            throw new SangamSecurityException.AdminConflict("UNKNOWN_PERSON", "person " + personId + " is not active");
        }
    }

    private static SecurityPorts.SeatHolder holder(OrgModel.Posting p) {
        return new SecurityPorts.SeatHolder(p.seatId(), p.personId(), p.postingId(), p.kind().name(), p.kind().acting(),
                p.validFrom(), p.validTo(), p.powerCap());
    }
}
