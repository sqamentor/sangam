// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.feel;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.camunda.feel.api.EvaluationFailure;
import org.camunda.feel.api.EvaluationResult;
import org.camunda.feel.impl.JavaValueMapper;
import org.camunda.feel.valuemapper.CustomValueMapper;
import org.operaton.bpm.dmn.feel.impl.FeelException;
import org.operaton.bpm.dmn.feel.impl.scala.ScalaFeelEngine;
import org.operaton.bpm.dmn.feel.impl.scala.function.FeelCustomFunctionProvider;
import org.operaton.bpm.dmn.feel.impl.scala.spin.SpinValueMapperFactory;
import org.operaton.bpm.engine.variable.context.VariableContext;

/**
 * SG-33 / SG-34 (ADR-D-26): the FEEL engine of every Sangam DMN engine. The kept feel-scala engine (SG-27 pin,
 * unchanged) with two Sangam policies on top:
 * <ol>
 *   <li><b>Exact numbers</b>: {@link SangamValueMapper} runs ahead of the stock mappers, so FEEL numbers leave and
 *       enter as {@code BigDecimal}/{@code Long}, never {@code Double}.</li>
 *   <li><b>Strict evaluation</b>: native FEEL is lenient: a missing variable, a null or mistyped operand, a division
 *       by zero, an unknown function or an invalid date yields {@code null} and only a "suppressed failure". Sangam
 *       raises every such failure as a {@link FeelException}, so the decision fails (an incident on the business rule
 *       task) instead of a silent {@code null}, a null condition taking the else branch, or a catch-all rule
 *       firing. A unary test against a genuinely null input is the one exception: it is a non-match here, and
 *       {@link StrictNullInputListener} decides on the table level whether a null input is allowed at all.</li>
 * </ol>
 * A value that is genuinely null (a present variable holding null, or {@code null} itself) is not a failure:
 * {@code x = null}, {@code x != null} and a {@code null} input entry keep working.
 */
public class SangamFeelEngine extends ScalaFeelEngine {

    public SangamFeelEngine(List<FeelCustomFunctionProvider> providers) {
        super(providers);
    }

    @Override
    protected scala.collection.immutable.List<CustomValueMapper> getValueMappers() {
        CustomValueMapper spin = new SpinValueMapperFactory().createInstance();
        return spin == null ? toScalaList(new SangamValueMapper(), new JavaValueMapper())
                : toScalaList(new SangamValueMapper(), new JavaValueMapper(), spin);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T evaluateSimpleExpression(String expression, VariableContext ctx) {
        EvaluationResult r = feelEngineApi.evaluateExpression(expression, new VariableMap(ctx));
        return (T) strict(expression, r);
    }

    @Override
    public boolean evaluateSimpleUnaryTests(String expression, String inputVariable, VariableContext ctx) {
        Object input = inputVariable != null && ctx != null && ctx.containsVariable(inputVariable)
                ? ctx.resolve(inputVariable).getValue() : null;
        EvaluationResult r = feelEngineApi.evaluateUnaryTests(expression, input, new VariableMap(ctx));
        if (r.isFailure()) {
            throw new FeelException("SANGAM-FEEL-STRICT: failed to evaluate '" + expression + "': "
                    + r.failure().message());
        }
        if (input == null) {
            // a null input is decided per table by StrictNullInputListener; here it only matches an explicit null test
            return Boolean.TRUE.equals(r.result());
        }
        Object v = strict(expression, r);
        if (!(v instanceof Boolean b)) {
            throw new FeelException("SANGAM-FEEL-STRICT: unary test '" + expression + "' gave " + v
                    + " for input " + input + "; a boolean is required");
        }
        return b;
    }

    /**
     * Built-in conversion functions ({@code date("2026-02-30")}, {@code duration("x")}, ...) return null without
     * any failure record in the pinned engine, and built-ins cannot be overridden by a function provider. Sangam
     * evaluates every literal call once (cached per expression) and refuses one that yields null.
     */
    private static final java.util.regex.Pattern LITERAL_CONVERSION = java.util.regex.Pattern.compile(
            "(?<![\\w.])(date and time|years and months duration|duration|date|time|number)\\(\\s*(\"(?:[^\"\\\\]|\\\\.)*\")\\s*\\)");
    private final Map<String, String> literalCheck = new java.util.concurrent.ConcurrentHashMap<>();

    private void checkLiteralConversions(String expression) {
        if (expression == null || expression.indexOf('"') < 0) {
            return;
        }
        String bad = literalCheck.computeIfAbsent(expression, e -> {
            java.util.regex.Matcher m = LITERAL_CONVERSION.matcher(e);
            while (m.find()) {
                EvaluationResult r = feelEngineApi.evaluateExpression(m.group(), Map.of());
                if (r.isFailure() || r.result() == null) {
                    return m.group();
                }
            }
            return "";
        });
        if (!bad.isEmpty()) {
            throw new FeelException("SANGAM-FEEL-STRICT: '" + expression + "': " + bad + " is not a valid value "
                    + "(native FEEL would return null; Sangam fails the decision, ADR-D-26)");
        }
    }

    private Object strict(String expression, EvaluationResult r) {
        checkLiteralConversions(expression);
        if (r.isFailure()) {
            throw new FeelException("SANGAM-FEEL-STRICT: failed to evaluate '" + expression + "': "
                    + r.failure().message());
        }
        if (r.hasSuppressedFailures()) {
            List<EvaluationFailure> f = r.getSuppressedFailures();
            throw new FeelException("SANGAM-FEEL-STRICT: '" + expression + "' has evaluation failures "
                    + f.stream().map(x -> x.failureType() + ": " + x.failureMessage()).collect(Collectors.joining("; "))
                    + " (native FEEL would return null; Sangam fails the decision, ADR-D-26)");
        }
        return r.result();
    }

    /** Read-only map view of an Operaton variable context (values resolved on access). */
    static final class VariableMap extends AbstractMap<String, Object> {
        private final VariableContext ctx;

        VariableMap(VariableContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public boolean containsKey(Object key) {
            return ctx != null && key instanceof String k && ctx.containsVariable(k);
        }

        @Override
        public Object get(Object key) {
            return containsKey(key) ? ctx.resolve((String) key).getValue() : null;
        }

        @Override
        public Set<Entry<String, Object>> entrySet() {
            Set<String> keys = ctx == null ? Set.of() : ctx.keySet();
            return new AbstractSet<>() {
                @Override
                public Iterator<Entry<String, Object>> iterator() {
                    Iterator<String> it = keys.iterator();
                    return new Iterator<>() {
                        @Override
                        public boolean hasNext() {
                            return it.hasNext();
                        }

                        @Override
                        public Entry<String, Object> next() {
                            String k = it.next();
                            return new SimpleImmutableEntry<>(k, ctx.resolve(k).getValue());
                        }
                    };
                }

                @Override
                public int size() {
                    return keys.size();
                }
            };
        }
    }

}
