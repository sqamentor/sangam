// SPDX-License-Identifier: Apache-2.0
package org.sangam.cell.rollup;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal dependency-free JSON writer/reader for the small envelopes of the scale modules. It keeps sangam-cell free of
 * a Jackson major-version choice (cells run Jackson 2; Spring Boot 4 apps default to Jackson 3). Depth is bounded (R11).
 */
public final class MiniJson {

    private static final int MAX_DEPTH = 32;

    private MiniJson() {
    }

    public static String write(Object o) {
        StringBuilder sb = new StringBuilder();
        write(sb, o);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object o) {
        if (o == null) {
            sb.append("null");
        } else if (o instanceof String s) {
            quote(sb, s);
        } else if (o instanceof Number || o instanceof Boolean) {
            sb.append(o);
        } else if (o instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                quote(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (o instanceof Collection<?> c) {
            sb.append('[');
            boolean first = true;
            for (Object x : c) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                write(sb, x);
            }
            sb.append(']');
        } else {
            quote(sb, o.toString());
        }
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '"') {
                sb.append("\\\"");
            } else if (c == '\\') {
                sb.append("\\\\");
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\t') {
                sb.append("\\t");
            } else if (c < 0x20) {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        sb.append('"');
    }

    /** Parses JSON into Map / List / String / Long / Double / Boolean / null. */
    public static Object read(String json) {
        Parser p = new Parser(json);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != json.length()) {
            throw new IllegalArgumentException("trailing characters at " + p.i);
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> readObject(String json) {
        Object o = read(json);
        if (!(o instanceof Map)) {
            throw new IllegalArgumentException("JSON object expected");
        }
        return (Map<String, Object>) o;
    }

    private static final class Parser {
        private final String s;
        private int i;
        private int depth;

        Parser(String s) {
            this.s = s;
        }

        void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        Object value() {
            if (i >= s.length()) {
                throw new IllegalArgumentException("unexpected end of JSON");
            }
            char c = s.charAt(i);
            if (c == '{') {
                return object();
            }
            if (c == '[') {
                return array();
            }
            if (c == '"') {
                return string();
            }
            if (c == 't') {
                return literal("true", Boolean.TRUE);
            }
            if (c == 'f') {
                return literal("false", Boolean.FALSE);
            }
            if (c == 'n') {
                return literal("null", null);
            }
            return number();
        }

        private Object literal(String word, Object v) {
            if (!s.startsWith(word, i)) {
                throw new IllegalArgumentException("bad literal at " + i);
            }
            i += word.length();
            return v;
        }

        private Map<String, Object> object() {
            enter();
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (peek() == '}') {
                i++;
                depth--;
                return m;
            }
            while (true) {
                ws();
                String k = string();
                ws();
                expect(':');
                ws();
                m.put(k, value());
                ws();
                if (peek() == ',') {
                    i++;
                    continue;
                }
                expect('}');
                depth--;
                return m;
            }
        }

        private List<Object> array() {
            enter();
            List<Object> l = new ArrayList<>();
            i++;
            ws();
            if (peek() == ']') {
                i++;
                depth--;
                return l;
            }
            while (true) {
                ws();
                l.add(value());
                ws();
                if (peek() == ',') {
                    i++;
                    continue;
                }
                expect(']');
                depth--;
                return l;
            }
        }

        private void enter() {
            if (++depth > MAX_DEPTH) {
                throw new IllegalArgumentException("JSON nesting deeper than " + MAX_DEPTH);
            }
        }

        private char peek() {
            if (i >= s.length()) {
                throw new IllegalArgumentException("unexpected end of JSON");
            }
            return s.charAt(i);
        }

        private String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = peek();
                i++;
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                char e = peek();
                i++;
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> sb.append(e);
                }
            }
        }

        private Object number() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            String n = s.substring(start, i);
            if (n.isEmpty()) {
                throw new IllegalArgumentException("unexpected character at " + start);
            }
            if (n.contains(".") || n.contains("e") || n.contains("E")) {
                return Double.parseDouble(n);
            }
            return Long.parseLong(n);
        }

        private void expect(char c) {
            if (i >= s.length() || s.charAt(i) != c) {
                throw new IllegalArgumentException("expected '" + c + "' at " + i);
            }
            i++;
        }
    }
}
