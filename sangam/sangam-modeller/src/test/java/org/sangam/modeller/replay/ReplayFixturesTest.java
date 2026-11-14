// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.replay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.sangam.modeller.replay.ReplayException.Code;
import org.sangam.modeller.replay.ReplayFixture.Event;
import org.sangam.modeller.replay.ReplayFixture.EventType;
import org.sangam.modeller.replay.ReplayFixture.ModelKind;
import org.sangam.modeller.replay.ReplayFixture.ModelRef;
import org.sangam.modeller.replay.ReplayFixture.Value;
import org.sangam.modeller.replay.ReplayFixture.ValueType;

/** M-136 (decision 151) fixture schema sangam.replay-fixture/1: JSON round trip and every refusal of an untrustworthy fixture. */
class ReplayFixturesTest {

    static void refused(Code code, Executable e) {
        ReplayException x = assertThrows(ReplayException.class, e);
        assertEquals(code, x.code(), x.getMessage());
    }

    static ReplayFixture with(ReplayFixture f, UnaryOperator<List<Event>> events, Map<String, Value> vars,
                              Map<String, String> config, List<ModelRef> models, String zone) {
        return new ReplayFixture(f.schemaVersion(), f.fixtureId(), f.scenario(), f.synthetic(),
                models == null ? f.models() : models,
                vars == null ? f.initialState() : new ReplayFixture.InitialState("loan", null, vars),
                config == null ? f.configuration() : config,
                zone == null ? f.tenant() : new ReplayFixture.TenantContext("tenant-synthetic", null, zone),
                f.versions(), f.startedAt(), events.apply(new ArrayList<>(f.events())), f.observed(), f.unsupported());
    }

    static ReplayFixture events(UnaryOperator<List<Event>> change) {
        return with(ReplayRunTest.captured(), change, null, null, null, null);
    }

    static Map<String, Value> var(Value v) {
        return Map.of("x", v);
    }

    @Test
    void jsonRoundTripKeepsEveryValueExactly() {
        ReplayFixture f = ReplayRunTest.captured();
        byte[] json = ReplayFixtures.write(f);
        ReplayFixture back = ReplayFixtures.read(json);
        assertEquals(f, back);
        assertTrue(new String(json, StandardCharsets.UTF_8).contains("\"1500.50\""), "decimals are text, never JSON numbers");
    }

    @Test
    void activityInstanceIdIsKeptForDiagnosticsAndNeverCompared() {
        ReplayFixture plain = ReplayRunTest.captured();
        ReplayFixture tagged = events(l -> {
            for (int i = 0; i < l.size(); i++) {
                Event e = l.get(i);
                l.set(i, new Event(e.seq(), e.type(), e.executionId(), e.activityId(), e.attempt(), e.at(), e.outcome(),
                        e.retriesLeft(), e.payload(), "ai-" + i));
            }
            return l;
        });
        assertEquals(tagged, ReplayFixtures.read(ReplayFixtures.write(tagged)), "the optional member round-trips");
        assertEquals("ai-0", ReplayFixtures.read(ReplayFixtures.write(tagged)).events().get(0).activityInstanceId());
        String json = new String(ReplayFixtures.write(plain), StandardCharsets.UTF_8);
        String withoutMember = json.replaceAll(",\\s*\"activityInstanceId\"\\s*:\\s*null", "");
        assertTrue(!withoutMember.contains("activityInstanceId"), "a fixture may omit the member");
        assertEquals(plain, ReplayFixtures.read(withoutMember.getBytes(StandardCharsets.UTF_8)), "absent means null");
        ReplayRun.Result a = ReplayRun.run(plain, new ReplayRunTest.LoanDriver(), ReplayRun.Mode.EXACT, List.of());
        ReplayRun.Result b = ReplayRun.run(tagged, new ReplayRunTest.LoanDriver(), ReplayRun.Mode.EXACT, List.of());
        assertTrue(ReplayRun.allEquivalent(List.of(a, b)), "instance ids differ between runs and are never compared");
    }

    @Test
    void theSchemaResourceNamesTheSameVersion() throws IOException {
        try (InputStream in = ReplayFixture.class.getResourceAsStream("replay-fixture-1.schema.json")) {
            assertNotNull(in);
            JsonNode schema = new ObjectMapper().readTree(in);
            assertEquals(ReplayFixture.SCHEMA_VERSION, schema.at("/properties/schemaVersion/const").asText());
        }
    }

    @Test
    void versionUnknownMembersAndUnsupportedScenarios() {
        String json = new String(ReplayFixtures.write(ReplayRunTest.captured()), StandardCharsets.UTF_8);
        refused(Code.INCOMPATIBLE_VERSION, () -> ReplayFixtures.read(json.replace("sangam.replay-fixture/1",
                "sangam.replay-fixture/2").getBytes(StandardCharsets.UTF_8)));
        refused(Code.INCOMPLETE_FIXTURE, () -> ReplayFixtures.read(json.replaceFirst("\\{", "{\"extra\":1,")
                .getBytes(StandardCharsets.UTF_8)));
        refused(Code.INCOMPLETE_FIXTURE, () -> ReplayFixtures.read("not json".getBytes(StandardCharsets.UTF_8)));
        ReplayFixture f = ReplayRunTest.captured();
        ReplayFixture unsupported = new ReplayFixture(f.schemaVersion(), f.fixtureId(), f.scenario(), true, f.models(),
                f.initialState(), f.configuration(), f.tenant(), f.versions(), f.startedAt(), f.events(), f.observed(),
                List.of("parallel gateway join order not captured"));
        refused(Code.UNSUPPORTED_SCENARIO, () -> ReplayFixtures.validate(unsupported));
    }

