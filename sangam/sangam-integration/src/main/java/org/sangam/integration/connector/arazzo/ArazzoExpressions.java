// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.arazzo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;

import org.sangam.integration.connector.Json;

/**
 * Owner decision 159: the subset of Arazzo 1.1.0 runtime expressions the operation step evaluates, and the "simple"
 * and "regex" success criteria. No conformance claim.
 *
 * <ul>
 *   <li>Expressions: {@code $statusCode}, {@code $response.body} and {@code $response.body#/json/pointer},
 *       {@code $inputs.<name>} (a process variable), {@code $steps.<stepId>.outputs.<name>} (the process variable of that
 *       name, as the importer's steps write it), each optionally followed by {@code #/json/pointer}. A string that is
 *       exactly one expression becomes its value; {@code {$...}} inside a longer string is replaced by the value's
 *       text. Anything else ({@code $response.header.*}, {@code $request.*}, {@code $url}, {@code $method},
 *       {@code $sourceDescriptions.*}, {@code $workflows.*}, {@code $components.*}) is refused: a step never runs on an
 *       expression it cannot evaluate.</li>
 *   <li>Simple conditions: operands are expressions, numbers, {@code 'strings'}, {@code true}, {@code false},
 *       {@code null}; operators {@code == != < <= > >=}, {@code &&}, {@code ||}, {@code !} and parentheses.</li>
 *   <li>Regex criteria: {@code context} is an expression, {@code condition} a Java regular expression that must find a
 *       match in its text.</li>
 * </ul>
 */
final class ArazzoExpressions {

    /** What expressions read: the response of this step and the process variables. */
    record Scope(Integer statusCode, Object responseBody, Map<String, Object> variables) {
        Scope {
            variables = variables == null ? Map.of() : variables;
        }
    }

    /** An expression or criterion this runtime does not evaluate. */
    static final class Unsupported extends IllegalArgumentException {
        Unsupported(String message) {
            super(message);
        }
    }

