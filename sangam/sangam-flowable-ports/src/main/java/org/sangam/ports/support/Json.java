// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.support;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * Shared Jackson mapper for the ports module (no default typing, no Java serialization: FLOWABLE-INTO-OPERATON D-47).
 *
 * <p>FW-02 (Flowable's Jackson 3.0.1 DoS findings: document-length, nesting-depth and number-length bypasses): every
 * JSON the ports read goes through this mapper, which carries explicit {@link StreamReadConstraints} instead of
 * relying on library defaults: document {@value #MAX_DOCUMENT_BYTES} bytes, nesting {@value #MAX_NESTING}, strings
 * {@value #MAX_STRING_CHARS} chars, numbers {@value #MAX_NUMBER_CHARS} digits, names {@value #MAX_NAME_CHARS} chars.
 */
public final class Json {

    public static final long MAX_DOCUMENT_BYTES = 16L * 1024 * 1024;
    public static final int MAX_NESTING = 200;
    public static final int MAX_STRING_CHARS = 8 * 1024 * 1024;
    public static final int MAX_NUMBER_CHARS = 400;
    public static final int MAX_NAME_CHARS = 10_000;

    public static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxDocumentLength(MAX_DOCUMENT_BYTES)
                    .maxNestingDepth(MAX_NESTING)
                    .maxStringLength(MAX_STRING_CHARS)
                    .maxNumberLength(MAX_NUMBER_CHARS)
                    .maxNameLength(MAX_NAME_CHARS)
                    .build())
            .build())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
            .findAndRegisterModules();

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private Json() {
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("not serialisable as JSON: " + e.getOriginalMessage(), e);
        }
    }

    public static <T> T read(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("invalid JSON for " + type.getSimpleName() + ": empty");
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("invalid JSON for " + type.getSimpleName() + ": " + e.getOriginalMessage(), e);
        }
    }

    public static JsonNode tree(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("invalid JSON: empty");
        }
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("invalid JSON: " + e.getOriginalMessage(), e);
        }
    }

    public static Map<String, Object> map(String json) {
        if (json == null || json.isBlank()) {
            return new java.util.LinkedHashMap<>();
        }
        try {
            return MAPPER.readValue(json, MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("invalid JSON object: " + e.getOriginalMessage(), e);
        }
    }

    public static Map<String, Object> map(JsonNode node) {
        return MAPPER.convertValue(node, MAP);
    }

    public static ObjectNode object() {
        return MAPPER.createObjectNode();
    }

    /** Resolves a JSON pointer ({@code /a/b}) or a dotted path ({@code a.b}); returns {@code null} when absent. */
    public static JsonNode at(JsonNode root, String path) {
        if (root == null || path == null || path.isEmpty()) {
            return root;
        }
        String pointer = path.startsWith("/") ? path : "/" + path.replace('.', '/');
        JsonNode n = root.at(pointer);
        return n.isMissingNode() ? null : n;
    }

    /** Converts a JSON value to a plain Java value (String, Long/Double, Boolean, Map, List, or null). */
    public static Object plain(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isTextual()) {
            return n.asText();
        }
        if (n.isIntegralNumber()) {
            return n.asLong();
        }
        if (n.isNumber()) {
            return n.asDouble();
        }
        if (n.isBoolean()) {
            return n.asBoolean();
        }
        return MAPPER.convertValue(n, Object.class);
    }
}
