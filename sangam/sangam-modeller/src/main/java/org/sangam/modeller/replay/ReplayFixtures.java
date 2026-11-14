// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.replay;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.sangam.modeller.replay.ReplayException.Code;
import org.sangam.modeller.replay.ReplayFixture.Event;
import org.sangam.modeller.replay.ReplayFixture.EventType;
import org.sangam.modeller.replay.ReplayFixture.ModelRef;
import org.sangam.modeller.replay.ReplayFixture.Observation;
import org.sangam.modeller.replay.ReplayFixture.Value;

/**
 * Reads, writes and checks {@link ReplayFixture}s (decision 151). Reading is strict: unknown members are refused, so a
 * fixture of a newer schema is never half-read. {@link #validate(ReplayFixture)} refuses a fixture that a replay could
 * not trust: wrong schema version, missing parts, unsupported scenario, a model whose content does not match its hash,
 * events out of order or with malformed values, or a credential-like configuration key.
 */
public final class ReplayFixtures {

    /** Longest DECIMAL text accepted (engine decimal text v1). */
    static final int MAX_DECIMAL_TEXT = 1000;

    /**
     * Map members are written in key order, so one fixture always gives one document (the immutable maps of the
     * records iterate in an order that depends on insertion and the JVM, which must not reach the bytes or the hash).
     */
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern CREDENTIAL_KEY = Pattern.compile(
            ".*(password|passwd|secret|token|credential|private[-_.]?key|api[-_.]?key).*");
    /** Offset-style ids ({@code UTC+05:30}, {@code GMT-3}, {@code Etc/GMT+5}); {@code UTC} itself is a named zone. */
    private static final Pattern FIXED_OFFSET_ID = Pattern.compile("(?i)(UTC|GMT|UT|Etc/GMT)[+-].*");

    private ReplayFixtures() {
    }

    /** Parses a fixture (JSON, UTF-8) and validates it. */
    public static ReplayFixture read(byte[] json) {
        ReplayFixture f;
        try {
            f = JSON.readValue(json, ReplayFixture.class);
        } catch (IOException e) {
            throw new ReplayException(Code.INCOMPLETE_FIXTURE, "not a readable fixture: " + e.getMessage());
        }
        if (f == null) {
            throw new ReplayException(Code.INCOMPLETE_FIXTURE, "empty document");
        }
        validate(f);
        return f;
    }

    /** The fixture as JSON (UTF-8), for the capture side; the fixture is validated first. */
    public static byte[] write(ReplayFixture f) {
        validate(f);
        try {
            return JSON.writeValueAsBytes(f);
        } catch (IOException e) {
            throw new IllegalStateException("cannot write the fixture", e);
        }
    }

    /** Refuses a fixture a replay cannot trust; see the class comment. */
    public static void validate(ReplayFixture f) {
        if (!ReplayFixture.SCHEMA_VERSION.equals(f.schemaVersion())) {
            throw new ReplayException(Code.INCOMPATIBLE_VERSION, "schemaVersion " + f.schemaVersion() + " is not "
                    + ReplayFixture.SCHEMA_VERSION);
        }
        if (!f.unsupported().isEmpty()) {
            throw new ReplayException(Code.UNSUPPORTED_SCENARIO, "the capture could not record or control: "
                    + String.join(", ", f.unsupported()));
        }
        require(f.fixtureId(), "fixtureId");
        if (f.models().isEmpty()) {
            throw incomplete("models is empty");
        }
        for (ModelRef m : f.models()) {
            model(m);
        }
        if (f.initialState() == null) {
            throw incomplete("initialState is missing");
        }
        require(f.initialState().definitionKey(), "initialState.definitionKey");
        values(f.initialState().variables(), "initialState.variables");
        if (f.tenant() == null) {
            throw incomplete("tenant is missing");
        }
        require(f.tenant().tenantId(), "tenant.tenantId");
        zone(f.tenant().zoneId(), "tenant.zoneId");
        if (f.versions() == null) {
            throw incomplete("versions is missing");
        }
        require(f.versions().engine(), "versions.engine");
        require(f.versions().evaluator(), "versions.evaluator");
        for (String key : f.configuration().keySet()) {
            if (CREDENTIAL_KEY.matcher(key.toLowerCase(Locale.ROOT)).matches()) {
                throw new ReplayException(Code.FORBIDDEN_CONTENT, "configuration key " + key
                        + " looks like a credential; fixtures hold no credentials");
            }
        }
        Instant clock = instant(f.startedAt(), "startedAt");
        long seq = Long.MIN_VALUE;
        for (Event e : f.events()) {
            String where = "event " + e.seq();
            if (e.seq() <= seq) {
                throw incomplete(where + ": seq must increase strictly (previous " + seq + ")");
            }
            seq = e.seq();
            event(e, where);
            Instant at = instant(e.at(), where + ".at");
            if (at.isBefore(clock)) {
                throw incomplete(where + ": at " + e.at() + " is before the previous event or startedAt"
                        + " (the controlled clock never goes back)");
            }
            clock = at;
        }
        if (f.observed() == null) {
            throw incomplete("observed is missing");
        }
        observation(f.observed());
    }

