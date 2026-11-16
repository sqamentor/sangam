// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.replay.operaton;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.sangam.engine.SangamEngineSettings;
import org.sangam.modeller.replay.ReplayComparison.Area;
import org.sangam.modeller.replay.ReplayException.Code;
import org.sangam.modeller.replay.ReplayFixture;
import org.sangam.modeller.replay.ReplayFixture.Event;
import org.sangam.modeller.replay.ReplayFixture.EventType;
import org.sangam.modeller.replay.ReplayFixture.ModelKind;
import org.sangam.modeller.replay.ReplayFixture.Transition;
import org.sangam.modeller.replay.ReplayFixture.Value;
import org.sangam.modeller.replay.ReplayFixture.ValueType;
import org.sangam.modeller.replay.ReplayFixtures;
import org.sangam.modeller.replay.ReplayRun;
import org.sangam.modeller.replay.ReplayRun.Mode;
import org.sangam.modeller.replay.ReplayRun.Result;
import org.sangam.modeller.replay.ReplayRun.Status;
import org.sangam.modeller.replay.ReplayVerifier;
import org.sangam.modeller.replay.ReplayVerifier.Change;
import org.sangam.modeller.replay.ReplayVerifier.Outcome;
import org.sangam.modeller.replay.ReplayVerifier.Verification;
import org.sangam.modeller.replay.operaton.OperatonReplayCapture.Advance;
import org.sangam.modeller.replay.operaton.OperatonReplayCapture.Complete;
import org.sangam.modeller.replay.operaton.OperatonReplayCapture.Correlate;
import org.sangam.modeller.replay.operaton.OperatonReplayCapture.Fail;
import org.sangam.modeller.replay.operaton.OperatonReplayCapture.Lease;
import org.sangam.modeller.replay.operaton.OperatonReplayCapture.Model;
import org.sangam.modeller.replay.operaton.OperatonReplayCapture.Scenario;

/**
 * M-136 (decision 151) executed end to end on H2: a real Sangam engine captures a synthetic grant review (an external
 * task failed, timed out and completed; a message; a DMN verdict that reads the FEEL clock; a timer; a notification
 * task), the fixture is committed ({@value #FIXTURE}), a fresh engine replays it, and {@link ReplayVerifier} proves the
 * chain with a changed input and a changed rule. A changed model, a tampered fixture and an uncapturable model are
 * reported as a difference, a named failure or unsupported, never as a pass.
 *
 * <p>Regenerate the committed fixture from a fresh capture with {@code -Dsangam.replay.regenerate=true}. The
 * verification report is written to {@code target/replay-evidence/}. H2 only; Oracle and SQL Server are NOT_RUN.
 */
class OperatonReplayH2Test {

    static final Instant T0 = Instant.parse("2026-11-16T04:00:00Z");
    static final String FIXTURE = "grant-review.fixture.json";
    static final String BPMN = "grant-review.bpmn";
    static final String DMN = "grant-verdict.dmn";

