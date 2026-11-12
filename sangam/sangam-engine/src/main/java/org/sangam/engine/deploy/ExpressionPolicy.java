// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.deploy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.operaton.bpm.impl.juel.AstBracket;
import org.operaton.bpm.impl.juel.AstChoice;
import org.operaton.bpm.impl.juel.AstDot;
import org.operaton.bpm.impl.juel.AstEval;
import org.operaton.bpm.impl.juel.AstFunction;
import org.operaton.bpm.impl.juel.AstIdentifier;
import org.operaton.bpm.impl.juel.AstMethod;
import org.operaton.bpm.impl.juel.AstNested;
import org.operaton.bpm.impl.juel.AstNode;
import org.operaton.bpm.impl.juel.AstNumber;
import org.operaton.bpm.impl.juel.AstString;
import org.operaton.bpm.impl.juel.Builder;
import org.operaton.bpm.impl.juel.Node;

/**
 * SL-160 / TM-10 part B (owner decisions 2026-11-12): what a JUEL expression in a model may reach, checked on the real
 * parse tree of Operaton's EL ({@code operaton-juel} 2.2.0 {@link Builder}, the parser Operaton itself uses). The
 * receiver of every member access is classified by the root of its chain:
 * <ul>
 *   <li><b>context</b> ({@code execution}, {@code task}, {@code caseExecution}, {@code externalTask}): only the
 *       variable getters and setters and the identity fields ({@link #CONTEXT_PROPERTIES}, {@link #CONTEXT_METHODS});
 *       nothing reaching engine services, the parent execution or the model ({@value #MEMBER});</li>
 *   <li><b>approved bean</b> (Sangam's own plus {@code sangam.engine.deploy-allowed-beans}; an entry is a bean name, any
 *       member, or {@code bean.member}): only approved members ({@value #BEAN});</li>
 *   <li><b>any other name</b> (a process variable, or a bean that is not approved: the two cannot be told apart at
 *       deploy): only the reviewed methods of {@link #SAFE_METHODS} ({@value #METHOD}); no property or bracket access on
 *       the name itself ({@value #BEAN}; a map variable is read with {@code name.get('key')});</li>
 *   <li><b>a value</b> (the result of an allowed method or property): the reviewed methods; properties and literal keys
 *       except the reflective ones of {@link #FORBIDDEN_MEMBERS}; a computed key only directly on a method result
 *       ({@code seats.split(',')[loopCounter]}).</li>
 * </ul>
 * EL functions ({@code fn:x()}, {@code now()}) are refused ({@value #FUNCTION}): no shipped model or test uses one.
 * {@code getClass} and the members of {@link #FORBIDDEN_MEMBERS} are refused on every receiver. An expression the
 * parser rejects is refused ({@value #SYNTAX}). Parentheses keep the receiver's class, and a {@code ? :} keeps the more
 * powerful of its two branches, so neither hides a context or bean.
 */
public final class ExpressionPolicy {

    public static final String MEMBER = "SL160-EXPR-MEMBER";
    public static final String METHOD = "SL160-EXPR-METHOD";
    public static final String BEAN = "SL160-EXPR-BEAN";
    public static final String FUNCTION = "SL160-EXPR-FUNCTION";
    public static final String SYNTAX = "SL160-EXPR-SYNTAX";

    /** The engine's implicit context objects (Operaton {@code VariableScopeElResolver}). */
    public static final Set<String> CONTEXTS = Set.of("execution", "task", "caseExecution", "externalTask");

    /** Identity fields and variable views of a context object, as properties. */
    public static final Set<String> CONTEXT_PROPERTIES = Set.of(
            "id", "processBusinessKey", "businessKey", "processInstanceId", "processDefinitionId", "activityInstanceId",
            "parentActivityInstanceId", "parentId", "executionId", "caseInstanceId", "caseExecutionId", "caseDefinitionId",
            "currentActivityId", "activityId", "taskDefinitionKey", "tenantId",
            "variables", "variablesLocal", "variablesTyped", "variablesLocalTyped", "variableNames", "variableNamesLocal");

    /** The variable getters and setters of a context object, and the getters of {@link #CONTEXT_PROPERTIES}. */
    public static final Set<String> CONTEXT_METHODS;

