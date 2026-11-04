// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.common;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Navigation and merge of profile data trees ({@code Map<String,Object>} / {@code List<Object>} / scalars).
 *
 * <p>Merge semantics (profile inheritance, most specific wins):
 * <ul>
 *   <li>map + map: merged key by key, recursively;</li>
 *   <li>list of maps where every element has an {@code id} (or {@code code}) key: merged element by key, so a lower
 *       layer can change one bill type or one unit without restating the list; an element with {@code remove: true}
 *       deletes the inherited element;</li>
 *   <li>any other list or scalar: replaced;</li>
 *   <li>an explicit {@code null} in the overlay removes the inherited key.</li>
 * </ul>
 */
public final class Tree {

    private Tree() {
    }

    @SuppressWarnings("unchecked")
    public static Object get(Map<String, Object> root, String path) {
        if (path == null || path.isEmpty()) {
            return root;
        }
        Object cur = root;
        for (String part : path.split("\\.")) {
            if (cur instanceof Map<?, ?> m) {
                cur = ((Map<String, Object>) m).get(part);
            } else if (cur instanceof List<?> l) {
                cur = findKeyed((List<Object>) l, part);
            } else {
                return null;
            }
            if (cur == null) {
                return null;
            }
        }
        return cur;
    }

    private static Object findKeyed(List<Object> list, String key) {
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                Object k = keyOf(m);
                if (k != null && k.toString().equals(key)) {
                    return o;
                }
            }
        }
        if (key.chars().allMatch(Character::isDigit)) {
            int i = Integer.parseInt(key);
            return i < list.size() ? list.get(i) : null;
        }
        return null;
    }

    public static boolean has(Map<String, Object> root, String path) {
        return get(root, path) != null;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object v) {
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> maps(Object v) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (v instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> m) {
                    out.add((Map<String, Object>) m);
                }
            }
        }
        return out;
    }

    public static List<String> strings(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> l) {
            for (Object o : l) {
                if (o != null) {
                    out.add(o.toString());
                }
            }
        } else if (v != null && !v.toString().isBlank()) {
            for (String s : v.toString().split(",")) {
                if (!s.isBlank()) {
                    out.add(s.trim());
                }
            }
        }
        return out;
    }

    public static String str(Object v, String def) {
        return v == null ? def : v.toString();
    }

    public static boolean bool(Object v, boolean def) {
        if (v == null) {
            return def;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(v.toString().trim());
    }

    public static int integer(Object v, int def) {
        if (v == null) {
            return def;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        return Integer.parseInt(v.toString().trim());
    }

    /** Plain decimal ({@code 1500}, {@code "12.5"}, {@code "1_00_000"}); unit suffixes go through MoneyFormat. */
    public static BigDecimal decimal(Object v, BigDecimal def) {
        if (v == null) {
            return def;
        }
        if (v instanceof BigDecimal b) {
            return b;
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        String s = v.toString().trim().replace("_", "").replace(",", "");
        return s.isEmpty() ? def : new BigDecimal(s);
    }

    public static LocalDate date(Object v, LocalDate def) {
        LocalDate d = EffectiveDated.date(v);
        return d == null ? def : d;
    }

    static Object keyOf(Map<?, ?> m) {
        Object k = m.get("id");
        return k != null ? k : m.get("code");
    }

    /** Deep copy of a data tree (maps keep insertion order). */
    @SuppressWarnings("unchecked")
    public static Object copy(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), copy(e.getValue()));
            }
            return out;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object o : l) {
                out.add(copy(o));
            }
            return out;
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> copyMap(Map<String, Object> m) {
        return (Map<String, Object>) copy(m);
    }

    /**
     * Merges {@code overlay} into {@code base} (base is modified and returned). {@code onSet} is told every leaf path
     * the overlay sets, so the resolver can record provenance and enforce locked paths.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> merge(Map<String, Object> base, Map<String, Object> overlay, String prefix,
                                            PathListener onSet) {
        for (Map.Entry<String, Object> e : overlay.entrySet()) {
            String key = e.getKey();
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            Object ov = e.getValue();
            Object bv = base.get(key);
            if (ov == null) {
                base.remove(key);
                onSet.set(path);
            } else if (ov instanceof Map<?, ?> om && bv instanceof Map<?, ?> bm) {
                merge((Map<String, Object>) bm, (Map<String, Object>) om, path, onSet);
            } else if (ov instanceof List<?> ol && bv instanceof List<?> bl && keyedList(ol) && keyedList(bl)) {
                base.put(key, mergeKeyed((List<Object>) bl, (List<Object>) ol, path, onSet));
            } else {
                base.put(key, copy(ov));
                onSet.set(path);
            }
        }
        return base;
    }

    @FunctionalInterface
    public interface PathListener {
        void set(String path);
    }

    static boolean keyedList(List<?> l) {
        if (l.isEmpty()) {
            return false;
        }
        for (Object o : l) {
            if (!(o instanceof Map<?, ?> m) || keyOf(m) == null) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> mergeKeyed(List<Object> base, List<Object> overlay, String path, PathListener onSet) {
        Map<String, Object> byKey = new LinkedHashMap<>();
        for (Object o : base) {
            byKey.put(keyOf((Map<?, ?>) o).toString(), o);
        }
        for (Object o : overlay) {
            Map<String, Object> om = (Map<String, Object>) o;
            String k = keyOf(om).toString();
            String p = path + "." + k;
            if (bool(om.get("remove"), false)) {
                byKey.remove(k);
                onSet.set(p);
                continue;
            }
            Object existing = byKey.get(k);
            if (existing instanceof Map<?, ?> em) {
                merge((Map<String, Object>) em, om, p, onSet);
            } else {
                byKey.put(k, copy(om));
                onSet.set(p);
            }
        }
        return new ArrayList<>(byKey.values());
    }
}
