// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.timer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.sangam.core.engine.TimerCatchUpPolicy;
import org.sangam.engine.SangamEngineSettings;

/**
 * Resolves the catch-up policy of a timer (R31): the BPMN extension property {@code sangam:catchUp} on the timer event
 * (collected at deploy by the deployment validator), else the per-process-key setting, else the engine default.
 */
public final class TimerPolicies {

    private final SangamEngineSettings settings;
    private final Map<String, TimerCatchUpPolicy> byActivity = new ConcurrentHashMap<>();

    public TimerPolicies(SangamEngineSettings settings) {
        this.settings = settings;
    }

    /** Called by the deployment validator for every timer event that declares {@code sangam:catchUp}. */
    public void declare(String processDefinitionKey, String activityId, TimerCatchUpPolicy policy) {
        byActivity.put(processDefinitionKey + ":" + activityId, policy);
    }

    public TimerCatchUpPolicy policyFor(String processDefinitionKey, String activityId) {
        TimerCatchUpPolicy p = byActivity.get(processDefinitionKey + ":" + activityId);
        return p != null ? p : settings.catchUpFor(processDefinitionKey);
    }

    public int maxCatchUpFires() {
        return settings.getMaxCatchUpFires();
    }
}
