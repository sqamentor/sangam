// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.replay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.modeller.replay.ReplayComparison.Area;
import org.sangam.modeller.replay.ReplayComparison.Rule;
import org.sangam.modeller.replay.ReplayComparison.RuleKind;
import org.sangam.modeller.replay.ReplayException.Code;
import org.sangam.modeller.replay.ReplayFixture.Decision;
import org.sangam.modeller.replay.ReplayFixture.Deadline;
import org.sangam.modeller.replay.ReplayFixture.Event;
import org.sangam.modeller.replay.ReplayFixture.EventType;
import org.sangam.modeller.replay.ReplayFixture.ModelKind;
import org.sangam.modeller.replay.ReplayFixture.ModelRef;
import org.sangam.modeller.replay.ReplayFixture.Observation;
import org.sangam.modeller.replay.ReplayFixture.SideEffectIntent;
import org.sangam.modeller.replay.ReplayFixture.TaskOutcome;
import org.sangam.modeller.replay.ReplayFixture.Transition;
import org.sangam.modeller.replay.ReplayFixture.TransitionKind;
import org.sangam.modeller.replay.ReplayFixture.Value;
import org.sangam.modeller.replay.ReplayFixture.ValueType;
import org.sangam.modeller.replay.ReplayRun.Mode;
import org.sangam.modeller.replay.ReplayRun.Result;
import org.sangam.modeller.replay.ReplayRun.Status;

/**
 * M-136 (decision 151) reproducer and comparison over a synthetic loan scenario and a fake engine driver: a scored
 * external task (one failure, then success), a FEEL clock read and a decision, a timer, a side-effect intent. Proves
 * repeated replay is equivalent, a changed rule or input gives a meaningful diff, and missing events, incompatible
 * versions and live calls fail clearly. Synthetic data only. No persistence is involved (fixtures are documents), so no
 * Oracle or SQL Server case applies to this part; the engine adapter's own tests (W4-E) cover the database side.
 */
class ReplayRunTest {

    static final String ENGINE = "operaton 2.2.0";
    static final String FEEL = "operaton-feel 2.2.0";
    static final Instant T0 = Instant.parse("2026-03-29T00:30:00Z");
    static final String BPMN = "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" id=\"loan\"/>";

    /** Fake engine: start, score (external task with retries), decide (clock read + rule), wait (timer), notify. */
    static final class LoanDriver implements ReplayDriver {
        final long threshold;
        final String engine;
        final boolean liveCall;

        LoanDriver(long threshold, String engine, boolean liveCall) {
            this.threshold = threshold;
            this.engine = engine;
            this.liveCall = liveCall;
        }

        LoanDriver() {
            this(600, ENGINE, false);
        }

        @Override
        public String engineVersion() {
            return engine;
        }

        @Override
        public String evaluatorVersion() {
            return FEEL;
        }

