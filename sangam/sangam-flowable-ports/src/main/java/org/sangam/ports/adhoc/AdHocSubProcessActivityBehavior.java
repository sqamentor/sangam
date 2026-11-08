// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.adhoc;

import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.impl.bpmn.behavior.SubProcessActivityBehavior;
import org.operaton.bpm.engine.impl.pvm.PvmActivity;
import org.operaton.bpm.engine.impl.pvm.delegate.ActivityExecution;
import org.operaton.bpm.engine.impl.pvm.runtime.PvmExecutionImpl;

/**
 * BPMN ad-hoc sub-process on Operaton's PVM (FLOWABLE-INTO-OPERATON FC-01, FLOWABLE-INTO-OPERATON D-09). Logic follows Flowable's
 * {@code AdhocSubProcessActivityBehavior} (Apache-2.0); the code is new because Flowable's agenda model does not map
 * onto the PVM (FLOWABLE-INTO-OPERATON §C caveat).
 *
 * <p>Semantics:
 * <ul>
 *   <li>Entering the ad-hoc sub-process starts nothing: the scope waits. Inner activities without an incoming
 *       sequence flow are the <em>enabled</em> activities; a user (or API caller) starts them on demand through
 *       {@link AdHocSubProcessService#executeActivity}. Inner sequence flows are followed normally.</li>
 *   <li>{@code ordering="Sequential"}: at most one inner activity may be active at a time
 *       (enforced by the service). {@code Parallel} (default): any number.</li>
 *   <li>{@code completionCondition}: evaluated whenever an inner path ends. When it is true and no inner work
 *       is active, the sub-process completes. When it is true and work is still active,
 *       {@code cancelRemainingInstances=true} (BPMN default) cancels the remaining work and completes; {@code false}
 *       waits for the remaining work to end.</li>
 *   <li>Without a completion condition the sub-process completes only through
 *       {@link AdHocSubProcessService#complete} (Flowable's {@code completeAdhocSubProcess}).</li>
 * </ul>
 */
public class AdHocSubProcessActivityBehavior extends SubProcessActivityBehavior {

    public enum Ordering { PARALLEL, SEQUENTIAL }

    private final Ordering ordering;
    private final Expression completionCondition;
    private final boolean cancelRemainingInstances;

    public AdHocSubProcessActivityBehavior(Ordering ordering, Expression completionCondition, boolean cancelRemainingInstances) {
        this.ordering = ordering;
        this.completionCondition = completionCondition;
        this.cancelRemainingInstances = cancelRemainingInstances;
    }

    public Ordering getOrdering() {
        return ordering;
    }

    public Expression getCompletionCondition() {
        return completionCondition;
    }

    public boolean isCancelRemainingInstances() {
        return cancelRemainingInstances;
    }

    @Override
    public void execute(ActivityExecution execution) {
        // wait state: inner activities are started on demand (no initial activity is required)
    }

    @Override
    public void concurrentChildExecutionEnded(ActivityExecution scopeExecution, ActivityExecution endedExecution) {
        super.concurrentChildExecutionEnded(scopeExecution, endedExecution);
        if (conditionSatisfied(scopeExecution) && cancelRemainingInstances) {
            completeScope(scopeExecution, true);
        }
    }

    @Override
    public void complete(ActivityExecution scopeExecution) {
        // the last inner path ended: the scope execution is back on the ad-hoc activity
        if (conditionSatisfied(scopeExecution)) {
            leave(scopeExecution);
        }
    }

    protected boolean conditionSatisfied(ActivityExecution execution) {
        if (completionCondition == null) {
            return false;
        }
        Object value = completionCondition.getValue(execution);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value == null) {
            return false;
        }
        throw new ProcessEngineException("completionCondition of ad-hoc sub-process " + execution.getActivity().getId()
                + " must evaluate to a boolean, got " + value.getClass().getName());
    }

    /**
     * Completes the scope: cancels remaining inner work (when allowed) and leaves through the outgoing flows. Used by
     * the completion condition and by {@link CompleteAdHocSubProcessCmd}.
     */
    public void completeScope(ActivityExecution scopeExecution, boolean cancelRemaining) {
        PvmExecutionImpl exec = (PvmExecutionImpl) scopeExecution;
        PvmActivity adHoc = AdHocSupport.adHocActivityOf(exec);
        String adHocInstanceId = AdHocSupport.adHocInstanceIdOf(exec, adHoc);
        boolean hasActiveWork = !exec.getNonEventScopeExecutions().isEmpty() || exec.getActivity() != adHoc;
        if (hasActiveWork) {
            if (!cancelRemaining) {
                throw new ProcessEngineException("ad-hoc sub-process " + adHoc.getId() + " still has active work and "
                        + "cancelRemainingInstances is false");
            }
            exec.interrupt("ad-hoc sub-process completed");
        }
        exec.setActivity(adHoc);
        exec.setActivityInstanceId(adHocInstanceId);
        leave(exec);
    }
}