    @Test
    void modelsNeedAPinnedVersionAndAMatchingHash() {
        ReplayFixture f = ReplayRunTest.captured();
        ModelRef bpmn = f.models().get(0);
        refused(Code.HASH_MISMATCH, () -> ReplayFixtures.validate(with(f, l -> l, null, null, List.of(
                new ModelRef(bpmn.resource(), ModelKind.BPMN, "loan", 3, bpmn.sha256(), bpmn.content() + " ", null)), null)));
        refused(Code.INCOMPLETE_FIXTURE, () -> ReplayFixtures.validate(with(f, l -> l, null, null, List.of(
                new ModelRef(bpmn.resource(), ModelKind.BPMN, "loan", null, bpmn.sha256(), bpmn.content(), null)), null)));
        refused(Code.INCOMPLETE_FIXTURE, () -> ReplayFixtures.validate(with(f, l -> l, null, null, List.of(
                new ModelRef(bpmn.resource(), ModelKind.BPMN, "loan", 3, bpmn.sha256(), bpmn.content(), "ref")), null)));
        refused(Code.INCOMPLETE_FIXTURE, () -> ReplayFixtures.validate(with(f, l -> l, null, null, List.of(), null)));
    }

    @Test
    void eventsMustBeOrderedTimedAndComplete() {
        refused(Code.INCOMPLETE_FIXTURE, () -> ReplayFixtures.validate(events(l -> {
            l.set(1, new Event(1, l.get(1).type(), "cap-1", "score", 2, l.get(1).at(), l.get(1).outcome(), null, l.get(1).payload(), null));
            return l;
        })));
        refused(Code.INCOMPLETE_FIXTURE, () -> ReplayFixtures.validate(events(l -> {
            Event e = l.get(4);
            l.set(4, new Event(e.seq(), e.type(), "cap-1", e.activityId(), 1, "2026-03-29T00:00:00Z", null, null, e.payload(), null));
            return l;
        })));
        refused(Code.INCOMPLETE_FIXTURE, () -> ReplayFixtures.validate(events(l -> {
            Event e = l.get(1);
            l.set(1, new Event(e.seq(), e.type(), "cap-1", "score", 2, e.at(), null, null, e.payload(), null));
            return l;
        })));
        refused(Code.INCOMPLETE_FIXTURE, () -> ReplayFixtures.validate(events(l -> {
            Event e = l.get(3);
            l.set(3, new Event(e.seq(), EventType.TIMER_SCHEDULED, "cap-1", "wait", 1, e.at(), null, null,
                    Map.of("timerId", Value.of(ValueType.STRING, "t1")), null));
            return l;
        })));
        refused(Code.INCOMPLETE_FIXTURE, () -> ReplayFixtures.validate(events(l -> {
            Event e = l.get(2);
            l.set(2, new Event(e.seq(), e.type(), "cap-1", "decide", 0, e.at(), null, null, e.payload(), null));
            return l;
        })));
    }

    @Test
    void valuesKeepExactDecimalsAndExplicitTimeSemantics() {
        ReplayFixture f = ReplayRunTest.captured();
        for (Value bad : List.of(
                Value.of(ValueType.DECIMAL, "1000E0"),
                Value.of(ValueType.DECIMAL, "+1.0"),
                Value.of(ValueType.DECIMAL, "1e+3"),
                Value.of(ValueType.DECIMAL, "1".repeat(1001)),
                Value.of(ValueType.DECIMAL, "12.5x"),
                Value.of(ValueType.INSTANT, "2026-03-29T06:00:00+05:30"),
                Value.of(ValueType.LOCAL_DATE_TIME, "2026-03-29T02:30:00"),
                new Value(ValueType.LOCAL_DATE_TIME, "2026-03-29T02:30:00", "+01:00"),
                new Value(ValueType.LOCAL_DATE, "2026-03-29", "UTC+05:30"),
                new Value(ValueType.STRING, "x", "Europe/Berlin"),
                new Value(ValueType.NULL, "x", null),
                Value.of(ValueType.BOOLEAN, "yes"),
                Value.of(ValueType.JSON, "{broken"),
                Value.of(ValueType.BYTES_SHA256, "ABC"))) {
            refused(Code.INCOMPLETE_FIXTURE, () -> ReplayFixtures.validate(with(f, l -> l, var(bad), null, null, null)));
        }
        ReplayFixtures.validate(with(f, l -> l, var(new Value(ValueType.LOCAL_DATE_TIME, "2026-03-29T02:30:00",
                "Europe/Berlin")), null, null, null));
        ReplayFixtures.validate(with(f, l -> l, var(Value.of(ValueType.DECIMAL, "0.10")), null, null, "UTC"));
        for (String canonical : List.of("1E+3", "-0.000001", "1.5E-7", "1".repeat(1000))) {
            ReplayFixtures.validate(with(f, l -> l, var(Value.of(ValueType.DECIMAL, canonical)), null, null, "UTC"));
        }
        refused(Code.INCOMPLETE_FIXTURE, () -> ReplayFixtures.validate(with(f, l -> l, null, null, null, "+05:30")));
        refused(Code.INCOMPLETE_FIXTURE, () -> ReplayFixtures.validate(with(f, l -> l, null, null, null, "Etc/GMT-5")));
    }

    @Test
    void fixturesHoldNoCredentials() {
        ReplayFixture f = ReplayRunTest.captured();
        for (String key : List.of("connector.api-key", "db.password", "oauth.client.secret", "bearer.token")) {
            refused(Code.FORBIDDEN_CONTENT, () -> ReplayFixtures.validate(with(f, l -> l, null, Map.of(key, "x"), null, null)));
        }
    }
}
