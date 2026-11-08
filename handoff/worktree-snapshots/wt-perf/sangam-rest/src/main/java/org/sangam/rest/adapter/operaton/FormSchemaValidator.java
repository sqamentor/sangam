// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.fasterxml.jackson.databind.JsonNode;

import org.sangam.rest.port.FormPort.FieldError;

/**
 * Authoritative server-side validator for the Sangam form-data profile of JSON Schema 2020-12 (X.2.5.1, X.2.5.2).
 *
 * <p>Supported keywords: {@code type} (string or array), {@code required}, {@code properties},
 * {@code additionalProperties: false}, {@code enum}, {@code const}, {@code minLength}, {@code maxLength},
 * {@code pattern}, {@code minimum}, {@code maximum}, {@code exclusiveMinimum}, {@code exclusiveMaximum},
 * {@code minItems}, {@code maxItems}, {@code items}; formats {@code decimal} (with {@code x-sangam-scale}),
 * {@code integer-string}, {@code date}, {@code date-time}, {@code email}. Numeric comparisons use {@link BigDecimal}
 * (money is never a binary float, R33). Error paths are JSON pointers; message keys are {@code error.<code>}.
 */
public final class FormSchemaValidator {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern DECIMAL = Pattern.compile("^-?(0|[1-9][0-9]*)(\\.[0-9]+)?$");
    private static final Pattern INTEGER_STRING = Pattern.compile("^-?(0|[1-9][0-9]*)$");
    private static final int MAX_DEPTH = 64;

    public List<FieldError> validate(JsonNode schema, JsonNode data) {
        List<FieldError> errors = new ArrayList<>();
        if (schema != null && !schema.isMissingNode() && !schema.isNull()) {
            check(schema, data, "", errors, 0);
        }
        return errors;
    }

    /** Required flags of a form-js layout ({@code components[].validate.required}), used when no data schema exists. */
    public List<FieldError> validateLayoutRequired(JsonNode layout, JsonNode data) {
        List<FieldError> errors = new ArrayList<>();
        walkLayout(layout, data, errors, 0);
        return errors;
    }

    private void walkLayout(JsonNode node, JsonNode data, List<FieldError> errors, int depth) {
        if (node == null || depth > MAX_DEPTH) {
            return;
        }
        JsonNode components = node.get("components");
        if (components == null || !components.isArray()) {
            return;
        }
        for (JsonNode c : components) {
            JsonNode key = c.get("key");
            JsonNode validate = c.get("validate");
            if (key != null && key.isTextual() && validate != null && validate.path("required").asBoolean(false)) {
                JsonNode v = data == null ? null : data.get(key.asText());
                if (v == null || v.isNull() || (v.isTextual() && v.asText().isEmpty())) {
                    errors.add(error("/" + escape(key.asText()), "REQUIRED"));
                }
            }
            walkLayout(c, data, errors, depth + 1);
        }
    }

