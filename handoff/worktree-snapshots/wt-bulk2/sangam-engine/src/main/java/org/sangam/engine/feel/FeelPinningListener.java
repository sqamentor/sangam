// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.feel;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

import org.operaton.bpm.dmn.engine.DmnDecision;
import org.operaton.bpm.dmn.engine.delegate.DmnDecisionEvaluationEvent;
import org.operaton.bpm.dmn.engine.delegate.DmnDecisionEvaluationListener;
import org.operaton.bpm.dmn.engine.delegate.DmnDecisionLogicEvaluationEvent;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.impl.context.Context;
import org.operaton.bpm.engine.impl.dmn.entity.repository.DecisionDefinitionEntity;
import org.sangam.core.engine.SangamClock;
import org.sangam.engine.tx.CommandState;

/**
 * R10 run-time half: before a decision's result is used, the decision (and every required decision) must be bound to
 * the FEEL engine running now. A mismatch fails the command (an incident in a job) with a message naming both
 * engines: the model must be redeployed (re-validated) under the new FEEL engine. A definition deployed before Sangam
 * recorded bindings is pinned on its first evaluation. The listener runs inside the engine command, so a refusal rolls
 * back the decision's effects.
 */
public final class FeelPinningListener implements DmnDecisionEvaluationListener {

    private final FeelBindingStore store;
    private final SangamClock clock;
    private volatile String current;

    public FeelPinningListener(FeelBindingStore store, SangamClock clock) {
        this.store = store;
        this.clock = clock;
    }

    @Override
    public void notify(DmnDecisionEvaluationEvent event) {
        if (!Context.hasActiveCommandContext()) {
            return; // standalone DMN engine use outside the process engine
        }
        String running = current;
        if (running == null) {
            running = FeelEngineIdentity.of(Context.getProcessEngineConfiguration().getDmnEngineConfiguration());
            current = running;
        }
        List<DmnDecisionLogicEvaluationEvent> all = new ArrayList<>(event.getRequiredDecisionResults());
        all.add(event.getDecisionResult());
        Connection c = CommandState.currentConnection();
        for (DmnDecisionLogicEvaluationEvent e : all) {
            DmnDecision d = e.getDecision();
            if (!(d instanceof DecisionDefinitionEntity def) || def.getId() == null) {
                continue;
            }
            String bound = store.record(c, def.getId(), def.getKey(), "DECISION", def.getDeploymentId(), running,
                    clock.now());
            if (!bound.equals(running)) {
                throw new ProcessEngineException("R10: decision '" + def.getKey() + "' (" + def.getId() + ") is bound to "
                        + "FEEL " + bound + " but the engine runs " + running + "; redeploy the model to re-validate "
                        + "it under the new FEEL engine");
            }
        }
    }
}
