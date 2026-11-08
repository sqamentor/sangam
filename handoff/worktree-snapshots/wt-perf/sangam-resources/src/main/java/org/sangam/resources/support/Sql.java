// SPDX-License-Identifier: Apache-2.0
package org.sangam.resources.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.sangam.core.layer.Jdbc;
import org.sangam.resources.dialect.ResourcesDialect;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** JDBC and JSON helpers shared by the resources services. */
public final class Sql {

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .findAndRegisterModules();

    private Sql() {
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    public static Instant dbNow(Connection c, ResourcesDialect d) {
        return Jdbc.one(c, "SELECT " + d.now() + " AS n" + d.fromDual(), rs -> Jdbc.instant(rs, "n")).orElseThrow();
    }

    /** Minute index used as the slot key of claim rows (time-zone free, identical on every dialect). */
    public static long slotKey(Instant t) {
        return Math.floorDiv(t.getEpochSecond(), 60L);
    }

    public static Instant fromSlotKey(long key) {
        return Instant.ofEpochSecond(key * 60L);
    }

    public static String toJson(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return JSON.writeValueAsString(o);
        } catch (IOException e) {
            throw ResourcesException.invalid("JSON", "value is not serialisable as JSON");
        }
    }

    public static Map<String, Object> mapJson(String s) {
        if (s == null || s.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return JSON.readValue(s, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (IOException e) {
            throw ResourcesException.invalid("JSON", "stored JSON is invalid");
        }
    }

    public static List<Object> listJson(String s) {
        if (s == null || s.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(s, new TypeReference<List<Object>>() { });
        } catch (IOException e) {
            throw ResourcesException.invalid("JSON", "stored JSON is invalid");
        }
    }

    /** Reads a {@code {text}} column (TEXT, CLOB, NVARCHAR(MAX), LONGTEXT) as a string. */
    public static String text(ResultSet rs, String col) throws SQLException {
        Object o = rs.getObject(col);
        if (o == null) {
            return null;
        }
        if (o instanceof Clob clob) {
            try (Reader r = clob.getCharacterStream()) {
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[4096];
                int n;
                while ((n = r.read(buf)) > 0) {
                    sb.append(buf, 0, n);
                }
                return sb.toString();
            } catch (IOException e) {
                throw new SQLException("cannot read CLOB", e);
            }
        }
        return o.toString();
    }

    public static BigDecimal decimal(ResultSet rs, String col) throws SQLException {
        String s = rs.getString(col);
        return s == null ? null : new BigDecimal(s.trim());
    }

    public static String str(Object o) {
        return o == null ? null : o.toString();
    }

    public static Integer intOrNull(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.intValue();
        }
        String s = o.toString().trim();
        return s.isEmpty() ? null : Integer.valueOf(s);
    }

    public static int intOr(Object o, int def) {
        Integer i = intOrNull(o);
        return i == null ? def : i;
    }

    public static BigDecimal decimalOrNull(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof BigDecimal b) {
            return b;
        }
        String s = o.toString().trim();
        return s.isEmpty() ? null : new BigDecimal(s);
    }

    public static boolean bool(Object o, boolean def) {
        if (o == null) {
            return def;
        }
        if (o instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(o.toString());
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object o) {
        return o instanceof List<?> l ? (List<Object>) l : List.of();
    }
}
