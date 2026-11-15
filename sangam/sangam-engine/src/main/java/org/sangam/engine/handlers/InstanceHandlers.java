// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.handlers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.sangam.core.engine.RoutingPolicy;

/**
 * M-059 (SANGAM.md §3.3d L6, E21; R19, R25, R42, R49; ADR-D-26 strict FEEL): the named query and update handlers a BPMN process
 * declares, read from its {@code extensionElements} in the Sangam namespace ({@value #NS}):
 *
 * <pre>{@code
 * <bpmn:process id="licence">
 *   <bpmn:extensionElements>
 *     <sangam:query name="feeDue" expression="vars.fee - vars.paid">
 *       <sangam:argument name="asOf" type="date"/>
 *     </sangam:query>
 *     <sangam:update name="correctAddress" activities="scrutiny verification">
 *       <sangam:argument name="address" type="string" required="true"/>
 *       <sangam:set variable="address" expression="args.address"/>
 *     </sangam:update>
 *   </bpmn:extensionElements>
 * </bpmn:process>
 * }</pre>
 *
 * <ul>
 *   <li>Expressions are FEEL, evaluated strictly ({@link InstanceHandlerEvaluator}). They see three names only:
 *       {@code vars} (the instance variables), {@code args} (the call's validated arguments) and {@code identity}
 *       (the caller: tenant, principal, seat). Nothing else is reachable: no execution object, no bean, no Java
 *       function (SL-160 part B: variables and identity only).</li>
 *   <li>A query is read-only. An update evaluates every {@code sangam:set} against the state before the call and
 *       applies all of them together; it may name the activities it is allowed at ({@code activities}, the E21
 *       business constraint).</li>
 *   <li>Everything is checked at deploy ({@link #parse}); only processes that declare a handler are affected.</li>
 * </ul>
 */
public final class InstanceHandlers {

    public static final String NS = RoutingPolicy.NS_URI;
    /** The FEEL names a handler expression may reference. */
    public static final Set<String> ROOTS = Set.of("vars", "args", "identity");
    public static final int MAX_HANDLERS = 64;
    public static final int MAX_ARGUMENTS = 32;
    public static final int MAX_ASSIGNMENTS = 32;
    public static final int MAX_EXPRESSION_CHARS = 2_000;

    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");
    private static final Pattern VARIABLE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");

    /** Argument types; {@code any} accepts every JSON value. */
    public enum ArgType {
        STRING, NUMBER, BOOLEAN, DATE, OBJECT, LIST, ANY;

