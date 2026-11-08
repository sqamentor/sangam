// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.telemetry;

import org.operaton.bpm.dmn.engine.DmnDecisionResult;
import org.operaton.bpm.dmn.engine.DmnDecisionResultEntries;
import org.operaton.bpm.engine.DecisionService;
import org.operaton.bpm.engine.dmn.DecisionsEvaluationBuilder;

import java.util.Map;

/**
 * {@link RuleDecisionEvaluator} on Operaton's {@link DecisionService}:
 * {@code evaluateDecisionByKey(key).variables(vars)[.decisionDefinitionTenantId(tenant)].evaluate()}, first result,
 * columns {@code alarm} (Boolean, or the strings {@code true}/{@code false}) and optional {@code severity}.
 *
 * <p>Only this class references Operaton types; construct it only when operaton-engine is on the class path.
 */
public final class OperatonRuleDecisionEvaluator implements RuleDecisionEvaluator {

    private final DecisionService decisions;
    private final boolean tenantScoped;

    /**
     * @param tenantScoped true when decision definitions are deployed per tenant (Operaton tenant id = Sangam tenant);
     *                     false for shared definitions deployed without a tenant id
     */
    public OperatonRuleDecisionEvaluator(DecisionService decisions, boolean tenantScoped) {
        this.decisions = decisions;
        this.tenantScoped = tenantScoped;
    }

    @Override
    public Decision evaluate(String tenant, String decisionKey, Map<String, Object> variables) {
        DecisionsEvaluationBuilder b = decisions.evaluateDecisionByKey(decisionKey).variables(variables);
        b = tenantScoped ? b.decisionDefinitionTenantId(tenant) : b.decisionDefinitionWithoutTenantId();
        DmnDecisionResult result = b.evaluate();
        DmnDecisionResultEntries first = result == null ? null : result.getFirstResult();
        if (first == null) {
            return new Decision(false, null);
        }
        Object alarm = first.getEntry("alarm");
        Object severity = first.getEntry("severity");
        return new Decision(truthy(alarm), severity == null ? null : severity.toString());
    }

    private static boolean truthy(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n) {
            return n.intValue() != 0;
        }
        return v != null && Boolean.parseBoolean(v.toString().trim());
    }
}
