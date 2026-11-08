// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.ExecutionListener;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.JavaDelegate;

/**
 * Moves the case to an IX.3 lifecycle state and writes the matching audit event (X.4.2.1). Usable as a service task
 * or as an execution listener. Field {@code state} (a {@link CaseState} name); optional {@code outcomeVar} and
 * {@code outcome} also set an outcome variable for the calling process.
 */
public class LifecycleStateDelegate implements JavaDelegate, ExecutionListener {

    private Expression state;
    private Expression outcomeVar;
    private Expression outcome;

    @Override
    public void execute(DelegateExecution execution) {
        apply(execution);
    }

    @Override
    public void notify(DelegateExecution execution) {
        apply(execution);
    }

    private void apply(DelegateExecution ex) {
        CaseState s = CaseState.valueOf(Vars.field(state, ex).trim());
        String previous = Vars.str(ex, Vars.CASE_STATE);
        Vars.setCaseState(ex, s.name());
        String ov = Vars.field(outcomeVar, ex);
        if (ov != null) {
            ex.setVariable(ov, Vars.field(outcome, ex, s.name()));
        }
        if (!s.name().equals(previous)) {
            Vars.audit(ex, s.auditEvent(), Vars.map("from", previous, "to", s.name(),
                    "activity", ex.getCurrentActivityId()));
        }
    }
}
