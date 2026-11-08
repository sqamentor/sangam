package org.sangam.core.layer;

import java.math.BigInteger;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;

/**
 * Minimal canonical JSON writer in the spirit of RFC 8785 (JCS) for the values this layer hashes:
 * object members sorted by key (UTF-16 code-unit order, as JCS requires), no whitespace, JCS string escaping.
 *
 * <p>Numbers: only integral values are accepted. Decimals must be carried as strings (R33: typed JSON carries
 * decimals and large integers as strings), which sidesteps JCS's ECMAScript number formatting.
 */
public final class CanonicalJson {

    private CanonicalJson() {
    }

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        append(sb, value);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void append(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            string(sb, s);
        } else if (v instanceof Boolean b) {
            sb.append(b ? "true" : "false");
        } else if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte || v instanceof BigInteger) {
            sb.append(v);
        } else if (v instanceof Number n) {
            throw new IllegalArgumentException("non-integral number " + n + ": carry decimals as strings (R33)");
        } else if (v instanceof Map<?, ?> m) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                sorted.put(String.valueOf(e.getKey()), e.getValue());
            }
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : sorted.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                string(sb, e.getKey());
                sb.append(':');
                append(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof Collection<?> c) {
            sb.append('[');
            boolean first = true;
            for (Object o : c) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                append(sb, o);
            }
            sb.append(']');
        } else {
            string(sb, v.toString());
        }
    }

    private static void string(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        sb.append('"');
    }
}
