// SPDX-License-Identifier: Apache-2.0
package org.sangam.resources.port;

import org.sangam.core.security.SecurityIdentity;

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Outbound ports of the resources module. Each is wired by the integrator in {@code sangam-server}; the module ships a
 * safe default for each ({@link Defaults}) so it runs stand-alone (development, tests). Every method that takes a
 * {@link Connection} runs inside the caller's transaction and never commits it.
 */
public final class ResourcesPorts {

    private ResourcesPorts() {
    }

    // ------------------------------------------------------------------ billing (N10 hand-off)

    /** One priced line of a quote. */
    public record ChargeLine(String code, String description, BigDecimal quantity, BigDecimal unitPrice, BigDecimal amount) {
    }

    /** A price for a booking or allotment. {@code amount} is zero when nothing is payable. */
    public record Quote(String tariffCode, BigDecimal amount, String currency, List<ChargeLine> lines) {
        public boolean payable() {
            return amount != null && amount.signum() > 0;
        }
    }

    /**
     * Charges and refunds (capability N10, or sangam-ledger for fund-controlled internal recoveries). Calls carry an
     * idempotency key derived from the booking id, so a retried transaction never charges twice (R41).
     */
    public interface BillingPort {

        /** Prices a booking; {@code inputs} carries the tariff-relevant facts (type, class, nights, units, attributes). */
        Quote quote(Connection c, String tenantId, String tariffCode, Map<String, Object> inputs);

        /** Raises the charge (demand / invoice / payment request); returns the charge reference. */
        String charge(Connection c, String tenantId, String idempotencyKey, String payerId, String subjectRef, Quote quote);

        /** Refunds part or all of a charge; returns the refund reference. */
        String refund(Connection c, String tenantId, String idempotencyKey, String chargeRef, BigDecimal amount,
                      String currency, String reason);
    }

    // ------------------------------------------------------------------ notifications

    /** Notices (bump notice, waitlist offer, token called, roster published, swap decided). */
    public interface NotificationPort {
        void notify(Connection c, String tenantId, String recipientId, String templateKey, Map<String, Object> data);
    }

    // ------------------------------------------------------------------ display feed (N07)

    /** One event of a queue display / SMS feed. */
    public record FeedEvent(String servicePointId, String kind, String tokenId, String displayNo, String counterId,
                            String counterName, Instant at, Map<String, Object> data) {
    }

    /** Display boards, kiosks and SMS gateways are outside; they receive this feed by webhook or websocket. */
    public interface DisplayFeedPort {
        void publish(String tenantId, FeedEvent event);
    }

    // ------------------------------------------------------------------ approvals (C02, BPMN)

    /** Starts an approval process for a subject (booking, allotment, roster publication, swap). */
    public interface ApprovalPort {
        /** @return a process reference (process instance id) */
        String startApproval(Connection c, SecurityIdentity actor, String processKey, String subjectType,
                             String subjectId, String businessKey, Map<String, Object> variables);
    }

    // ------------------------------------------------------------------ people (N08)

    /** A leave interval of a person (half-open). */
    public record LeaveInterval(String personId, Instant from, Instant to, String substitutePersonId, String ref) {
    }

    /** Leave integration through sangam-security postings / leave records. */
    public interface LeaveCalendar {
        List<LeaveInterval> leaveOf(Connection c, String tenantId, String personId, Instant from, Instant to);
    }

    /**
     * Duty => seat link: for the duty window the person on duty acts in the duty seat (IX.5 additional charge), so
     * tasks routed to that seat reach whoever is on shift.
     */
    public interface DutySeatLink {
        /** @return the posting reference that grants the acting charge */
        String link(Connection c, SecurityIdentity actor, String dutySeatId, String personId, Instant from, Instant to,
                    String orderRef);

        void unlink(Connection c, SecurityIdentity actor, String postingRef, Instant at);
    }

    /** Time-and-attendance connector for roster reconciliation. */
    public record AttendanceRecord(String personId, Instant in, Instant out, String source) {
    }

    public interface AttendancePort {
        List<AttendanceRecord> attendance(Connection c, String tenantId, String personId, Instant from, Instant to);
    }

    /** Wait-time and utilisation statistics sink (C14 analytics). */
    public interface MetricsPort {
        void record(String tenantId, String metric, double value, Map<String, String> tags);
    }

    /** Optional external class-B audit (sangam-security SecurityAudit); RES_EVENT always records locally. */
    public interface AuditPort {
        void record(Connection c, SecurityIdentity actor, String eventType, String subjectId, Map<String, ?> payload);
    }

    // ------------------------------------------------------------------ defaults

    /** Stand-alone defaults: free of charge, silent notifications, no leave, no seat link. */
    public static final class Defaults {

        private Defaults() {
        }

        public static final BillingPort FREE = new BillingPort() {
            @Override
            public Quote quote(Connection c, String tenantId, String tariffCode, Map<String, Object> inputs) {
                return new Quote(tariffCode, BigDecimal.ZERO, String.valueOf(inputs.getOrDefault("currency", "XXX")), List.of());
            }

            @Override
            public String charge(Connection c, String tenantId, String key, String payerId, String subjectRef, Quote q) {
                return "free:" + key;
            }

            @Override
            public String refund(Connection c, String tenantId, String key, String chargeRef, BigDecimal amount,
                                 String currency, String reason) {
                return "free-refund:" + key;
            }
        };

        public static final NotificationPort SILENT = (c, t, r, k, d) -> { };

        public static final DisplayFeedPort NO_FEED = (t, e) -> { };

        public static final LeaveCalendar NO_LEAVE = (c, t, p, f, to) -> List.of();

        public static final AttendancePort NO_ATTENDANCE = (c, t, p, f, to) -> List.of();

        public static final MetricsPort NO_METRICS = (t, m, v, tags) -> { };

        public static final AuditPort NO_AUDIT = (c, a, e, s, p) -> { };

        public static final DutySeatLink NO_SEAT_LINK = new DutySeatLink() {
            @Override
            public String link(Connection c, SecurityIdentity actor, String seat, String person, Instant f, Instant t, String o) {
                return null;
            }

            @Override
            public void unlink(Connection c, SecurityIdentity actor, String postingRef, Instant at) {
            }
        };

        /** Without an engine, approval requests fail loudly rather than silently auto-approving. */
        public static final ApprovalPort NO_APPROVAL = (c, a, k, st, sid, bk, v) -> {
            throw new IllegalStateException("no ApprovalPort is wired; cannot start approval process " + k);
        };
    }

    /** Convenience holder for optional lookups. */
    public static <T> Optional<T> opt(T value) {
        return Optional.ofNullable(value);
    }
}
