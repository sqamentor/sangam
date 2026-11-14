// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.replay;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;

import org.sangam.modeller.replay.ReplayComparison.Area;
import org.sangam.modeller.replay.ReplayComparison.Difference;
import org.sangam.modeller.replay.ReplayFixture.EventType;
import org.sangam.modeller.replay.ReplayRun.Mode;
import org.sangam.modeller.replay.ReplayRun.Result;
import org.sangam.modeller.replay.ReplayRun.Status;

/**
 * The decision 151 end-to-end verifier: capture, fixture, replay, comparison, in one run that either proves the chain
 * or names the step that broke it.
 *
 * <ol>
 *   <li><b>CAPTURE</b>: {@link ReplayCapture#capture()} runs the scenario live (isolated engine, synthetic data). A
 *       capture that marks the scenario unsupported ends the verification as {@link Outcome#UNSUPPORTED} with the
 *       reasons; a non-synthetic fixture is refused unless the scenario explicitly allows real data.</li>
 *   <li><b>FIXTURE</b>: the fixture is written, read back and written again; the read-back fixture must equal the
 *       captured one and the two documents must be byte-identical (nothing lost or reordered by serialisation). The
 *       SHA-256 of the document identifies what was replayed.</li>
 *   <li><b>REPLAY</b>: the read-back fixture is replayed exactly {@code repeats} times (at least two); every run must be
 *       {@link Status#EQUIVALENT} to the capture with no unconsumed input. Required capture kinds the scenario never
 *       exercised are reported as gaps, not hidden.</li>
 *   <li><b>CHANGED_INPUT</b>: each declared {@link Change} (a changed input in the fixture, or a changed rule or model
 *       through another driver) must give {@link Status#DIFFERENT}, never FAILED, with differences in every area the
 *       change declares; afterwards the original fixture must still replay equivalent.</li>
 * </ol>
 * Everything here is an exact replay ({@link Mode#EXACT}); comparison against an upgraded engine stays with
 * {@link ReplayRun} in {@link Mode#UPGRADE} and is never reported as a verification.
 */
public final class ReplayVerifier {

    /** Result of a verification. */
    public enum Outcome { VERIFIED, FAILED, UNSUPPORTED }

    /** The four steps, in order. */
    public enum Step { CAPTURE, FIXTURE, REPLAY, CHANGED_INPUT }

    /** One step's result. */
    public record StepResult(Step step, boolean passed, String detail) {
    }

    /**
     * A declared change and the difference it must produce.
     *
     * @param description what was changed and why it must matter
     * @param fixture     changes the fixture (an input, an initial variable), or null to keep it
     * @param driver      a driver with a changed rule or model, or null to keep the scenario's driver
     * @param expectAreas comparison areas the change must show a difference in (at least one, never only INPUT)
     */
    public record Change(String description, UnaryOperator<ReplayFixture> fixture, ReplayDriver driver,
                         Set<Area> expectAreas) {
        public Change {
            if (description == null || description.isBlank()) {
                throw new IllegalArgumentException("a change needs a description");
            }
            if (fixture == null && driver == null) {
                throw new IllegalArgumentException("a change must change the fixture or the driver");
            }
            if (expectAreas == null || expectAreas.isEmpty() || expectAreas.equals(EnumSet.of(Area.INPUT))) {
                throw new IllegalArgumentException("a change must name the outcome areas it affects");
            }
            expectAreas = Set.copyOf(expectAreas);
        }

        /** A changed input: the fixture is altered, the driver is the scenario's. */
        public static Change input(String description, UnaryOperator<ReplayFixture> fixture, Set<Area> expectAreas) {
            return new Change(description, Objects.requireNonNull(fixture, "fixture"), null, expectAreas);
        }

        /** A changed rule or model: the same fixture through another driver. */
        public static Change rule(String description, ReplayDriver driver, Set<Area> expectAreas) {
            return new Change(description, null, Objects.requireNonNull(driver, "driver"), expectAreas);
        }
    }

