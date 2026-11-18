// SPDX-License-Identifier: Apache-2.0
package org.sangam.feel;

import org.operaton.bpm.dmn.engine.DmnDecision;
import org.operaton.bpm.dmn.engine.DmnEngineException;
import org.operaton.bpm.dmn.engine.delegate.DmnDecisionTableEvaluationEvent;
import org.operaton.bpm.dmn.engine.delegate.DmnEvaluatedDecisionRule;
import org.operaton.bpm.dmn.engine.delegate.DmnEvaluatedOutput;
import org.operaton.bpm.dmn.engine.impl.DefaultDmnEngineConfiguration;
import org.operaton.bpm.dmn.engine.impl.DmnDecisionImpl;
import org.operaton.bpm.dmn.engine.impl.DmnDecisionTableImpl;
import org.operaton.bpm.dmn.engine.impl.DmnDecisionTableInputImpl;
import org.operaton.bpm.dmn.engine.impl.DmnDecisionTableOutputImpl;
import org.operaton.bpm.dmn.engine.impl.DmnDecisionTableRuleImpl;
import org.operaton.bpm.dmn.engine.impl.delegate.DmnDecisionTableEvaluationEventImpl;
import org.operaton.bpm.dmn.engine.impl.delegate.DmnEvaluatedDecisionRuleImpl;
import org.operaton.bpm.dmn.engine.impl.delegate.DmnEvaluatedOutputImpl;
import org.operaton.bpm.dmn.engine.impl.hitpolicy.HitPolicyEntry;
import org.operaton.bpm.dmn.engine.impl.spi.hitpolicy.DmnHitPolicyHandler;
import org.operaton.bpm.dmn.engine.impl.spi.hitpolicy.DmnHitPolicyHandlerRegistry;
import org.operaton.bpm.dmn.engine.impl.spi.transform.DmnTransformListener;
import org.operaton.bpm.dmn.engine.impl.spi.transform.DmnTransformer;
import org.operaton.bpm.dmn.engine.impl.transform.DmnTransformException;
import org.operaton.bpm.dmn.engine.DmnDecisionRequirementsGraph;
import org.operaton.bpm.engine.variable.Variables;
import org.operaton.bpm.engine.variable.value.TypedValue;
import org.operaton.bpm.model.dmn.BuiltinAggregator;
import org.operaton.bpm.model.dmn.HitPolicy;
import org.operaton.bpm.model.dmn.instance.Decision;
import org.operaton.bpm.model.dmn.instance.DefaultOutputEntry;
import org.operaton.bpm.model.dmn.instance.Definitions;
import org.operaton.bpm.model.dmn.instance.Input;
import org.operaton.bpm.model.dmn.instance.Output;
import org.operaton.bpm.model.dmn.instance.Rule;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Decision-table rules of ADR-DMN-ENGINE (owner decision D1, 2026-11-18), installed by {@link SangamFeel#install} on
 * Operaton's own SPI (hit-policy handler registry and transform listener; the engine is not patched):
 * <ul>
 *   <li><b>HIT-8, exact aggregation (SG-33)</b>: COLLECT with SUM, MIN or MAX aggregates in {@link BigDecimal}.
 *       Operaton's handlers convert any non-integer output to {@code double} ({@code 0.1 + 0.2} gave
 *       {@code 0.30000000000000004}). The result follows {@link SangamValueMapper}: a whole number that fits is a
 *       {@code Long}, otherwise a {@code BigDecimal}. A null or non-number output fails the decision (SG-34).</li>
 *   <li><b>GAP-2, default output entry (DMN 1.5 clause 8.2.11)</b>: when no rule matches, a table whose output clauses
 *       declare a {@code defaultOutputEntry} yields those values, typed by their output, as if one rule had matched
 *       (single-hit: the value; COLLECT: a one-element list or the aggregate of it). Operaton ignores the element
 *       and returns no result. Only constant default entries are supported: one that reads a variable is refused at
 *       parse ({@code SANGAM-DMN-DEFAULT-OUTPUT}). The synthetic match carries the rule id
 *       {@value #DEFAULT_RULE_ID} in history.</li>
 * </ul>
 */
public final class DecisionTableRules {

    /** History rule id of a default-output match. */
    public static final String DEFAULT_RULE_ID = "sangam:defaultOutputEntry";

    private DecisionTableRules() {
    }

    public static void install(DefaultDmnEngineConfiguration dmn) {
        DmnTransformer t = dmn.getTransformer();
        DmnHitPolicyHandlerRegistry reg = t.getHitPolicyHandlerRegistry();
        if (reg instanceof Registry) {
            return; // a repeated install keeps one wrapper
        }
        Registry wrapped = new Registry(reg, dmn);
        for (BuiltinAggregator a : new BuiltinAggregator[]{BuiltinAggregator.SUM, BuiltinAggregator.MIN, BuiltinAggregator.MAX}) {
            wrapped.addHandler(HitPolicy.COLLECT, a, new ExactCollect(a));
        }
        t.setHitPolicyHandlerRegistry(wrapped);
        List<DmnTransformListener> listeners = new ArrayList<>(t.getTransformListeners() == null ? List.of() : t.getTransformListeners());
        listeners.add(new DefaultOutputListener(dmn));
        t.setTransformListeners(listeners);
    }

    // ------------------------------------------------------------------ HIT-8

    static final class ExactCollect implements DmnHitPolicyHandler {
        private final BuiltinAggregator aggregator;

        ExactCollect(BuiltinAggregator aggregator) {
            this.aggregator = aggregator;
        }

        @Override
        public DmnDecisionTableEvaluationEvent apply(DmnDecisionTableEvaluationEvent event) {
            String name = null;
            BigDecimal acc = null;
            for (DmnEvaluatedDecisionRule rule : event.getMatchingRules()) {
                Map<String, DmnEvaluatedOutput> outputs = rule.getOutputEntries();
                if (outputs.size() > 1) {
                    throw new DmnEngineException("SANGAM-DMN-COLLECT: aggregation " + aggregator
                            + " needs a single output, decision '" + event.getDecision().getKey() + "' has " + outputs.keySet());
                }
                if (outputs.isEmpty()) {
                    continue;
                }
                DmnEvaluatedOutput o = outputs.values().iterator().next();
                name = o.getOutputName();
                BigDecimal v = number(o.getValue() == null ? null : o.getValue().getValue(), event, aggregator);
                acc = acc == null ? v : switch (aggregator) {
                    case SUM -> acc.add(v);
                    case MIN -> acc.min(v);
                    case MAX -> acc.max(v);
                    default -> throw new IllegalStateException(String.valueOf(aggregator));
                };
            }
            DmnDecisionTableEvaluationEventImpl e = (DmnDecisionTableEvaluationEventImpl) event;
            e.setCollectResultName(name);
            e.setCollectResultValue(acc == null ? null : Variables.untypedValue(exact(acc))); // no match: no result (spec 8.2.11)
            return event;
        }

        @Override
        public HitPolicyEntry getHitPolicyEntry() {
            return new HitPolicyEntry(HitPolicy.COLLECT, aggregator);
        }
    }

    static BigDecimal number(Object v, DmnDecisionTableEvaluationEvent event, BuiltinAggregator aggregator) {
        if (v instanceof BigDecimal b) {
            return b;
        }
        if (v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte) {
            return BigDecimal.valueOf(((Number) v).longValue());
        }
        if (v instanceof BigInteger b) {
            return new BigDecimal(b);
        }
        if (v instanceof Double || v instanceof Float) {
            return new BigDecimal(v.toString()); // a double output column (non-money, DmnModelLint) at its shortest decimal
        }
        throw new DmnEngineException("SANGAM-DMN-COLLECT: " + aggregator + " of decision '" + event.getDecision().getKey()
                + "' needs numbers, got " + (v == null ? "null" : v.getClass().getSimpleName() + " " + v)
                + " (Sangam fails the decision, ADR-D-26)");
    }

    /** As {@link SangamValueMapper}: a whole number that fits is a Long, otherwise a BigDecimal. */
    static Object exact(BigDecimal v) {
        BigDecimal s = v.stripTrailingZeros();
        if (s.scale() <= 0) {
            try {
                return s.longValueExact();
            } catch (ArithmeticException tooBig) {
                return v;
            }
        }
        return v;
    }

    // ------------------------------------------------------------------ GAP-2

    /** Transformed output clause -> its constant default value (raw FEEL text until first use). Weak keys, as in FC-54. */
    private static final Map<DmnDecisionTableOutputImpl, String> DEFAULTS = Collections.synchronizedMap(new WeakHashMap<>());

    static final class DefaultOutputListener implements DmnTransformListener {
        private final DefaultDmnEngineConfiguration dmn;

        DefaultOutputListener(DefaultDmnEngineConfiguration dmn) {
            this.dmn = dmn;
        }

        @Override
        public void transformDecisionTableOutput(Output output, DmnDecisionTableOutputImpl out) {
            DefaultOutputEntry d = output.getDefaultOutputEntry();
            if (d == null || d.getText() == null || d.getText().getTextContent() == null
                    || d.getText().getTextContent().isBlank()) {
                return;
            }
            String text = d.getText().getTextContent().trim();
            if (dmn.getFeelEngine() instanceof SangamFeelEngine feel) {
                checkConstant(feel.referencedNames(text), text);
            }
            DEFAULTS.put(out, text);
        }

        @Override
        public void transformDecision(Decision decision, DmnDecision dmnDecision) {
        }

        @Override
        public void transformDecisionTableInput(Input input, DmnDecisionTableInputImpl in) {
        }

        @Override
        public void transformDecisionTableRule(Rule rule, DmnDecisionTableRuleImpl r) {
        }

        @Override
        public void transformDecisionRequirementsGraph(Definitions definitions, DmnDecisionRequirementsGraph drg) {
        }
    }

    /** The engine's registry; every handler it hands out first applies the default output entries. */
    static final class Registry implements DmnHitPolicyHandlerRegistry {
        private final DmnHitPolicyHandlerRegistry delegate;
        private final DefaultDmnEngineConfiguration dmn;

        Registry(DmnHitPolicyHandlerRegistry delegate, DefaultDmnEngineConfiguration dmn) {
            this.delegate = delegate;
            this.dmn = dmn;
        }

        @Override
        public DmnHitPolicyHandler getHandler(HitPolicy hitPolicy, BuiltinAggregator aggregator) {
            DmnHitPolicyHandler h = delegate.getHandler(hitPolicy, aggregator);
            return h == null ? null : new WithDefaults(h, dmn);
        }

        @Override
        public void addHandler(HitPolicy hitPolicy, BuiltinAggregator aggregator, DmnHitPolicyHandler handler) {
            delegate.addHandler(hitPolicy, aggregator, handler);
        }
    }

    static final class WithDefaults implements DmnHitPolicyHandler {
        private final DmnHitPolicyHandler delegate;
        private final DefaultDmnEngineConfiguration dmn;

        WithDefaults(DmnHitPolicyHandler delegate, DefaultDmnEngineConfiguration dmn) {
            this.delegate = delegate;
            this.dmn = dmn;
        }

        @Override
        public DmnDecisionTableEvaluationEvent apply(DmnDecisionTableEvaluationEvent event) {
            if (event.getMatchingRules().isEmpty() && event.getDecision() instanceof DmnDecisionImpl d
                    && d.getDecisionLogic() instanceof DmnDecisionTableImpl table) {
                Map<String, DmnEvaluatedOutput> outputs = new LinkedHashMap<>();
                for (DmnDecisionTableOutputImpl o : table.getOutputs()) {
                    String text = DEFAULTS.get(o);
                    if (text != null) {
                        DmnEvaluatedOutputImpl v = new DmnEvaluatedOutputImpl(o, o.getTypeDefinition().transform(constant(text, d)));
                        outputs.put(o.getId(), v);
                    }
                }
                if (!outputs.isEmpty()) {
                    DmnEvaluatedDecisionRuleImpl rule = new DmnEvaluatedDecisionRuleImpl(defaultRule());
                    rule.setId(DEFAULT_RULE_ID);
                    rule.setOutputEntries(outputs);
                    ((DmnDecisionTableEvaluationEventImpl) event).setMatchingRules(new ArrayList<>(List.of(rule)));
                }
            }
            return delegate.apply(event);
        }

        private Object constant(String text, DmnDecision d) {
            return DecisionTableRules.constant(dmn, text, d.getKey());
        }

        @Override
        public HitPolicyEntry getHitPolicyEntry() {
            return delegate.getHitPolicyEntry();
        }
    }

    private static DmnDecisionTableRuleImpl defaultRule() {
        DmnDecisionTableRuleImpl r = new DmnDecisionTableRuleImpl();
        r.setId(DEFAULT_RULE_ID);
        return r;
    }

    /** Evaluates a default output entry with no variables; one that reads a variable is refused. */
    static Object constant(DefaultDmnEngineConfiguration dmn, String text, String decisionKey) {
        try {
            return dmn.getFeelEngine().evaluateSimpleExpression(text,
                    org.operaton.bpm.engine.variable.Variables.emptyVariableContext());
        } catch (RuntimeException e) {
            throw new DmnEngineException("SANGAM-DMN-DEFAULT-OUTPUT: decision '" + decisionKey + "': default output entry '"
                    + text + "' must be a constant (" + e.getMessage() + ")");
        }
    }

    /** Parse-time check used by the transform listener of {@link SangamFeel}: refuses a non-constant default entry. */
    static void checkConstant(Set<String> referenced, String text) {
        if (!referenced.isEmpty()) {
            throw new DmnTransformException("SANGAM-DMN-DEFAULT-OUTPUT: default output entry '" + text
                    + "' reads " + referenced + "; only constant default output entries are supported", null);
        }
    }
}
