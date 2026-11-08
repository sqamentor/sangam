// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.dmn;

import org.operaton.bpm.dmn.engine.DmnDecision;
import org.operaton.bpm.dmn.engine.DmnDecisionRequirementsGraph;
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
import org.operaton.bpm.dmn.engine.impl.hitpolicy.DefaultHitPolicyHandlerRegistry;
import org.operaton.bpm.dmn.engine.impl.hitpolicy.HitPolicyEntry;
import org.operaton.bpm.dmn.engine.impl.spi.hitpolicy.DmnHitPolicyHandler;
import org.operaton.bpm.dmn.engine.impl.spi.hitpolicy.DmnHitPolicyHandlerRegistry;
import org.operaton.bpm.dmn.engine.impl.spi.transform.DmnTransformListener;
import org.operaton.bpm.dmn.engine.impl.spi.transform.DmnTransformer;
import org.operaton.bpm.dmn.engine.impl.transform.DefaultDmnTransformer;
import org.operaton.bpm.model.dmn.HitPolicy;
import org.operaton.bpm.model.dmn.instance.Decision;
import org.operaton.bpm.model.dmn.instance.Definitions;
import org.operaton.bpm.model.dmn.instance.Input;
import org.operaton.bpm.model.dmn.instance.Output;
import org.operaton.bpm.model.dmn.instance.Rule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * DMN hit policies PRIORITY and OUTPUT ORDER (FLOWABLE-INTO-OPERATON FC-54). Logic ported from Flowable's {@code HitPolicyPriority},
 * {@code HitPolicyOutputOrder} and {@code OutputOrderComparator} (Apache-2.0; see NOTICE): the priority of an output
 * value is its position in the output clause's {@code outputValues} list (first = highest); rules are compared output
 * by output in clause order. Values not in the list rank last.
 * <ul>
 *   <li>PRIORITY: the single matching rule with the highest-priority outputs.</li>
 *   <li>OUTPUT ORDER: all matching rules, sorted by decreasing priority.</li>
 * </ul>
 * Operaton's model knows both policies but its registry has no handler; {@link #install} registers the handlers on the
 * DMN transformer and a transform listener that records each output clause's {@code outputValues}.
 */
public final class PriorityHitPolicies {

    /**
     * Transformed output clause → allowed values in priority order. Keyed by the transformed output object itself (not
     * by decision key): two tenants, or two versions, of the same decision key can rank values differently. Weak keys,
     * so decisions evicted from the deployment cache do not stay reachable.
     */
    private static final Map<DmnDecisionTableOutputImpl, List<String>> PRIORITIES = Collections.synchronizedMap(new WeakHashMap<>());

    private PriorityHitPolicies() {
    }

    public static void install(DefaultDmnEngineConfiguration dmn) {
        DmnTransformer t = dmn.getTransformer();
        if (t == null) {
            t = new DefaultDmnTransformer();
            dmn.setTransformer(t);
        }
        DmnHitPolicyHandlerRegistry reg = t.getHitPolicyHandlerRegistry();
        if (reg == null) {
            reg = new DefaultHitPolicyHandlerRegistry();
            t.setHitPolicyHandlerRegistry(reg);
        }
        reg.addHandler(HitPolicy.PRIORITY, null, new Handler(HitPolicy.PRIORITY));
        reg.addHandler(HitPolicy.OUTPUT_ORDER, null, new Handler(HitPolicy.OUTPUT_ORDER));
        List<DmnTransformListener> listeners = new ArrayList<>(t.getTransformListeners() == null ? List.of() : t.getTransformListeners());
        listeners.add(new OutputValuesListener());
        t.setTransformListeners(listeners);
    }

    static final class Handler implements DmnHitPolicyHandler {
        private final HitPolicy policy;

        Handler(HitPolicy policy) {
            this.policy = policy;
        }

        @Override
        public DmnDecisionTableEvaluationEvent apply(DmnDecisionTableEvaluationEvent event) {
            List<DmnEvaluatedDecisionRule> rules = new ArrayList<>(event.getMatchingRules());
            if (rules.size() > 1) {
                rules.sort(comparator(outputPriorities(event.getDecisionTable())));
                if (policy == HitPolicy.PRIORITY) {
                    rules = List.of(rules.get(0));
                }
                ((DmnDecisionTableEvaluationEventImpl) event).setMatchingRules(rules);
            }
            return event;
        }

        @Override
        public HitPolicyEntry getHitPolicyEntry() {
            return new HitPolicyEntry(policy, null);
        }
    }

    /** Output id → priority order, for the evaluated decision table's own output clauses. */
    static Map<String, List<String>> outputPriorities(DmnDecision decision) {
        Map<String, List<String>> out = new java.util.HashMap<>();
        if (decision instanceof DmnDecisionImpl d && d.getDecisionLogic() instanceof DmnDecisionTableImpl table) {
            for (DmnDecisionTableOutputImpl o : table.getOutputs()) {
                List<String> order = PRIORITIES.get(o);
                if (order != null) {
                    out.put(o.getId(), order);
                }
            }
        }
        return out;
    }

    static Comparator<DmnEvaluatedDecisionRule> comparator(Map<String, List<String>> priorities) {
        return (a, b) -> {
            for (Map.Entry<String, DmnEvaluatedOutput> e : a.getOutputEntries().entrySet()) {
                DmnEvaluatedOutput bo = b.getOutputEntries().get(e.getKey());
                List<String> order = priorities.get(e.getValue().getId());
                if (order == null || bo == null) {
                    continue;
                }
                int c = Integer.compare(rank(order, e.getValue()), rank(order, bo));
                if (c != 0) {
                    return c;
                }
            }
            return 0;
        };
    }

    private static int rank(List<String> order, DmnEvaluatedOutput o) {
        Object v = o.getValue() == null ? null : o.getValue().getValue();
        int i = v == null ? -1 : order.indexOf(String.valueOf(v));
        return i < 0 ? Integer.MAX_VALUE : i;
    }

    /** Parses {@code "high","medium",low} into [high, medium, low]. */
    static List<String> parseValues(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (char ch : text.toCharArray()) {
            if (ch == '"') {
                quoted = !quoted;
            } else if (ch == ',' && !quoted) {
                out.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        if (!cur.toString().isBlank()) {
            out.add(cur.toString().trim());
        }
        return out;
    }

    static final class OutputValuesListener implements DmnTransformListener {
        @Override
        public void transformDecisionTableOutput(Output output, DmnDecisionTableOutputImpl dmnOutput) {
            if (output.getOutputValues() == null) {
                return;
            }
            PRIORITIES.put(dmnOutput, parseValues(output.getOutputValues().getTextContent()));
        }

        @Override
        public void transformDecision(Decision decision, DmnDecision dmnDecision) {
        }

        @Override
        public void transformDecisionTableInput(Input input, DmnDecisionTableInputImpl dmnInput) {
        }

        @Override
        public void transformDecisionTableRule(Rule rule, DmnDecisionTableRuleImpl dmnRule) {
        }

        @Override
        public void transformDecisionRequirementsGraph(Definitions definitions, DmnDecisionRequirementsGraph dmnDecisionGraph) {
        }
    }
}
