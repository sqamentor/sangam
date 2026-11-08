// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.telemetry;

import java.util.Map;

/**
 * Evaluates the DMN decision of a {@link ThresholdRule} ({@code dmnDecisionKey}). Kept as an interface so this package
 * loads without the optional Operaton dependency; {@link OperatonRuleDecisionEvaluator} is the engine implementation.
 */
@FunctionalInterface
public interface RuleDecisionEvaluator {

    /**
     * @param alarm    the decision's {@code alarm} column (false when the decision matched no rule)
     * @param severity the optional {@code severity} column, or null
     */
    record Decision(boolean alarm, String severity) {
    }

    /**
     * Evaluates {@code decisionKey} for {@code tenant} with {@code variables}. Throws on a missing or failing decision;
     * the handler logs a configuration error and skips the rule (a database failure in the cause chain is rethrown).
     */
    Decision evaluate(String tenant, String decisionKey, Map<String, Object> variables);
}
