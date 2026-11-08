// SPDX-License-Identifier: Apache-2.0
package org.sangam.onboarding.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.sangam.core.layer.CanonicalJson;
import org.sangam.core.layer.Hashes;

/** JSON and canonical hashing for runs, requests and bundles. */
public final class OnboardingJson {

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private OnboardingJson() {
    }

    public static String write(Object v) {
        try {
            return MAPPER.writeValueAsString(v);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A plain tree in which every non-integral number became a string, as R33 and the canonical writer require. */
    public static Map<String, Object> tree(Object v) {
        Map<String, Object> m = MAPPER.convertValue(v, new TypeReference<LinkedHashMap<String, Object>>() { });
        return decimalsAsStrings(m);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> decimalsAsStrings(Map<String, Object> m) {
        return (Map<String, Object>) clean(m);
    }

    private static Object clean(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, x) -> out.put(String.valueOf(k), clean(x)));
            return out;
        }
        if (v instanceof Collection<?> c) {
            List<Object> out = new ArrayList<>();
            c.forEach(x -> out.add(clean(x)));
            return out;
        }
        if (v instanceof Double || v instanceof Float || v instanceof java.math.BigDecimal) {
            return new java.math.BigDecimal(v.toString()).stripTrailingZeros().toPlainString();
        }
        return v;
    }

    /** SHA-256 hex over the canonical JSON of {@code v}. */
    public static String hash(Object v) {
        return Hashes.hex(Hashes.sha256(canonical(v)));
    }

    public static String canonical(Object v) {
        return CanonicalJson.write(v instanceof Map<?, ?> ? clean(v) : tree(v));
    }
}