    /**
     * One scenario to verify.
     *
     * @param name            scenario name, for the report
     * @param capture         the live capture
     * @param driver          the replay driver (same engine and evaluator versions as the capture)
     * @param repeats         exact replays of the unchanged fixture, at least two
     * @param changes         changed-input or changed-rule proofs, at least one
     * @param realDataAllowed true only when the owner has supplied real data for this scenario
     */
    public record Scenario(String name, ReplayCapture capture, ReplayDriver driver, int repeats, List<Change> changes,
                           boolean realDataAllowed) {
        public Scenario {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(capture, "capture");
            Objects.requireNonNull(driver, "driver");
            if (repeats < 2) {
                throw new IllegalArgumentException("repeated replay needs at least two runs");
            }
            changes = changes == null ? List.of() : List.copyOf(changes);
        }
    }

    /** The difference a change produced. */
    public record ChangeProof(String description, Status status, Set<Area> areas, List<Difference> differences,
                              String failure) {
    }

    /**
     * @param scenario      scenario name
     * @param outcome       verified, failed or unsupported
     * @param failedStep    the first step that failed, null otherwise
     * @param message       why it failed or is unsupported, null when verified
     * @param steps         every step that ran, in order
     * @param fixtureSha256 SHA-256 of the written fixture, null before the FIXTURE step passed
     * @param replays       the exact replays of the unchanged fixture
     * @param changeProofs  one per declared change
     * @param gaps          required capture kinds the scenario never exercised
     * @param unsupported   the capture's unsupported reasons
     */
    public record Verification(String scenario, Outcome outcome, Step failedStep, String message,
                               List<StepResult> steps, String fixtureSha256, List<Result> replays,
                               List<ChangeProof> changeProofs, List<EventType> gaps, List<String> unsupported) {
    }

    private ReplayVerifier() {
    }

    /** Runs the four steps; never throws for a broken chain, it reports it. */
    public static Verification verify(Scenario s) {
        Run run = new Run(s);
        return run.go();
    }

    private static final class Run {
        private final Scenario s;
        private final List<StepResult> steps = new ArrayList<>();
        private final List<Result> replays = new ArrayList<>();
        private final List<ChangeProof> proofs = new ArrayList<>();
        private List<EventType> gaps = List.of();
        private List<String> unsupported = List.of();
        private String sha;

        Run(Scenario s) {
            this.s = s;
        }

        Verification go() {
            ReplayFixture captured;
            try {
                captured = s.capture().capture();
            } catch (RuntimeException e) {
                return fail(Step.CAPTURE, "the capture failed: " + describe(e));
            }
            if (captured == null) {
                return fail(Step.CAPTURE, "the capture returned no fixture");
            }
            if (!captured.unsupported().isEmpty()) {
                unsupported = captured.unsupported();
                steps.add(new StepResult(Step.CAPTURE, false, "unsupported: " + String.join("; ", unsupported)));
                return done(Outcome.UNSUPPORTED, Step.CAPTURE, "the capture marked the scenario unsupported: "
                        + String.join("; ", unsupported));
            }
            if (!captured.synthetic() && !s.realDataAllowed()) {
                return fail(Step.CAPTURE, "the fixture is not synthetic and the scenario does not allow real data");
            }
            steps.add(new StepResult(Step.CAPTURE, true, captured.events().size() + " events captured"));

            ReplayFixture fixture;
            try {
                byte[] written = ReplayFixtures.write(captured);
                fixture = ReplayFixtures.read(written);
                if (!fixture.equals(captured)) {
                    return fail(Step.FIXTURE, "the fixture read back differs from the captured fixture");
                }
                if (!Arrays.equals(written, ReplayFixtures.write(fixture))) {
                    return fail(Step.FIXTURE, "writing the read-back fixture gives a different document");
                }
                sha = ReplayFixtures.sha256(written);
            } catch (RuntimeException e) {
                return fail(Step.FIXTURE, describe(e));
            }
            steps.add(new StepResult(Step.FIXTURE, true, "round trip stable, sha256 " + sha));

            String broken = replayed(fixture, s.repeats());
            if (broken != null) {
                return fail(Step.REPLAY, broken);
            }
            gaps = replays.get(0).coverage().absentKinds();
            steps.add(new StepResult(Step.REPLAY, true, s.repeats() + " exact replays equivalent"
                    + (gaps.isEmpty() ? "" : "; not exercised by this scenario: " + gaps)));

            if (s.changes().isEmpty()) {
                return fail(Step.CHANGED_INPUT, "no changed input or rule declared; decision 151 needs a diff proof");
            }
            List<String> problems = new ArrayList<>();
            for (Change c : s.changes()) {
                String p = prove(fixture, c);
                if (p != null) {
                    problems.add(p);
                }
            }
            String after = replayed(fixture, 2);
            if (after != null) {
                problems.add("after the changes the original fixture no longer replays equivalent: " + after);
            }
            if (!problems.isEmpty()) {
                return fail(Step.CHANGED_INPUT, String.join("; ", problems));
            }
            steps.add(new StepResult(Step.CHANGED_INPUT, true, proofs.size() + " change(s) gave the declared differences"));
            return done(Outcome.VERIFIED, null, null);
        }