    private static void model(ModelRef m) {
        require(m.resource(), "models.resource");
        String where = "model " + m.resource();
        if (m.kind() == null) {
            throw incomplete(where + ": kind is missing");
        }
        require(m.definitionKey(), where + ".definitionKey");
        if (m.version() == null) {
            throw incomplete(where + ": version is missing (the replay never resolves the latest definition)");
        }
        if (m.sha256() == null || !SHA256.matcher(m.sha256()).matches()) {
            throw incomplete(where + ": sha256 must be 64 lower-case hex digits");
        }
        boolean inline = m.content() != null;
        boolean ref = m.reference() != null && !m.reference().isBlank();
        if (inline == ref) {
            throw incomplete(where + ": exactly one of content and reference is required");
        }
        if (inline && !sha256(m.content().getBytes(StandardCharsets.UTF_8)).equals(m.sha256())) {
            throw new ReplayException(Code.HASH_MISMATCH, where + ": content does not match sha256 " + m.sha256());
        }
    }

    private static void event(Event e, String where) {
        if (e.type() == null) {
            throw incomplete(where + ": type is missing");
        }
        require(e.executionId(), where + ".executionId");
        if (e.attempt() < 1) {
            throw incomplete(where + ": attempt starts at 1");
        }
        boolean result = e.type() == EventType.EXTERNAL_TASK_RESULT;
        if (result != (e.outcome() != null)) {
            throw incomplete(where + ": outcome is required for EXTERNAL_TASK_RESULT and only there");
        }
        if (!result && e.retriesLeft() != null) {
            throw incomplete(where + ": retriesLeft belongs to EXTERNAL_TASK_RESULT only");
        }
        values(e.payload(), where + ".payload");
        switch (e.type()) {
            case TIMER_SCHEDULED -> {
                payload(e, "timerId", where);
                Value due = payload(e, "dueAt", where);
                if (due.type() != ReplayFixture.ValueType.INSTANT) {
                    throw incomplete(where + ": payload dueAt must be an INSTANT");
                }
            }
            case TIMER_FIRED -> payload(e, "timerId", where);
            case MESSAGE_DELIVERED -> payload(e, "messageName", where);
            case RANDOM_VALUE, GENERATED_ID -> payload(e, "value", where);
            case EXTERNAL_LOOKUP -> {
                payload(e, "request", where);
                payload(e, "response", where);
            }
            case CLOCK_READ, EXTERNAL_TASK_RESULT -> {
                // CLOCK_READ: the value read is the event's at; a task result's payload is its result or error
            }
        }
    }

    private static Value payload(Event e, String name, String where) {
        Value v = e.payload().get(name);
        if (v == null) {
            throw incomplete(where + ": payload " + name + " is required for " + e.type());
        }
        return v;
    }