        @Override
        public Observation replay(ReplayFixture f, RecordedInputs in) {
            String exec = "r-1";
            List<Transition> tr = new ArrayList<>();
            tr.add(new Transition(exec, "start", TransitionKind.STARTED));
            tr.add(new Transition(exec, "start", TransitionKind.COMPLETED));
            tr.add(new Transition(exec, "score", TransitionKind.STARTED));
            Long score = null;
            while (score == null) {
                Event s = in.nextStimulus();
                if (s == null || s.type() != EventType.EXTERNAL_TASK_RESULT || !"score".equals(s.activityId())) {
                    throw new ReplayException(Code.MISSING_EVENT, "no score result");
                }
                in.bind(exec, s.executionId());
                if (s.outcome() == TaskOutcome.SUCCESS) {
                    score = Long.parseLong(s.payload().get("score").value());
                }
            }
            tr.add(new Transition(exec, "score", TransitionKind.COMPLETED));
            tr.add(new Transition(exec, "decide", TransitionKind.STARTED));
            Instant today = in.clockRead(exec, "decide");
            boolean approve = score >= threshold;
            Value scoreValue = Value.of(ValueType.LONG, Long.toString(score));
            Decision d = new Decision("decide", "loan-approval", Map.of("score", scoreValue),
                    List.of(approve ? "rule-approve" : "rule-decline"),
                    List.of(Map.of("approved", Value.of(ValueType.BOOLEAN, Boolean.toString(approve)))));
            tr.add(new Transition(exec, "decide", TransitionKind.COMPLETED));
            tr.add(new Transition(exec, "wait", TransitionKind.STARTED));
            in.timerScheduled(exec, "wait");
            Instant due = today.plus(Duration.ofDays(2));
            Event fired = in.nextStimulus();
            if (fired == null || fired.type() != EventType.TIMER_FIRED) {
                throw new ReplayException(Code.MISSING_EVENT, "the wait timer never fired");
            }
            tr.add(new Transition(exec, "wait", TransitionKind.COMPLETED));
            Value amount = f.initialState().variables().get("amount");
            if (liveCall) {
                in.externalCall("payments", "disburse " + amount.value());
            }
            List<SideEffectIntent> effects = approve
                    ? List.of(new SideEffectIntent("notify", "loan.approved", f.fixtureId() + ":approved",
                            ReplayFixtures.sha256(("approved:" + amount.value()).getBytes(StandardCharsets.UTF_8))))
                    : List.of();
            return new Observation("COMPLETED", Map.of("approved", Value.of(ValueType.BOOLEAN, Boolean.toString(approve)),
                    "amount", amount, "decidedAt", Value.of(ValueType.INSTANT, today.toString())),
                    tr, List.of(d), List.of(new Deadline("wait", "TIMER", due.toString())), effects);
        }
    }

    static Event event(long seq, EventType type, String activity, int attempt, Duration after, TaskOutcome outcome,
                       Integer retriesLeft, Map<String, Value> payload) {
        return new Event(seq, type, "cap-1", activity, attempt, T0.plus(after).toString(), outcome, retriesLeft, payload, null);
    }

    static List<Event> events() {
        Instant read = T0.plus(Duration.ofMinutes(3));
        return List.of(
                event(1, EventType.EXTERNAL_TASK_RESULT, "score", 1, Duration.ofMinutes(1), TaskOutcome.FAILURE, 1,
                        Map.of("error", Value.of(ValueType.STRING, "scoring service timed out"))),
                event(2, EventType.EXTERNAL_TASK_RESULT, "score", 2, Duration.ofMinutes(2), TaskOutcome.SUCCESS, null,
                        Map.of("score", Value.of(ValueType.LONG, "700"))),
                event(3, EventType.CLOCK_READ, "decide", 1, Duration.ofMinutes(3), null, null, Map.of()),
                event(4, EventType.TIMER_SCHEDULED, "wait", 1, Duration.ofMinutes(3), null, null,
                        Map.of("timerId", Value.of(ValueType.STRING, "t1"),
                                "dueAt", Value.of(ValueType.INSTANT, read.plus(Duration.ofDays(2)).toString()))),
                event(5, EventType.TIMER_FIRED, "wait", 1, Duration.ofDays(2).plusMinutes(3), null, null,
                        Map.of("timerId", Value.of(ValueType.STRING, "t1"))));
    }

    static final Observation PLACEHOLDER = new Observation("PENDING", Map.of(), List.of(), List.of(), List.of(), List.of());

    static ReplayFixture fixture(String amount, List<Event> events, Observation observed) {
        return new ReplayFixture(ReplayFixture.SCHEMA_VERSION, "loan-approve-1", "synthetic loan approval", true,
                List.of(new ModelRef("loan.bpmn", ModelKind.BPMN, "loan", 3,
                                ReplayFixtures.sha256(BPMN.getBytes(StandardCharsets.UTF_8)), BPMN, null),
                        new ModelRef("loan.dmn", ModelKind.DMN, "loan-approval", 2, "a".repeat(64), null,
                                "package:sha256:" + "b".repeat(64) + "/loan.dmn")),
                new ReplayFixture.InitialState("loan", "LN-0001", Map.of("amount", Value.of(ValueType.DECIMAL, amount))),
                Map.of("sangam.history.level", "full"),
                new ReplayFixture.TenantContext("tenant-synthetic", "user-synthetic", "Asia/Kolkata"),
                new ReplayFixture.Versions(ENGINE, FEEL, "0.1.0"), T0.toString(), events, observed, List.of());
    }

