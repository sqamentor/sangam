// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.feel;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.ExecutionListener;

/**
 * R10 strict null, <b>only where it is required</b>: an end listener on a business rule task that declares
 * <ul>
 *   <li>{@code sangam:routing=true}: the mapped result (the result variable) is used for routing and must be a
 *       non-null Boolean;</li>
 *   <li>{@code sangam:requiredOutputs=a,b}: these outputs are mandatory and must be non-null in every result entry.</li>
 * </ul>
 * A violation fails the command (an incident in a job) instead of routing on a silent {@code null}/false. Every other
 * null stays valid: undeclared outputs, optional outputs, and decisions without these properties are not checked.
 * Gateway conditions need no extra guard: Operaton's {@code UelExpressionCondition} already refuses a null or
 * non-Boolean condition result (2.2.0: "condition expression returns null").
 */
public final class StrictNullResultGuard implements ExecutionListener {

    private final String activityId;
    private final String resultVariable;
    private final boolean routing;
    private final List<String> requiredOutputs;

    public StrictNullResultGuard(String activityId, String resultVariable, boolean routing, List<String> requiredOutputs) {
        this.activityId = activityId;
        this.resultVariable = resultVariable;
        this.routing = routing;
        this.requiredOutputs = List.copyOf(requiredOutputs);
    }

    @Override
    public void notify(DelegateExecution execution) {
        Object result = execution.getVariable(resultVariable);
        if (routing && !(result instanceof Boolean)) {
            throw new ProcessEngineException("R10: business rule task '" + activityId + "' is a routing decision but "
                    + "result '" + resultVariable + "' is " + (result == null ? "null" : result.getClass().getSimpleName())
                    + "; a Boolean is required");
        }
        if (requiredOutputs.isEmpty()) {
            return;
        }
        if (result instanceof Collection<?> rows) {
            for (Object row : rows) {
                checkRow(row);
            }
        } else {
            checkRow(result);
        }
    }

    private void checkRow(Object row) {
        if (row instanceof Map<?, ?> m) {
            for (String out : requiredOutputs) {
                if (m.get(out) == null) {
                    throw new ProcessEngineException("R10: business rule task '" + activityId + "': mandatory output '"
                            + out + "' is null");
                }
            }
        } else if (row == null) {
            throw new ProcessEngineException("R10: business rule task '" + activityId + "': mandatory output "
                    + requiredOutputs + " is null");
        }
    }
}