    private static void observation(Observation o) {
        require(o.endState(), "observed.endState");
        values(o.outputs(), "observed.outputs");
        for (ReplayFixture.Decision d : o.decisions()) {
            require(d.decisionKey(), "observed.decisions.decisionKey");
            values(d.inputs(), "observed.decisions.inputs");
            for (Map<String, Value> out : d.outputs()) {
                values(out, "observed.decisions.outputs");
            }
        }
        for (ReplayFixture.Deadline d : o.deadlines()) {
            instant(d.dueAt(), "observed.deadlines." + d.activityId());
        }
        for (ReplayFixture.Transition t : o.transitions()) {
            if (t.kind() == null || t.activityId() == null) {
                throw incomplete("observed.transitions: activityId and kind are required");
            }
        }
    }

    static void values(Map<String, Value> values, String where) {
        values.forEach((k, v) -> value(v, where + "." + k));
    }

    /** Checks one typed value against the semantics in {@link ReplayFixture}. */
    static void value(Value v, String where) {
        if (v == null || v.type() == null) {
            throw incomplete(where + ": type is missing");
        }
        String s = v.value();
        if (v.type() == ReplayFixture.ValueType.NULL) {
            if (s != null) {
                throw incomplete(where + ": NULL carries no value");
            }
            return;
        }
        if (s == null) {
            throw incomplete(where + ": value is missing");
        }
        boolean local = v.type() == ReplayFixture.ValueType.LOCAL_DATE || v.type() == ReplayFixture.ValueType.LOCAL_DATE_TIME;
        if (local) {
            zone(v.zone(), where + ".zone");
        } else if (v.zone() != null) {
            throw incomplete(where + ": only LOCAL_DATE and LOCAL_DATE_TIME carry a zone");
        }
        try {
            switch (v.type()) {
                case BOOLEAN -> {
                    if (!s.equals("true") && !s.equals("false")) {
                        throw incomplete(where + ": BOOLEAN is true or false");
                    }
                }
                case LONG -> Long.parseLong(s);
                case DECIMAL -> {
                    // engine decimal text v1: the canonical BigDecimal.toString() form, so value and scale round-trip
                    if (s.length() > MAX_DECIMAL_TEXT || !new BigDecimal(s).toString().equals(s)) {
                        throw incomplete(where + ": DECIMAL must be canonical BigDecimal text of at most "
                                + MAX_DECIMAL_TEXT + " characters: " + s);
                    }
                }
                case INSTANT -> instant(s, where);
                case LOCAL_DATE -> LocalDate.parse(s);
                case LOCAL_DATE_TIME -> LocalDateTime.parse(s);
                case JSON -> JSON.readTree(s);
                case BYTES_SHA256 -> {
                    if (!SHA256.matcher(s).matches()) {
                        throw incomplete(where + ": BYTES_SHA256 is 64 lower-case hex digits");
                    }
                }
                case STRING, NULL -> {
                }
            }
        } catch (NumberFormatException | DateTimeException | IOException e) {
            throw incomplete(where + ": " + v.type() + " value " + s + " is malformed");
        }
    }

    static Instant instant(String s, String where) {
        if (s == null || !s.endsWith("Z")) {
            throw incomplete(where + ": an INSTANT is ISO-8601 UTC ending in Z, got " + s);
        }
        try {
            return Instant.parse(s);
        } catch (DateTimeException e) {
            throw incomplete(where + ": malformed INSTANT " + s);
        }
    }

    private static void zone(String id, String where) {
        if (id == null || id.isBlank()) {
            throw incomplete(where + " is missing");
        }
        ZoneId z;
        try {
            z = ZoneId.of(id);
        } catch (DateTimeException e) {
            throw incomplete(where + ": unknown zone " + id);
        }
        if (z instanceof java.time.ZoneOffset || FIXED_OFFSET_ID.matcher(id).matches()) {
            throw incomplete(where + ": " + id + " is a fixed offset; a named zone with historical rules is required");
        }
    }

    private static void require(String s, String name) {
        if (s == null || s.isBlank()) {
            throw incomplete(name + " is missing");
        }
    }

    private static ReplayException incomplete(String message) {
        return new ReplayException(Code.INCOMPLETE_FIXTURE, message);
    }

    /** Lower-case hex SHA-256. */
    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The fixture's events of one type, in order (helper for drivers and reports). */
    public static List<Event> events(ReplayFixture f, EventType type) {
        return f.events().stream().filter(e -> e.type() == type).toList();
    }
}
