// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.sangam.sdk.http.HttpTransport;
import org.sangam.sdk.model.TypedValue;
import org.sangam.sdk.testing.SangamTestEngine;
import org.sangam.sdk.worker.SangamWorker;
import org.sangam.sdk.worker.TopicSubscription;

/**
 * T18 (SANGAM.md II §16, R33): Decimal, large integer, date and null keep their business value through the SDK
 * (decode, encode) and through a worker round trip, using the vectors shared by all five SDKs
 * ({@code sdks/testing/t18-vectors.json}).
 */
class ContractT18TypedValuesTest {

    private static final Path VECTORS = Paths.get("..", "testing", "t18-vectors.json");
    private static final ObjectMapper JSON = HttpTransport.defaultMapper();

    record Vector(String name, Object wire, String value, String kind) { }

    @SuppressWarnings("unchecked")
    static List<Vector> vectors() throws IOException {
        assertTrue(Files.isRegularFile(VECTORS), "vectors not found at " + VECTORS.toAbsolutePath()
                + " (the test runs from the sangam/sdks/java module directory)");
        JsonNode root = JSON.readTree(Files.readAllBytes(VECTORS));
        List<Vector> out = new java.util.ArrayList<>();
        for (JsonNode v : root.get("vectors")) {
            Object wire = v.get("wire").isNull() ? null : JSON.convertValue(v.get("wire"), Map.class);
            out.add(new Vector(v.get("name").asText(), wire, v.get("value").isNull() ? null : v.get("value").asText(),
                    v.get("kind").asText()));
        }
        assertTrue(out.size() >= 10);
        return out;
    }

    /** The business value a vector names, as the Java type the SDK maps it to. */
    static Object expected(Vector v) {
        if (v.value() == null) return null;
        return switch (v.kind()) {
            case "decimal" -> new BigDecimal(v.value());
            case "biginteger" -> new BigInteger(v.value());
            case "long" -> Long.parseLong(v.value());
            case "integer" -> Integer.parseInt(v.value());
            case "boolean" -> Boolean.parseBoolean(v.value());
            case "string" -> v.value();
            case "date" -> Instant.parse(v.value());
            case "localdate" -> LocalDate.parse(v.value());
            default -> throw new AssertionError("unknown kind " + v.kind());
        };
    }

    static Class<?> javaType(String kind) {
        return switch (kind) {
            case "decimal" -> BigDecimal.class;
            case "biginteger" -> BigInteger.class;
            case "long" -> Long.class;
            case "integer" -> Integer.class;
            case "boolean" -> Boolean.class;
            case "string" -> String.class;
            case "date" -> Instant.class;
            case "localdate" -> LocalDate.class;
            default -> Object.class;
        };
    }

    /** Encodes a native value with the SDK's R33 factories. */
    static TypedValue encode(String kind, Object v) {
        return switch (kind) {
            case "decimal" -> TypedValue.decimal((BigDecimal) v);
            case "biginteger" -> TypedValue.bigInteger((BigInteger) v);
            case "long" -> TypedValue.ofLong((Long) v);
            case "integer" -> TypedValue.ofInteger((Integer) v);
            case "boolean" -> TypedValue.ofBoolean((Boolean) v);
            case "string" -> TypedValue.string((String) v);
            case "date" -> TypedValue.date((Instant) v);
            case "localdate" -> TypedValue.localDate((LocalDate) v);
            default -> throw new AssertionError(kind);
        };
    }

    /** Decimal must keep its scale: 1234.50 is not 1234.5 (BigDecimal.equals compares the scale). */
    static void assertSameValue(String name, Object want, Object got) {
        assertEquals(want, got, name);
        if (want != null) assertEquals(want.getClass(), got.getClass(), name + ": type");
    }

    @Test
    void t18_decodeGivesTheBusinessValue() throws IOException {
        for (Vector v : vectors()) {
            Object got = TypedValue.unwrap(v.wire(), javaType(v.kind()), JSON);
            assertSameValue(v.name(), expected(v), got);
        }
    }

    @Test
    void t18_encodeThenDecodeKeepsTheBusinessValue() throws IOException {
        for (Vector v : vectors()) {
            Object value = expected(v);
            if (value == null) {
                assertNull(TypedValue.unwrap(null, Object.class, JSON), v.name());
                continue;
            }
            // through the wire form the transport writes
            byte[] wire = JSON.writeValueAsBytes(encode(v.kind(), value));
            Object back = TypedValue.unwrap(JSON.readValue(wire, Map.class), javaType(v.kind()), JSON);
            assertSameValue(v.name(), value, back);
        }
    }

    @Test
    void t18_valuesSurviveAWorkerRoundTripThroughTheEngine() throws Exception {
        List<Vector> vectors = vectors().stream().filter(v -> v.wire() != null).toList();
        Map<String, Object> in = new LinkedHashMap<>();
        for (Vector v : vectors) in.put(v.name(), v.wire());
        try (SangamTestEngine engine = SangamTestEngine.start()) {
            String id = engine.addWork("t18", in);
            SangamWorker w = ContractSupport.worker(engine, "w-t18", ContractSupport.logs()).subscribe(TopicSubscription.topic("t18", ctx -> {
                Map<String, Object> out = new LinkedHashMap<>();
                for (Vector v : vectors) {
                    Object nativeValue = ctx.variable(v.name(), javaType(v.kind()));
                    out.put(v.name(), encode(v.kind(), nativeValue));
                }
                return out;
            }).lockDuration(Duration.ofMinutes(1)).build()).build().start();
            try {
                engine.awaitState(id, "COMPLETED", Duration.ofSeconds(10));
            } finally {
                w.close();
            }
            Map<String, Object> back = engine.completedVariableMap(id);
            assertNotNull(back);
            for (Vector v : vectors) {
                Object got = TypedValue.unwrap(back.get(v.name()), javaType(v.kind()), JSON);
                assertSameValue(v.name(), expected(v), got);
            }
        }
    }
}
