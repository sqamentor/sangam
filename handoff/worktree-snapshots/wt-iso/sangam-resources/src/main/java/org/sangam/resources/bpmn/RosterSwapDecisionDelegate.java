// SPDX-License-Identifier: Apache-2.0
package org.sangam.resources.bpmn;

import org.operaton.bpm.engine.delegate.BpmnError;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.sangam.resources.roster.RosterModel;
import org.sangam.resources.roster.RosterService;
import org.sangam.resources.support.ResourcesException;

/**
 * Applies the decision of a swap / cover approval process.
 *
 * <p>Inputs: {@code swapId} (required), {@code approved} (boolean, required), {@code reason} (optional).
 * Output: {@code swapState}. When an approved swap breaks a working-time constraint at decision time, the BPMN error
 * {@code ROSTER_CONSTRAINT} is thrown (message = the violations) so the model can route it back to the approver.
 */
public final class RosterSwapDecisionDelegate implements JavaDelegate {

    public static final String CONSTRAINT_ERROR = "ROSTER_CONSTRAINT";

    @Override
    public void execute(DelegateExecution execution) {
        String swapId = ResourcesRuntime.required(execution, "swapId");
        Object a = execution.getVariable("approved");
        if (a == null) {
            throw new IllegalArgumentException("process variable 'approved' is required");
        }
        boolean approved = a instanceof Boolean b ? b : Boolean.parseBoolean(a.toString());
        String reason = ResourcesRuntime.str(execution, "reason");
        try {
            RosterModel.SwapRequest s = ResourcesRuntime.service(RosterService.class).decideSwap(
                    ResourcesRuntime.connection(), ResourcesRuntime.identity(execution), swapId, null, approved, reason);
            execution.setVariable("swapState", s.state().name());
        } catch (ResourcesException e) {
            if (e.code() == ResourcesException.Code.CONSTRAINT) {
                throw new BpmnError(CONSTRAINT_ERROR, e.getMessage());
            }
            throw e;
        }
    }
}