    static {
        Set<String> m = new java.util.TreeSet<>(Set.of(
                "getVariable", "getVariableLocal", "getVariableTyped", "getVariableLocalTyped", "hasVariable",
                "hasVariableLocal", "hasVariables", "hasVariablesLocal", "setVariable", "setVariableLocal", "setVariables",
                "setVariablesLocal", "removeVariable", "removeVariableLocal", "removeVariables", "removeVariablesLocal"));
        for (String p : CONTEXT_PROPERTIES) {
            m.add("get" + Character.toUpperCase(p.charAt(0)) + p.substring(1));
        }
        CONTEXT_METHODS = Set.copyOf(m);
    }

    /**
     * The reviewed methods on variables and values (owner list 2026-11-12): text, collections, DMN results. Checked
     * against every method the shipped models and the other lanes' tests use (W2-D report): no addition was needed.
     */
    public static final Set<String> SAFE_METHODS = Set.of(
            "concat", "split", "substring", "trim", "toUpperCase", "toLowerCase", "length", "contains", "startsWith",
            "endsWith", "equals", "isEmpty",
            "size", "get",
            "getSingleResult", "getSingleEntry", "getResultList", "collectEntries");

    /** Reflective members refused on every receiver (property, literal key or method). */
    public static final Set<String> FORBIDDEN_MEMBERS = Set.of(
            "class", "getClass", "classLoader", "getClassLoader", "declaringClass", "getDeclaringClass", "module",
            "getModule", "protectionDomain", "getProtectionDomain");

    /** One refusal. */
    public record Refusal(String code, String detail) {
    }

    /** Approved beans: an entry is {@code bean} (every member) or {@code bean.member}. */
    public record Beans(Set<String> entries) {
        boolean whole(String bean) {
            return entries.contains(bean);
        }

        boolean known(String bean) {
            if (whole(bean)) {
                return true;
            }
            for (String e : entries) {
                if (e.startsWith(bean + ".")) {
                    return true;
                }
            }
            return false;
        }

        boolean member(String bean, String member) {
            return whole(bean) || entries.contains(bean + "." + member);
        }
    }

    private enum Kind { VALUE, ROOT, BEAN, CONTEXT }

    private record Val(Kind kind, String name) {
        static final Val VALUE = new Val(Kind.VALUE, null);
    }

    private final Beans beans;
    private final List<Refusal> out = new ArrayList<>();

    private ExpressionPolicy(Beans beans) {
        this.beans = beans;
    }

    /** {@code true} when {@code text} holds an EL expression ({@code ${..}} or {@code #{..}}). */
    public static boolean hasExpression(String text) {
        return text != null && (text.contains("${") || text.contains("#{"));
    }

    /** The refusals of one expression text (literal text with embedded {@code ${..}} included). */
    public static List<Refusal> check(String text, Set<String> approvedBeans) {
        ExpressionPolicy p = new ExpressionPolicy(new Beans(approvedBeans));
        AstNode root;
        try {
            root = (AstNode) new Builder(Builder.Feature.METHOD_INVOCATIONS, Builder.Feature.VARARGS).build(text).getRoot();
        } catch (RuntimeException e) {
            return List.of(new Refusal(SYNTAX, "'" + text + "' is not a valid expression: " + e.getMessage()));
        }
        p.visit(root);
        return p.out;
    }

    private void refuse(String code, String detail) {
        out.add(new Refusal(code, detail));
    }

    private Val visit(Node n) {
        if (n instanceof AstIdentifier id) {
            String name = id.getName();
            if (CONTEXTS.contains(name)) {
                return new Val(Kind.CONTEXT, name);
            }
            return new Val(beans.known(name) ? Kind.BEAN : Kind.ROOT, name);
        }
        if (n instanceof AstNested || n instanceof AstEval) {
            return visit(n.getChild(0));
        }
        if (n instanceof AstChoice c) {
            visit(c.getChild(0));
            Val a = visit(c.getChild(1));
            Val b = visit(c.getChild(2));
            return a.kind().ordinal() >= b.kind().ordinal() ? a : b;
        }
        if (n instanceof AstMethod m) {
            Node property = m.getChild(0);
            visitChildren(m.getChild(1));
            if (property instanceof AstDot d) {
                call(visit(d.getChild(0)), dotName(d));
            } else {
                visitChildren(property);
                refuse(METHOD, "a computed method name is not allowed");
            }
            return Val.VALUE;
        }
        if (n instanceof AstDot d) {
            property(visit(d.getChild(0)), dotName(d));
            return Val.VALUE;
        }
        if (n instanceof AstBracket b) {
            Node prefix = b.getChild(0);
            Val p = visit(prefix);
            Node key = b.getChild(1);
            visit(key);
            bracket(p, prefix, key);
            return Val.VALUE;
        }
        if (n instanceof AstFunction f) {
            visitChildren(f);
            refuse(FUNCTION, "EL function '" + f.getName() + "()' is not allowed");
            return Val.VALUE;
        }
        visitChildren(n);
        return Val.VALUE;
    }

