// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON helpers for the registry's JSON columns and configuration documents. */
public final class Json {

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {
    };
    private static final TypeReference<List<Object>> LIST = new TypeReference<>() {
    };

    private Json() {
    }

    /** Serialises after {@link #canonical(Object)} conversion (dates, instants and decimals as strings). */
    public static String write(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(canonical(value));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("not serialisable as JSON: " + e.getOriginalMessage(), e);
        }
    }

    public static Map<String, Object> map(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return MAPPER.readValue(json, MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("invalid JSON object: " + e.getOriginalMessage(), e);
        }
    }

    public static List<Object> list(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return MAPPER.readValue(json, LIST);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("invalid JSON array: " + e.getOriginalMessage(), e);
        }
    }

    /**
     * Converts a value to the shape {@link org.sangam.core.layer.CanonicalJson} accepts for hashing (R33): decimals,
     * dates and instants become strings, nested maps and lists are converted recursively.
     */
    public static Object canonical(Object v) {
        if (v == null || v instanceof String || v instanceof Boolean || v instanceof Integer || v instanceof Long
                || v instanceof Short || v instanceof Byte || v instanceof java.math.BigInteger) {
            return v;
        }
        if (v instanceof BigDecimal d) {
            return d.stripTrailingZeros().toPlainString();
        }
        if (v instanceof Double || v instanceof Float) {
            BigDecimal d = new BigDecimal(v.toString());
            try {
                return d.longValueExact();
            } catch (ArithmeticException e) {
                return d.stripTrailingZeros().toPlainString();
            }
        }
        if (v instanceof Number n) {
            return n.toString();
        }
        if (v instanceof Instant || v instanceof LocalDate || v instanceof Enum<?>) {
            return v.toString();
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, val) -> out.put(String.valueOf(k), canonical(val)));
            return out;
        }
        if (v instanceof Collection<?> c) {
            List<Object> out = new ArrayList<>();
            c.forEach(x -> out.add(canonical(x)));
            return out;
        }
        if (v instanceof byte[] b) {
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        }
        if (v instanceof Record) {
            return canonical(MAPPER.convertValue(v, MAP));
        }
        return v.toString();
    }

    /** Canonical map (see {@link #canonical(Object)}) for audit payloads. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> canonicalMap(Map<String, ?> m) {
        return m == null ? Map.of() : (Map<String, Object>) canonical(m);
    }
}
