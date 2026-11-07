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

    private static String key(Object o, int i) {
        if (o instanceof Map<?, ?> m) {
            for (String k : List.of("id", "code", "tag", "packConnector")) {
                if (m.get(k) != null) {
                    return String.valueOf(m.get(k));
                }
            }
        }
        return String.valueOf(i);
    }
}
