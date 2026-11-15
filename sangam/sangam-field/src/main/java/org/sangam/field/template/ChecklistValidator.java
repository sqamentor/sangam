// SPDX-License-Identifier: Apache-2.0
package org.sangam.field.template;

import org.sangam.field.template.FieldTemplate.Item;
import org.sangam.field.template.FieldTemplate.Option;

import java.math.BigDecimal;
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
                // decision 212: exact decimal comparison; answer text must already be canonical so every client hashes one form
                BigDecimal d = exactNumber(a);
                if (d == null) {
                    // a number written with blanks around it is a number in another form, not a missing number: the
                    // answer is refused all the same, and the message names the form to send
                    BigDecimal padded = a instanceof String s ? exactNumber(s.strip()) : null;
                    return padded == null ? "number expected" : "number not canonical: expected " + canonical(padded);
                }
                if (a instanceof String s && !s.equals(canonical(d))) {
                    return "number not canonical: expected " + canonical(d);
                }
                if (!i.withinScale(d)) {
                    return "too many decimal places: at most " + i.scale();
                }
                if (!inRange(i, d)) {
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

    /**
     * Decision 212: the exact value of a NUMBER answer, or {@code null} when it is not a number. A JSON number (older
     * clients) is read exactly as {@link org.sangam.field.evidence.PacketHasher} normalises it ({@code new
     * BigDecimal(n.toString())}); text is parsed as written and is not stripped. This does not check canonical form;
     * {@link #canonicalNumber(Object)} does.
     */
    public static BigDecimal exactNumber(Object a) {
        if (a == null || a instanceof Boolean) {
            return null;
        }
        try {
            if (a instanceof BigDecimal b) {
                return b;
            }
            if (a instanceof Number n) {
                return new BigDecimal(n.toString());
            }
            if (a instanceof String s) {
                return new BigDecimal(s);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return null;
    }

    /**
     * Decision 212: the value of a NUMBER answer that is acceptable for capture, or {@code null}. JSON numbers are
     * accepted in any form and canonicalised; text must equal its canonical form ({@code 12.50}, {@code +3} and
     * {@code 1E+2} are refused).
     */
    public static BigDecimal canonicalNumber(Object a) {
        BigDecimal d = exactNumber(a);
        if (d == null || a instanceof String s && !s.equals(canonical(d))) {
            return null;
        }
        return d;
    }

    /** Canonical NUMBER text, identical to what PacketHasher.normalise writes for a JSON number (zero as "0"). */
    public static String canonical(BigDecimal d) {
        return d.signum() == 0 ? "0" : d.stripTrailingZeros().toPlainString();
    }

    /**
     * Exact range check against the template bounds, which are exact decimals ({@link FieldTemplate.Bound}, owner
     * decision 230); both bounds are inclusive.
     */
    public static boolean inRange(Item i, BigDecimal d) {
        // owner decision 230: the bounds are exact decimals, so no binary double enters the comparison
        return (i.min() == null || d.compareTo(i.min()) >= 0)
                && (i.max() == null || d.compareTo(i.max()) <= 0);
    }

    /** Coordinates of a GEO_POINT answer (not NUMBER answers; those use {@link #canonicalNumber(Object)}). */
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
