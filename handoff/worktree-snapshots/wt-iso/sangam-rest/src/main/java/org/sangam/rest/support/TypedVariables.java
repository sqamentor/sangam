// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.support;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * R33 typed JSON for variables. Plain JSON values pass through. A typed value is written as
 * {@code {"type":"Decimal","value":"1234.50"}}; money is never a binary float and large integers are strings (E18).
 * Supported types: Decimal, BigInteger, Long, Integer, Boolean, String, Date (ISO-8601 instant), LocalDate, Json.
 * Outbound, BigDecimal/BigInteger/Date/LocalDate are written back in the typed form.
 */
public final class TypedVariables {

    private TypedVariables() {
    }

    public static Map<String, Object> fromJson(Map<String, Object> in) {
        if (in == null) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        in.forEach((k, v) -> out.put(k, fromJsonValue(v)));
        return out;
    }

    @SuppressWarnings("unchecked")
    public static Object fromJsonValue(Object v) {
        if (v instanceof Map<?, ?> m && m.size() == 2 && m.containsKey("type") && m.containsKey("value")
                && m.get("type") instanceof String type) {
            Object value = m.get("value");
            if (value == null) {
                return null;
            }
            String s = String.valueOf(value);
            return switch (type) {
                case "Decimal", "decimal" -> new BigDecimal(s);
                case "BigInteger", "integer-string" -> new BigInteger(s);
                case "Long", "long" -> Long.parseLong(s);
                case "Integer", "integer" -> Integer.parseInt(s);
                case "Boolean", "boolean" -> Boolean.parseBoolean(s);
                case "String", "string" -> s;
                case "Date", "date-time" -> Date.from(Instant.parse(s));
                case "LocalDate", "date" -> LocalDate.parse(s).toString();
                case "Json", "json" -> value;
                default -> throw new IllegalArgumentException("unknown typed variable type " + type);
            };
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            ((Map<String, Object>) m).forEach((k, x) -> out.put(k, fromJsonValue(x)));
            return out;
        }
        if (v instanceof List<?> l) {
            return l.stream().map(TypedVariables::fromJsonValue).toList();
        }
        if (v instanceof Double d) {
            // A JSON number with a fraction: keep it exact (R33), never a binary float.
            return new BigDecimal(d.toString());
        }
        return v;
    }

    public static Map<String, Object> toJson(Map<String, ?> in) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (in != null) {
            in.forEach((k, v) -> out.put(k, toJsonValue(v)));
        }
        return out;
    }

    public static Object toJsonValue(Object v) {
        if (v instanceof BigDecimal d) {
            return typed("Decimal", d.toPlainString());
        }
        if (v instanceof BigInteger i) {
            return typed("BigInteger", i.toString());
        }
        if (v instanceof Long l && Math.abs(l) > (1L << 53)) {
            return typed("Long", l.toString());
        }
        if (v instanceof Date d) {
            return typed("Date", d.toInstant().toString());
        }
        if (v instanceof Instant i) {
            return typed("Date", i.toString());
        }
        if (v instanceof LocalDate d) {
            return typed("LocalDate", d.toString());
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, x) -> out.put(String.valueOf(k), toJsonValue(x)));
            return out;
        }
        if (v instanceof Collection<?> c) {
            return c.stream().map(TypedVariables::toJsonValue).toList();
        }
        if (v instanceof byte[] b) {
            return typed("Bytes", java.util.Base64.getEncoder().encodeToString(b));
        }
        if (v == null || v instanceof String || v instanceof Number || v instanceof Boolean) {
            return v;
        }
        return String.valueOf(v);
    }

    private static Map<String, Object> typed(String type, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("value", value);
        return m;
    }
}
