// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.support;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.sangam.packs.engine.DecimalText;
import org.sangam.packs.engine.LocalDateText;
import org.sangam.rest.port.ApiProblem;

/**
 * R33 typed JSON for variables. Plain JSON values pass through. A typed value is written as
 * {@code {"type":"Decimal","value":"1234.50"}}; money is never a binary float and large integers are strings (E18).
 * Supported types: Decimal, BigInteger, Long, Integer, Boolean, String, Date (ISO-8601 instant), LocalDate, Json.
 * Outbound, BigDecimal/BigInteger/Date/LocalDate are written back in the typed form.
 * A typed value that is not of its declared type (a {@code Date} that is not an ISO-8601 instant such as
 * {@code 2026-03-31T18:30:00Z}, a {@code Long} that is not an integer, a {@code Boolean} other than {@code true} or
 * {@code false}) or an unknown type is refused with {@code VALIDATION_FAILED} (400), never coerced.
 *
 * <p>Decision 333: {@code {"type":"LocalDate","value":"2026-03-31"}} becomes a {@link LocalDate}, which the engine stores
 * as a variable type of its own ({@code sangam-local-date-v1}, ISO text) and which comes back as the same typed value,
 * also inside a Json map or list. The text must be {@code uuuu-MM-dd} exactly, years 0000-9999 (engine format v1,
 * {@link LocalDateText}); anything else, a date-time included, is refused with {@code VALIDATION_FAILED}, never
 * coerced. {@code Date} is a different type: an instant ({@code java.util.Date}), never a calendar date. The lower-case
 * aliases ({@code decimal}, {@code integer-string}, {@code date-time}, {@code date}, ...) name the same types as the JSON
 * Schema formats of the form profile; {@code date} is {@code LocalDate}. A plain string stays a string: a date written
 * untyped is stored and returned as text, as before.
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
            if ("Decimal".equals(type) || "decimal".equals(type)) {
                return decimal(value);
            }
            String s = String.valueOf(value);
            try {
                return switch (type) {
                    case "BigInteger", "integer-string" -> new BigInteger(s);
                    case "Long", "long" -> Long.parseLong(s);
                    case "Integer", "integer" -> Integer.parseInt(s);
                    case "Boolean", "boolean" -> bool(s);
                    case "String", "string" -> s;
                    case "Date", "date-time" -> Date.from(Instant.parse(s));
                    case "LocalDate", "date" -> localDate(s);
                    case "Json", "json" -> value;
                    default -> throw ApiProblem.badRequest("VALIDATION_FAILED", "unknown typed variable type " + type);
                };
            } catch (NumberFormatException | DateTimeParseException e) {
                // UI-DEMO sweep S8: a value that is not of its declared type is the caller's error (400), never a 500
                throw ApiProblem.badRequest("VALIDATION_FAILED", "typed variable " + type + ": not a valid value: " + s);
            }
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
            // Never a binary float in the engine (R33). A request body never brings a Double here: the server's JSON
            // mapper reads fractions as BigDecimal from their text (ExactJsonNumbersWiring, decision 138); this branch
            // only keeps an in-process caller's Double from becoming a double variable.
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
            return typed("Decimal", decimalText(d));
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

    /**
     * G25 (decision 138): the exact decimal of a typed {@code Decimal} value, through the engine's strict
     * {@link DecimalText} rules: decimal text ({@link DecimalText#parse}: no blanks, no plus sign, no {@code NaN}, at
     * most {@value DecimalText#MAX_CHARS} characters) or an exact JSON integer; a JSON number with a fraction arrives as
     * a binary float and is refused, as is anything else. Every refusal is {@code VALIDATION_FAILED}.
     */
    static BigDecimal decimal(Object value) {
        try {
            return DecimalText.exact(value);
        } catch (DecimalText.DecimalFormatException e) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "typed variable Decimal: " + e.getMessage());
        }
    }

    /**
     * G25: the text of a decimal on the way out, lossless: plain decimal text (the DecimalString form, scale kept as
     * written, {@code 1.50} stays {@code 1.50}) whenever that keeps value and scale, which is every decimal of scale 0
     * or more; a decimal of negative scale ({@code 1E+3}) has no plain text of the same scale ({@code toPlainString}
     * gave {@code 1000}, scale 0) and is written as its {@link DecimalText} canonical text, as is one whose plain text
     * would exceed {@value DecimalText#MAX_CHARS} characters. Either text reads back through {@link #decimal} to the same
     * value and scale.
     */
    static String decimalText(BigDecimal d) {
        String canonical = DecimalText.format(d);
        if (d.scale() < 0 || d.scale() > DecimalText.MAX_CHARS) {
            return canonical;
        }
        String plain = d.toPlainString();
        return plain.length() <= DecimalText.MAX_CHARS ? plain : canonical;
    }

    /** A typed Boolean is {@code true} or {@code false}; any other text is refused, never read as {@code false}. */
    private static Boolean bool(String text) {
        return switch (text) {
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            default -> throw ApiProblem.badRequest("VALIDATION_FAILED", "typed variable Boolean: not a valid value: " + text);
        };
    }

    /** Decision 333: strict ISO date text as a {@link LocalDate}; invalid text is a validation problem. */
    static LocalDate localDate(String text) {
        try {
            return LocalDateText.parse(text);
        } catch (LocalDateText.LocalDateFormatException e) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "typed variable LocalDate: " + e.getMessage());
        }
    }

    private static Map<String, Object> typed(String type, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("value", value);
        return m;
    }
}
