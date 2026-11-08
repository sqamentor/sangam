// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.relay;

import java.time.Duration;

/**
 * Admission control from the outbox capacity level (SANGAM.md VI.2.4, R40). Called by REST commands (WS-REST), the
 * inbox receive path and the event gateway before they start a command.
 *
 * <table>
 *   <caption>Admission per level</caption>
 *   <tr><th>Level</th><th>Rejected with 503 BACKPRESSURE</th></tr>
 *   <tr><td>NORMAL, ALERT</td><td>nothing</td></tr>
 *   <tr><td>BACKPRESSURE (≥ 80%)</td><td>{@link Category#START}, {@link Category#INBOUND_EVENT}</td></tr>
 *   <tr><td>CRITICAL (≥ 95%)</td><td>everything except {@link Category#COMPLETION} and {@link Category#CANCELLATION}</td></tr>
 * </table>
 * A {@code 503 BACKPRESSURE} is transient: the idempotency layer does not store it (X.2.2.4 step 3), so the client's
 * retry executes once capacity is back.
 */
public final class BackpressureGate {

    /** Kind of command being admitted. */
    public enum Category {
        /** New process/case start, deployment. */
        START,
        /** Inbound event or message from outside (inbox receive, webhook, broker). */
        INBOUND_EVENT,
        /** Continuation of a running instance (claim, variable update, message to a running instance). */
        CONTINUATION,
        /** Task or external-work completion. */
        COMPLETION,
        /** Cancel / delete. */
        CANCELLATION
    }

    /** {@code 503 BACKPRESSURE} with {@code Retry-After}. */
    public static final class BackpressureException extends RuntimeException {
        private final Duration retryAfter;
        private final OutboxCapacityMonitor.Level level;

        public BackpressureException(OutboxCapacityMonitor.Level level, Duration retryAfter) {
            super("503 BACKPRESSURE: outbox capacity level " + level);
            this.level = level;
            this.retryAfter = retryAfter;
        }

        public int httpStatus() {
            return 503;
        }

        public String code() {
            return "BACKPRESSURE";
        }

        public Duration retryAfter() {
            return retryAfter;
        }

        public OutboxCapacityMonitor.Level level() {
            return level;
        }
    }

    private final OutboxCapacityMonitor monitor;

    public BackpressureGate(OutboxCapacityMonitor monitor) {
        this.monitor = monitor;
    }

    /** A gate that admits everything (development, or no budget configured). */
    public static BackpressureGate open() {
        return new BackpressureGate(null);
    }

    public boolean admits(Category category) {
        if (monitor == null) {
            return true;
        }
        return switch (monitor.last().level()) {
            case NORMAL, ALERT -> true;
            case BACKPRESSURE -> category != Category.START && category != Category.INBOUND_EVENT;
            case CRITICAL -> category == Category.COMPLETION || category == Category.CANCELLATION;
        };
    }

    /** Throws {@link BackpressureException} when the category is not admitted. */
    public void check(Category category) {
        if (!admits(category)) {
            OutboxCapacityMonitor.Sample s = monitor.last();
            long retry = s.drainSeconds() == null ? 30 : Math.max(1, Math.min(300, Math.round(s.drainSeconds() / 10)));
            throw new BackpressureException(s.level(), Duration.ofSeconds(retry));
        }
    }
}
