// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.sod;

import org.sangam.core.layer.Jdbc;
import org.sangam.core.security.SangamSecurityException;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.core.security.SecurityPorts;
import org.sangam.security.audit.SecurityAuditService;
import org.sangam.security.audit.SecurityEventType;
import org.sangam.security.dialect.SecurityDialect;
import org.sangam.security.org.PostingService;
import org.sangam.security.org.SeatService;
import org.sangam.security.policy.AuthorizationService;
import org.sangam.security.policy.Permission;
import org.sangam.security.policy.ResourceRegistry;
import org.sangam.security.support.Sql;

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
 * Maker-checker and segregation of duties (IX.5 rule 5, K5, CX-8, AJ-08, T17).
 *
 * <p>Initiator ≠ checker ≠ approver, <b>by person and by seat</b>: an actor conflicts with an earlier actor of a
 * conflicting duty on the same case when it is the same person, or when the acting seat (the seat or the seat acted
 * for under additional charge/leave) is the same seat. Every actor is recorded in {@code SEC_CASE_ACTOR}.
 *
 * <p>A controlled exception (AJ-08) is requested with a reason, and granted by a higher authority who
 * <ul>
 *   <li>holds the {@code APPROVE_SOD_EXCEPTION} permission,</li>
 *   <li>holds an approving seat at the time of the grant that is senior to the seats involved,</li>
 *   <li>is neither the requester nor anyone in the case chain,</li>
 * </ul>
 * and it applies to that case, person and duty only, until its expiry, and is consumed when used.
 */
public final class SegregationOfDutiesService implements SecurityPorts.SegregationOfDuties {

    public static final String INITIATOR = "INITIATOR";
    public static final String CHECKER = "CHECKER";
    public static final String APPROVER = "APPROVER";

    private final SecurityDialect dialect;
    private final AuthorizationService authz;
    private final SecurityAuditService audit;
    private final SeatService seats;
    private final PostingService postings;
    private final Map<String, Set<String>> conflicts;

    /**
     * @param conflicts duty → duties it conflicts with (symmetric closure is applied). Null uses the default
     *                  INITIATOR/CHECKER/APPROVER triangle.
     */
    public SegregationOfDutiesService(SecurityDialect dialect, AuthorizationService authz, SecurityAuditService audit,
                                      SeatService seats, PostingService postings, Map<String, Set<String>> conflicts) {
        this.dialect = dialect;
        this.authz = authz;
        this.audit = audit;
        this.seats = seats;
        this.postings = postings;
        this.conflicts = symmetric(conflicts == null
                ? Map.of(INITIATOR, Set.of(CHECKER, APPROVER), CHECKER, Set.of(APPROVER))
                : conflicts);
    }

    public Set<String> conflictingDuties(String duty) {
        return conflicts.getOrDefault(duty, Set.of());
    }

    @Override
    public Optional<String> requireAllowed(Connection c, String tenant, String caseId, String duty, String personId,
                                           String seatId, String actedForSeatId) {
        return check(c, tenant, caseId, duty, personId, seatId, actedForSeatId, true);
    }

