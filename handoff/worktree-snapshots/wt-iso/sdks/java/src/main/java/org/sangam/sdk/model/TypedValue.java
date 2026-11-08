// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

/**
 * R33 typed JSON value (sangam-api.yaml TypedValue): {@code {"type":"Decimal","value":"1234.50"}}.
 * Money is never a binary float; Decimal, BigInteger, Long, Date, LocalDate and Bytes (base64) travel as strings,
 * so all five SDKs read and write identical values (R33, T18).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TypedValue(String type, Object value) {

    public static final Set<String> TYPES = Set.of("Decimal", "BigInteger", "Long", "Integer", "Boolean", "String",
            "Date", "LocalDate", "Json", "Bytes");

    public static TypedValue decimal(BigDecimal v) { return new TypedValue("Decimal", v.toPlainString()); }
    public static TypedValue decimal(String v) { return decimal(new BigDecimal(v)); }
    public static TypedValue bigInteger(BigInteger v) { return new TypedValue("BigInteger", v.toString()); }
    public static TypedValue ofLong(long v) { return new TypedValue("Long", Long.toString(v)); }
    public static TypedValue ofInteger(int v) { return new TypedValue("Integer", v); }
    public static TypedValue ofBoolean(boolean v) { return new TypedValue("Boolean", v); }
    public static TypedValue string(String v) { return new TypedValue("String", v); }
    public static TypedValue date(Instant v) { return new TypedValue("Date", v.toString()); }
    public static TypedValue localDate(LocalDate v) { return new TypedValue("LocalDate", v.toString()); }
    public static TypedValue json(Object v) { return new TypedValue("Json", v); }
    public static TypedValue bytes(byte[] v) { return new TypedValue("Bytes", Base64.getEncoder().encodeToString(v)); }

    /** True when {@code o} is a JSON object of the form {type, value} with a known R33 type. */
    public static boolean isTypedValue(Object o) {
        return o instanceof Map<?, ?> m && m.size() <= 2 && m.containsKey("type") && m.containsKey("value")
                && m.get("type") instanceof String t && TYPES.contains(t);
    }

    /** Native Java value of a TypedValue (BigDecimal, BigInteger, Long, Integer, Boolean, String, Instant, LocalDate, byte[], JSON). */
    public Object toJava() {
        if (value == null) return null;
        String s = String.valueOf(value);
        return switch (type) {
            case "Decimal" -> new BigDecimal(s);
            case "BigInteger" -> new BigInteger(s);
            case "Long" -> Long.parseLong(s);
            case "Integer" -> value instanceof Number n ? n.intValue() : Integer.parseInt(s);
            case "Boolean" -> value instanceof Boolean b ? b : Boolean.parseBoolean(s);
            case "String" -> s;
            case "Date" -> Instant.parse(s);
            case "LocalDate" -> LocalDate.parse(s);
            case "Bytes" -> Base64.getDecoder().decode(s);
            default -> value; // Json
        };
    }

    /** Unwraps a VariableMap entry (typed or plain) and converts it to {@code type}. */
    public static <T> T unwrap(Object raw, Class<T> type, ObjectMapper json) {
        if (raw == null) return null;
        Object v = raw;
        if (isTypedValue(raw)) {
            Map<?, ?> m = (Map<?, ?>) raw;
            v = new TypedValue((String) m.get("type"), m.get("value")).toJava();
        } else if (raw instanceof TypedValue tv) {
            v = tv.toJava();
        }
        if (type.isInstance(v)) return type.cast(v);
        if (type == byte[].class && v instanceof String s) return type.cast(Base64.getDecoder().decode(s));
        return json.convertValue(v, type);
    }
}
