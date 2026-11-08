// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.feel;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.operaton.bpm.dmn.engine.DmnEngineException;
import org.operaton.bpm.dmn.engine.delegate.DmnDecisionEvaluationEvent;
import org.operaton.bpm.dmn.engine.delegate.DmnDecisionEvaluationListener;
import org.operaton.bpm.dmn.engine.delegate.DmnDecisionLogicEvaluationEvent;
import org.operaton.bpm.dmn.engine.delegate.DmnDecisionTableEvaluationEvent;
import org.operaton.bpm.dmn.engine.delegate.DmnEvaluatedInput;
import org.operaton.bpm.dmn.engine.impl.DmnDecisionTableImpl;
import org.operaton.bpm.dmn.engine.impl.DmnDecisionTableRuleImpl;
import org.operaton.bpm.dmn.engine.impl.DmnExpressionImpl;

/**
 * SG-34 (ADR-D-26) null-input policy: a decision-table input whose value is null is an evaluation error unless the
 * table handles null explicitly, i.e. at least one rule's entry for that input is a {@code null} test (for example
 * {@code null}, {@code null, < 0} or {@code not(null)}). Native DMN lets a null input fall through to a catch-all
 * {@code -} rule; Sangam refuses it. Runs as a pre decision-evaluation listener (inside the engine command, before
 * the result reaches the process), so a refusal means no rule's output is used: the business rule task fails (an
 * incident in a job) and the command's effects roll back. Required decisions are checked too.
 */
public final class StrictNullInputListener implements DmnDecisionEvaluationListener {

    private static final Pattern NULL_TEST = Pattern.compile("(?<![\\w\"])null(?![\\w\"])");

    @Override
    public void notify(DmnDecisionEvaluationEvent event) {
        List<DmnDecisionLogicEvaluationEvent> all = new ArrayList<>(event.getRequiredDecisionResults());
        all.add(event.getDecisionResult());
        for (DmnDecisionLogicEvaluationEvent e : all) {
            if (e instanceof DmnDecisionTableEvaluationEvent t
                    && t.getDecision().getDecisionLogic() instanceof DmnDecisionTableImpl table) {
                check(t, table);
            }
        }
    }

    static void check(DmnDecisionTableEvaluationEvent event, DmnDecisionTableImpl table) {
        List<DmnEvaluatedInput> inputs = event.getInputs();
        for (int i = 0; i < inputs.size(); i++) {
            DmnEvaluatedInput in = inputs.get(i);
            if (in.getValue() != null && in.getValue().getValue() != null) {
                continue;
            }
            if (!handlesNull(table, i)) {
                throw new DmnEngineException("SANGAM-DMN-NULL-INPUT: decision '" + event.getDecision().getKey()
                        + "' input '" + (in.getName() != null ? in.getName() : in.getId()) + "' is null and no rule "
                        + "tests it with 'null'; a catch-all rule does not match a null input (ADR-D-26). Supply the "
                        + "value or add an explicit null rule.");
            }
        }
    }

    static boolean handlesNull(DmnDecisionTableImpl table, int inputIndex) {
        for (DmnDecisionTableRuleImpl rule : table.getRules()) {
            List<DmnExpressionImpl> c = rule.getConditions();
            if (inputIndex < c.size() && c.get(inputIndex) != null && c.get(inputIndex).getExpression() != null
                    && NULL_TEST.matcher(c.get(inputIndex).getExpression()).find()) {
                return true;
            }
        }
        return false;
    }
}
