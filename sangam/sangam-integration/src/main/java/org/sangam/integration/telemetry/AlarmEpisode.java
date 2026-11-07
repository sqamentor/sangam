// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.telemetry;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One alarm episode ({@code ALARM_EPISODE}, N12): the life of one alarm condition of one rule on one device sensor.
 *
 * <pre>
 *   (breach) → PENDING ──debounce met──→ OPEN ──clear──→ CLEARED ──ack──→ CLOSED
 *                 │                       │  ↑              │  └─re-breach, debounce met─→ OPEN (re-opened)
 *                 └─non-breach→ CLOSED     │  └─────────────┘
 *                                          └─clear, if acknowledged or autoCloseOnClear─→ CLOSED
 * </pre>
 * At most one non-closed episode exists per (tenant, device, sensor, rule): a flapping signal re-opens the same episode
 * instead of creating new ones (de-duplication). Getters are public; mutation is package-private (the handler and the
 * acknowledgement service only, under the row lock).
 */
public final class AlarmEpisode {

    public enum State { PENDING, OPEN, CLEARED, CLOSED }

    private final String episodeId;
    private final String tenantId;
    private final String deviceId;
    private final String sensor;
    private final String ruleId;
    State state;
    String severity;
    int breachCount;
    Instant firstBreachAt;
    Instant openedAt;
    BigDecimal lastValue;
    BigDecimal peakValue;
    Instant lastReadingAt;
    Instant clearedAt;
    String ackedBy;
    Instant ackedAt;
    String ackNote;
    Instant ackDueAt;
    Instant overdueNotifiedAt;
    Instant closedAt;
    String closeReason;
    int reopenCount;
    String lastEvent;
    String lastEventOutcome;
    String lastEventError;
    Instant createdAt;
    Instant updatedAt;
    long version;

    AlarmEpisode(String episodeId, String tenantId, String deviceId, String sensor, String ruleId) {
        this.episodeId = episodeId;
        this.tenantId = tenantId;
        this.deviceId = deviceId;
        this.sensor = sensor;
        this.ruleId = ruleId;
    }

    public String episodeId() {
        return episodeId;
    }

    public String tenantId() {
        return tenantId;
    }

    public String deviceId() {
        return deviceId;
    }

    public String sensor() {
        return sensor;
    }

    public String ruleId() {
        return ruleId;
    }

    public State state() {
        return state;
    }

    public String severity() {
        return severity;
    }

    public int breachCount() {
        return breachCount;
    }

    public Instant firstBreachAt() {
        return firstBreachAt;
    }

    public Instant openedAt() {
        return openedAt;
    }

    public BigDecimal lastValue() {
        return lastValue;
    }

    public BigDecimal peakValue() {
        return peakValue;
    }

    /** Device time of the last reading applied to this episode (out-of-order readings older than this are ignored). */
    public Instant lastReadingAt() {
        return lastReadingAt;
    }

    public Instant clearedAt() {
        return clearedAt;
    }

    public String ackedBy() {
        return ackedBy;
    }

    public Instant ackedAt() {
        return ackedAt;
    }

    public String ackNote() {
        return ackNote;
    }

    /** Acknowledgement deadline (database clock at opening + the rule's ack SLA); null without an SLA. */
    public Instant ackDueAt() {
        return ackDueAt;
    }

    public Instant overdueNotifiedAt() {
        return overdueNotifiedAt;
    }

    public Instant closedAt() {
        return closedAt;
    }

    public String closeReason() {
        return closeReason;
    }

    public int reopenCount() {
        return reopenCount;
    }

    /** Last transition submitted to WS-PORTS ({@code opened}, {@code cleared}, {@code reopened-2}, {@code ack-overdue} ...). */
    public String lastEvent() {
        return lastEvent;
    }

    /** {@code ACCEPTED}, {@code DUPLICATE}, {@code REJECTED} or {@code NO_EVENT}. */
    public String lastEventOutcome() {
        return lastEventOutcome;
    }

    public String lastEventError() {
        return lastEventError;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long version() {
        return version;
    }

    public boolean acknowledged() {
        return ackedAt != null;
    }

    public boolean active() {
        return state != State.CLOSED;
    }

    /** JSON-ready view for APIs and consoles. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("episodeId", episodeId);
        m.put("tenantId", tenantId);
        m.put("deviceId", deviceId);
        m.put("sensor", sensor);
        m.put("ruleId", ruleId);
        m.put("state", state.name());
        m.put("severity", severity);
        m.put("breachCount", breachCount);
        m.put("firstBreachAt", text(firstBreachAt));
        m.put("openedAt", text(openedAt));
        m.put("lastValue", lastValue);
        m.put("peakValue", peakValue);
        m.put("lastReadingAt", text(lastReadingAt));
        m.put("clearedAt", text(clearedAt));
        m.put("ackedBy", ackedBy);
        m.put("ackedAt", text(ackedAt));
        m.put("ackNote", ackNote);
        m.put("ackDueAt", text(ackDueAt));
        m.put("overdueNotifiedAt", text(overdueNotifiedAt));
        m.put("closedAt", text(closedAt));
        m.put("closeReason", closeReason);
        m.put("reopenCount", reopenCount);
        m.put("lastEvent", lastEvent);
        m.put("lastEventOutcome", lastEventOutcome);
        m.put("lastEventError", lastEventError);
        m.put("version", version);
        return m;
    }

    static String text(Instant t) {
        return t == null ? null : t.toString();
    }

    @Override
    public String toString() {
        return "AlarmEpisode[" + episodeId + " " + deviceId + "/" + sensor + " rule " + ruleId + " " + state + "]";
    }
}
