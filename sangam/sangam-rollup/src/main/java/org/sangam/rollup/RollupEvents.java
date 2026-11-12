// SPDX-License-Identifier: Apache-2.0
package org.sangam.rollup;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.sangam.cell.rollup.RollupEnvelope;

/**
 * What one roll-up event means (SCALE-ARCHITECTURE.md 8.2 and 8.4), with no database access: its validation, its UTC
 * day, its counter deltas and the hierarchy nodes they apply to. The per-event path and the micro-batch path both use
 * it, so they cannot count differently.
 *
 * <p>Two kinds of event, one rule (deltas go to the tenant path and every prefix of it):
 * <ul>
 *   <li>a <b>stream event</b> (process start/end, task complete ...): metric = the type without
 *       {@code sangam.history.}, value 1; optionally {@code <metric>#<processDefinitionKey>}; plus the derived gauge
 *       {@code process.open} (+1 on start, -1 on end);</li>
 *   <li>an <b>explicit metric event</b> ({@value #METRIC_TYPE} whose {@code data} holds {@code metric}): exactly one
 *       delta, {@code data.delta} (an integer, may be negative), on day {@code data.day} ({@code yyyyMMdd}). A lower
 *       roll-up tier ships its counts this way (M-063, 8.4 a); only a caller with the TIER (or ADMIN) role may send
 *       one, because it moves totals by more than one.</li>
 * </ul>
 * A {@value #METRIC_TYPE} event without {@code data.metric} keeps the 8.2 meaning (counted like a stream event).
 */
final class RollupEvents {

    static final String METRIC_TYPE = "sangam.rollup.metric";
    static final String HISTORY_PREFIX = "sangam.history.";
    static final int MAX_METRIC = 96;

    private RollupEvents() {
    }

    record Delta(String metric, long value) {
    }

    /** One event's effect: its day bucket, its deltas, and the hierarchy prefixes (root first) they apply to. */
    record Effect(int day, List<Delta> deltas, List<String> prefixes) {
    }

    static boolean explicit(RollupEnvelope e) {
        return METRIC_TYPE.equals(e.type()) && e.data() != null && e.data().containsKey("metric");
    }

    /**
     * Null when the event can be projected, else the reason it is rejected (a poison event for the sender).
     *
     * @param tierCaller the caller holds the TIER or ADMIN role
     * @param scope      the normalised scope of this instance ({@link RollupProperties#scope()}), empty = all
     */
    static String validate(RollupEnvelope e, boolean tierCaller, String scope) {
        if (e.id() == null || e.id().isBlank() || e.id().length() > 64) {
            return "missing or oversized id";
        }
        if (e.type() == null || e.type().isBlank()) {
            return "missing type";
        }
        if (e.cell() == null || e.cell().isBlank() || e.cell().length() > 16) {
            return "missing sangamcell";
        }
        if (e.tenantPath() == null || e.tenantPath().isBlank() || e.tenantPath().length() > 256) {
            return "missing or oversized sangamtenantpath";
        }
        List<String> prefixes = RollupProjector.prefixes(e.tenantPath());
        if (prefixes.isEmpty()) {
            return "empty sangamtenantpath";
        }
        if (!inScope(prefixes.getLast(), scope)) {
            return "sangamtenantpath outside this roll-up's scope " + scope;
        }
        if (explicit(e)) {
            if (!tierCaller) {
                return "an explicit metric event needs the TIER role";
            }
            Object metric = e.data().get("metric");
            if (!(metric instanceof String m) || m.isBlank() || m.length() > MAX_METRIC) {
                return "data.metric must be a non-blank string of at most " + MAX_METRIC + " characters";
            }
            if (!(e.data().get("delta") instanceof Long)) {
                return "data.delta must be an integer";
            }
            if (!(e.data().get("day") instanceof Long day) || !validDay(day)) {
                return "data.day must be a valid yyyyMMdd day";
            }
        }
        return null;
    }

    static boolean inScope(String path, String scope) {
        return scope == null || scope.isEmpty() || path.equals(scope) || path.startsWith(scope + "/");
    }

    static boolean validDay(long day) {
        long y = day / 10000;
        if (y < 1970 || y > 9999) {
            return false;
        }
        try {
            LocalDate.of((int) y, (int) (day / 100 % 100), (int) (day % 100));
            return true;
        } catch (DateTimeException ex) {
            return false;
        }
    }

    /** The effect of a valid event ({@link #validate} returned null). */
    static Effect effect(RollupEnvelope e, boolean perDefinitionMetrics) {
        List<String> prefixes = RollupProjector.prefixes(e.tenantPath());
        if (explicit(e)) {
            Map<String, Object> d = e.data();
            return new Effect(((Long) d.get("day")).intValue(),
                    List.of(new Delta((String) d.get("metric"), (Long) d.get("delta"))), prefixes);
        }
        return new Effect(RollupProjector.dayBucket(e.time()), deltas(e, perDefinitionMetrics), prefixes);
    }

    static List<Delta> deltas(RollupEnvelope e, boolean perDefinitionMetrics) {
        String metric = e.type().startsWith(HISTORY_PREFIX) ? e.type().substring(HISTORY_PREFIX.length()) : e.type();
        metric = clip(metric);
        List<Delta> out = new ArrayList<>();
        out.add(new Delta(metric, 1));
        Object def = e.data() == null ? null : e.data().get("processDefinitionKey");
        if (perDefinitionMetrics && def != null) {
            out.add(new Delta(clip(metric + "#" + def), 1));
        }
        if ("process.start".equals(metric)) {
            out.add(new Delta("process.open", 1));
        } else if ("process.end".equals(metric)) {
            out.add(new Delta("process.open", -1));
        }
        return out;
    }

    /**
     * The node a forwarded delta is shipped under: the event's prefix at {@code depth}, or its deepest prefix when
     * the path is shorter. The upper tier adds the delta to that node and every prefix of it, so each event reaches
     * every level at or above {@code depth} exactly once.
     */
    static String cutNode(List<String> prefixes, int depth) {
        return prefixes.get(Math.min(depth, prefixes.size() - 1));
    }

    static String lastSegment(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }

    private static String clip(String m) {
        return m.length() > MAX_METRIC ? m.substring(0, MAX_METRIC) : m;
    }
}
