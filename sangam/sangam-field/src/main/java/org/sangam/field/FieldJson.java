// SPDX-License-Identifier: Apache-2.0
package org.sangam.field;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.util.List;
import java.util.Map;

/** Shared JSON mapper (ISO-8601 instants, tolerant of extra fields from newer clients). */
public final class FieldJson {

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private FieldJson() {
    }

    public static String write(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            throw new FieldException("INVALID_JSON", "cannot serialise: " + e.getMessage(), e);
        }
    }

    public static <T> T read(String json, Class<T> type) {
        if (json == null) {
            return null;
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new FieldException("INVALID_JSON", "cannot parse " + type.getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    public static <T> T convert(Object value, Class<T> type) {
        try {
            return MAPPER.convertValue(value, type);
        } catch (IllegalArgumentException e) {
            throw new FieldException("INVALID_REQUEST", "invalid " + type.getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    public static Map<String, Object> map(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            throw new FieldException("INVALID_JSON", "cannot parse map: " + e.getMessage(), e);
        }
    }

    public static List<String> strings(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<String>>() { });
        } catch (Exception e) {
            throw new FieldException("INVALID_JSON", "cannot parse list: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> toMap(Object o) {
        return o == null ? Map.of() : MAPPER.convertValue(o, Map.class);
    }
}
