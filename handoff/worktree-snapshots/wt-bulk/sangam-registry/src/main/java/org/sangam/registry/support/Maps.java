// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry.support;

import org.sangam.registry.RegistryException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Null-tolerant readers for configuration maps and request bodies. */
public final class Maps {

    private Maps() {
    }

    /** An ordered map from alternating key/value arguments; null values are skipped. */
    public static Map<String, Object> of(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i + 1] != null) {
                m.put(String.valueOf(kv[i]), kv[i + 1]);
            }
        }
        return m;
    }

    public static String str(Map<String, ?> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v == null ? null : String.valueOf(v);
    }

    public static String str(Map<String, ?> m, String key, String def) {
        String v = str(m, key);
        return v == null || v.isBlank() ? def : v;
    }

    public static String required(Map<String, ?> m, String key) {
        String v = str(m, key);
        if (v == null || v.isBlank()) {
            throw RegistryException.invalid("missing required field: " + key);
        }
        return v;
    }

    public static Integer integer(Map<String, ?> m, String key) {
        Object v = m == null ? null : m.get(key);
        if (v == null || "".equals(v)) {
            return null;
        }
        return v instanceof Number n ? n.intValue() : Integer.valueOf(v.toString().trim());
    }

    public static int integer(Map<String, ?> m, String key, int def) {
        Integer v = integer(m, key);
        return v == null ? def : v;
    }

    public static Long lng(Map<String, ?> m, String key) {
        Object v = m == null ? null : m.get(key);
        if (v == null || "".equals(v)) {
            return null;
        }
        return v instanceof Number n ? n.longValue() : Long.valueOf(v.toString().trim());
    }

    public static BigDecimal decimal(Map<String, ?> m, String key) {
        Object v = m == null ? null : m.get(key);
        if (v == null || "".equals(v)) {
            return null;
        }
        return v instanceof BigDecimal b ? b : new BigDecimal(v.toString().trim());
    }

    public static boolean bool(Map<String, ?> m, String key, boolean def) {
        Object v = m == null ? null : m.get(key);
        if (v == null) {
            return def;
        }
        return v instanceof Boolean b ? b : Boolean.parseBoolean(v.toString().trim());
    }

    public static LocalDate date(Map<String, ?> m, String key) {
        String v = str(m, key);
        if (v == null || v.isBlank()) {
            return null;
        }
        String s = v.trim();
        return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
    }

    public static Instant instant(Map<String, ?> m, String key) {
        Object v = m == null ? null : m.get(key);
        if (v == null || "".equals(v)) {
            return null;
        }
        if (v instanceof Instant i) {
            return i;
        }
        if (v instanceof java.util.Date d) {
            return d.toInstant();
        }
        String s = v.toString().trim();
        return s.length() == 10 ? LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant() : Instant.parse(s);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Map<String, ?> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v instanceof Map<?, ?> x ? new LinkedHashMap<>((Map<String, Object>) x) : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> maps(Map<String, ?> m, String key) {
        Object v = m == null ? null : m.get(key);
        List<Map<String, Object>> out = new ArrayList<>();
        if (v instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> x) {
                    out.add((Map<String, Object>) x);
                }
            }
        }
        return out;
    }

    public static List<String> strings(Map<String, ?> m, String key) {
        Object v = m == null ? null : m.get(key);
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> l) {
            l.forEach(o -> out.add(String.valueOf(o)));
        } else if (v instanceof String s && !s.isBlank()) {
            for (String p : s.split(",")) {
                if (!p.isBlank()) {
                    out.add(p.trim());
                }
            }
        }
        return out;
    }
}
