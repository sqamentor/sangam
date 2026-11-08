// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.sangam.core.layer.CanonicalJson;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Hashes;
import org.sangam.core.layer.Jdbc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** JDBC and JSON helpers shared by the compliance services. All times come from the database clock (A-1). */
public final class Sql {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Sql() {
    }

    /** Ordered map from key/value pairs; unlike {@code Map.of} it accepts null values. */
    public static Map<String, Object> kv(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("key/value pairs expected");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return m;
    }

    public static String newId() {
        return Hashes.uuidV7().toString();
    }

    /** Database time, truncated to microseconds (MySQL/SQL Server precision). */
    public static Instant now(Connection c, Dialect d) {
        return Jdbc.dbNow(c, d).truncatedTo(ChronoUnit.MICROS);
    }

    public static String json(Object value) {
        return CanonicalJson.write(value == null ? Map.of() : value);
    }

    public static Map<String, Object> map(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (IOException e) {
            throw new IllegalStateException("stored JSON is not an object", e);
        }
    }

    public static List<String> list(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<ArrayList<String>>() {
            });
        } catch (IOException e) {
            throw new IllegalStateException("stored JSON is not a string array", e);
        }
    }

    public static List<Map<String, Object>> objects(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<ArrayList<Map<String, Object>>>() {
            });
        } catch (IOException e) {
            throw new IllegalStateException("stored JSON is not an object array", e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, String> strings(Object value) {
        Map<String, String> out = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> m) {
            m.forEach((k, v) -> out.put(String.valueOf(k), v == null ? null : String.valueOf(v)));
        }
        return out;
    }

    public static Long seconds(java.time.Duration d) {
        return d == null ? null : d.getSeconds();
    }

    public static java.time.Duration duration(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : java.time.Duration.ofSeconds(v);
    }

    public static <E extends Enum<E>> E enumOrNull(Class<E> type, String name) {
        return name == null || name.isBlank() ? null : Enum.valueOf(type, name);
    }

    public static <E extends Enum<E>> java.util.EnumSet<E> enums(Class<E> type, String json) {
        java.util.EnumSet<E> s = java.util.EnumSet.noneOf(type);
        for (String n : list(json)) {
            s.add(Enum.valueOf(type, n));
        }
        return s;
    }

    public static String enumList(Collection<? extends Enum<?>> values) {
        List<String> names = new ArrayList<>();
        if (values != null) {
            values.forEach(v -> names.add(v.name()));
        }
        java.util.Collections.sort(names);
        return CanonicalJson.write(names);
    }

    public static Map<String, String> stringMap(String json) {
        Map<String, String> out = new LinkedHashMap<>();
        map(json).forEach((k, v) -> out.put(k, v == null ? null : String.valueOf(v)));
        return out;
    }

    public static Set<String> set(String json) {
        return new LinkedHashSet<>(list(json));
    }

    public static String jsonList(Collection<String> values) {
        return CanonicalJson.write(values == null ? List.of() : new ArrayList<>(values));
    }

    /** SHA-256 hex of the canonical JSON of {@code value}: evidence hash of a stored artefact. */
    public static String evidenceHash(Object value) {
        return Hashes.hex(Hashes.sha256(json(value).getBytes(StandardCharsets.UTF_8)));
    }

    public static String hashHex(String text) {
        return Hashes.hex(Hashes.sha256(text));
    }

    public static boolean bool(int smallint) {
        return smallint != 0;
    }

    public static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    public static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new org.sangam.compliance.ComplianceException(org.sangam.compliance.ComplianceException.Code.INVALID_INPUT,
                    name + " is required");
        }
        return value;
    }
}
