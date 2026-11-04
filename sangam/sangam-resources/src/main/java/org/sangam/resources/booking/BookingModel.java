// SPDX-License-Identifier: Apache-2.0
package org.sangam.resources.booking;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shapes of the booking, allotment and waitlist services (N01). */
public final class BookingModel {

    private BookingModel() {
    }

    /**
     * Booking life cycle: HELD → (PENDING_APPROVAL →) CONFIRMED → CHECKED_IN → CHECKED_OUT, with exits CANCELLED,
     * NO_SHOW, BUMPED, EXPIRED (hold lapsed) and REJECTED (approval refused). Only HELD, PENDING_APPROVAL, CONFIRMED and
     * CHECKED_IN own claim rows.
     */
    public enum State {
        HELD, PENDING_APPROVAL, CONFIRMED, CHECKED_IN, CHECKED_OUT, CANCELLED, NO_SHOW, BUMPED, EXPIRED, REJECTED;

        public boolean active() {
            return this == HELD || this == PENDING_APPROVAL || this == CONFIRMED || this == CHECKED_IN;
        }

        public boolean canMoveTo(State next) {
            return switch (this) {
                case HELD -> EnumSet.of(PENDING_APPROVAL, CONFIRMED, CANCELLED, EXPIRED, BUMPED).contains(next);
                case PENDING_APPROVAL -> EnumSet.of(CONFIRMED, REJECTED, CANCELLED, BUMPED, EXPIRED).contains(next);
                case CONFIRMED -> EnumSet.of(CHECKED_IN, CANCELLED, NO_SHOW, BUMPED).contains(next);
                case CHECKED_IN -> EnumSet.of(CHECKED_OUT).contains(next);
                default -> false;
            };
        }
    }

    public record Booking(String tenantId, String bookingId, String bookingRef, String resourceId, String typeId,
                          State state, String requesterId, String beneficiaryId, String partyRef, String purpose,
                          String entitlementClass, int priorityLevel, int quantity, Instant startsAt, Instant endsAt,
                          Instant holdExpiresAt, String tariffCode, BigDecimal chargeAmount, BigDecimal refundAmount,
                          String currency, String chargeRef, String businessKey, String processRef, String recurrenceId,
                          String waitlistId, String bumpedBy, String stateReason, Map<String, Object> attributes,
                          long version, Instant createdAt, Instant updatedAt) {
    }

    /**
     * A hold request.
     *
     * @param priorityLevel   lower is more important; null = from the entitlement decision (default 100)
     * @param bump            try to bump lower-priority bookings when the slot is taken (type must allow it; an
     *                        {@code authorityRef} and {@code reason} are mandatory and audited)
     * @param waitlistIfFull  join the waitlist instead of failing when the slot is taken
     * @param holdMinutes     override of the type's hold expiry
     */
    public record HoldRequest(String resourceId, Instant startsAt, Instant endsAt, Integer quantity, String requesterId,
                              String beneficiaryId, String partyRef, String purpose, String entitlementClass,
                              Integer priorityLevel, Map<String, Object> attributes, String businessKey, boolean bump,
                              String authorityRef, String reason, boolean waitlistIfFull, Integer holdMinutes) {
    }

    /** Result of a hold: either a booking or, when full and requested, a waitlist entry. */
    public record HoldResult(Booking booking, WaitlistEntry waitlisted, List<String> bumpedBookingIds) {
        public boolean held() {
            return booking != null;
        }
    }

    /** Recurrence of a series of bookings (weekly hall booking, daily session). */
    public record Recurrence(Frequency frequency, int interval, Set<DayOfWeek> byDays, Integer count, LocalDate until,
                             boolean allOrNothing) {
        public enum Frequency { DAILY, WEEKLY, MONTHLY }
    }

    public record SeriesResult(String recurrenceId, List<Booking> held, List<Instant> unavailableStarts) {
    }

    /** Free capacity of one slot. */
    public record SlotAvailability(String resourceId, Instant startsAt, Instant endsAt, int units, int free) {
    }

    /** A candidate resource for a search over a type. */
    public record Candidate(String resourceId, String code, String name, int capacity, int free) {
    }

    /** Outcome of the entitlement rule (DMN or type defaults). */
    public record Entitlement(boolean allowed, String entitlementClass, int priorityLevel, Integer maxDurationMinutes,
                              Integer maxAdvanceDays, String tariffCode, boolean requiresApproval, String reason,
                              String decisionHash) {
    }

    /** Outcome of the cancellation-refund rule. */
    public record RefundDecision(BigDecimal refundPercent, BigDecimal fee, String rule, String decisionHash) {
    }

    // ------------------------------------------------------------------ waitlist

    public enum WaitState { WAITING, OFFERED, PROMOTED, EXPIRED, WITHDRAWN }

    public record WaitlistEntry(String tenantId, String entryId, String scopeKey, String typeId, String resourceId,
                                String requesterId, String beneficiaryId, String category, int priorityLevel,
                                long priorityScore, Instant seniorityAt, Instant wantedFrom, Instant wantedTo, int quantity,
                                WaitState state, Instant offerExpiresAt, String bookingId, String allotmentId,
                                String drawId, Integer drawPosition, Map<String, Object> attributes, long version,
                                Instant createdAt) {
    }

    public record WaitlistRequest(String typeId, String resourceId, String requesterId, String beneficiaryId,
                                  String category, Integer priorityLevel, Instant seniorityAt, Instant wantedFrom,
                                  Instant wantedTo, Integer quantity, Map<String, Object> attributes) {
    }

    /** An audited draw (lottery): published seed, entrants hash and the full resulting order. */
    public record Draw(String drawId, String scopeKey, String seed, String algorithm, String entrantsHash,
                       int entrantCount, List<String> orderedEntryIds, String drawnBy, Instant drawnAt) {
    }

    // ------------------------------------------------------------------ allotment

    public enum AllotState { ACTIVE, VACATED, CANCELLED }

    public record Allotment(String tenantId, String allotmentId, String allotmentRef, String resourceId, int unitNo,
                            String holderId, String beneficiaryId, String waitlistId, AllotState state, Instant validFrom,
                            Instant validTo, Instant vacateDueAt, Instant vacatedAt, String orderRef, String tariffCode,
                            BigDecimal chargeAmount, String currency, String chargeRef, String stateReason,
                            Map<String, Object> attributes, long version) {
    }

    public record AllotRequest(String resourceId, Integer unitNo, String holderId, String beneficiaryId,
                               Instant validFrom, Instant validTo, String orderRef, String waitlistId,
                               String entitlementClass, Map<String, Object> attributes) {
    }
}