    /**
     * The SoD check. With {@code consume} false (claim time) a granted exception is honoured but not used up; with
     * {@code consume} true (the decisive action) it is marked {@code USED}.
     */
    public Optional<String> check(Connection c, String tenant, String caseId, String duty, String personId,
                                  String seatId, String actedForSeatId, boolean consume) {
        List<String> found = conflictsFor(c, tenant, caseId, duty, personId, seatId, actedForSeatId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Instant now = Sql.dbNow(c, dialect);
        Optional<String> exception = Jdbc.one(c, "SELECT exception_id FROM SEC_SOD_EXCEPTION WHERE tenant_id=? AND case_id=?"
                        + " AND person_id=? AND duty=? AND status='GRANTED' AND expires_at > ?",
                rs -> rs.getString(1), tenant, caseId, personId, duty, now);
        if (exception.isPresent()) {
            if (!consume) {
                return exception;
            }
            Jdbc.update(c, "UPDATE SEC_SOD_EXCEPTION SET status='USED', used_at=? WHERE tenant_id=? AND exception_id=?",
                    now, tenant, exception.get());
            return exception;
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("case_id", caseId);
        p.put("duty", duty);
        p.put("person_id", personId);
        p.put("seat_id", seatId);
        p.put("acted_for_seat_id", actedForSeatId);
        p.put("conflicts", found);
        p.put("outcome", "DENIED");
        p.put("reason", "SOD_VIOLATION");
        audit.denial(c, SecurityIdentity.system(tenant, personId), SecurityEventType.SOD_VIOLATION_BLOCKED, caseId, p);
        throw new SangamSecurityException.SodViolation("segregation of duties: you already acted on this case as "
                + String.join(", ", found) + " and cannot act as " + duty);
    }

    /** {@link #requireAllowed} for the calling identity and its acting seat. */
    public Optional<String> requireAllowed(Connection c, SecurityIdentity actor, String caseId, String duty, String seatId) {
        String actedFor = seatId != null && actor.actsForSeat(seatId) ? seatId : null;
        return requireAllowed(c, actor.tenantId(), caseId, duty, actor.personId(), seatId, actedFor);
    }

    @Override
    public void recordActor(Connection c, String tenant, String caseId, String duty, String personId, String seatId,
                            String actedForSeatId, String taskId, String exceptionId) {
        Sql.insertIfAbsent(c, dialect, "INSERT INTO SEC_CASE_ACTOR(tenant_id, case_id, duty, person_id, seat_id, acted_for_seat_id,"
                        + " task_id, exception_id, acted_at) VALUES (?,?,?,?,?,?,?,?,?)",
                tenant, caseId, duty, personId, seatId == null ? "-" : seatId, actedForSeatId, taskId, exceptionId,
                Sql.dbNow(c, dialect));
    }

    @Override
    public Set<String> excludedPersons(Connection c, String tenant, String caseId, String duty) {
        Set<String> conf = conflictingDuties(duty);
        if (conf.isEmpty()) {
            return Set.of();
        }
        List<Object> params = new ArrayList<>(List.of(tenant, caseId));
        params.addAll(conf);
        return new LinkedHashSet<>(Jdbc.query(c, "SELECT DISTINCT person_id FROM SEC_CASE_ACTOR WHERE tenant_id=? AND case_id=?"
                + " AND duty IN (" + Sql.placeholders(conf.size()) + ")", rs -> rs.getString(1), params.toArray()));
    }

    @Override
    public Set<String> excludedSeats(Connection c, String tenant, String caseId, String duty) {
        Set<String> conf = conflictingDuties(duty);
        if (conf.isEmpty()) {
            return Set.of();
        }
        List<Object> params = new ArrayList<>(List.of(tenant, caseId));
        params.addAll(conf);
        Set<String> out = new LinkedHashSet<>();
        Jdbc.query(c, "SELECT seat_id, acted_for_seat_id FROM SEC_CASE_ACTOR WHERE tenant_id=? AND case_id=? AND duty IN ("
                + Sql.placeholders(conf.size()) + ")", rs -> {
                    if (rs.getString(1) != null && !"-".equals(rs.getString(1))) {
                        out.add(rs.getString(1));
                    }
                    if (rs.getString(2) != null) {
                        out.add(rs.getString(2));
                    }
                    return null;
                }, params.toArray());
        return out;
    }

    // ------------------------------------------------------------------ controlled exceptions (AJ-08)

    public String requestException(Connection c, SecurityIdentity requester, String caseId, String duty, String seatId,
                                   String reason) {
        requireReason(reason);
        String id = Sql.newId();
        Jdbc.update(c, "INSERT INTO SEC_SOD_EXCEPTION(tenant_id, exception_id, case_id, person_id, seat_id, duty, reason,"
                        + " requested_by, status, created_at) VALUES (?,?,?,?,?,?,?,?,'REQUESTED',?)",
                requester.tenantId(), id, caseId, requester.personId(), seatId, duty, reason, requester.personId(),
                Sql.dbNow(c, dialect));
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("exception_id", id);
        p.put("duty", duty);
        p.put("seat_id", seatId);
        p.put("reason", reason);
        audit.record(c, requester, SecurityEventType.SOD_EXCEPTION_REQUESTED, caseId, p);
        return id;
    }

    public void grantException(Connection c, SecurityIdentity approver, String exceptionId, String approverSeatId,
                               Instant expiresAt, String reason) {
        requireReason(reason);
        String t = approver.tenantId();
        authz.require(c, approver, ResourceRegistry.SOD_EXCEPTION, Permission.APPROVE_SOD_EXCEPTION, exceptionId);
        String[] ex = Jdbc.one(c, "SELECT case_id, person_id, seat_id, duty, status FROM SEC_SOD_EXCEPTION WHERE tenant_id=? AND exception_id=?",
                        rs -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)},
                        t, exceptionId)
                .orElseThrow(() -> new SangamSecurityException.AdminConflict("UNKNOWN_EXCEPTION", "exception not found"));
        if (!"REQUESTED".equals(ex[4])) {
            throw new SangamSecurityException.AdminConflict("EXCEPTION_NOT_PENDING", "exception is " + ex[4]);
        }
        Instant now = Sql.dbNow(c, dialect);
        if (expiresAt == null || !expiresAt.isAfter(now)) {
            throw new SangamSecurityException.AdminConflict("INVALID_EXPIRY", "an exception needs a future expiry");
        }
        String caseId = ex[0];
        if (approver.personId().equals(ex[1])) {
            throw new SangamSecurityException.SodViolation("the requester cannot approve their own exception");
        }
        long inChain = Jdbc.one(c, "SELECT COUNT(*) FROM SEC_CASE_ACTOR WHERE tenant_id=? AND case_id=? AND (person_id=? OR seat_id=?"
                        + " OR acted_for_seat_id=?)",
                rs -> rs.getLong(1), t, caseId, approver.personId(), approverSeatId, approverSeatId).orElse(0L);
        if (inChain > 0) {
            throw new SangamSecurityException.SodViolation("the approver is in the case chain");
        }
        postings.requireHolder(c, t, approver.personId(), approverSeatId, now);
        Set<String> involved = new LinkedHashSet<>();
        if (ex[2] != null) {
            involved.add(ex[2]);
        }
        involved.addAll(excludedSeats(c, t, caseId, ex[3]));
        for (String s : involved) {
            if (!seats.isSenior(c, t, approverSeatId, s, now)) {
                throw new SangamSecurityException.SodViolation("approver seat " + approverSeatId + " is not senior to seat " + s);
            }
        }
        Jdbc.update(c, "UPDATE SEC_SOD_EXCEPTION SET status='GRANTED', approved_by=?, approver_seat_id=?, approval_reason=?,"
                        + " expires_at=?, decided_at=? WHERE tenant_id=? AND exception_id=?",
                approver.personId(), approverSeatId, reason, expiresAt, now, t, exceptionId);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("exception_id", exceptionId);
        p.put("for_person_id", ex[1]);
        p.put("duty", ex[3]);
        p.put("seat_id", approverSeatId);
        p.put("reason", reason);
        p.put("expires_at", expiresAt);
        p.put("authority_basis", "SOD_EXCEPTION:" + exceptionId);
        audit.record(c, approver, SecurityEventType.SOD_EXCEPTION_GRANTED, caseId, p);
    }

