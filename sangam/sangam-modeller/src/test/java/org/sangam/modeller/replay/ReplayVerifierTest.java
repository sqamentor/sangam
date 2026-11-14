// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.replay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sangam.modeller.replay.ReplayComparison.Area;
import org.sangam.modeller.replay.ReplayException.Code;
import org.sangam.modeller.replay.ReplayFixture.Decision;
import org.sangam.modeller.replay.ReplayFixture.Deadline;
import org.sangam.modeller.replay.ReplayFixture.Event;
import org.sangam.modeller.replay.ReplayFixture.EventType;
import org.sangam.modeller.replay.ReplayFixture.Observation;
import org.sangam.modeller.replay.ReplayFixture.SideEffectIntent;
import org.sangam.modeller.replay.ReplayFixture.TaskOutcome;
import org.sangam.modeller.replay.ReplayFixture.Transition;
import org.sangam.modeller.replay.ReplayFixture.TransitionKind;
import org.sangam.modeller.replay.ReplayFixture.Value;
import org.sangam.modeller.replay.ReplayFixture.ValueType;
import org.sangam.modeller.replay.ReplayRun.Status;
import org.sangam.modeller.replay.ReplayVerifier.Change;
import org.sangam.modeller.replay.ReplayVerifier.Outcome;
import org.sangam.modeller.replay.ReplayVerifier.Scenario;
import org.sangam.modeller.replay.ReplayVerifier.Step;
import org.sangam.modeller.replay.ReplayVerifier.Verification;

/**
 * Decision 151 end-to-end verifier over the synthetic loan scenario of {@link ReplayRunTest}: a separate "live" engine
 * captures through {@link FixtureRecorder} (it does not reuse the replay driver), the fixture goes through a JSON round
 * trip, {@link ReplayRunTest.LoanDriver} replays it, and changed inputs and rules must give the declared differences.
 * The engine-backed capture and driver (Operaton, W4-E) plug into the same {@link ReplayVerifier.Scenario}; this test
 * proves the chain and its failure reporting, not the engine. Synthetic data only.
 */
class ReplayVerifierTest {

    static final Instant T0 = ReplayRunTest.T0;

    /** The live side: runs the loan scenario with its own stimuli and records what the engine consumed. */
    static final class LiveLoan implements ReplayCapture {
        final String amount;
        final boolean randomScript;
        final boolean synthetic;

        LiveLoan(String amount, boolean randomScript, boolean synthetic) {
            this.amount = amount;
            this.randomScript = randomScript;
            this.synthetic = synthetic;
        }

        LiveLoan() {
            this("1500.50", false, true);
        }

        @Override
        public ReplayFixture capture() {
            ReplayFixture shape = ReplayRunTest.fixture(amount, List.of(), ReplayRunTest.PLACEHOLDER);
            FixtureRecorder rec = new FixtureRecorder(new FixtureRecorder.Header(shape.fixtureId(), shape.scenario(),
                    synthetic, shape.models(), shape.initialState(), shape.configuration(), shape.tenant(),
                    shape.versions(), shape.startedAt()));
            String exec = "cap-1";
            List<Transition> tr = new ArrayList<>();
            tr.add(new Transition(exec, "start", TransitionKind.STARTED));
            tr.add(new Transition(exec, "start", TransitionKind.COMPLETED));
            tr.add(new Transition(exec, "score", TransitionKind.STARTED));
            rec.record(EventType.EXTERNAL_TASK_RESULT, exec, "score", 1, T0.plus(Duration.ofMinutes(1)), TaskOutcome.FAILURE, 1,
                    Map.of("error", Value.of(ValueType.STRING, "scoring service timed out")), "ai-score");
            rec.record(EventType.EXTERNAL_TASK_RESULT, exec, "score", 2, T0.plus(Duration.ofMinutes(2)), TaskOutcome.SUCCESS, null,
                    Map.of("score", Value.of(ValueType.LONG, "700")), "ai-score");
            long score = 700;
            tr.add(new Transition(exec, "score", TransitionKind.COMPLETED));
            tr.add(new Transition(exec, "decide", TransitionKind.STARTED));
            Instant today = T0.plus(Duration.ofMinutes(3));
            rec.clockRead(exec, "decide", today);
            if (randomScript) {
                rec.unsupported("script task jitter calls Math.random() without a capture hook");
            }
            boolean approve = score >= 600;
            Decision d = new Decision("decide", "loan-approval", Map.of("score", Value.of(ValueType.LONG, "700")),
                    List.of(approve ? "rule-approve" : "rule-decline"),
                    List.of(Map.of("approved", Value.of(ValueType.BOOLEAN, Boolean.toString(approve)))));
            tr.add(new Transition(exec, "decide", TransitionKind.COMPLETED));
            tr.add(new Transition(exec, "wait", TransitionKind.STARTED));
            Instant due = today.plus(Duration.ofDays(2));
            rec.record(EventType.TIMER_SCHEDULED, exec, "wait", 1, today, null, null,
                    Map.of("timerId", Value.of(ValueType.STRING, "t1"), "dueAt", Value.of(ValueType.INSTANT, due.toString())), null);
            rec.record(EventType.TIMER_FIRED, exec, "wait", 1, due, null, null,
                    Map.of("timerId", Value.of(ValueType.STRING, "t1")), null);
            tr.add(new Transition(exec, "wait", TransitionKind.COMPLETED));
            List<SideEffectIntent> effects = approve ? List.of(new SideEffectIntent("notify", "loan.approved",
                    shape.fixtureId() + ":approved", ReplayFixtures.sha256(("approved:" + amount).getBytes(StandardCharsets.UTF_8))))
                    : List.of();
            return rec.finish(new Observation("COMPLETED", Map.of("approved", Value.of(ValueType.BOOLEAN, Boolean.toString(approve)),
                    "amount", Value.of(ValueType.DECIMAL, amount), "decidedAt", Value.of(ValueType.INSTANT, today.toString())),
                    tr, List.of(d), List.of(new Deadline("wait", "TIMER", due.toString())), effects));
        }
    }

