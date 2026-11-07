// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.telemetry;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;

/**
 * A threshold / alarm rule (N12): a simple comparator with hysteresis, or a DMN decision.
 *
 * <p><b>Comparator rules.</b> {@code limit} (and {@code upperLimit} for the range comparators) define the breach;
 * {@code clearLimit} (and {@code clearUpperLimit}) define when an open alarm clears. Readings between the two are the
 * hysteresis band: they neither count as a breach nor clear the alarm. Without clear limits the breach limits are used
 * (no hysteresis).
 * <table>
 *   <tr><th>comparator</th><th>breach</th><th>clear</th><th>constraint</th></tr>
 *   <tr><td>GT</td><td>v &gt; limit</td><td>v &le; clearLimit</td><td>clearLimit &le; limit</td></tr>
 *   <tr><td>GTE</td><td>v &ge; limit</td><td>v &lt; clearLimit</td><td>clearLimit &le; limit</td></tr>
 *   <tr><td>LT</td><td>v &lt; limit</td><td>v &ge; clearLimit</td><td>clearLimit &ge; limit</td></tr>
 *   <tr><td>LTE</td><td>v &le; limit</td><td>v &gt; clearLimit</td><td>clearLimit &ge; limit</td></tr>
 *   <tr><td>OUTSIDE_RANGE</td><td>v &lt; limit or v &gt; upperLimit</td><td>clearLimit &le; v &le; clearUpperLimit</td>
 *       <td>limit &le; clearLimit &le; clearUpperLimit &le; upperLimit</td></tr>
 *   <tr><td>INSIDE_RANGE</td><td>limit &le; v &le; upperLimit</td><td>v &lt; clearLimit or v &gt; clearUpperLimit</td>
 *       <td>clearLimit &le; limit &le; upperLimit &le; clearUpperLimit</td></tr>
 * </table>
 *
 * <p><b>DMN rules.</b> When {@code dmnDecisionKey} is set the comparator is ignored and the decision is evaluated
 * through Operaton ({@link RuleDecisionEvaluator}); its result column {@code alarm} (boolean) is the breach, and an
 * optional {@code severity} column overrides {@link #severity}. {@code alarm = false} clears; a table implements its
 * own hysteresis with the {@code episodeState} input variable.
 *
 * <p><b>Debounce.</b> An episode opens after {@code debounceCount} consecutive breaching readings and, when
 * {@code debounceDuration} is set, once the breach has lasted that long (both conditions must hold). A non-breaching
 * reading before that resets the pending episode. The duration is evaluated on reading arrival only.
 *
 * @param ruleId             rule id, unique per tenant (1..64)
 * @param tenantId           owning tenant
 * @param sensor             sensor the rule watches
 * @param deviceId           optional device filter (null: every device of the tenant with that sensor)
 * @param channelKey         WS-PORTS inbound channel the alarm events are submitted to
 * @param openedEventKey     ports event key emitted when an episode opens (and re-opens)
 * @param clearedEventKey    ports event key emitted when an episode clears or is closed by an operator while open
 *                           (optional)
 * @param reopenedEventKey   ports event key emitted when a cleared, not yet closed episode breaches again (optional;
 *                           default {@code openedEventKey}). Use a message event here when {@code openedEventKey}
 *                           starts a process, so a re-open correlates to that process instead of starting another
 * @param ackOverdueEventKey ports event key emitted once when the acknowledgement SLA passes (optional)
 * @param ackSla             acknowledgement SLA from opening; null for none
 * @param autoCloseOnClear   close an episode on clear even if nobody acknowledged it
 */
