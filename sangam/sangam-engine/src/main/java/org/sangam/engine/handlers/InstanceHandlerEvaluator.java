// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.handlers;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.temporal.Temporal;
import java.time.temporal.TemporalAmount;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.operaton.bpm.dmn.feel.impl.FeelException;
import org.operaton.bpm.engine.variable.VariableMap;
import org.operaton.bpm.engine.variable.Variables;
import org.sangam.engine.feel.JacksonCompatibilityGuard;
import org.sangam.engine.feel.SangamFeelEngine;

/**
 * M-059 (L6; R19, R25; ADR-D-26): checks and evaluates handler expressions with Sangam's strict FEEL engine (exact
 * numbers, every evaluation failure raised, never a silent null).
 *
 * <p><b>Bounded by construction (R19).</b> A handler expression may not contain an iteration or a definition that
 * could run without bound: {@code for}, {@code some}, {@code every}, ranges ({@code ..}), {@code function} definitions
 * (recursion) and the regular-expression built-ins {@code matches}, {@code replace}, {@code split} (backtracking on
 * caller-supplied text) are refused at deploy. What remains evaluates in time linear in the expression and the
 * instance data, which R11 already bounds. Expressions are at most {@value InstanceHandlers#MAX_EXPRESSION_CHARS}
 * characters, a result at most {@value #MAX_RESULT_NODES} values and {@value #MAX_RESULT_DEPTH} levels deep.
 *
 * <p><b>Reach (SL-160 part B).</b> The evaluation context holds only {@code vars}, {@code args} and {@code identity};
 * the deploy check refuses a reference to any other name, and external Java functions are disabled in the pinned
 * engine ({@code FeelEngineBuilder.withEnabledExternalFunctions(false)} in Operaton's {@code ScalaFeelEngine}).
 */
public final class InstanceHandlerEvaluator implements InstanceHandlers.ExpressionCheck {

    public static final int MAX_RESULT_NODES = 10_000;
    public static final int MAX_RESULT_DEPTH = 32;

    private static final Pattern STRING_OR_COMMENT = Pattern.compile(
            "\"(?:[^\"\\\\]|\\\\.)*\"|//[^\\n]*|/\\*(?s:.*?)\\*/");
    private static final Pattern UNBOUNDED = Pattern.compile(
            "(?<![\\w.])(for|some|every|function|matches|replace|split)(?![\\w])|\\.\\.");

    private volatile SangamFeelEngine feel;

    private SangamFeelEngine feel() {
        SangamFeelEngine f = feel;
        if (f == null) {
            synchronized (this) {
                if (feel == null) {
                    JacksonCompatibilityGuard.check(); // SG-33: fail fast before feel-scala builds
                    feel = new SangamFeelEngine(List.of());
                }
                f = feel;
            }
        }
        return f;
    }

    /** Deploy check of one expression: bounded constructs only, FEEL syntax, references to the three roots only. */
    @Override
    public void check(String where, String expression) {
        String code = STRING_OR_COMMENT.matcher(expression).replaceAll(" ");
        Matcher m = UNBOUNDED.matcher(code);
        if (m.find()) {
            throw new InstanceHandlerException("L6-FEEL-UNBOUNDED", where + ": '" + m.group()
                    + "' is not allowed in a handler expression (R19 bounded evaluation: no iteration, range, function "
                    + "definition or regular expression)");
        }
        Set<String> names;
        try {
            names = feel().referencedNames(expression);
        } catch (FeelException e) {
            throw new InstanceHandlerException("L6-FEEL-SYNTAX", where + ": " + e.getMessage());
        }
        for (String n : names) {
            String root = n.contains(".") ? n.substring(0, n.indexOf('.')) : n;
            if (!InstanceHandlers.ROOTS.contains(root)) {
                throw new InstanceHandlerException("L6-FEEL-ROOT", where + ": '" + n + "' is not reachable; a handler "
                        + "expression reads only vars.<name>, args.<name> and identity.<field> (SL-160 part B)");
            }
        }
    }

