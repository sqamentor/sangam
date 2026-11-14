// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.replay;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

import org.sangam.modeller.replay.ReplayFixture.Observation;
import org.sangam.modeller.replay.ReplayFixture.Transition;
import org.sangam.modeller.replay.ReplayFixture.Value;

/**
 * Compares a replay's observation with the fixture's (decision 151): end state, outputs, state transitions, decisions,
 * deadlines and side-effect intents. Values are compared exactly (a decimal {@code 1.50} differs from {@code 1.5}; an
 * instant differs by any nanosecond).
 *
 * <p>Normalisation is explicit and never hides a difference: every value set aside by a normalisation is listed in
 * {@link Report#normalised()} with both sides, and every normalisation in force is named in
 * {@link Report#appliedNormalisations()}.
 * <ul>
 *   <li>EXACT: replay execution ids are translated to captured ids through the recorded binding (an id without a
 *       binding stays visible as {@code unbound:<id>}); no {@link Rule} is allowed.</li>
 *   <li>UPGRADE (comparison against a changed model or engine): execution ids are not compared; {@link Rule}s may set
 *       aside named outputs or activities, each with a reason.</li>
 * </ul>
 */
public final class ReplayComparison {

    /** What was compared. */
    public enum Area { END_STATE, OUTPUT, TRANSITION, DECISION, DEADLINE, SIDE_EFFECT, INPUT }

    /**
     * One difference.
     *
     * @param area     what differs
     * @param path     output name or list index
     * @param expected the captured value (rendered), {@code (absent)} when missing
     * @param actual   the replayed value (rendered), {@code (absent)} when missing
     */
    public record Difference(Area area, String path, String expected, String actual) {
    }

    /** Kinds of explicit normalisation rule (UPGRADE mode only). */
    public enum RuleKind {
        /** Sets aside one output variable (target = variable name). */
        IGNORE_OUTPUT,
        /** Sets aside transitions, decisions, deadlines and side effects of one activity (target = activity id). */
        IGNORE_ACTIVITY
    }

    /**
     * An explicit normalisation rule.
     *
     * @param kind   what it sets aside
     * @param target the output name or activity id
     * @param reason why (required; shown in the report)
     */
    public record Rule(RuleKind kind, String target, String reason) {
        public Rule {
            Objects.requireNonNull(kind, "kind");
            if (target == null || target.isBlank() || reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("a normalisation rule needs a target and a reason");
            }
        }
    }

    /**
     * A value set aside by a normalisation, still shown.
     *
     * @param rule     the normalisation that set it aside
     * @param area     the area
     * @param path     the output name or activity
     * @param expected captured value (rendered)
     * @param actual   replayed value (rendered)
     */
    public record Normalised(String rule, Area area, String path, String expected, String actual) {
    }

    /**
     * @param differences           remaining differences; empty means equivalent
     * @param normalised            values set aside, with both sides
     * @param appliedNormalisations every normalisation in force, in words
     */
    public record Report(List<Difference> differences, List<Normalised> normalised, List<String> appliedNormalisations) {
        public boolean equivalent() {
            return differences.isEmpty();
        }
    }

    static final String ABSENT = "(absent)";

    private ReplayComparison() {
    }

