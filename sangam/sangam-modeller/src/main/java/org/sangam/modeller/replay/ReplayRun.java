// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.replay;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.sangam.modeller.replay.ReplayException.Code;
import org.sangam.modeller.replay.ReplayFixture.EventType;

/**
 * Runs a replay of one fixture through a {@link ReplayDriver} and compares the result (M-136, decision 151).
 *
 * <ul>
 *   <li>{@link Mode#EXACT}: the same models on the same engine and evaluator versions; inputs consumed in recorded
 *       order; every recorded input must be consumed (an unconsumed one is an {@link ReplayComparison.Area#INPUT}
 *       difference); no normalisation rule.</li>
 *   <li>{@link Mode#UPGRADE}: the fixture against an upgraded engine or a changed model; versions may differ (reported
 *       in {@link Result#versions()}); explicit {@link ReplayComparison.Rule}s allowed; unconsumed inputs reported in
 *       the coverage, not failed. Never reported as an exact replay.</li>
 * </ul>
 * A replay that cannot be trusted ends {@link Status#FAILED} with the {@link ReplayException.Code}; it never falls back
 * to live data or the current time.
 */
public final class ReplayRun {

    /** Exact replay or comparison against an upgraded version. */
    public enum Mode { EXACT, UPGRADE }

    /** Outcome of one run. */
    public enum Status { EQUIVALENT, DIFFERENT, FAILED }

    /** Capture kinds 0.1.0 requires when the scenario has them; an absent one is reported as a coverage gap. */
    public static final List<EventType> REQUIRED_CAPTURE = List.of(EventType.CLOCK_READ, EventType.TIMER_SCHEDULED,
            EventType.TIMER_FIRED, EventType.MESSAGE_DELIVERED, EventType.EXTERNAL_TASK_RESULT);

    /**
     * What the replay used of the fixture.
     *
     * @param recorded         recorded events per type
     * @param consumed         consumed events per type
     * @param unconsumedSeq    seq of every recorded event the replay never asked for
     * @param absentKinds      required capture kinds the fixture has none of (gap: not exercised by this scenario)
     * @param executionBinding replay execution id to captured execution id
     * @param refusedCalls     live calls the replay attempted and was refused
     */
    public record Coverage(Map<EventType, Integer> recorded, Map<EventType, Integer> consumed, List<Long> unconsumedSeq,
                           List<EventType> absentKinds, Map<String, String> executionBinding, List<String> refusedCalls) {
    }

    /**
     * @param fixtureId  the fixture
     * @param mode       exact or upgrade
     * @param status     equivalent, different or failed
     * @param versions   captured and replay versions, for example {@code engine: operaton 2.2.0 -> operaton 2.3.0}
     * @param failure    the failure code, null unless FAILED
     * @param message    the failure text, null unless FAILED
     * @param report     the comparison, null when FAILED
     * @param coverage   what the run consumed (also when FAILED)
     * @param observed   the replay's observation, null when FAILED
     */
    public record Result(String fixtureId, Mode mode, Status status, List<String> versions, Code failure, String message,
                         ReplayComparison.Report report, Coverage coverage, ReplayFixture.Observation observed) {
    }

    private ReplayRun() {
    }

    /** Replays {@code fixture} once and compares it with the captured observation. */
    public static Result run(ReplayFixture fixture, ReplayDriver driver, Mode mode, List<ReplayComparison.Rule> rules) {
        Objects.requireNonNull(driver, "driver");
        ReplayFixtures.validate(fixture);
        List<String> versions = List.of(
                "engine: " + fixture.versions().engine() + " -> " + driver.engineVersion(),
                "evaluator: " + fixture.versions().evaluator() + " -> " + driver.evaluatorVersion());
        if (mode == Mode.EXACT && (!fixture.versions().engine().equals(driver.engineVersion())
                || !fixture.versions().evaluator().equals(driver.evaluatorVersion()))) {
            return failed(fixture, mode, versions, new ReplayException(Code.INCOMPATIBLE_VERSION, "an exact replay needs"
                    + " the captured versions (" + String.join("; ", versions) + "); use UPGRADE to compare"), null);
        }
        if (mode == Mode.EXACT && rules != null && !rules.isEmpty()) {
            throw new IllegalArgumentException("an exact replay allows no normalisation rule");
        }
        RecordedInputs inputs = new RecordedInputs(fixture, mode);
        ReplayFixture.Observation observed;
        try {
            observed = Objects.requireNonNull(driver.replay(fixture, inputs), "the driver returned no observation");
        } catch (ReplayException e) {
            return failed(fixture, mode, versions, e, inputs.coverage());
        }
        Coverage coverage = inputs.coverage();
        ReplayComparison.Report report = ReplayComparison.compare(fixture.observed(), observed, mode,
                inputs.executionBinding(), rules);
        if (mode == Mode.EXACT && !coverage.unconsumedSeq().isEmpty()) {
            List<ReplayComparison.Difference> diffs = new ArrayList<>(report.differences());
            for (Long seq : coverage.unconsumedSeq()) {
                diffs.add(new ReplayComparison.Difference(ReplayComparison.Area.INPUT, "seq " + seq,
                        "consumed", "never asked for"));
            }
            report = new ReplayComparison.Report(List.copyOf(diffs), report.normalised(), report.appliedNormalisations());
        }
        Status status = report.equivalent() ? Status.EQUIVALENT : Status.DIFFERENT;
        return new Result(fixture.fixtureId(), mode, status, versions, null, null, report, coverage, observed);
    }

    /**
     * Replays the fixture {@code times} times exactly (repeated replay must be deterministic). Returns every result;
     * {@link #allEquivalent(List)} tells whether all of them matched the capture.
     */
    public static List<Result> repeat(ReplayFixture fixture, ReplayDriver driver, int times) {
        if (times < 2) {
            throw new IllegalArgumentException("repeat needs at least two runs");
        }
        List<Result> results = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            results.add(run(fixture, driver, Mode.EXACT, List.of()));
        }
        return results;
    }

    /**
     * True when every run is EQUIVALENT to the capture. Each run is compared exactly with the same captured
     * observation (execution ids through that run's own binding), so equivalent runs are equivalent to each other
     * even when the engine generates different raw execution ids per run.
     */
    public static boolean allEquivalent(List<Result> results) {
        return !results.isEmpty() && results.stream().allMatch(r -> r.status() == Status.EQUIVALENT);
    }

    private static Result failed(ReplayFixture f, Mode mode, List<String> versions, ReplayException e, Coverage coverage) {
        return new Result(f.fixtureId(), mode, Status.FAILED, versions, e.code(), e.getMessage(), null, coverage, null);
    }
}
