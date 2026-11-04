package org.sangam.spike.govc.support;

import java.util.Collection;
import java.util.Map;

/** Minimal JSON writer (no Jackson: the two engines need different Jackson lines, see README). */
public final class Json {

    private Json() {
    }

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, 0);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v, int indent) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Boolean || v instanceof Integer || v instanceof Long || v instanceof Short) {
            sb.append(v);
        } else if (v instanceof Number n) {
            double d = n.doubleValue();
            sb.append(Double.isFinite(d) ? n.toString() : "null");
        } else if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append("{\n");
            int i = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                pad(sb, indent + 1);
                string(sb, String.valueOf(e.getKey()));
                sb.append(": ");
                write(sb, e.getValue(), indent + 1);
                if (++i < m.size()) {
                    sb.append(',');
                }
                sb.append('\n');
            }
            pad(sb, indent);
            sb.append('}');
        } else if (v instanceof Collection<?> c) {
            if (c.isEmpty()) {
                sb.append("[]");
                return;
            }
            boolean simple = c.stream().allMatch(x -> x == null || x instanceof String || x instanceof Number
                    || x instanceof Boolean);
            if (simple) {
                sb.append('[');
                int i = 0;
                for (Object x : c) {
                    write(sb, x, indent);
                    if (++i < c.size()) {
                        sb.append(", ");
                    }
                }
                sb.append(']');
                return;
            }
            sb.append("[\n");
            int i = 0;
            for (Object x : c) {
                pad(sb, indent + 1);
                write(sb, x, indent + 1);
                if (++i < c.size()) {
                    sb.append(',');
                }
                sb.append('\n');
            }
            pad(sb, indent);
            sb.append(']');
        } else {
            string(sb, String.valueOf(v));
        }
    }

    private static void pad(StringBuilder sb, int indent) {
        sb.append("  ".repeat(indent));
    }

    private static void string(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
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
