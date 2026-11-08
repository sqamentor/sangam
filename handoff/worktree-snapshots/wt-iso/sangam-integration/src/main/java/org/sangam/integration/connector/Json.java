// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared Jackson mapper for the integration layer. Decimals are read as {@link java.math.BigDecimal} so money values
 * never pass through binary floating point (R33).
 */
public final class Json {

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {
    };

    private Json() {
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] bytes(Object value) {
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Parses a JSON object; null or blank yields an empty map. */
    public static Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return MAPPER.readValue(json, MAP);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Parses JSON; returns null for malformed input instead of throwing. */
    public static JsonNode tree(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            return MAPPER.readTree(body);
        } catch (IOException e) {
            return null;
        }
    }

    public static JsonNode tree(String body) {
        return body == null ? null : tree(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public static Map<String, Object> toMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return new LinkedHashMap<>();
        }
        return MAPPER.convertValue(node, MAP);
    }
}
