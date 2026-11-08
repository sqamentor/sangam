// SPDX-License-Identifier: Apache-2.0
package org.sangam.resources.bpmn;

import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.sangam.resources.roster.RosterModel;
import org.sangam.resources.roster.RosterService;

/**
 * Applies the outcome of a roster publication approval (the {@code sangam-roster-publish} process or a pack's own).
 *
 * <p>Inputs: {@code periodId} (required), {@code approved} (boolean, required), {@code reason} (optional).
 * Output: {@code rosterState} (the period state afterwards). Approved publishes the roster (assignments PUBLISHED,
 * members notified, duty seats linked); rejected returns it to GENERATED with the reason recorded.
 */
public final class PublishRosterDelegate implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) {
        String periodId = ResourcesRuntime.required(execution, "periodId");
        Object a = execution.getVariable("approved");
        if (a == null) {
            throw new IllegalArgumentException("process variable 'approved' is required");
        }
        boolean approved = a instanceof Boolean b ? b : Boolean.parseBoolean(a.toString());
        String reason = ResourcesRuntime.str(execution, "reason");
        RosterModel.RosterPeriod p = ResourcesRuntime.service(RosterService.class).decidePublication(
                ResourcesRuntime.connection(), ResourcesRuntime.identity(execution), periodId, null, approved, reason);
        execution.setVariable("rosterState", p.state().name());
    }
}