    private void visitChildren(Node n) {
        for (int i = 0; i < n.getCardinality(); i++) {
            visit(n.getChild(i));
        }
    }

    /** The property name of {@code a.b}: {@code AstDot.toString()} is {@code ". " + property} (javap 2.2.0, pinned). */
    static String dotName(AstDot d) {
        String s = d.toString();
        return s.startsWith(". ") ? s.substring(2) : s;
    }

    private void call(Val receiver, String method) {
        if (FORBIDDEN_MEMBERS.contains(method)) {
            refuse(MEMBER, "'" + method + "()' is reflective and never allowed");
            return;
        }
        switch (receiver.kind()) {
            case CONTEXT -> {
                if (!CONTEXT_METHODS.contains(method)) {
                    refuse(MEMBER, "'" + receiver.name() + "." + method + "()' is not allowed (only the variable getters and "
                            + "setters and the identity fields of " + receiver.name() + ")");
                }
            }
            case BEAN -> {
                if (!beans.member(receiver.name(), method)) {
                    refuse(BEAN, "'" + receiver.name() + "." + method + "()' is not an approved bean member "
                            + "(sangam.engine.deploy-allowed-beans)");
                }
            }
            default -> {
                if (!SAFE_METHODS.contains(method)) {
                    refuse(METHOD, "method '" + method + "()' is not on the reviewed list " + new java.util.TreeSet<>(SAFE_METHODS)
                            + (receiver.kind() == Kind.ROOT ? " (on '" + receiver.name() + "', which is not an approved bean)" : ""));
                }
            }
        }
    }

    private void property(Val receiver, String name) {
        if (FORBIDDEN_MEMBERS.contains(name)) {
            refuse(MEMBER, "property '" + name + "' is reflective and never allowed");
            return;
        }
        switch (receiver.kind()) {
            case CONTEXT -> {
                if (!CONTEXT_PROPERTIES.contains(name)) {
                    refuse(MEMBER, "'" + receiver.name() + "." + name + "' is not allowed (only the variable views and the "
                            + "identity fields of " + receiver.name() + ")");
                }
            }
            case BEAN -> {
                if (!beans.member(receiver.name(), name)) {
                    refuse(BEAN, "'" + receiver.name() + "." + name + "' is not an approved bean member");
                }
            }
            case ROOT -> refuse(BEAN, "'" + receiver.name() + "." + name + "': '" + receiver.name() + "' is not an approved "
                    + "bean (sangam.engine.deploy-allowed-beans); read a map variable with " + receiver.name() + ".get('"
                    + name + "')");
            default -> {
                // a property of a value (a map key, a DMN result entry): allowed
            }
        }
    }

    private void bracket(Val receiver, Node prefix, Node key) {
        String literal = key instanceof AstString s ? String.valueOf(s.eval(null, null)) : null;
        if (literal != null && FORBIDDEN_MEMBERS.contains(literal)) {
            refuse(MEMBER, "key '" + literal + "' is reflective and never allowed");
            return;
        }
        boolean constant = literal != null || key instanceof AstNumber;
        switch (receiver.kind()) {
            case CONTEXT -> refuse(MEMBER, "'" + receiver.name() + "[...]' is not allowed (use the named members)");
            case BEAN -> {
                if (!constant || !beans.member(receiver.name(), literal == null ? "" : literal)) {
                    refuse(BEAN, "'" + receiver.name() + "[...]' is not an approved bean member");
                }
            }
            case ROOT -> refuse(BEAN, "'" + receiver.name() + "[...]': '" + receiver.name() + "' is not an approved bean "
                    + "(sangam.engine.deploy-allowed-beans); read a list or map variable with " + receiver.name() + ".get(...)");
            default -> {
                if (!constant && !(prefix instanceof AstMethod)) {
                    refuse(MEMBER, "a computed key is only allowed directly on a method result (for example "
                            + "seats.split(',')[loopCounter])");
                }
            }
        }
    }
}