    /**
     * Validates the call's arguments against the declaration: unknown and missing required arguments and wrong types
     * are {@code VALIDATION_FAILED}. Returns the arguments with every declared, absent one present as {@code null}.
     */
    public static Map<String, Object> arguments(List<InstanceHandlers.Argument> declared, Map<String, ?> given) {
        Map<String, Object> in = given == null ? Map.of() : new LinkedHashMap<>(given);
        List<String> problems = new ArrayList<>();
        for (String k : in.keySet()) {
            if (declared.stream().noneMatch(a -> a.name().equals(k))) {
                problems.add("unknown argument '" + k + "'");
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (InstanceHandlers.Argument a : declared) {
            Object v = in.get(a.name());
            if (v == null) {
                if (a.required()) {
                    problems.add("argument '" + a.name() + "' is required");
                }
                out.put(a.name(), null);
                continue;
            }
            if (!typeMatches(a.type(), v)) {
                problems.add("argument '" + a.name() + "' must be of type " + a.type().name().toLowerCase(java.util.Locale.ROOT));
            }
            out.put(a.name(), v);
        }
        if (!problems.isEmpty()) {
            throw new InstanceHandlerException("VALIDATION_FAILED", String.join("; ", problems));
        }
        return out;
    }

    static boolean typeMatches(InstanceHandlers.ArgType type, Object v) {
        return switch (type) {
            case ANY -> true;
            case STRING -> v instanceof String;
            case NUMBER -> v instanceof Number;
            case BOOLEAN -> v instanceof Boolean;
            case DATE -> v instanceof String s && isIsoDate(s) || v instanceof Temporal;
            case OBJECT -> v instanceof Map;
            case LIST -> v instanceof Collection;
        };
    }

    private static boolean isIsoDate(String s) {
        try {
            java.time.LocalDate.parse(s);
            return true;
        } catch (java.time.format.DateTimeParseException e) {
            return false;
        }
    }

    /** A query: the value of its expression over the instance, made storable / serialisable. */
    public Object query(InstanceHandlers.Query q, Map<String, ?> variables, Map<String, ?> args,
                        Map<String, ?> identity) {
        return result(q.name(), evaluate(q.name(), q.expression(), context(variables, args, identity)));
    }

    /**
     * An update: every assignment evaluated against the state before the call (so their order does not matter), in
     * declaration order. Nothing is written here; the caller applies the map in its transaction.
     */
    public Map<String, Object> update(InstanceHandlers.Update u, Map<String, ?> variables, Map<String, ?> args,
                                      Map<String, ?> identity) {
        VariableMap ctx = context(variables, args, identity);
        Map<String, Object> out = new LinkedHashMap<>();
        for (InstanceHandlers.Assignment a : u.assignments()) {
            out.put(a.variable(), result(u.name() + "." + a.variable(), evaluate(u.name(), a.expression(), ctx)));
        }
        return out;
    }

    private Object evaluate(String handler, String expression, VariableMap ctx) {
        try {
            return feel().evaluateSimpleExpression(expression, ctx.asVariableContext());
        } catch (FeelException e) {
            throw new InstanceHandlerException("HANDLER_EVALUATION_FAILED", "handler '" + handler + "': "
                    + e.getMessage(), e);
        }
    }

    private static VariableMap context(Map<String, ?> variables, Map<String, ?> args, Map<String, ?> identity) {
        return Variables.createVariables()
                .putValue("vars", variables == null ? Map.of() : new LinkedHashMap<>(variables))
                .putValue("args", args == null ? Map.of() : new LinkedHashMap<>(args))
                .putValue("identity", identity == null ? Map.of() : new LinkedHashMap<>(identity));
    }

    /**
     * The result as plain values the engine can store and the API can serialise: maps with string keys, lists,
     * strings, booleans, {@code Long}/{@code BigDecimal}/{@code BigInteger}; dates, times and durations as their ISO
     * text. Bounded in size and depth ({@code RESULT_TOO_LARGE}).
     */
    static Object result(String where, Object v) {
        int[] budget = {MAX_RESULT_NODES};
        return plain(where, v, 0, budget);
    }

    private static Object plain(String where, Object v, int depth, int[] budget) {
        if (depth > MAX_RESULT_DEPTH || --budget[0] < 0) {
            throw new InstanceHandlerException("RESULT_TOO_LARGE", "handler '" + where + "': the result exceeds "
                    + MAX_RESULT_NODES + " values or " + MAX_RESULT_DEPTH + " levels (R19)");
        }
        if (v == null || v instanceof String || v instanceof Boolean || v instanceof Long
                || v instanceof BigDecimal || v instanceof BigInteger) {
            return v;
        }
        if (v instanceof Integer || v instanceof Short || v instanceof Byte) {
            return ((Number) v).longValue();
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString()); // never a binary float (R33)
        }
        if (v instanceof Temporal || v instanceof TemporalAmount) {
            return v.toString();
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), plain(where, e.getValue(), depth + 1, budget));
            }
            return out;
        }
        if (v instanceof Collection<?> c) {
            List<Object> out = new ArrayList<>(c.size());
            for (Object x : c) {
                out.add(plain(where, x, depth + 1, budget));
            }
            return out;
        }
        return v.toString();
    }
}