    /**
     * The "capture": the fake engine run once, its observation (with the captured execution id cap-1, as a capture
     * records it) becoming the fixture's expected outcome.
     */
    static ReplayFixture captured() {
        ReplayFixture draft = fixture("1500.50", events(), PLACEHOLDER);
        Observation seen = ReplayRun.run(draft, new LoanDriver(), Mode.EXACT, List.of()).observed();
        Observation asCaptured = new Observation(seen.endState(), seen.outputs(), seen.transitions().stream()
                .map(t -> new Transition("cap-1", t.activityId(), t.kind())).toList(),
                seen.decisions(), seen.deadlines(), seen.sideEffects());
        return fixture("1500.50", events(), asCaptured);
    }

    @Test
    void repeatedExactReplayIsEquivalent() {
        ReplayFixture f = captured();
        List<Result> runs = ReplayRun.repeat(f, new LoanDriver(), 3);
        assertEquals(3, runs.size());
        assertTrue(ReplayRun.allEquivalent(runs), runs.toString());
        Result r = runs.get(0);
        assertTrue(r.coverage().unconsumedSeq().isEmpty());
        assertEquals(Map.of("r-1", "cap-1"), r.coverage().executionBinding());
        assertEquals(List.of(EventType.MESSAGE_DELIVERED), r.coverage().absentKinds());
        assertEquals(2, r.coverage().consumed().get(EventType.EXTERNAL_TASK_RESULT));
        assertTrue(r.report().appliedNormalisations().get(0).contains("binding"));
    }

    @Test
    void aChangedRuleGivesAMeaningfulDiff() {
        Result r = ReplayRun.run(captured(), new LoanDriver(800, ENGINE, false), Mode.EXACT, List.of());
        assertEquals(Status.DIFFERENT, r.status());
        List<ReplayComparison.Difference> d = r.report().differences();
        assertTrue(d.contains(new ReplayComparison.Difference(Area.OUTPUT, "approved", "BOOLEAN true", "BOOLEAN false")), d.toString());
        assertTrue(d.stream().anyMatch(x -> x.area() == Area.DECISION && x.actual().contains("rule-decline")), d.toString());
        assertTrue(d.stream().anyMatch(x -> x.area() == Area.SIDE_EFFECT && x.actual().equals(ReplayComparison.ABSENT)), d.toString());
    }

    @Test
    void aChangedInputGivesAMeaningfulDiffAndDecimalsAreExact() {
        ReplayFixture original = captured();
        ReplayFixture changed = fixture("1500.5", events(), original.observed());
        Result r = ReplayRun.run(changed, new LoanDriver(), Mode.EXACT, List.of());
        assertEquals(Status.DIFFERENT, r.status());
        assertTrue(r.report().differences().contains(new ReplayComparison.Difference(Area.OUTPUT, "amount",
                "DECIMAL 1500.50", "DECIMAL 1500.5")), r.report().differences().toString());
        assertTrue(r.report().differences().stream().anyMatch(x -> x.area() == Area.SIDE_EFFECT));
    }

    @Test
    void aMissingEventFailsClearly() {
        List<Event> withoutClockRead = new ArrayList<>(events());
        withoutClockRead.removeIf(e -> e.type() == EventType.CLOCK_READ);
        ReplayFixture f = fixture("1500.50", withoutClockRead, captured().observed());
        Result r = ReplayRun.run(f, new LoanDriver(), Mode.EXACT, List.of());
        assertEquals(Status.FAILED, r.status());
        assertEquals(Code.MISSING_EVENT, r.failure());
        assertNull(r.report());
        assertTrue(r.message().contains("CLOCK_READ"), r.message());
    }

