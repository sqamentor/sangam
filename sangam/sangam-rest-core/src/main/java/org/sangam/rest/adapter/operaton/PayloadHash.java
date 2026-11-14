// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

import org.sangam.core.layer.CanonicalJson;
import org.sangam.core.layer.Hashes;
import org.sangam.rest.support.TypedVariables;

/**
 * Material payload hash (VI.4 rule 5, K4, R43). The material fields are named by the process variable
 * {@code sangamMaterialFields} (a list or a comma-separated string); without it, every process variable whose name
 * does not start with {@code sangam} is material. The hash is the hex SHA-256 of the RFC 8785 canonical JSON of
 * {@code {field: typedJsonValue}} (R33 typed values, so a money amount hashes exactly).
 */
public final class PayloadHash {

    public static final String MATERIAL_FIELDS = "sangamMaterialFields";

    private PayloadHash() {
    }

    public static List<String> materialFields(Map<String, ?> vars) {
        Object declared = vars == null ? null : vars.get(MATERIAL_FIELDS);
        TreeSet<String> names = new TreeSet<>();
        if (declared instanceof Collection<?> c) {
            c.forEach(x -> names.add(String.valueOf(x).trim()));
        } else if (declared instanceof String s && !s.isBlank()) {
            Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty()).forEach(names::add);
        } else if (vars != null) {
            vars.keySet().stream().filter(k -> !k.startsWith("sangam")).forEach(names::add);
        }
        return new ArrayList<>(names);
    }

    public static Map<String, Object> material(Map<String, ?> vars) {
        TreeMap<String, Object> m = new TreeMap<>();
        for (String f : materialFields(vars)) {
            m.put(f, jsonSafe(vars == null ? null : vars.get(f)));
        }
        return m;
    }

    /**
     * R33 typed JSON that {@link CanonicalJson} accepts: decimals and binary floats become typed decimal strings,
     * nested maps/lists are converted recursively, unknown objects become their string form.
     */
    public static Object jsonSafe(Object v) {
        Object t = TypedVariables.toJsonValue(v);
        return normalize(t);
    }

    private static Object normalize(Object v) {
        if (v == null || v instanceof String || v instanceof Boolean || v instanceof Integer || v instanceof Long
                || v instanceof Short || v instanceof Byte || v instanceof java.math.BigInteger) {
            return v;
        }
        if (v instanceof Double || v instanceof Float || v instanceof java.math.BigDecimal) {
            TreeMap<String, Object> typed = new TreeMap<>();
            typed.put("type", "Decimal");
            typed.put("value", new java.math.BigDecimal(v.toString()).toPlainString());
            return typed;
        }
        if (v instanceof Number n) {
            return n.toString();
        }
        if (v instanceof Map<?, ?> m) {
            TreeMap<String, Object> out = new TreeMap<>();
            m.forEach((k, x) -> out.put(String.valueOf(k), normalize(x)));
            return out;
        }
        if (v instanceof Collection<?> c) {
            List<Object> out = new ArrayList<>(c.size());
            c.forEach(x -> out.add(normalize(x)));
            return out;
        }
        return String.valueOf(v);
    }

    /** Canonical JSON of any variable-like value (never throws on floats). */
    public static String canonical(Object v) {
        return CanonicalJson.write(jsonSafe(v));
    }

    public static String hash(Map<String, ?> vars) {
        return Hashes.hex(Hashes.sha256(CanonicalJson.write(material(vars))));
    }

    /** Material fields whose values differ between two variable maps (the fields of {@code before} decide). */
    public static List<String> changedFields(Map<String, ?> before, Map<String, ?> after) {
        Map<String, Object> a = material(before);
        TreeSet<String> fields = new TreeSet<>(a.keySet());
        fields.addAll(materialFields(after));
        List<String> changed = new ArrayList<>();
        for (String f : fields) {
            Object x = a.get(f);
            Object y = jsonSafe(after == null ? null : after.get(f));
            if (!Objects.equals(CanonicalJson.write(x == null ? "\u0000null" : x),
                    CanonicalJson.write(y == null ? "\u0000null" : y))) {
                changed.add(f);
            }
        }
        return changed;
    }

    /** Hex SHA-256 over {@code {field: {old, new}}} of the changed material fields (CASE_RESUBMITTED diff hash). */
    public static String diffHash(Map<String, ?> before, Map<String, ?> after, List<String> changed) {
        TreeMap<String, Object> diff = new TreeMap<>();
        for (String f : changed) {
            TreeMap<String, Object> d = new TreeMap<>();
            d.put("old", jsonSafe(before == null ? null : before.get(f)));
            d.put("new", jsonSafe(after == null ? null : after.get(f)));
            diff.put(f, d);
        }
        return Hashes.hex(Hashes.sha256(CanonicalJson.write(diff)));
    }
}