        /** Null when every run is equivalent with no unconsumed input; otherwise what broke. */
        private String replayed(ReplayFixture fixture, int times) {
            List<Result> runs = ReplayRun.repeat(fixture, s.driver(), times);
            if (replays.isEmpty()) {
                replays.addAll(runs);
            }
            for (int i = 0; i < runs.size(); i++) {
                Result r = runs.get(i);
                if (r.status() == Status.FAILED) {
                    return "run " + (i + 1) + " failed: " + r.message();
                }
                if (r.status() != Status.EQUIVALENT) {
                    return "run " + (i + 1) + " differs from the capture: " + r.report().differences();
                }
            }
            return null;
        }

        private String prove(ReplayFixture fixture, Change c) {
            ReplayFixture changed = c.fixture() == null ? fixture : c.fixture().apply(fixture);
            if (c.fixture() != null && fixture.equals(changed)) {
                proofs.add(new ChangeProof(c.description(), null, Set.of(), List.of(), "the change left the fixture as it was"));
                return c.description() + ": the change left the fixture as it was";
            }
            Result r;
            try {
                r = ReplayRun.run(changed, c.driver() == null ? s.driver() : c.driver(), Mode.EXACT, List.of());
            } catch (RuntimeException e) {
                proofs.add(new ChangeProof(c.description(), null, Set.of(), List.of(), describe(e)));
                return c.description() + ": " + describe(e);
            }
            if (r.status() != Status.DIFFERENT) {
                String why = r.status() == Status.FAILED ? "the replay failed (" + r.message() + ")" : "no difference found";
                proofs.add(new ChangeProof(c.description(), r.status(), Set.of(), List.of(), why));
                return c.description() + ": " + why;
            }
            Set<Area> hit = new LinkedHashSet<>();
            r.report().differences().forEach(d -> hit.add(d.area()));
            Set<Area> missing = EnumSet.noneOf(Area.class);
            for (Area a : c.expectAreas()) {
                if (!hit.contains(a)) {
                    missing.add(a);
                }
            }
            String failure = missing.isEmpty() ? null : "no difference in " + missing + " (found " + hit + ")";
            proofs.add(new ChangeProof(c.description(), r.status(), Set.copyOf(hit), r.report().differences(), failure));
            return failure == null ? null : c.description() + ": " + failure;
        }

        private Verification fail(Step step, String message) {
            steps.add(new StepResult(step, false, message));
            return done(Outcome.FAILED, step, message);
        }

        private Verification done(Outcome outcome, Step failed, String message) {
            return new Verification(s.name(), outcome, failed, message, List.copyOf(steps), sha, List.copyOf(replays),
                    List.copyOf(proofs), List.copyOf(gaps), List.copyOf(unsupported));
        }

        private static String describe(RuntimeException e) {
            return e instanceof ReplayException re ? re.getMessage() : e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }
}
