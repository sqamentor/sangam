// SPDX-License-Identifier: Apache-2.0
package org.sangam.programme.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.sangam.core.layer.CanonicalJson;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Hashes;
import org.sangam.core.layer.Jdbc;
import org.sangam.programme.ProgrammeException;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JDBC and JSON helpers shared by the programme services. All times come from the database clock (A-1). */
public final class Db {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Db() {
    }

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

    public static Instant now(Connection c, Dialect d) {
        return Jdbc.dbNow(c, d).truncatedTo(ChronoUnit.MICROS);
    }

    /** Database "today" in UTC (period arithmetic is date based). */
    public static LocalDate today(Connection c, Dialect d) {
        return now(c, d).atZone(ZoneOffset.UTC).toLocalDate();
    }

    /** Canonical JSON; decimals, dates and other scalars are carried as strings (R33). */
    public static String json(Object value) {
        return CanonicalJson.write(normalise(value == null ? Map.of() : value));
    }

    private static Object normalise(Object v) {
        if (v == null || v instanceof String || v instanceof Boolean || v instanceof Integer || v instanceof Long
                || v instanceof Short || v instanceof Byte || v instanceof java.math.BigInteger) {
            return v;
        }
        if (v instanceof BigDecimal b) {
            return b.stripTrailingZeros().toPlainString();
        }
        if (v instanceof Number n) {
            double d = n.doubleValue();
            if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                return (long) d;
            }
            return new BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, x) -> out.put(String.valueOf(k), normalise(x)));
            return out;
        }
        if (v instanceof java.util.Collection<?> c) {
            List<Object> out = new ArrayList<>();
            c.forEach(x -> out.add(normalise(x)));
            return out;
        }
        return v.toString();
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

    public static List<Object> list(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<ArrayList<Object>>() {
            });
        } catch (IOException e) {
            throw new IllegalStateException("stored JSON is not an array", e);
        }
    }

    public static Map<String, Object> parseObject(String json) {
        try {
            return MAPPER.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (IOException e) {
            throw new ProgrammeException(ProgrammeException.Code.INVALID_CONFIG, "configuration is not a JSON object");
        }
    }

    public static BigDecimal dec(ResultSet rs, String col) throws SQLException {
        return rs.getBigDecimal(col);
    }

    public static BigDecimal dec(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal b) {
            return b;
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        String s = v.toString().trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            throw ProgrammeException.invalid("not a number: " + s);
        }
    }

    public static LocalDate date(ResultSet rs, String col) throws SQLException {
        Date d = rs.getDate(col);
        return d == null ? null : d.toLocalDate();
    }

    public static Date sqlDate(LocalDate d) {
        return d == null ? null : Date.valueOf(d);
    }

    public static LocalDate date(Object v) {
        if (v == null || v.toString().isBlank()) {
            return null;
        }
        if (v instanceof LocalDate d) {
            return d;
        }
        return LocalDate.parse(v.toString().substring(0, Math.min(10, v.toString().length())));
    }

    public static String str(Object v) {
        return v == null ? null : v.toString();
    }

    public static String str(Map<String, ?> m, String key, String dflt) {
        Object v = m.get(key);
        return v == null || v.toString().isBlank() ? dflt : v.toString();
    }

    public static int intVal(Map<String, ?> m, String key, int dflt) {
        Object v = m.get(key);
        if (v == null || v.toString().isBlank()) {
            return dflt;
        }
        return v instanceof Number n ? n.intValue() : Integer.parseInt(v.toString().trim());
    }

    public static boolean boolVal(Map<String, ?> m, String key, boolean dflt) {
        Object v = m.get(key);
        if (v == null) {
            return dflt;
        }
        return v instanceof Boolean b ? b : Boolean.parseBoolean(v.toString());
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object v) {
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object v) {
        return v instanceof List<?> l ? (List<Object>) l : new ArrayList<>();
    }

    public static boolean bool(int smallint) {
        return smallint != 0;
    }

    public static int small(boolean b) {
        return b ? 1 : 0;
    }

    public static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw ProgrammeException.invalid(name + " is required");
        }
        return value;
    }

    public static String tenant(String t) {
        return t == null ? "" : t;
    }

    public static String hashOf(Object value) {
        return Hashes.hex(Hashes.sha256(json(value).getBytes(StandardCharsets.UTF_8)));
    }

    /** Builds {@code IN (?,?,..)} for {@code n} binds (n &gt; 0). */
    public static String in(int n) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < n; i++) {
            sb.append(i == 0 ? "?" : ",?");
        }
        return sb.append(')').toString();
    }

    /** Requires exactly one row updated, else a concurrent-edit conflict (optimistic row_version). */
    public static void requireOne(int updated, String what) {
        if (updated != 1) {
            throw new ProgrammeException(ProgrammeException.Code.CONFLICT, what + " was changed concurrently or is in another state");
        }
    }
}
