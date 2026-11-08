// SPDX-License-Identifier: Apache-2.0
package org.sangam.field.template;

import org.sangam.field.template.FieldTemplate.Item;
import org.sangam.field.template.FieldTemplate.Option;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Validates captured answers against a template: visibility, required items, types, ranges, options, photo counts. */
public final class ChecklistValidator {

    public record Violation(String itemId, String code, String message) {
    }

    private ChecklistValidator() {
    }

    public static boolean visible(Item item, Map<String, Object> answers) {
        FieldTemplate.VisibleIf v = item.visibleIf();
        if (v == null) {
            return true;
        }
        Object a = answers.get(v.item());
        if (v.in() != null && !v.in().isEmpty()) {
            return v.in().stream().anyMatch(x -> same(x, a));
        }
        return same(v.equals(), a);
    }

    static boolean same(Object expected, Object actual) {
        if (expected == null || actual == null) {
            return expected == actual;
        }
        if (expected instanceof Number en && actual instanceof Number an) {
            return Double.compare(en.doubleValue(), an.doubleValue()) == 0;
        }
        return Objects.equals(String.valueOf(expected), String.valueOf(actual));
    }

    static boolean blank(Object a) {
        return a == null || (a instanceof String s && s.isBlank()) || (a instanceof Collection<?> c && c.isEmpty());
    }

    /**
     * @param photoCounts number of evidence items attached per item id (PHOTO / SIGNATURE items)
     */
    public static List<Violation> validate(FieldTemplate t, Map<String, Object> answers, Map<String, Integer> photoCounts) {
        Map<String, Object> ans = answers == null ? Map.of() : answers;
        Map<String, Integer> photos = photoCounts == null ? Map.of() : photoCounts;
        List<Violation> out = new ArrayList<>();
        for (String k : ans.keySet()) {
            if (t.item(k) == null) {
                out.add(new Violation(k, "UNKNOWN_ITEM", "answer for an item not in the template"));
            }
        }
        for (Item i : t.items()) {
            if (!visible(i, ans)) {
                continue;
            }
            Object a = ans.get(i.id());
            boolean media = i.type() == FieldTemplate.ItemType.PHOTO || i.type() == FieldTemplate.ItemType.SIGNATURE;
            if (media) {
                int n = photos.getOrDefault(i.id(), 0);
                int need = Math.max(i.minPhotos(), i.required() ? 1 : 0);
                if (n < need) {
                    out.add(new Violation(i.id(), "EVIDENCE_MISSING", "needs " + need + " attachment(s), got " + n));
                }
                continue;
            }
            if (blank(a)) {
                if (i.required()) {
                    out.add(new Violation(i.id(), "REQUIRED", "answer is required"));
                }
                continue;
            }
            String bad = typeError(i, a);
            if (bad != null) {
                out.add(new Violation(i.id(), "INVALID_ANSWER", bad));
            }
        }
        return out;
    }

    private static String typeError(Item i, Object a) {
        switch (i.type()) {
            case BOOLEAN -> {
                if (!(a instanceof Boolean) && !"true".equals(String.valueOf(a)) && !"false".equals(String.valueOf(a))) {
                    return "boolean expected";
                }
            }
            case NUMBER -> {
                Double d = number(a);
                if (d == null) {
                    return "number expected";
                }
                if (i.min() != null && d < i.min() || i.max() != null && d > i.max()) {
                    return "out of range";
                }
            }
            case CHOICE -> {
                if (option(i, a) == null) {
                    return "unknown option";
                }
            }
            case MULTI_CHOICE -> {
                if (!(a instanceof Collection<?> c)) {
                    return "list expected";
                }
                for (Object x : c) {
                    if (option(i, x) == null) {
                        return "unknown option: " + x;
                    }
                }
            }
            case DATE -> {
                try {
                    LocalDate.parse(String.valueOf(a).substring(0, Math.min(10, String.valueOf(a).length())));
                } catch (RuntimeException e) {
                    return "ISO date expected";
                }
            }
            case GEO_POINT -> {
                if (!(a instanceof Map<?, ?> m) || number(m.get("lat")) == null || number(m.get("lon")) == null) {
                    return "{lat, lon} expected";
                }
            }
            default -> {
                return null;
            }
        }
        return null;
    }

    public static Option option(Item i, Object a) {
        if (i.options() == null || a == null) {
            return null;
        }
        return i.options().stream().filter(o -> Objects.equals(o.value(), String.valueOf(a))).findFirst().orElse(null);
    }

    public static Double number(Object a) {
        if (a instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return a == null ? null : Double.parseDouble(String.valueOf(a).strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