    /**
     * Compares {@code expected} (captured) with {@code actual} (replayed).
     *
     * @param binding replay execution id to captured execution id (EXACT), ignored in UPGRADE
     */
    public static Report compare(Observation expected, Observation actual, ReplayRun.Mode mode,
                                 Map<String, String> binding, List<Rule> rules) {
        List<Rule> rs = rules == null ? List.of() : List.copyOf(rules);
        if (mode == ReplayRun.Mode.EXACT && !rs.isEmpty()) {
            throw new IllegalArgumentException("an exact replay allows no normalisation rule");
        }
        List<Difference> diffs = new ArrayList<>();
        List<Normalised> normalised = new ArrayList<>();
        List<String> applied = new ArrayList<>();
        Set<String> ignoredOutputs = new TreeSet<>();
        Set<String> ignoredActivities = new TreeSet<>();
        for (Rule r : rs) {
            (r.kind() == RuleKind.IGNORE_OUTPUT ? ignoredOutputs : ignoredActivities).add(r.target());
            applied.add(r.kind() + " " + r.target() + ": " + r.reason());
        }
        Function<Transition, Transition> expectedIds;
        Function<Transition, Transition> actualIds;
        if (mode == ReplayRun.Mode.EXACT) {
            applied.add("execution ids of the replay translated to captured ids by first-use binding " + binding);
            expectedIds = t -> t;
            actualIds = t -> new Transition(binding.getOrDefault(t.executionId(), "unbound:" + t.executionId()),
                    t.activityId(), t.kind());
        } else {
            applied.add("execution ids not compared (upgrade comparison)");
            expectedIds = t -> new Transition(null, t.activityId(), t.kind());
            actualIds = expectedIds;
        }
        if (!Objects.equals(expected.endState(), actual.endState())) {
            diffs.add(new Difference(Area.END_STATE, "", String.valueOf(expected.endState()), String.valueOf(actual.endState())));
        }
        Set<String> names = new TreeSet<>(expected.outputs().keySet());
        names.addAll(actual.outputs().keySet());
        for (String n : names) {
            Value e = expected.outputs().get(n);
            Value a = actual.outputs().get(n);
            if (ignoredOutputs.contains(n)) {
                normalised.add(new Normalised("IGNORE_OUTPUT " + n, Area.OUTPUT, n, render(e), render(a)));
            } else if (!Objects.equals(e, a)) {
                diffs.add(new Difference(Area.OUTPUT, n, render(e), render(a)));
            }
        }
        list(Area.TRANSITION, expected.transitions().stream().map(expectedIds).toList(),
                actual.transitions().stream().map(actualIds).toList(), Transition::activityId, ignoredActivities, diffs, normalised);
        list(Area.DECISION, expected.decisions(), actual.decisions(), ReplayFixture.Decision::activityId,
                ignoredActivities, diffs, normalised);
        list(Area.DEADLINE, expected.deadlines(), actual.deadlines(), ReplayFixture.Deadline::activityId,
                ignoredActivities, diffs, normalised);
        list(Area.SIDE_EFFECT, expected.sideEffects(), actual.sideEffects(), ReplayFixture.SideEffectIntent::activityId,
                ignoredActivities, diffs, normalised);
        return new Report(List.copyOf(diffs), List.copyOf(normalised), List.copyOf(applied));
    }

    private static <T> void list(Area area, List<T> expected, List<T> actual, Function<T, String> activity,
                                 Set<String> ignored, List<Difference> diffs, List<Normalised> normalised) {
        List<T> e = keep(expected, activity, ignored, area, normalised, true);
        List<T> a = keep(actual, activity, ignored, area, normalised, false);
        for (int i = 0; i < Math.max(e.size(), a.size()); i++) {
            T x = i < e.size() ? e.get(i) : null;
            T y = i < a.size() ? a.get(i) : null;
            if (!Objects.equals(x, y)) {
                diffs.add(new Difference(area, "[" + i + "]", render(x), render(y)));
            }
        }
    }

    private static <T> List<T> keep(List<T> items, Function<T, String> activity, Set<String> ignored, Area area,
                                    List<Normalised> normalised, boolean expectedSide) {
        List<T> kept = new ArrayList<>();
        for (T t : items) {
            String act = activity.apply(t);
            if (act != null && ignored.contains(act)) {
                normalised.add(new Normalised("IGNORE_ACTIVITY " + act, area, act,
                        expectedSide ? render(t) : ABSENT, expectedSide ? ABSENT : render(t)));
            } else {
                kept.add(t);
            }
        }
        return kept;
    }

    /** Deterministic text of a compared item (maps sorted by key). */
    static String render(Object o) {
        if (o == null) {
            return ABSENT;
        }
        if (o instanceof Value v) {
            return v.type() + (v.value() == null ? "" : " " + v.value()) + (v.zone() == null ? "" : " [" + v.zone() + "]");
        }
        if (o instanceof Map<?, ?> m) {
            Map<String, String> sorted = new TreeMap<>();
            m.forEach((k, v) -> sorted.put(String.valueOf(k), render(v)));
            return sorted.toString();
        }
        if (o instanceof ReplayFixture.Decision d) {
            return "Decision[activity=" + d.activityId() + ", key=" + d.decisionKey() + ", inputs=" + render(d.inputs())
                    + ", rules=" + d.matchedRules() + ", outputs=" + d.outputs().stream().map(ReplayComparison::render).toList()
                    + "]";
        }
        return o.toString();
    }
}