    private static final Pattern EMBEDDED = Pattern.compile("\\{(\\$[^{}]+)}");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.\\-]+");

    private ArazzoExpressions() {
    }

    static boolean isExpression(Object v) {
        return v instanceof String s && s.startsWith("$");
    }

    /** Evaluates one expression ({@code $...}). */
    static Object evaluate(String expr, Scope scope) {
        String e = expr.strip();
        String pointer = null;
        int hash = e.indexOf('#');
        if (hash >= 0) {
            pointer = e.substring(hash + 1);
            e = e.substring(0, hash);
        }
        Object base;
        if (e.equals("$statusCode")) {
            base = scope.statusCode();
        } else if (e.equals("$response.body")) {
            base = scope.responseBody();
        } else if (e.startsWith("$inputs.")) {
            base = variable(e.substring("$inputs.".length()), scope);
        } else if (e.startsWith("$steps.") && e.contains(".outputs.")) {
            base = variable(e.substring(1), scope);
        } else {
            throw new Unsupported("Arazzo runtime expression not supported here: " + expr);
        }
        return pointer == null ? base : pointer(base, pointer, expr);
    }

    private static Object variable(String name, Scope scope) {
        if (!NAME.matcher(name).matches()) {
            throw new Unsupported("not a variable name: " + name);
        }
        return scope.variables().get(name);
    }

    private static Object pointer(Object base, String pointer, String expr) {
        if (base == null) {
            return null;
        }
        JsonNode n = Json.MAPPER.valueToTree(base);
        if (n.isTextual()) {
            JsonNode parsed = Json.tree(n.asText());
            n = parsed == null ? n : parsed;
        }
        JsonNode v;
        try {
            v = n.at(JsonPointer.compile(pointer));
        } catch (IllegalArgumentException e) {
            throw new Unsupported("not a JSON pointer in " + expr + ": " + pointer);
        }
        return v.isMissingNode() ? null : Json.MAPPER.convertValue(v, Object.class);
    }

    /** Resolves expressions in a JSON-like value: exact expressions become values, {@code {$...}} become text. */
    @SuppressWarnings("unchecked")
    static Object resolve(Object value, Scope scope) {
        if (value instanceof String s) {
            if (isExpression(s)) {
                return evaluate(s, scope);
            }
            Matcher m = EMBEDDED.matcher(s);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                Object v = evaluate(m.group(1), scope);
                m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? "" : text(v)));
            }
            m.appendTail(sb);
            return sb.toString();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            ((Map<String, Object>) map).forEach((k, v) -> out.put(k, resolve(v, scope)));
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            list.forEach(v -> out.add(resolve(v, scope)));
            return out;
        }
        return value;
    }

    static String text(Object v) {
        return v instanceof String s ? s : v instanceof Number || v instanceof Boolean ? v.toString() : Json.write(v);
    }

    // ---- criteria ----------------------------------------------------------------------------------

    /** True when every criterion holds; an empty list means "the status code is 2xx". */
    static boolean criteriaHold(List<Map<String, Object>> criteria, Scope scope) {
        if (criteria == null || criteria.isEmpty()) {
            return scope.statusCode() != null && scope.statusCode() >= 200 && scope.statusCode() < 300;
        }
        for (Map<String, Object> c : criteria) {
            if (!criterion(c, scope)) {
                return false;
            }
        }
        return true;
    }

    private static boolean criterion(Map<String, Object> c, Scope scope) {
        Object typeValue = c.get("type");
        String type = typeValue == null ? "simple" : typeValue instanceof Map<?, ?> m ? String.valueOf(m.get("type"))
                : String.valueOf(typeValue);
        String condition = Objects.toString(c.get("condition"), "");
        switch (type) {
            case "simple" -> {
                if (c.get("context") != null) {
                    throw new Unsupported("a simple criterion with a context is not supported");
                }
                return new Condition(condition, scope).parse();
            }
            case "regex" -> {
                Object ctx = c.get("context");
                if (!isExpression(ctx)) {
                    throw new Unsupported("a regex criterion needs a context expression");
                }
                Object v = evaluate((String) ctx, scope);
                return v != null && Pattern.compile(condition).matcher(text(v)).find();
            }
            default -> throw new Unsupported("success criterion type '" + type + "' is not supported (simple, regex)");
        }
    }

    /** Recursive-descent evaluator of a simple condition. */
    private static final class Condition {
        private final String s;
        private final Scope scope;
        private int i;

        Condition(String s, Scope scope) {
            this.s = s;
            this.scope = scope;
        }

        boolean parse() {
            boolean v = or();
            skip();
            if (i != s.length()) {
                throw new Unsupported("cannot read condition '" + s + "' at " + i);
            }
            return v;
        }

        private boolean or() {
            boolean v = and();
            while (eat("||")) {
                boolean r = and();
                v = v || r;
            }
            return v;
        }

        private boolean and() {
            boolean v = unary();
            while (eat("&&")) {
                boolean r = unary();
                v = v && r;
            }
            return v;
        }

        private boolean unary() {
            if (eat("!") ) {
                return !unary();
            }
            if (eat("(")) {
                boolean v = or();
                if (!eat(")")) {
                    throw new Unsupported("missing ')' in condition '" + s + "'");
                }
                return v;
            }
            Object left = operand();
            for (String op : new String[]{"==", "!=", "<=", ">=", "<", ">"}) {
                if (eat(op)) {
                    return compare(left, op, operand());
                }
            }
            if (left instanceof Boolean b) {
                return b;
            }
            throw new Unsupported("condition '" + s + "' needs a comparison");
        }

        private Object operand() {
            skip();
            if (i >= s.length()) {
                throw new Unsupported("condition '" + s + "' ends early");
            }
            char c = s.charAt(i);
            if (c == '\'') {
                int end = s.indexOf('\'', i + 1);
                if (end < 0) {
                    throw new Unsupported("unclosed string in condition '" + s + "'");
                }
                String v = s.substring(i + 1, end);
                i = end + 1;
                return v;
            }
            int start = i;
            while (i < s.length() && " \t()=!<>&|".indexOf(s.charAt(i)) < 0) {
                i++;
            }
            String token = s.substring(start, i);
            if (token.startsWith("$")) {
                return evaluate(token, scope);
            }
            return switch (token) {
                case "true" -> Boolean.TRUE;
                case "false" -> Boolean.FALSE;
                case "null" -> null;
                default -> {
                    try {
                        yield new java.math.BigDecimal(token);
                    } catch (NumberFormatException e) {
                        throw new Unsupported("unknown operand '" + token + "' in condition '" + s + "'");
                    }
                }
            };
        }

        private static boolean compare(Object a, String op, Object b) {
            if (a instanceof Number || b instanceof Number) {
                java.math.BigDecimal x = number(a);
                java.math.BigDecimal y = number(b);
                if (x == null || y == null) {
                    return "!=".equals(op);
                }
                int c = x.compareTo(y);
                return switch (op) {
                    case "==" -> c == 0;
                    case "!=" -> c != 0;
                    case "<" -> c < 0;
                    case "<=" -> c <= 0;
                    case ">" -> c > 0;
                    default -> c >= 0;
                };
            }
            boolean eq = Objects.equals(a == null ? null : text(a), b == null ? null : text(b));
            return switch (op) {
                case "==" -> eq;
                case "!=" -> !eq;
                default -> throw new Unsupported("operator " + op + " needs numbers");
            };
        }

        private static java.math.BigDecimal number(Object v) {
            if (v instanceof java.math.BigDecimal d) {
                return d;
            }
            if (v instanceof Number n) {
                return new java.math.BigDecimal(n.toString());
            }
            if (v instanceof String str) {
                try {
                    return new java.math.BigDecimal(str.trim());
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            return null;
        }

        private boolean eat(String token) {
            skip();
            if (s.startsWith(token, i)) {
                // "!" must not swallow "!=", "<" must not swallow "<="
                if (token.equals("!") && s.startsWith("!=", i)) {
                    return false;
                }
                i += token.length();
                return true;
            }
            return false;
        }

        private void skip() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }
    }
}
