// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.forms;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Server-side form validation (FLOWABLE-INTO-OPERATON FC-39, R47): the client renderer validates for usability, the server validates for
 * correctness. Values are type-coerced (strings from HTML forms become numbers/booleans/dates), unknown fields are
 * dropped (no mass assignment of arbitrary variables), read-only fields cannot be set, and conditional visibility /
 * requiredness is evaluated from the submitted values. Patterns are length-guarded against ReDoS-prone input.
 */
public final class FormValidator {

    private static final int MAX_PATTERN_INPUT = 10_000;

    public record Result(boolean valid, Map<String, List<String>> errors, Map<String, Object> values) {
    }

    private FormValidator() {
    }

    public static Result validate(FormDefinition def, Map<String, Object> submitted) {
        Map<String, Object> in = submitted == null ? Map.of() : submitted;
        Map<String, List<String>> errors = new LinkedHashMap<>();
        Map<String, Object> out = new LinkedHashMap<>();
        for (FormDefinition.Field f : def.fields()) {
            boolean visible = f.visibleIf() == null || matches(f.visibleIf(), in);
            if (!visible) {
                continue;
            }
            if (f.isReadOnly()) {
                if (in.containsKey(f.id())) {
                    err(errors, f, "is read-only");
                }
                continue;
            }
            boolean required = f.isRequired() || (f.requiredIf() != null && matches(f.requiredIf(), in));
            Object raw = in.get(f.id());
            if (raw == null || (raw instanceof String s && s.isBlank())) {
                if (required) {
                    err(errors, f, "is required");
                } else if (f.defaultValue() != null) {
                    out.put(f.id(), f.defaultValue());
                }
                continue;
            }
            try {
                Object v = coerce(f, raw);
                check(f, v, errors);
                out.put(f.id(), v);
            } catch (IllegalArgumentException e) {
                err(errors, f, e.getMessage());
            }
        }
        return new Result(errors.isEmpty(), errors, out);
    }

    static boolean matches(FormDefinition.Condition c, Map<String, Object> values) {
        Object v = values.get(c.field());
        return v != null && c.in() != null && c.in().contains(String.valueOf(v));
    }

    static Object coerce(FormDefinition.Field f, Object raw) {
        String t = f.typeOrText();
        return switch (t) {
            case "text", "textarea", "email", "phone", "select", "radio", "document" -> String.valueOf(raw);
            case "integer" -> raw instanceof Number n ? n.longValue() : parseLong(raw);
            case "decimal" -> raw instanceof Number n ? n.doubleValue() : parseDouble(raw);
            case "boolean" -> raw instanceof Boolean b ? b : parseBoolean(raw);
            case "date" -> LocalDate.parse(String.valueOf(raw)).toString();
            case "datetime" -> java.util.Date.from(Instant.parse(String.valueOf(raw)));
            case "multiselect" -> raw instanceof Collection<?> col ? col.stream().map(String::valueOf).toList()
                    : List.of(String.valueOf(raw).split(","));
            case "json" -> raw instanceof String s ? s : org.sangam.ports.support.Json.write(raw);
            default -> throw new IllegalArgumentException("has unsupported type " + t);
        };
    }

    private static void check(FormDefinition.Field f, Object v, Map<String, List<String>> errors) {
        String t = f.typeOrText();
        if (v instanceof String s) {
            if (f.minLength() != null && s.length() < f.minLength()) {
                err(errors, f, "must have at least " + f.minLength() + " characters");
            }
            if (f.maxLength() != null && s.length() > f.maxLength()) {
                err(errors, f, "must have at most " + f.maxLength() + " characters");
            }
            if ("email".equals(t) && !s.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
                err(errors, f, "is not a valid e-mail address");
            }
            if ("phone".equals(t) && !s.matches("^\\+?[0-9 ()-]{6,20}$")) {
                err(errors, f, "is not a valid phone number");
            }
            if (f.pattern() != null) {
                if (s.length() > MAX_PATTERN_INPUT) {
                    err(errors, f, "is too long to validate");
                } else {
                    try {
                        if (!Pattern.compile(f.pattern()).matcher(s).matches()) {
                            err(errors, f, "does not match the required format");
                        }
                    } catch (PatternSyntaxException e) {
                        err(errors, f, "has an invalid pattern in the form definition");
                    }
                }
            }
            if (("select".equals(t) || "radio".equals(t)) && f.options() != null && !f.options().isEmpty()
                    && f.options().stream().noneMatch(o -> o.value().equals(s))) {
                err(errors, f, "has a value that is not an option");
            }
        }
        if (v instanceof List<?> l && "multiselect".equals(t) && f.options() != null && !f.options().isEmpty()) {
            for (Object o : l) {
                if (f.options().stream().noneMatch(op -> op.value().equals(String.valueOf(o)))) {
                    err(errors, f, "has a value that is not an option: " + o);
                }
            }
        }
        if (v instanceof Number n) {
            BigDecimal d = new BigDecimal(n.toString());
            if (f.min() != null && d.compareTo(BigDecimal.valueOf(f.min())) < 0) {
                err(errors, f, "must be at least " + f.min());
            }
            if (f.max() != null && d.compareTo(BigDecimal.valueOf(f.max())) > 0) {
                err(errors, f, "must be at most " + f.max());
            }
        }
    }

    private static long parseLong(Object raw) {
        try {
            return Long.parseLong(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("must be a whole number");
        }
    }

    private static double parseDouble(Object raw) {
        try {
            return Double.parseDouble(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("must be a number");
        }
    }

    private static boolean parseBoolean(Object raw) {
        String s = String.valueOf(raw).trim().toLowerCase(java.util.Locale.ROOT);
        return switch (s) {
            case "true", "on", "yes", "1" -> true;
            case "false", "off", "no", "0" -> false;
            default -> throw new IllegalArgumentException("must be true or false");
        };
    }

    private static void err(Map<String, List<String>> errors, FormDefinition.Field f, String msg) {
        errors.computeIfAbsent(f.id(), k -> new ArrayList<>()).add((f.label() == null ? f.id() : f.label()) + " " + msg);
    }
}