public record ThresholdRule(String ruleId, String tenantId, String sensor, String deviceId, Comparator comparator,
                            BigDecimal limit, BigDecimal upperLimit, BigDecimal clearLimit, BigDecimal clearUpperLimit,
                            int debounceCount, Duration debounceDuration, String severity, String channelKey,
                            String openedEventKey, String clearedEventKey, String reopenedEventKey, String ackOverdueEventKey,
                            String dmnDecisionKey,
                            Duration ackSla, boolean autoCloseOnClear, boolean enabled) {

    public enum Comparator { GT, GTE, LT, LTE, OUTSIDE_RANGE, INSIDE_RANGE }

    /** Result of evaluating one reading. */
    public enum Verdict { BREACH, CLEAR, HOLD }

    public ThresholdRule {
        requireId(ruleId, "ruleId");
        requireId(tenantId, "tenantId");
        requireId(sensor, "sensor");
        requireId(channelKey, "channelKey");
        requireId(openedEventKey, "openedEventKey");
        if (deviceId != null && deviceId.isBlank()) {
            deviceId = null;
        }
        if (debounceCount < 1) {
            throw new IllegalArgumentException("debounceCount must be at least 1");
        }
        if (debounceDuration != null && (debounceDuration.isNegative() || debounceDuration.isZero())) {
            debounceDuration = null;
        }
        if (ackSla != null && (ackSla.isNegative() || ackSla.isZero())) {
            throw new IllegalArgumentException("ackSla must be positive");
        }
        severity = severity == null || severity.isBlank() ? "MAJOR" : severity;
        if (dmnDecisionKey != null && dmnDecisionKey.isBlank()) {
            dmnDecisionKey = null;
        }
        if (dmnDecisionKey == null) {
            Objects.requireNonNull(comparator, "comparator (or dmnDecisionKey)");
            Objects.requireNonNull(limit, "limit");
            boolean range = comparator == Comparator.OUTSIDE_RANGE || comparator == Comparator.INSIDE_RANGE;
            if (range) {
                Objects.requireNonNull(upperLimit, "upperLimit for " + comparator);
                if (limit.compareTo(upperLimit) > 0) {
                    throw new IllegalArgumentException("limit must not exceed upperLimit");
                }
                if (clearUpperLimit == null) {
                    clearUpperLimit = upperLimit;
                }
            } else {
                upperLimit = null;
                clearUpperLimit = null;
            }
            if (clearLimit == null) {
                clearLimit = limit;
            }
            validateHysteresis(comparator, limit, upperLimit, clearLimit, clearUpperLimit);
        }
    }

    /** A comparator rule with defaults: no hysteresis, debounce 1, severity MAJOR, no ack SLA. */
    public static ThresholdRule simple(String ruleId, String tenantId, String sensor, Comparator comparator, BigDecimal limit,
                                       String channelKey, String openedEventKey, String clearedEventKey) {
        return new ThresholdRule(ruleId, tenantId, sensor, null, comparator, limit, null, null, null, 1, null, null, channelKey,
                openedEventKey, clearedEventKey, null, null, null, null, false, true);
    }

    /** Event key for a re-open: {@code reopenedEventKey}, else {@code openedEventKey}. */
    public String reopenEventKey() {
        return reopenedEventKey != null && !reopenedEventKey.isBlank() ? reopenedEventKey : openedEventKey;
    }

    public boolean dmn() {
        return dmnDecisionKey != null;
    }

    /** Whether this rule applies to a reading of {@code deviceId}/{@code sensor}. */
    public boolean matches(String deviceId, String sensor) {
        return enabled && this.sensor.equals(sensor) && (this.deviceId == null || this.deviceId.equals(deviceId));
    }

    /** Comparator evaluation (not for DMN rules). */
    public Verdict evaluate(BigDecimal v) {
        if (dmn()) {
            throw new IllegalStateException("rule " + ruleId + " is a DMN rule");
        }
        return switch (comparator) {
            case GT -> v.compareTo(limit) > 0 ? Verdict.BREACH : v.compareTo(clearLimit) <= 0 ? Verdict.CLEAR : Verdict.HOLD;
            case GTE -> v.compareTo(limit) >= 0 ? Verdict.BREACH : v.compareTo(clearLimit) < 0 ? Verdict.CLEAR : Verdict.HOLD;
            case LT -> v.compareTo(limit) < 0 ? Verdict.BREACH : v.compareTo(clearLimit) >= 0 ? Verdict.CLEAR : Verdict.HOLD;
            case LTE -> v.compareTo(limit) <= 0 ? Verdict.BREACH : v.compareTo(clearLimit) > 0 ? Verdict.CLEAR : Verdict.HOLD;
            case OUTSIDE_RANGE -> v.compareTo(limit) < 0 || v.compareTo(upperLimit) > 0 ? Verdict.BREACH
                    : v.compareTo(clearLimit) >= 0 && v.compareTo(clearUpperLimit) <= 0 ? Verdict.CLEAR : Verdict.HOLD;
            case INSIDE_RANGE -> v.compareTo(limit) >= 0 && v.compareTo(upperLimit) <= 0 ? Verdict.BREACH
                    : v.compareTo(clearLimit) < 0 || v.compareTo(clearUpperLimit) > 0 ? Verdict.CLEAR : Verdict.HOLD;
        };
    }

    /**
     * The "worse" of two values for the episode's peak: the maximum for GT/GTE/INSIDE_RANGE/DMN, the minimum for
     * LT/LTE, the one farther outside the range for OUTSIDE_RANGE.
     */
    public BigDecimal worse(BigDecimal a, BigDecimal b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        if (dmn()) {
            return a.max(b);
        }
        return switch (comparator) {
            case GT, GTE, INSIDE_RANGE -> a.max(b);
            case LT, LTE -> a.min(b);
            case OUTSIDE_RANGE -> deviation(a).compareTo(deviation(b)) >= 0 ? a : b;
        };
    }

    private BigDecimal deviation(BigDecimal v) {
        if (v.compareTo(limit) < 0) {
            return limit.subtract(v);
        }
        if (v.compareTo(upperLimit) > 0) {
            return v.subtract(upperLimit);
        }
        return BigDecimal.ZERO;
    }

    private static void validateHysteresis(Comparator c, BigDecimal limit, BigDecimal upper, BigDecimal clear, BigDecimal clearUpper) {
        boolean ok = switch (c) {
            case GT, GTE -> clear.compareTo(limit) <= 0;
            case LT, LTE -> clear.compareTo(limit) >= 0;
            case OUTSIDE_RANGE -> limit.compareTo(clear) <= 0 && clear.compareTo(clearUpper) <= 0 && clearUpper.compareTo(upper) <= 0;
            case INSIDE_RANGE -> clear.compareTo(limit) <= 0 && upper.compareTo(clearUpper) <= 0;
        };
        if (!ok) {
            throw new IllegalArgumentException("clear limits are on the wrong side of the breach limits for " + c);
        }
    }

    private static void requireId(String v, String name) {
        if (v == null || v.isBlank() || v.length() > 255) {
            throw new IllegalArgumentException(name + " must be 1..255 characters");
        }
        if ((name.equals("ruleId") || name.equals("sensor") || name.equals("tenantId")) && v.length() > 64) {
            throw new IllegalArgumentException(name + " longer than 64 characters");
        }
    }
}