    public void rejectException(Connection c, SecurityIdentity approver, String exceptionId, String reason) {
        requireReason(reason);
        String t = approver.tenantId();
        authz.require(c, approver, ResourceRegistry.SOD_EXCEPTION, Permission.APPROVE_SOD_EXCEPTION, exceptionId);
        String caseId = Jdbc.one(c, "SELECT case_id FROM SEC_SOD_EXCEPTION WHERE tenant_id=? AND exception_id=? AND status='REQUESTED'",
                rs -> rs.getString(1), t, exceptionId)
                .orElseThrow(() -> new SangamSecurityException.AdminConflict("EXCEPTION_NOT_PENDING", "no pending exception"));
        Jdbc.update(c, "UPDATE SEC_SOD_EXCEPTION SET status='REJECTED', approved_by=?, approval_reason=?, decided_at=?"
                + " WHERE tenant_id=? AND exception_id=?", approver.personId(), reason, Sql.dbNow(c, dialect), t, exceptionId);
        audit.record(c, approver, SecurityEventType.SOD_EXCEPTION_REJECTED, caseId, Map.of("exception_id", exceptionId, "reason", reason));
    }

    // ------------------------------------------------------------------ internals

    private List<String> conflictsFor(Connection c, String tenant, String caseId, String duty, String personId, String seatId,
                                      String actedForSeatId) {
        Set<String> conf = conflictingDuties(duty);
        if (conf.isEmpty()) {
            return List.of();
        }
        Set<String> actorSeats = new LinkedHashSet<>();
        if (seatId != null) {
            actorSeats.add(seatId);
        }
        if (actedForSeatId != null) {
            actorSeats.add(actedForSeatId);
        }
        List<Object> params = new ArrayList<>(List.of(tenant, caseId));
        params.addAll(conf);
        params.add(personId);
        StringBuilder sql = new StringBuilder("SELECT DISTINCT duty FROM SEC_CASE_ACTOR WHERE tenant_id=? AND case_id=? AND duty IN (")
                .append(Sql.placeholders(conf.size())).append(") AND (person_id=?");
        if (!actorSeats.isEmpty()) {
            sql.append(" OR seat_id IN (").append(Sql.placeholders(actorSeats.size())).append(")")
                    .append(" OR acted_for_seat_id IN (").append(Sql.placeholders(actorSeats.size())).append(")");
            params.addAll(actorSeats);
            params.addAll(actorSeats);
        }
        sql.append(") ORDER BY duty");
        return Jdbc.query(c, sql.toString(), rs -> rs.getString(1), params.toArray());
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new SangamSecurityException.AdminConflict("REASON_REQUIRED", "a reason is required");
        }
    }

    private static Map<String, Set<String>> symmetric(Map<String, Set<String>> in) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        in.forEach((k, vs) -> vs.forEach(v -> {
            out.computeIfAbsent(k, x -> new LinkedHashSet<>()).add(v);
            out.computeIfAbsent(v, x -> new LinkedHashSet<>()).add(k);
        }));
        Map<String, Set<String>> frozen = new LinkedHashMap<>();
        out.forEach((k, v) -> frozen.put(k, Set.copyOf(v)));
        return Map.copyOf(frozen);
    }
}
