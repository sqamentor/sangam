// SPDX-License-Identifier: Apache-2.0
package org.sangam.onboarding.flow;

import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.sangam.onboarding.api.StepId;

/** Every service task of the process: the activity id names the step, {@code readinessOk} routes the gateway. */
public final class OnboardingStepDelegate implements JavaDelegate {

    private final OnboardingService service;

    public OnboardingStepDelegate(OnboardingService service) {
        this.service = service;
    }

    @Override
    public void execute(DelegateExecution execution) {
        String runId = String.valueOf(execution.getVariable("runId"));
        StepId step = StepId.ofActivity(execution.getCurrentActivityId());
        boolean ok = service.executeStep(runId, step);
        if (step == StepId.READINESS) {
            execution.setVariable("readinessOk", ok);
        }
    }
}