    static ReplayEngines.Factory h2() {
        return ReplayEngines.scratch(() -> {
            JdbcDataSource ds = new JdbcDataSource();
            ds.setURL("jdbc:h2:mem:replay" + UUID.randomUUID().toString().replace("-", "")
                    + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
            return ds;
        }, "h2", () -> {
            SangamEngineSettings s = new SangamEngineSettings();
            s.setSystemAuditScope("system:platform"); // decision 144: required at engine start
            return s;
        }, false);
    }

    static String resource(String name) {
        try (InputStream in = OperatonReplayH2Test.class.getResourceAsStream(name)) {
            assertNotNull(in, name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static Value v(ValueType t, String s) {
        return Value.of(t, s);
    }

    static Scenario scenario() {
        return new Scenario("grant-review-h2-1", "Synthetic grant review: scoring fails, times out and succeeds; documents "
                + "arrive; the verdict reads the FEEL clock; a cooling-off timer; the applicant is notified",
                List.of(new Model(BPMN, ModelKind.BPMN, "grantReview", resource(BPMN)),
                        new Model(DMN, ModelKind.DMN, "grantVerdict", resource(DMN))),
                new ReplayFixture.InitialState("grantReview", "SYN-GR-0001", Map.of(
                        "applicant", v(ValueType.STRING, "SYN-APPLICANT-0001"),
                        "requested", v(ValueType.DECIMAL, "125000.50"))),
                "t-replay", "synthetic-officer", T0,
                List.of(new Advance(T0.plus(Duration.ofMinutes(1))),
                        new Fail("score", "scoring service unavailable", 2),
                        new Advance(T0.plus(Duration.ofMinutes(2))),
                        new Lease("score", Duration.ofSeconds(30)),
                        new Advance(T0.plus(Duration.ofMinutes(3))),
                        new Complete("score", Map.of("score", v(ValueType.LONG, "700"), "rate", v(ValueType.DECIMAL, "7.25"))),
                        new Advance(T0.plus(Duration.ofMinutes(10))),
                        new Correlate("documentsReceived", "SYN-GR-0001", Map.of("documentsComplete",
                                v(ValueType.BOOLEAN, "true"))),
                        new Advance(T0.plus(Duration.ofHours(2))),
                        new Complete("notify", Map.of("notified", v(ValueType.BOOLEAN, "true")))));
    }

    static ReplayFixture committed() {
        return ReplayFixtures.read(resource(FIXTURE).getBytes(StandardCharsets.UTF_8));
    }

    static OperatonReplayDriver driver() {
        return new OperatonReplayDriver(h2());
    }

    // ------------------------------------------------------------------ the executed chain

    @Test
    void theCommittedFixtureIsAFreshCaptureOfTheScenario() throws IOException {
        ReplayFixture fresh = new OperatonReplayCapture(scenario(), h2()).capture();
        assertTrue(fresh.unsupported().isEmpty(), fresh.unsupported().toString());
        ReplayFixture committed;
        if (Boolean.getBoolean("sangam.replay.regenerate")) {
            Path out = Path.of(System.getProperty("basedir", "."), "src/test/resources/org/sangam/modeller/replay/operaton",
                    FIXTURE);
            Files.write(out, ReplayFixtures.write(fresh));
            committed = ReplayFixtures.read(Files.readAllBytes(out));
        } else {
            committed = committed();
        }
        assertEquals(List.of("EXTERNAL_TASK_RESULT:FAILURE@score#1", "EXTERNAL_TASK_RESULT:TIMEOUT@score#2",
                "EXTERNAL_TASK_RESULT:SUCCESS@score#3", "MESSAGE_DELIVERED@documents#1", "CLOCK_READ@decide#1",
                "TIMER_SCHEDULED@cooling#1", "TIMER_FIRED@cooling#1", "EXTERNAL_TASK_RESULT:SUCCESS@notify#1"),
                fresh.events().stream().map(e -> e.type() + (e.outcome() == null ? "" : ":" + e.outcome()) + "@"
                        + e.activityId() + "#" + e.attempt()).toList(), "the engine's own inputs, in engine order");
        assertEquals("COMPLETED", fresh.observed().endState());
        assertEquals(normalised(committed), normalised(fresh),
                "the committed fixture is this scenario's capture (generated ids aside); regenerate with "
                        + "-Dsangam.replay.regenerate=true after a deliberate change");
    }

    @Test
    void theCommittedFixtureReplaysExactlyOnAFreshEngineEveryTime() {
        ReplayFixture f = committed();
        List<Result> runs = ReplayRun.repeat(f, driver(), 2);
        for (Result r : runs) {
            assertEquals(Status.EQUIVALENT, r.status(), () -> r.message() + " " + (r.report() == null ? "" : r.report()));
            assertTrue(r.coverage().unconsumedSeq().isEmpty(), r.coverage().toString());
            assertTrue(r.coverage().absentKinds().isEmpty(), "all five required capture kinds: " + r.coverage());
            assertTrue(r.coverage().refusedCalls().isEmpty());
        }
        assertTrue(ReplayRun.allEquivalent(runs));
    }

    @Test
    void theVerifierProvesCaptureFixtureReplayAndChangedInputs() throws IOException {
        OperatonReplayDriver driver = driver();
        Verification v = ReplayVerifier.verify(new ReplayVerifier.Scenario("grant-review on H2",
                new OperatonReplayCapture(scenario(), h2()), driver, 2, List.of(
                        Change.input("score 500 instead of 700: the verdict refers the application to review",
                                f -> withEvent(f, 3, e -> payload(e, "score", v(ValueType.LONG, "500"))),
                                EnumSet.of(Area.DECISION, Area.OUTPUT, Area.TRANSITION, Area.SIDE_EFFECT)),
                        Change.rule("verdict threshold 800 instead of 600 (changed DMN)",
                                driver.withChangedModel(DMN, resource(DMN).replace("&gt;= 600", "&gt;= 800")),
                                EnumSet.of(Area.DECISION, Area.OUTPUT, Area.TRANSITION, Area.SIDE_EFFECT))), false));
        Path report = buildDirectory().resolve("replay-evidence").resolve("grant-review-h2-verification.txt");
        Files.createDirectories(report.getParent());
        Files.writeString(report, report(v));
        assertEquals(Outcome.VERIFIED, v.outcome(), () -> v.failedStep() + ": " + v.message());
        assertTrue(v.gaps().isEmpty(), v.gaps().toString());
        assertEquals(2, v.changeProofs().size());
        v.changeProofs().forEach(p -> assertEquals(Status.DIFFERENT, p.status(), p.description()));
    }

    // ------------------------------------------------------------------ divergence is never a pass

    @Test
    void aChangedModelWhoseReplayDivergesIsReportedAsADivergence() {
        OperatonReplayDriver changed = driver().withChangedModel(BPMN, resource(BPMN).replace("PT1H", "PT3H"));
        Result r = ReplayRun.run(committed(), changed, Mode.EXACT, List.of());
        assertEquals(Status.DIFFERENT, r.status(), () -> String.valueOf(r.message()));
        Set<Area> areas = EnumSet.noneOf(Area.class);
        r.report().differences().forEach(d -> areas.add(d.area()));
        assertTrue(areas.containsAll(EnumSet.of(Area.DEADLINE, Area.TRANSITION, Area.END_STATE, Area.INPUT)), areas.toString());
        assertEquals(List.of(7L, 8L), r.coverage().unconsumedSeq(), "the timer is not due at its recorded firing");

        Verification v = ReplayVerifier.verify(new ReplayVerifier.Scenario("grant-review, changed timer",
                new OperatonReplayCapture(scenario(), h2()), changed, 2, List.of(Change.rule("unused",
                changed, EnumSet.of(Area.DEADLINE))), false));
        assertEquals(Outcome.FAILED, v.outcome());
        assertEquals(ReplayVerifier.Step.REPLAY, v.failedStep(), v.message());
    }

    @Test
    void aRecordedStimulusTheReplayNeverTakesStaysUnconsumed() {
        ReplayFixture f = committed();
        Event last = f.events().get(f.events().size() - 1);
        Event message = f.events().stream().filter(e -> e.type() == EventType.MESSAGE_DELIVERED).findFirst().orElseThrow();
        List<Event> events = new ArrayList<>(f.events());
        events.add(new Event(last.seq() + 1, EventType.MESSAGE_DELIVERED, message.executionId(), message.activityId(), 1,
                last.at(), null, null, message.payload(), null));
        Result r = ReplayRun.run(copy(f, events, f.configuration(), f.models()), driver(), Mode.EXACT, List.of());
        assertEquals(Status.DIFFERENT, r.status(), () -> String.valueOf(r.message()));
        assertEquals(List.of(last.seq() + 1), r.coverage().unconsumedSeq(), "the completed instance never waits for it");
        assertEquals(Area.INPUT, r.report().differences().get(0).area());
    }

    @Test
    void aClockReadAtAnotherTimeThanRecordedIsRefused() {
        ReplayFixture f = committed();
        UnaryOperator<Event> later = e -> new Event(e.seq(), e.type(), e.executionId(), e.activityId(), e.attempt(),
                Instant.parse(e.at()).plusSeconds(1).toString(), e.outcome(), e.retriesLeft(), e.payload(),
                e.activityInstanceId());
        ReplayFixture tampered = withEvent(withEvent(f, 5, later), 6, later);
        Result r = ReplayRun.run(tampered, driver(), Mode.EXACT, List.of());
        assertEquals(Status.FAILED, r.status());
        assertEquals(Code.MISSING_EVENT, r.failure());
        assertTrue(r.message().contains("read the clock"), r.message());
    }

    @Test
    void anInputTakenOnAnotherAttemptThanRecordedIsRefused() {
        ReplayFixture tampered = withEvent(committed(), 3, e -> new Event(e.seq(), e.type(), e.executionId(),
                e.activityId(), 4, e.at(), e.outcome(), e.retriesLeft(), e.payload(), e.activityInstanceId()));
        Result r = ReplayRun.run(tampered, driver(), Mode.EXACT, List.of());
        assertEquals(Status.FAILED, r.status());
        assertEquals(Code.MISSING_EVENT, r.failure());
        assertTrue(r.message().contains("attempt 4"), r.message());
    }

    @Test
    void anotherEngineConfigurationOrDefinitionVersionIsRefused() {
        ReplayFixture f = committed();
        Map<String, String> config = new HashMap<>(f.configuration());
        config.put("engine.maxLease", "PT1H");
        Result r = ReplayRun.run(copy(f, f.events(), config, f.models()), driver(), Mode.EXACT, List.of());
        assertEquals(Status.FAILED, r.status());
        assertEquals(Code.INCOMPATIBLE_VERSION, r.failure());
        assertTrue(r.message().contains("configuration"), r.message());

        List<ReplayFixture.ModelRef> models = new ArrayList<>();
        for (ReplayFixture.ModelRef m : f.models()) {
            models.add(m.kind() == ModelKind.BPMN ? new ReplayFixture.ModelRef(m.resource(), m.kind(), m.definitionKey(),
                    2, m.sha256(), m.content(), m.reference()) : m);
        }
        Result v = ReplayRun.run(copy(f, f.events(), f.configuration(), models), driver(), Mode.EXACT, List.of());
        assertEquals(Status.FAILED, v.status());
        assertEquals(Code.INCOMPATIBLE_VERSION, v.failure());
        assertTrue(v.message().contains("version 2"), v.message());
    }

    // ------------------------------------------------------------------ unsupported, never approximate

    @Test
    void aModelWithAScriptIsUnsupportedAndNeverRun() {
        Scenario s = scenario();
        String scripted = resource(BPMN).replace("<conditionExpression xsi:type=\"tFormalExpression\">${verdict != 'APPROVE'}",
                "<conditionExpression xsi:type=\"tFormalExpression\" language=\"javascript\">verdict != 'APPROVE'");
        Scenario withScript = new Scenario(s.fixtureId(), s.description(), List.of(new Model(BPMN, ModelKind.BPMN,
                "grantReview", scripted), s.models().get(1)), s.initialState(), s.tenantId(), s.actorId(), s.startedAt(),
                s.steps());
        Verification v = ReplayVerifier.verify(new ReplayVerifier.Scenario("scripted", new OperatonReplayCapture(withScript,
                h2()), driver(), 2, List.of(), false));
        assertEquals(Outcome.UNSUPPORTED, v.outcome(), v.message());
        assertTrue(v.unsupported().get(0).contains("script"), v.unsupported().toString());
    }

    @Test
    void anInputWithoutAnExecutionMakesTheCaptureUnsupported() {
        String kick = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" id="kickDefinitions"
                             targetNamespace="urn:sangam:replay:kick">
                  <message id="kickMessage" name="kick"/>
                  <process id="kick" isExecutable="true">
                    <startEvent id="kickStart"><messageEventDefinition messageRef="kickMessage"/></startEvent>
                    <sequenceFlow id="k1" sourceRef="kickStart" targetRef="kickWait"/>
                    <userTask id="kickWait"/>
                  </process>
                </definitions>
                """.strip();
        Scenario s = scenario();
        List<Model> models = new ArrayList<>(s.models());
        models.add(new Model("kick.bpmn", ModelKind.BPMN, "kick", kick));
        Scenario withStart = new Scenario(s.fixtureId(), s.description(), models, s.initialState(), s.tenantId(),
                s.actorId(), s.startedAt(), List.of(new Correlate("kick", "SYN-KICK-1", Map.of())));
        ReplayFixture f = new OperatonReplayCapture(withStart, h2()).capture();
        assertEquals(1, f.unsupported().size(), f.unsupported().toString());
        assertTrue(f.unsupported().get(0).contains("has no execution"), f.unsupported().toString());
    }

    // ------------------------------------------------------------------ helpers

    static ReplayFixture copy(ReplayFixture f, List<Event> events, Map<String, String> configuration,
                              List<ReplayFixture.ModelRef> models) {
        return new ReplayFixture(f.schemaVersion(), f.fixtureId(), f.scenario(), f.synthetic(), models, f.initialState(),
                configuration, f.tenant(), f.versions(), f.startedAt(), events, f.observed(), f.unsupported());
    }

    static ReplayFixture withEvent(ReplayFixture f, long seq, UnaryOperator<Event> change) {
        List<Event> events = new ArrayList<>();
        for (Event e : f.events()) {
            events.add(e.seq() == seq ? change.apply(e) : e);
        }
        return copy(f, events, f.configuration(), f.models());
    }

    static Event payload(Event e, String name, Value value) {
        Map<String, Value> p = new LinkedHashMap<>(e.payload());
        p.put(name, value);
        return new Event(e.seq(), e.type(), e.executionId(), e.activityId(), e.attempt(), e.at(), e.outcome(),
                e.retriesLeft(), p, e.activityInstanceId());
    }

    /** The fixture with every generated id (executions, timers, activity instances) renamed by first appearance. */
    static ReplayFixture normalised(ReplayFixture f) {
        Map<String, String> ids = new LinkedHashMap<>();
        UnaryOperator<String> id = s -> s == null ? null : ids.computeIfAbsent(s, k -> "id-" + (ids.size() + 1));
        List<Event> events = new ArrayList<>();
        for (Event e : f.events()) {
            Map<String, Value> p = new LinkedHashMap<>(e.payload());
            if (p.containsKey("timerId")) {
                p.put("timerId", v(ValueType.STRING, id.apply(p.get("timerId").value())));
            }
            events.add(new Event(e.seq(), e.type(), id.apply(e.executionId()), e.activityId(), e.attempt(), e.at(),
                    e.outcome(), e.retriesLeft(), p, null));
        }
        List<Transition> transitions = new ArrayList<>();
        for (Transition t : f.observed().transitions()) {
            transitions.add(new Transition(id.apply(t.executionId()), t.activityId(), t.kind()));
        }
        ReplayFixture.Observation o = f.observed();
        return new ReplayFixture(f.schemaVersion(), f.fixtureId(), f.scenario(), f.synthetic(), f.models(),
                f.initialState(), f.configuration(), f.tenant(), new ReplayFixture.Versions(f.versions().engine(),
                f.versions().evaluator(), null), f.startedAt(), events, new ReplayFixture.Observation(o.endState(),
                o.outputs(), transitions, o.decisions(), o.deadlines(), o.sideEffects()), f.unsupported());
    }

    /** The module's build directory (the parent of test-classes), whatever {@code sangam.target} names it. */
    static Path buildDirectory() {
        try {
            return Path.of(OperatonReplayH2Test.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .getParent();
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    static String report(Verification v) {
        StringBuilder sb = new StringBuilder();
        sb.append("M-136 / decision 151 capture-to-replay verification (OperatonReplayH2Test, H2)\n");
        sb.append("scenario: ").append(v.scenario()).append('\n');
        sb.append("outcome: ").append(v.outcome()).append(v.failedStep() == null ? "" : " at " + v.failedStep())
                .append(v.message() == null ? "" : ": " + v.message()).append('\n');
        sb.append("fixture sha256: ").append(v.fixtureSha256()).append('\n');
        v.steps().forEach(s -> sb.append("step ").append(s.step()).append(s.passed() ? " PASSED: " : " FAILED: ")
                .append(s.detail()).append('\n'));
        int i = 0;
        for (Result r : v.replays()) {
            sb.append("replay ").append(++i).append(": ").append(r.status()).append(' ').append(r.versions())
                    .append("; recorded ").append(r.coverage().recorded()).append(", consumed ")
                    .append(r.coverage().consumed()).append(", unconsumed ").append(r.coverage().unconsumedSeq())
                    .append('\n');
        }
        for (ReplayVerifier.ChangeProof p : v.changeProofs()) {
            sb.append("change: ").append(p.description()).append(" -> ").append(p.status()).append(" in ")
                    .append(new java.util.TreeSet<>(p.areas())).append(p.failure() == null ? "" : " (" + p.failure() + ")")
                    .append('\n');
            p.differences().forEach(d -> sb.append("  ").append(d.area()).append(' ').append(d.path()).append(": ")
                    .append(d.expected()).append(" -> ").append(d.actual()).append('\n'));
        }
        sb.append("gaps (required capture kinds not exercised): ").append(v.gaps()).append('\n');
        return sb.toString();
    }
}