    @Test
    void anUnexpectedExternalCallFailsClearly() {
        Result r = ReplayRun.run(captured(), new LoanDriver(600, ENGINE, true), Mode.EXACT, List.of());
        assertEquals(Status.FAILED, r.status());
        assertEquals(Code.UNEXPECTED_EXTERNAL_CALL, r.failure());
        assertEquals(List.of("payments: disburse 1500.50"), r.coverage().refusedCalls());
    }

    @Test
    void anIncompatibleVersionFailsExactAndIsReportedOnUpgrade() {
        LoanDriver newer = new LoanDriver(600, "operaton 2.3.0", false);
        Result exact = ReplayRun.run(captured(), newer, Mode.EXACT, List.of());
        assertEquals(Status.FAILED, exact.status());
        assertEquals(Code.INCOMPATIBLE_VERSION, exact.failure());
        Result upgrade = ReplayRun.run(captured(), newer, Mode.UPGRADE, List.of());
        assertEquals(Status.EQUIVALENT, upgrade.status());
        assertEquals("engine: operaton 2.2.0 -> operaton 2.3.0", upgrade.versions().get(0));
        assertEquals(Mode.UPGRADE, upgrade.mode());
    }

    @Test
    void upgradeNormalisationIsExplicitAndNeverHidesTheValues() {
        List<Rule> rules = List.of(new Rule(RuleKind.IGNORE_OUTPUT, "approved", "threshold raised on purpose"),
                new Rule(RuleKind.IGNORE_ACTIVITY, "decide", "new rule table"),
                new Rule(RuleKind.IGNORE_ACTIVITY, "notify", "no approval notice at the new threshold"));
        Result r = ReplayRun.run(captured(), new LoanDriver(800, ENGINE, false), Mode.UPGRADE, rules);
        assertEquals(Status.EQUIVALENT, r.status(), r.report().differences().toString());
        assertTrue(r.report().normalised().stream().anyMatch(n -> n.path().equals("approved")
                && n.expected().equals("BOOLEAN true") && n.actual().equals("BOOLEAN false")), r.report().normalised().toString());
        assertTrue(r.report().appliedNormalisations().contains("IGNORE_OUTPUT approved: threshold raised on purpose"));
        assertTrue(r.report().appliedNormalisations().contains("execution ids not compared (upgrade comparison)"));
        assertThrows(IllegalArgumentException.class, () -> ReplayRun.run(captured(), new LoanDriver(), Mode.EXACT, rules));
        assertThrows(IllegalArgumentException.class, () -> new Rule(RuleKind.IGNORE_OUTPUT, "approved", " "));
    }

    @Test
    void anUnconsumedInputIsADifferenceWhenExactAndAGapOnUpgrade() {
        List<Event> extra = new ArrayList<>(events());
        extra.add(new Event(6, EventType.RANDOM_VALUE, "cap-1", "wait", 1,
                T0.plus(Duration.ofDays(2).plusMinutes(4)).toString(), null, null,
                Map.of("value", Value.of(ValueType.LONG, "42")), null));
        ReplayFixture f = fixture("1500.50", extra, captured().observed());
        Result exact = ReplayRun.run(f, new LoanDriver(), Mode.EXACT, List.of());
        assertEquals(Status.DIFFERENT, exact.status());
        assertTrue(exact.report().differences().contains(new ReplayComparison.Difference(Area.INPUT, "seq 6",
                "consumed", "never asked for")));
        Result upgrade = ReplayRun.run(f, new LoanDriver(), Mode.UPGRADE, List.of());
        assertEquals(Status.EQUIVALENT, upgrade.status());
        assertEquals(List.of(6L), upgrade.coverage().unconsumedSeq());
    }

    @Test
    void theClockIsControlledAndNeverTheSystemClock() {
        ReplayFixture f = captured();
        RecordedInputs in = new RecordedInputs(f, Mode.EXACT);
        assertEquals(T0, in.currentTime());
        in.nextStimulus();
        assertEquals(T0.plus(Duration.ofMinutes(1)), in.currentTime());
        assertEquals("2026-03-29T00:33:00Z", f.observed().outputs().get("decidedAt").value());
        assertFalse(ReplayFixtures.events(f, EventType.TIMER_FIRED).isEmpty());
    }
}