    static ReplayFixture withAmount(ReplayFixture f, String amount) {
        return copy(f, new ReplayFixture.InitialState(f.initialState().definitionKey(), f.initialState().businessKey(),
                Map.of("amount", Value.of(ValueType.DECIMAL, amount))), f.events());
    }

    static ReplayFixture copy(ReplayFixture f, ReplayFixture.InitialState start, List<Event> events) {
        return new ReplayFixture(f.schemaVersion(), f.fixtureId(), f.scenario(), f.synthetic(), f.models(), start,
                f.configuration(), f.tenant(), f.versions(), f.startedAt(), events, f.observed(), f.unsupported());
    }

    static final Change AMOUNT = Change.input("amount 1500.50 becomes 1500.5 (same number, other scale)",
            f -> withAmount(f, "1500.5"), Set.of(Area.OUTPUT, Area.SIDE_EFFECT));
    static final Change THRESHOLD = Change.rule("approval threshold raised from 600 to 800",
            new ReplayRunTest.LoanDriver(800, ReplayRunTest.ENGINE, false), Set.of(Area.OUTPUT, Area.DECISION, Area.SIDE_EFFECT));

    static Scenario scenario(ReplayCapture capture, ReplayDriver driver, List<Change> changes) {
        return new Scenario("loan approval", capture, driver, 3, changes, false);
    }

    @Test
    void theLoanScenarioIsVerifiedFromCaptureToDiff() {
        Verification v = ReplayVerifier.verify(scenario(new LiveLoan(), new ReplayRunTest.LoanDriver(), List.of(AMOUNT, THRESHOLD)));
        assertEquals(Outcome.VERIFIED, v.outcome(), v.toString());
        assertNull(v.failedStep());
        assertEquals(List.of(Step.CAPTURE, Step.FIXTURE, Step.REPLAY, Step.CHANGED_INPUT),
                v.steps().stream().map(ReplayVerifier.StepResult::step).toList());
        assertTrue(v.steps().stream().allMatch(ReplayVerifier.StepResult::passed), v.steps().toString());
        assertTrue(v.fixtureSha256().matches("[0-9a-f]{64}"), v.fixtureSha256());
        assertEquals(3, v.replays().size());
        assertTrue(v.replays().stream().allMatch(r -> r.status() == Status.EQUIVALENT));
        assertEquals(List.of(EventType.MESSAGE_DELIVERED), v.gaps());
        assertEquals(2, v.changeProofs().size());
        ReplayVerifier.ChangeProof amount = v.changeProofs().get(0);
        assertEquals(Status.DIFFERENT, amount.status());
        assertTrue(amount.differences().contains(new ReplayComparison.Difference(Area.OUTPUT, "amount",
                "DECIMAL 1500.50", "DECIMAL 1500.5")), amount.differences().toString());
        ReplayVerifier.ChangeProof threshold = v.changeProofs().get(1);
        assertTrue(threshold.areas().containsAll(Set.of(Area.OUTPUT, Area.DECISION, Area.SIDE_EFFECT)), threshold.toString());
        assertNull(threshold.failure());
    }

    @Test
    void theVerifiedFixtureIsTheSameDocumentEveryTime() {
        Verification a = ReplayVerifier.verify(scenario(new LiveLoan(), new ReplayRunTest.LoanDriver(), List.of(AMOUNT)));
        Verification b = ReplayVerifier.verify(scenario(new LiveLoan(), new ReplayRunTest.LoanDriver(), List.of(AMOUNT)));
        assertEquals(Outcome.VERIFIED, a.outcome(), a.toString());
        assertEquals(a.fixtureSha256(), b.fixtureSha256());
    }

    @Test
    void anUnsupportedCaptureIsReportedAndNeverReplayed() {
        Verification v = ReplayVerifier.verify(scenario(new LiveLoan("1500.50", true, true), new ReplayRunTest.LoanDriver(),
                List.of(AMOUNT)));
        assertEquals(Outcome.UNSUPPORTED, v.outcome());
        assertEquals(Step.CAPTURE, v.failedStep());
        assertEquals(List.of("script task jitter calls Math.random() without a capture hook"), v.unsupported());
        assertTrue(v.replays().isEmpty());
        assertNull(v.fixtureSha256());
    }

