// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry.kernel;

import org.sangam.registry.support.Maps;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One attribute of a configured object type (R78 data object field). Types: {@code STRING}, {@code TEXT},
 * {@code INTEGER}, {@code DECIMAL}, {@code BOOLEAN}, {@code DATE}, {@code INSTANT}, {@code CODE} (value from
 * {@code values} or a code list), {@code REF} (id of another registered object or party), {@code OBJECT}, {@code LIST}.
 *
 * @param sensitive masked by the privacy port unless the purpose allows it
 * @param publicField included in the public verification view
 * @param immutable  cannot change after creation (only an endorsement or a correction can)
 */
public record FieldDefinition(String name, String type, boolean required, String pattern, BigDecimal min,
                              BigDecimal max, Integer maxLength, List<String> values, String codeList,
                              boolean sensitive, boolean publicField, boolean immutable, String label) {

    public static FieldDefinition from(Map<String, Object> m) {
        return new FieldDefinition(Maps.required(m, "name"), Maps.str(m, "type", "STRING").toUpperCase(),
                Maps.bool(m, "required", false), Maps.str(m, "pattern"), Maps.decimal(m, "min"), Maps.decimal(m, "max"),
                Maps.integer(m, "maxLength"), Maps.strings(m, "values"), Maps.str(m, "codeList"),
                Maps.bool(m, "sensitive", false), Maps.bool(m, "public", false), Maps.bool(m, "immutable", false),
                Maps.str(m, "label"));
    }

    public Map<String, Object> toMap() {
        return Maps.of("name", name, "type", type, "required", required, "pattern", pattern, "min", min, "max", max,
                "maxLength", maxLength, "values", values == null || values.isEmpty() ? null : values, "codeList", codeList,
                "sensitive", sensitive, "public", publicField, "immutable", immutable, "label", label);
    }

    /**
     * Validates and normalises one value.
     *
     * @return the normalised value
     * @throws IllegalArgumentException with a field-qualified message
     */
    public Object normalise(Object v) {
        if (v == null || (v instanceof String s && s.isEmpty())) {
            if (required) {
                throw new IllegalArgumentException(name + ": required");
            }
            return null;
        }
        Object out = switch (type) {
            case "STRING", "TEXT", "REF" -> {
                String s = v.toString();
                if (maxLength != null && s.length() > maxLength) {
                    throw new IllegalArgumentException(name + ": longer than " + maxLength);
                }
                if (pattern != null && !Pattern.compile(pattern).matcher(s).matches()) {
                    throw new IllegalArgumentException(name + ": does not match " + pattern);
                }
                yield s;
            }
            case "CODE" -> {
                String s = v.toString();
                if (values != null && !values.isEmpty() && !values.contains(s)) {
                    throw new IllegalArgumentException(name + ": not one of " + values);
                }
                yield s;
            }
            case "INTEGER" -> {
                long n = v instanceof Number num ? num.longValue() : Long.parseLong(v.toString().trim());
                range(BigDecimal.valueOf(n));
                yield n;
            }
            case "DECIMAL" -> {
                BigDecimal d = v instanceof BigDecimal b ? b : new BigDecimal(v.toString().trim());
                range(d);
                yield d.stripTrailingZeros().toPlainString();
            }
            case "BOOLEAN" -> v instanceof Boolean b ? b : Boolean.parseBoolean(v.toString());
            case "DATE" -> LocalDate.parse(v.toString().length() > 10 ? v.toString().substring(0, 10) : v.toString()).toString();
            case "INSTANT" -> Instant.parse(v.toString()).toString();
            case "OBJECT" -> {
                if (!(v instanceof Map<?, ?> m)) {
                    throw new IllegalArgumentException(name + ": object expected");
                }
                yield new LinkedHashMap<>(m);
            }
            case "LIST" -> {
                if (!(v instanceof List<?> l)) {
                    throw new IllegalArgumentException(name + ": list expected");
                }
                yield l;
            }
            default -> throw new IllegalArgumentException(name + ": unknown field type " + type);
        };
        return out;
    }

    private void range(BigDecimal d) {
        if (min != null && d.compareTo(min) < 0) {
            throw new IllegalArgumentException(name + ": below " + min.toPlainString());
        }
        if (max != null && d.compareTo(max) > 0) {
            throw new IllegalArgumentException(name + ": above " + max.toPlainString());
        }
    }
}
