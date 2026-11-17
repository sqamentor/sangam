// SPDX-License-Identifier: Apache-2.0
package org.sangam.spike.dmn;

import org.kie.dmn.api.core.DMNContext;
import org.kie.dmn.api.core.DMNDecisionResult;
import org.kie.dmn.api.core.DMNMessage;
import org.kie.dmn.api.core.DMNResult;
import org.kie.dmn.feel.lang.types.impl.ComparablePeriod;
import org.operaton.bpm.dmn.engine.DmnDecision;
import org.operaton.bpm.dmn.engine.DmnDecisionResult;
import org.operaton.bpm.dmn.engine.DmnEngineException;
import org.operaton.bpm.dmn.engine.delegate.DmnDecisionLiteralExpressionEvaluationEvent;
import org.operaton.bpm.dmn.engine.delegate.DmnDecisionLogicEvaluationEvent;
import org.operaton.bpm.dmn.engine.impl.DmnDecisionResultEntriesImpl;
import org.operaton.bpm.dmn.engine.impl.DmnDecisionResultImpl;
import org.operaton.bpm.dmn.engine.impl.delegate.DmnDecisionLiteralExpressionEvaluationEventImpl;
import org.operaton.bpm.dmn.engine.impl.evaluation.DmnDecisionLogicEvaluationHandler;
import org.operaton.bpm.engine.variable.Variables;
import org.operaton.bpm.engine.variable.context.VariableContext;
import org.operaton.bpm.engine.variable.value.TypedValue;

import java.time.Period;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Evaluates a {@link DroolsDecisionLogic} and reports it to Operaton as a literal-expression evaluation: one decision,
 * one output (its variable). Operaton's history producer already records that shape (HistoricDecisionInstance plus
 * one HistoricDecisionOutputInstance), so no history change is needed.
 *
 * <p>Strict: any Drools error message (FEEL error, type error, missing input) fails the evaluation, as Sangam's
 * SG-34 policy does for the Operaton path; the spec would return null.
 */
public class DroolsEvaluationHandler implements DmnDecisionLogicEvaluationHandler {

    @Override
    public DmnDecisionLogicEvaluationEvent evaluate(DmnDecision decision, VariableContext variableContext) {
        DroolsDecisionLogic logic = (DroolsDecisionLogic) decision.getDecisionLogic();
        DMNContext ctx = logic.runtime().newContext();
        for (String key : variableContext.keySet()) {
            TypedValue v = variableContext.resolve(key);
            ctx.set(key, v == null ? null : v.getValue());
        }
        DMNResult result = logic.runtime().evaluateByName(logic.model(), ctx, logic.decisionName());
        if (result.hasErrors()) {
            throw new DmnEngineException("SANGAM-DMN-DROOLS: decision '" + logic.decisionName() + "' failed: "
                    + result.getMessages(DMNMessage.Severity.ERROR).stream().map(DMNMessage::getText)
                    .collect(Collectors.joining("; ")));
        }
        DMNDecisionResult dr = result.getDecisionResultByName(logic.decisionName());
        if (dr == null || dr.getEvaluationStatus() != DMNDecisionResult.DecisionEvaluationStatus.SUCCEEDED) {
            throw new DmnEngineException("SANGAM-DMN-DROOLS: decision '" + logic.decisionName() + "' status "
                    + (dr == null ? "missing" : dr.getEvaluationStatus()));
        }
        DmnDecisionLiteralExpressionEvaluationEventImpl event = new DmnDecisionLiteralExpressionEvaluationEventImpl();
        event.setDecision(decision);
        event.setOutputName(logic.variableName());
        event.setOutputValue(Variables.untypedValue(toEngineValue(dr.getResult())));
        event.setExecutedDecisionElements(1);
        return event;
    }

    @Override
    public DmnDecisionResult generateDecisionResult(DmnDecisionLogicEvaluationEvent event) {
        DmnDecisionLiteralExpressionEvaluationEvent e = (DmnDecisionLiteralExpressionEvaluationEvent) event;
        DmnDecisionResultEntriesImpl entries = new DmnDecisionResultEntriesImpl();
        entries.putValue(e.getOutputName(), e.getOutputValue());
        return new DmnDecisionResultImpl(List.of(entries));
    }

    /** Drools-only value types become JDK types (history serialises them). */
    static Object toEngineValue(Object v) {
        if (v instanceof ComparablePeriod p) {
            return p.asPeriod();
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, x) -> out.put(String.valueOf(k), toEngineValue(x)));
            return out;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            l.forEach(x -> out.add(toEngineValue(x)));
            return out;
        }
        return v;
    }
}
