// SPDX-License-Identifier: Apache-2.0
package org.sangam.spike.dmn;

import org.kie.dmn.api.core.DMNModel;
import org.kie.dmn.api.core.DMNRuntime;
import org.operaton.bpm.dmn.engine.DmnDecisionLogic;

/**
 * The logic of one decision evaluated by Drools. {@code variableName} is the decision's variable (DMN: equal to the
 * decision name); it is the single output Operaton records in history, as for a literal expression.
 */
public record DroolsDecisionLogic(DMNRuntime runtime, DMNModel model, String decisionName, String variableName)
        implements DmnDecisionLogic {
}
