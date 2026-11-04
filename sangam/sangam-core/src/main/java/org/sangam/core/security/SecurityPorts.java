// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.security;

import org.sangam.core.layer.SqlPredicate;

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Engine-neutral ports of the security layer. Other workstreams (REST, packs, integration, engine) depend on these
 * interfaces only; {@code sangam-security} implements them. Every method takes the caller's transaction
 * ({@link Connection}) and never commits it, as the rest of the product layer does.
 */
public final class SecurityPorts {

    private SecurityPorts() {
    }

    /** VI.4: one policy, two compiled forms. Permission names are those of {@code org.sangam.security.policy.Permission}. */
    public interface Authorization {

        /** SQL predicate over {@code alias} for list, count, search, export and dashboards (VI.4 rules 1–3, A-3, A-13). */
        SqlPredicate visible(Connection c, SecurityIdentity identity, String resourceType, String permission, String alias);

        /** Object check through the same predicate, inside the command transaction, no cache (VI.4 rule 5, K3). */
        boolean allow(Connection c, SecurityIdentity identity, String resourceType, String permission, String resourceId);

        /** {@link #allow} or {@link SangamSecurityException.AccessDenied}, with an {@code AUTH_DENIED} audit event. */
        void require(Connection c, SecurityIdentity identity, String resourceType, String permission, String resourceId);

        long policyVersion(Connection c, String tenantId);
    }

    /** A person who can act for a seat at an instant (IX.5 rules 1 and 4). */
    public record SeatHolder(String seatId, String personId, String postingId, String kind, boolean actingFor,
                      Instant validFrom, Instant validTo, BigDecimal powerCap) {
    }

    /** IX.5 rules 1, 2 and 4; K7. */
    public interface SeatDirectory {

        /** Persons who may act for the seat at {@code at} (regular holder, or the substitute / additional charge). */
        List<SeatHolder> holdersAt(Connection c, String tenantId, String seatId, Instant at);

        /** Seats a person may act for at {@code at}. */
        List<SeatHolder> seatsOf(Connection c, String tenantId, String personId, Instant at);

        /** The escalation seat used when a seat is vacant (AJ-04 step 6). */
        Optional<String> escalationSeat(Connection c, String tenantId, String seatId, Instant at);
    }

    /** Result of a delegation-of-powers check (IX.5 rule 3, K6). */
    public record PowerDecision(boolean allowed, String tableKey, int tableVersion, String dmnHash, Instant effectiveFrom,
                         BigDecimal limit, String matchedRule, String reason) {
    }

    public interface DelegationOfPowers {
        PowerDecision check(Connection c, String tenantId, String tableKey, String seatId, String caseType,
                            BigDecimal amount, Instant actionTime, Map<String, Object> extraInputs);

        /** {@link #check} or {@link SangamSecurityException.PowerLimitExceeded}. */
        PowerDecision require(Connection c, SecurityIdentity actor, String tableKey, String seatId, String caseType,
                              BigDecimal amount, Map<String, Object> extraInputs);
    }

    /** IX.5 rule 5, K5, CX-8. Duties are free strings; the defaults are INITIATOR, CHECKER, APPROVER. */
    public interface SegregationOfDuties {

        /** Throws {@link SangamSecurityException.SodViolation} unless allowed or covered by a granted exception. */
        Optional<String> requireAllowed(Connection c, String tenantId, String caseId, String duty, String personId,
                                        String seatId, String actedForSeatId);

        void recordActor(Connection c, String tenantId, String caseId, String duty, String personId, String seatId,
                         String actedForSeatId, String taskId, String exceptionId);

        /** Persons and seats that must not be offered as candidates for {@code duty} on this case. */
        Set<String> excludedPersons(Connection c, String tenantId, String caseId, String duty);

        Set<String> excludedSeats(Connection c, String tenantId, String caseId, String duty);
    }

    /** VI.4 rule 5, K4. */
    public interface ApprovalBinding {
        String payloadHash(Map<String, ?> materialPayload);

        String recordApproval(Connection c, SecurityIdentity approver, String caseId, String taskId, long taskVersion,
                              Map<String, ?> materialPayload, String approverSeatId, String ruleRef, BigDecimal powerLimit);

        /** Fails with {@link SangamSecurityException.ApprovalInvalidated} unless a valid approval matches. */
        void requireValidApproval(Connection c, String tenantId, String caseId, Map<String, ?> currentMaterialPayload);
    }

    /** Class-B security and authority audit (R42, X.2.3.2, X.4.0.3). */
    public interface SecurityAudit {
        void record(Connection c, SecurityIdentity actor, String eventType, String rootId, Map<String, ?> payload);
    }
}
