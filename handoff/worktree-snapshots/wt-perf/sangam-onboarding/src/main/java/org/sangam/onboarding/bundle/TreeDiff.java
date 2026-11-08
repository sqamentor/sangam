// SPDX-License-Identifier: Apache-2.0
package org.sangam.onboarding.bundle;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Structural difference of two JSON-like trees. Lists compare by position, except lists of keyed objects (id/code/tag). */
public final class TreeDiff {

    public enum Kind { ADDED, REMOVED, CHANGED }

    public record Entry(String path, Kind kind, Object left, Object right) {
    }

    private TreeDiff() {
    }

    public static List<Entry> diff(Object left, Object right) {
        List<Entry> out = new ArrayList<>();
        walk("", left, right, out);
        return out;
    }

    private static void walk(String path, Object a, Object b, List<Entry> out) {
        if (a instanceof Map<?, ?> x && b instanceof Map<?, ?> y) {
            Set<Object> keys = new LinkedHashSet<>(x.keySet());
            keys.addAll(y.keySet());
            for (Object k : keys) {
                String p = path.isEmpty() ? String.valueOf(k) : path + "." + k;
                if (!x.containsKey(k)) {
                    out.add(new Entry(p, Kind.ADDED, null, y.get(k)));
                } else if (!y.containsKey(k)) {
                    out.add(new Entry(p, Kind.REMOVED, x.get(k), null));
                } else {
                    walk(p, x.get(k), y.get(k), out);
                }
            }
        } else if (a instanceof List<?> x && b instanceof List<?> y && keyed(x) && keyed(y)) {
            // lists of keyed objects match by key, so a reordering is not reported as a change
            Map<String, Object> xm = new java.util.LinkedHashMap<>();
            Map<String, Object> ym = new java.util.LinkedHashMap<>();
            for (int i = 0; i < x.size(); i++) {
                xm.put(key(x.get(i), i), x.get(i));
            }
            for (int i = 0; i < y.size(); i++) {
                ym.put(key(y.get(i), i), y.get(i));
            }
            Set<String> keys = new LinkedHashSet<>(xm.keySet());
            keys.addAll(ym.keySet());
            for (String k : keys) {
                String p = path + "[" + k + "]";
                if (!xm.containsKey(k)) {
                    out.add(new Entry(p, Kind.ADDED, null, ym.get(k)));
                } else if (!ym.containsKey(k)) {
                    out.add(new Entry(p, Kind.REMOVED, xm.get(k), null));
                } else {
                    walk(p, xm.get(k), ym.get(k), out);
                }
            }
        } else if (a instanceof List<?> x && b instanceof List<?> y) {
            for (int i = 0; i < Math.max(x.size(), y.size()); i++) {
                String p = path + "[" + key(i < x.size() ? x.get(i) : y.get(i), i) + "]";
                if (i >= x.size()) {
                    out.add(new Entry(p, Kind.ADDED, null, y.get(i)));
                } else if (i >= y.size()) {
                    out.add(new Entry(p, Kind.REMOVED, x.get(i), null));
                } else {
                    walk(p, x.get(i), y.get(i), out);
                }
            }
        } else if (!Objects.equals(a, b)) {
            out.add(new Entry(path, Kind.CHANGED, a, b));
        }
    }

    /** Every element is an object with a key, and no key repeats. */
    private static boolean keyed(List<?> l) {
        Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < l.size(); i++) {
            String k = key(l.get(i), -1);
            if ("-1".equals(k) || !seen.add(k)) {
                return false;
            }
        }
        return true;
    }

    private static String key(Object o, int i) {
        if (o instanceof Map<?, ?> m) {
            for (String k : List.of("id", "code", "tag", "packConnector")) {
                if (m.get(k) != null) {
                    return String.valueOf(m.get(k));
                }
            }
            if (m.get("profile") instanceof Map<?, ?> h && h.get("id") != null) {
                return "profile:" + h.get("id"); // profile documents of a bundle
            }
        }
        return String.valueOf(i);
    }
}
