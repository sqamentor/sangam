// SPDX-License-Identifier: Apache-2.0
package org.sangam.casefile.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.sangam.casefile.CaseFileException;
import org.sangam.core.layer.CanonicalJson;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Hashes;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.SqlPredicate;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JDBC and JSON helpers shared by the case-file services. All times come from the database clock (A-1). */
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

    /** Canonical JSON; decimals and other non-integral scalars are carried as strings (CanonicalJson rejects them). */
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
        if (v instanceof Collection<?> c) {
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
            throw new CaseFileException(CaseFileException.Code.INVALID_INPUT, "not a JSON object");
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
            throw new CaseFileException(CaseFileException.Code.INVALID_INPUT, "not a JSON array");
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object v) {
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object v) {
        return v instanceof List<?> l ? (List<Object>) l : new ArrayList<>();
    }

    public static List<String> strings(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof Collection<?> c) {
            c.forEach(x -> {
                if (x != null) {
                    out.add(x.toString());
                }
            });
        } else if (v != null && !v.toString().isBlank()) {
            for (String s : v.toString().split(",")) {
                if (!s.isBlank()) {
                    out.add(s.trim());
                }
            }
        }
        return out;
    }

    public static String str(Object v) {
        return v == null ? null : v.toString();
    }

    public static String str(Map<String, ?> m, String key, String dflt) {
        Object v = m == null ? null : m.get(key);
        return v == null || v.toString().isBlank() ? dflt : v.toString();
    }

    public static int intVal(Map<String, ?> m, String key, int dflt) {
        Object v = m == null ? null : m.get(key);
        if (v == null || v.toString().isBlank()) {
            return dflt;
        }
        return v instanceof Number n ? n.intValue() : Integer.parseInt(v.toString().trim());
    }

    public static boolean boolVal(Map<String, ?> m, String key, boolean dflt) {
        Object v = m == null ? null : m.get(key);
        if (v == null) {
            return dflt;
        }
        return v instanceof Boolean b ? b : Boolean.parseBoolean(v.toString());
    }

    public static boolean bool(int smallint) {
        return smallint != 0;
    }

    public static int small(boolean b) {
        return b ? 1 : 0;
    }

    public static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw CaseFileException.invalid(name + " is required");
        }
        return value;
    }

    public static <T> T require(T value, String name) {
        if (value == null) {
            throw CaseFileException.invalid(name + " is required");
        }
        return value;
    }

    public static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    public static String tenant(String t) {
        return t == null ? "" : t;
    }

    public static String hash(Object value) {
        return Hashes.hex(Hashes.sha256(json(value).getBytes(StandardCharsets.UTF_8)));
    }

    public static String hashText(String text) {
        return Hashes.hex(Hashes.sha256(text == null ? "" : text));
    }

    public static Instant instant(Object v) {
        if (v == null || v.toString().isBlank()) {
            return null;
        }
        if (v instanceof Instant i) {
            return i;
        }
        if (v instanceof java.util.Date d) {
            return d.toInstant();
        }
        return Instant.parse(v.toString());
    }

    /** ISO-8601 {@code P3D}, {@code PT4H} or {@code P1DT2H}: split into a period and a duration. */
    public static Object[] periodAndDuration(String iso) {
        if (iso == null || iso.isBlank()) {
            return new Object[]{Period.ZERO, Duration.ZERO};
        }
        String s = iso.trim().toUpperCase();
        boolean negative = s.startsWith("-");
        if (negative) {
            s = s.substring(1);
        }
        int t = s.indexOf('T');
        Period p = Period.ZERO;
        Duration d = Duration.ZERO;
        if (t < 0) {
            p = Period.parse(s);
        } else {
            String datePart = s.substring(0, t);
            if (datePart.length() > 1) {
                p = Period.parse(datePart);
            }
            d = Duration.parse("P" + s.substring(t));
        }
        return negative ? new Object[]{p.negated(), d.negated()} : new Object[]{p, d};
    }

    /** Escapes LIKE wildcards; the caller adds {@code ESCAPE '!'}. */
    public static String likeContains(String text) {
        return "%" + text.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    public static SqlPredicate orTrue(SqlPredicate p) {
        return p == null ? new SqlPredicate("1=1", List.of()) : p;
    }
}