        static ArgType parse(String s) {
            if (s == null || s.isBlank()) {
                return ANY;
            }
            try {
                return valueOf(s.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    public record Argument(String name, ArgType type, boolean required) {
    }

    public record Query(String name, String expression, List<Argument> arguments) {
    }

    public record Assignment(String variable, String expression) {
    }

    /** {@code activities} empty: allowed whatever the instance is waiting at. */
    public record Update(String name, List<Argument> arguments, List<Assignment> assignments, Set<String> activities) {
    }

    public record Definitions(Map<String, Query> queries, Map<String, Update> updates) {
        public static final Definitions NONE = new Definitions(Map.of(), Map.of());

        public boolean isEmpty() {
            return queries.isEmpty() && updates.isEmpty();
        }
    }

    /** A read-only view of an XML element (Operaton's parse element at deploy, the model API's DOM at run time). */
    public interface Node {
        String uri();

        String localName();

        /** An unqualified attribute, or null. */
        String attr(String name);

        List<Node> children();
    }

    /** Checks one FEEL expression; throws {@link InstanceHandlerException} with an {@code L6-*} code. */
    @FunctionalInterface
    public interface ExpressionCheck {
        void check(String where, String expression);
    }

    private InstanceHandlers() {
    }

    /**
     * The handlers declared under a process's {@code extensionElements} (null: none).
     *
     * @param activityIds whether an id is a flow node of the process, for {@code activities}
     * @throws InstanceHandlerException {@code L6-*} on any rule a declaration breaks
     */
    public static Definitions parse(String processId, Node extensionElements,
                                    java.util.function.Predicate<String> activityIds, ExpressionCheck feel) {
        if (extensionElements == null) {
            return Definitions.NONE;
        }
        Map<String, Query> queries = new LinkedHashMap<>();
        Map<String, Update> updates = new LinkedHashMap<>();
        for (Node n : extensionElements.children()) {
            if (!NS.equals(n.uri())) {
                continue;
            }
            switch (n.localName()) {
                case "query" -> {
                    Query q = query(processId, n, feel);
                    unique(processId, "query", q.name(), queries.containsKey(q.name()));
                    queries.put(q.name(), q);
                }
                case "update" -> {
                    Update u = update(processId, n, activityIds, feel);
                    unique(processId, "update", u.name(), updates.containsKey(u.name()));
                    updates.put(u.name(), u);
                }
                case "argument", "set" -> throw fail("L6-STRUCTURE", processId, "sangam:" + n.localName()
                        + " belongs inside a sangam:query or sangam:update");
                default -> {
                    // other Sangam extension elements are not handlers
                }
            }
        }
        if (queries.size() + updates.size() > MAX_HANDLERS) {
            throw fail("L6-LIMIT", processId, "at most " + MAX_HANDLERS + " handlers per process");
        }
        return queries.isEmpty() && updates.isEmpty() ? Definitions.NONE
                : new Definitions(Collections.unmodifiableMap(queries), Collections.unmodifiableMap(updates));
    }

    private static Query query(String processId, Node n, ExpressionCheck feel) {
        String name = name(processId, "query", n.attr("name"));
        String where = "query '" + name + "'";
        String expression = expression(processId, where, n.attr("expression"), feel);
        List<Argument> args = new ArrayList<>();
        for (Node c : n.children()) {
            if (!NS.equals(c.uri())) {
                continue;
            }
            if ("argument".equals(c.localName())) {
                args.add(argument(processId, where, c, args));
            } else {
                throw fail("L6-STRUCTURE", processId, where + ": only sangam:argument is allowed inside a query, found "
                        + "sangam:" + c.localName());
            }
        }
        return new Query(name, expression, List.copyOf(args));
    }

    private static Update update(String processId, Node n, java.util.function.Predicate<String> activityIds,
                                 ExpressionCheck feel) {
        String name = name(processId, "update", n.attr("name"));
        String where = "update '" + name + "'";
        if (n.attr("expression") != null) {
            throw fail("L6-STRUCTURE", processId, where + ": an update changes variables through sangam:set, it has no "
                    + "expression attribute");
        }
        List<Argument> args = new ArrayList<>();
        List<Assignment> sets = new ArrayList<>();
        Set<String> targets = new LinkedHashSet<>();
        for (Node c : n.children()) {
            if (!NS.equals(c.uri())) {
                continue;
            }
            switch (c.localName()) {
                case "argument" -> args.add(argument(processId, where, c, args));
                case "set" -> {
                    String variable = c.attr("variable");
                    if (variable == null || !VARIABLE.matcher(variable).matches()) {
                        throw fail("L6-SET", processId, where + ": sangam:set needs a variable name matching "
                                + VARIABLE.pattern() + ", got '" + variable + "'");
                    }
                    if (variable.toLowerCase(Locale.ROOT).startsWith("sangam")) {
                        throw fail("L6-SET-RESERVED", processId, where + ": variable '" + variable
                                + "' is managed by the platform (names starting with 'sangam' are reserved)");
                    }
                    if (!targets.add(variable)) {
                        throw fail("L6-SET", processId, where + ": variable '" + variable + "' is set twice");
                    }
                    sets.add(new Assignment(variable, expression(processId, where + " set '" + variable + "'",
                            c.attr("expression"), feel)));
                }
                default -> throw fail("L6-STRUCTURE", processId, where + ": only sangam:argument and sangam:set are "
                        + "allowed inside an update, found sangam:" + c.localName());
            }
        }
        if (sets.isEmpty()) {
            throw fail("L6-SET", processId, where + ": an update needs at least one sangam:set");
        }
        if (sets.size() > MAX_ASSIGNMENTS) {
            throw fail("L6-LIMIT", processId, where + ": at most " + MAX_ASSIGNMENTS + " sangam:set");
        }
        Set<String> activities = new LinkedHashSet<>();
        String declared = n.attr("activities");
        if (declared != null) {
            for (String a : declared.trim().split("[\\s,]+")) {
                if (a.isEmpty()) {
                    continue;
                }
                if (!activityIds.test(a)) {
                    throw fail("L6-ACTIVITY", processId, where + ": activity '" + a + "' does not exist in the process");
                }
                activities.add(a);
            }
        }
        return new Update(name, List.copyOf(args), List.copyOf(sets), Collections.unmodifiableSet(activities));
    }

    private static Argument argument(String processId, String where, Node c, List<Argument> earlier) {
        String name = c.attr("name");
        if (name == null || !VARIABLE.matcher(name).matches()) {
            throw fail("L6-ARGUMENT", processId, where + ": argument name must match " + VARIABLE.pattern() + ", got '"
                    + name + "'");
        }
        if (earlier.stream().anyMatch(a -> a.name().equals(name))) {
            throw fail("L6-ARGUMENT", processId, where + ": argument '" + name + "' is declared twice");
        }
        if (earlier.size() >= MAX_ARGUMENTS) {
            throw fail("L6-LIMIT", processId, where + ": at most " + MAX_ARGUMENTS + " arguments");
        }
        ArgType type = ArgType.parse(c.attr("type"));
        if (type == null) {
            throw fail("L6-ARGUMENT", processId, where + ": argument '" + name + "' has unknown type '" + c.attr("type")
                    + "' (string, number, boolean, date, object, list, any)");
        }
        String required = c.attr("required");
        if (required != null && !required.equals("true") && !required.equals("false")) {
            throw fail("L6-ARGUMENT", processId, where + ": argument '" + name + "' required must be true or false");
        }
        return new Argument(name, type, "true".equals(required));
    }

    private static String name(String processId, String kind, String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw fail("L6-NAME", processId, "sangam:" + kind + " needs a name matching " + NAME.pattern() + ", got '"
                    + name + "'");
        }
        return name;
    }

    private static String expression(String processId, String where, String expression, ExpressionCheck feel) {
        if (expression == null || expression.isBlank()) {
            throw fail("L6-EXPRESSION", processId, where + ": a FEEL expression is required");
        }
        if (expression.length() > MAX_EXPRESSION_CHARS) {
            throw fail("L6-LIMIT", processId, where + ": expression longer than " + MAX_EXPRESSION_CHARS + " characters");
        }
        try {
            feel.check(where, expression);
        } catch (InstanceHandlerException e) {
            throw new InstanceHandlerException(e.code(), "process '" + processId + "': " + e.detail());
        }
        return expression;
    }

    private static void unique(String processId, String kind, String name, boolean duplicate) {
        if (duplicate) {
            throw fail("L6-DUPLICATE", processId, kind + " '" + name + "' is declared twice");
        }
    }

    private static InstanceHandlerException fail(String code, String processId, String message) {
        return new InstanceHandlerException(code, "process '" + processId + "': " + message);
    }
}