    @Test
    void realDataIsRefusedUnlessTheScenarioAllowsIt() {
        Verification v = ReplayVerifier.verify(scenario(new LiveLoan("1500.50", false, false), new ReplayRunTest.LoanDriver(),
                List.of(AMOUNT)));
        assertEquals(Outcome.FAILED, v.outcome());
        assertEquals(Step.CAPTURE, v.failedStep());
        assertTrue(v.message().contains("not synthetic"), v.message());
    }

    @Test
    void aReplayThatCallsOutFailsAtTheReplayStep() {
        Verification v = ReplayVerifier.verify(scenario(new LiveLoan(),
                new ReplayRunTest.LoanDriver(600, ReplayRunTest.ENGINE, true), List.of(AMOUNT)));
        assertEquals(Outcome.FAILED, v.outcome());
        assertEquals(Step.REPLAY, v.failedStep());
        assertTrue(v.message().contains(Code.UNEXPECTED_EXTERNAL_CALL.name()), v.message());
    }

    @Test
    void aChangeWithoutEffectIsNotAProof() {
        Change same = Change.rule("threshold kept at 600", new ReplayRunTest.LoanDriver(), Set.of(Area.OUTPUT));
        Verification v = ReplayVerifier.verify(scenario(new LiveLoan(), new ReplayRunTest.LoanDriver(), List.of(same)));
        assertEquals(Outcome.FAILED, v.outcome());
        assertEquals(Step.CHANGED_INPUT, v.failedStep());
        assertTrue(v.message().contains("no difference found"), v.message());
    }

    @Test
    void aChangeThatBreaksTheReplayIsNotAProof() {
        Change dropClock = Change.input("the clock read is removed", f -> copy(f, f.initialState(),
                f.events().stream().filter(e -> e.type() != EventType.CLOCK_READ).toList()), Set.of(Area.OUTPUT));
        Verification v = ReplayVerifier.verify(scenario(new LiveLoan(), new ReplayRunTest.LoanDriver(), List.of(dropClock)));
        assertEquals(Outcome.FAILED, v.outcome());
        assertEquals(Step.CHANGED_INPUT, v.failedStep());
        assertTrue(v.message().contains("the replay failed") && v.message().contains(Code.MISSING_EVENT.name()), v.message());
    }

    @Test
    void aDifferenceOutsideTheDeclaredAreasIsNotEnough() {
        Change wrongArea = Change.input("amount changed, deadline expected to move", f -> withAmount(f, "1500.5"),
                Set.of(Area.DEADLINE));
        Verification v = ReplayVerifier.verify(scenario(new LiveLoan(), new ReplayRunTest.LoanDriver(), List.of(wrongArea)));
        assertEquals(Outcome.FAILED, v.outcome());
        assertTrue(v.message().contains("no difference in [DEADLINE]"), v.message());
    }

    @Test
    void aScenarioWithoutAChangeCannotBeVerified() {
        Verification v = ReplayVerifier.verify(scenario(new LiveLoan(), new ReplayRunTest.LoanDriver(), List.of()));
        assertEquals(Outcome.FAILED, v.outcome());
        assertEquals(Step.CHANGED_INPUT, v.failedStep());
        assertThrows(IllegalArgumentException.class, () -> new Scenario("x", new LiveLoan(), new ReplayRunTest.LoanDriver(), 1,
                List.of(AMOUNT), false));
        assertThrows(IllegalArgumentException.class, () -> Change.input("only input", f -> f, Set.of(Area.INPUT)));
    }

    @Test
    void theRecorderNumbersEventsAndKeepsTheClockForward() {
        ReplayFixture shape = ReplayRunTest.fixture("1.00", List.of(), ReplayRunTest.PLACEHOLDER);
        FixtureRecorder rec = new FixtureRecorder(new FixtureRecorder.Header(shape.fixtureId(), shape.scenario(), true,
                shape.models(), shape.initialState(), shape.configuration(), shape.tenant(), shape.versions(), shape.startedAt()));
        Event first = rec.clockRead("e1", "decide", T0.plus(Duration.ofMinutes(5)));
        Event second = rec.clockRead("e1", "decide", T0.plus(Duration.ofMinutes(5)));
        assertEquals(1, first.seq());
        assertEquals(2, second.seq());
        ReplayException back = assertThrows(ReplayException.class, () -> rec.clockRead("e1", "decide", T0));
        assertEquals(Code.INCOMPLETE_FIXTURE, back.code());
        assertThrows(IllegalArgumentException.class, () -> rec.unsupported(" "));
        ReplayFixture f = rec.finish(ReplayRunTest.PLACEHOLDER);
        assertEquals(2, f.events().size());
        assertThrows(IllegalStateException.class, () -> rec.clockRead("e1", "decide", T0.plus(Duration.ofHours(1))));
    }
}