    private void check(JsonNode schema, JsonNode value, String path, List<FieldError> errors, int depth) {
        if (depth > MAX_DEPTH || !schema.isObject()) {
            return;
        }
        if (value == null || value.isMissingNode()) {
            return; // absence is handled by "required" of the parent
        }
        JsonNode type = schema.get("type");
        if (type != null && !typeMatches(type, value)) {
            errors.add(error(path, "TYPE"));
            return;
        }
        JsonNode en = schema.get("enum");
        if (en != null && en.isArray()) {
            boolean found = false;
            for (JsonNode e : en) {
                if (jsonEquals(e, value)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                errors.add(error(path, "ENUM"));
            }
        }
        JsonNode cst = schema.get("const");
        if (cst != null && !jsonEquals(cst, value)) {
            errors.add(error(path, "CONST"));
        }
        if (value.isTextual()) {
            checkString(schema, value.asText(), path, errors);
        }
        if (value.isNumber() || (value.isTextual() && isNumericFormat(schema))) {
            checkNumber(schema, value, path, errors);
        }
        if (value.isObject()) {
            checkObject(schema, value, path, errors, depth);
        }
        if (value.isArray()) {
            checkArray(schema, value, path, errors, depth);
        }
    }

    private void checkString(JsonNode schema, String s, String path, List<FieldError> errors) {
        int len = s.codePointCount(0, s.length());
        if (schema.has("minLength") && len < schema.get("minLength").asInt()) {
            errors.add(error(path, "MIN_LENGTH"));
        }
        if (schema.has("maxLength") && len > schema.get("maxLength").asInt()) {
            errors.add(error(path, "MAX_LENGTH"));
        }
        if (schema.has("pattern")) {
            try {
                if (!Pattern.compile(schema.get("pattern").asText()).matcher(s).find()) {
                    errors.add(error(path, "PATTERN"));
                }
            } catch (PatternSyntaxException e) {
                errors.add(error(path, "SCHEMA_PATTERN_INVALID"));
            }
        }
        String format = schema.path("format").asText(null);
        if (format == null) {
            return;
        }
        switch (format) {
            case "decimal" -> {
                if (!DECIMAL.matcher(s).matches()) {
                    errors.add(error(path, "FORMAT_DECIMAL"));
                } else if (schema.has("x-sangam-scale")) {
                    int scale = schema.get("x-sangam-scale").asInt();
                    if (new BigDecimal(s).scale() > scale) {
                        errors.add(error(path, "DECIMAL_SCALE"));
                    }
                }
            }
            case "integer-string" -> {
                if (!INTEGER_STRING.matcher(s).matches()) {
                    errors.add(error(path, "FORMAT_INTEGER_STRING"));
                }
            }
            case "date" -> {
                try {
                    LocalDate.parse(s);
                } catch (DateTimeParseException e) {
                    errors.add(error(path, "FORMAT_DATE"));
                }
            }
            case "date-time" -> {
                try {
                    OffsetDateTime.parse(s);
                } catch (DateTimeParseException e) {
                    errors.add(error(path, "FORMAT_DATE_TIME"));
                }
            }
            case "local-date-time" -> {
                try {
                    java.time.LocalDateTime.parse(s);
                } catch (DateTimeParseException e) {
                    errors.add(error(path, "FORMAT_LOCAL_DATE_TIME"));
                }
            }
            case "email" -> {
                if (!EMAIL.matcher(s).matches()) {
                    errors.add(error(path, "FORMAT_EMAIL"));
                }
            }
            default -> {
                // unknown formats are annotations only (JSON Schema 2020-12 default)
            }
        }
    }

    private static boolean isNumericFormat(JsonNode schema) {
        String f = schema.path("format").asText("");
        return f.equals("decimal") || f.equals("integer-string");
    }

    private void checkNumber(JsonNode schema, JsonNode value, String path, List<FieldError> errors) {
        BigDecimal n;
        try {
            n = value.isNumber() ? value.decimalValue() : new BigDecimal(value.asText());
        } catch (NumberFormatException e) {
            return; // the format check reports it
        }
        if (schema.has("minimum") && n.compareTo(bound(schema.get("minimum"))) < 0) {
            errors.add(error(path, "MINIMUM"));
        }
        if (schema.has("maximum") && n.compareTo(bound(schema.get("maximum"))) > 0) {
            errors.add(error(path, "MAXIMUM"));
        }
        if (schema.has("exclusiveMinimum") && n.compareTo(bound(schema.get("exclusiveMinimum"))) <= 0) {
            errors.add(error(path, "EXCLUSIVE_MINIMUM"));
        }
        if (schema.has("exclusiveMaximum") && n.compareTo(bound(schema.get("exclusiveMaximum"))) >= 0) {
            errors.add(error(path, "EXCLUSIVE_MAXIMUM"));
        }
    }

    private static BigDecimal bound(JsonNode b) {
        return b.isNumber() ? b.decimalValue() : new BigDecimal(b.asText());
    }

    private void checkObject(JsonNode schema, JsonNode value, String path, List<FieldError> errors, int depth) {
        JsonNode props = schema.get("properties");
        JsonNode req = schema.get("required");
        if (req != null && req.isArray()) {
            for (JsonNode r : req) {
                JsonNode v = value.get(r.asText());
                if (v == null || v.isNull()) {
                    errors.add(error(path + "/" + escape(r.asText()), "REQUIRED"));
                }
            }
        }
        JsonNode additional = schema.get("additionalProperties");
        Iterator<Map.Entry<String, JsonNode>> it = value.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> f = it.next();
            String childPath = path + "/" + escape(f.getKey());
            JsonNode childSchema = props == null ? null : props.get(f.getKey());
            if (childSchema != null) {
                if (!f.getValue().isNull()) {
                    check(childSchema, f.getValue(), childPath, errors, depth + 1);
                }
            } else if (additional != null && additional.isBoolean() && !additional.asBoolean()) {
                errors.add(error(childPath, "ADDITIONAL_PROPERTY"));
            } else if (additional != null && additional.isObject()) {
                check(additional, f.getValue(), childPath, errors, depth + 1);
            }
        }
    }

    private void checkArray(JsonNode schema, JsonNode value, String path, List<FieldError> errors, int depth) {
        if (schema.has("minItems") && value.size() < schema.get("minItems").asInt()) {
            errors.add(error(path, "MIN_ITEMS"));
        }
        if (schema.has("maxItems") && value.size() > schema.get("maxItems").asInt()) {
            errors.add(error(path, "MAX_ITEMS"));
        }
        JsonNode items = schema.get("items");
        if (items != null && items.isObject()) {
            for (int i = 0; i < value.size(); i++) {
                check(items, value.get(i), path + "/" + i, errors, depth + 1);
            }
        }
    }

    private static boolean typeMatches(JsonNode type, JsonNode v) {
        if (type.isArray()) {
            for (JsonNode t : type) {
                if (typeMatches(t, v)) {
                    return true;
                }
            }
            return false;
        }
        return switch (type.asText()) {
            case "string" -> v.isTextual();
            case "number" -> v.isNumber();
            case "integer" -> v.isIntegralNumber() || (v.isNumber() && isWhole(v.decimalValue()));
            case "boolean" -> v.isBoolean();
            case "object" -> v.isObject();
            case "array" -> v.isArray();
            case "null" -> v.isNull();
            default -> true;
        };
    }

    private static boolean isWhole(BigDecimal d) {
        try {
            d.toBigIntegerExact();
            return true;
        } catch (ArithmeticException e) {
            return false;
        }
    }

    private static boolean jsonEquals(JsonNode a, JsonNode b) {
        if (a.isNumber() && b.isNumber()) {
            return a.decimalValue().compareTo(b.decimalValue()) == 0;
        }
        return a.equals(b);
    }

    static String escape(String key) {
        return key.replace("~", "~0").replace("/", "~1");
    }

    static FieldError error(String path, String code) {
        return new FieldError(path.isEmpty() ? "" : path, code, "error." + code);
    }

    /** Exposed for tests of integer bounds. */
    static BigInteger parseInteger(String s) {
        return new BigInteger(s);
    }
}
