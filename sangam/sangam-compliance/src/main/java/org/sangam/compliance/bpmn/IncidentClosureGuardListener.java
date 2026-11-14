// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.bpmn;

import java.util.List;

import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.delegate.DelegateTask;
import org.operaton.bpm.engine.delegate.TaskListener;

import org.sangam.compliance.breach.IncidentReportingService;

/**
 * Closure rule of the security incident journey (SANGAM.md OJ-06 step 8, coordinator ruling 2026-11-13): the follow-up
 * task of {@code sangam-incident-report} ({@code u_follow}, form {@code compliance/incident-follow-up}) completes towards
 * closing only with a non-blank {@code rootCause} and {@code lessonsLearned}. Otherwise {@code completeTask} is refused
 * with {@value #REFUSAL}, in the caller's command, so nothing is committed and the caller sees the 422 (the close step
 * itself is asynchronous, where a refusal could only fail a job; it keeps the same check as defence in depth).
 *
 * <p>The choice is to close when {@code followUpNeeded} is not true, which is the default flow of the process's
 * {@code g_follow} gateway ({@code ${followUpNeeded == true}} leads to the follow-up). A follow-up completion needs
 * neither field.
 */
public class IncidentClosureGuardListener implements TaskListener {

    public static final String REFUSAL = "422 INCIDENT_CLOSURE_INCOMPLETE";
    public static final String FOLLOW_UP_NEEDED = "followUpNeeded";

    @Override
    public void notify(DelegateTask t) {
        if (!EVENTNAME_COMPLETE.equals(t.getEventName()) || followUpChosen(t.getVariable(FOLLOW_UP_NEEDED))) {
            return;
        }
        List<String> missing = IncidentReportingService.missingClosureFields(t.getVariable(IncidentReportingService.ROOT_CAUSE),
                t.getVariable(IncidentReportingService.LESSONS_LEARNED));
        if (!missing.isEmpty()) {
            Object incident = t.getVariable("incidentId");
            throw new ProcessEngineException(REFUSAL + ": " + IncidentReportingService.closureRefusal(String.valueOf(incident), missing)
                    + ". Enter them in the follow-up form, or choose a follow-up instead of closing.");
        }
    }

    /** The gateway's reading of {@code followUpNeeded == true}: a boolean true, or the text true. */
    static boolean followUpChosen(Object v) {
        return v instanceof Boolean b ? b : v != null && "true".equalsIgnoreCase(v.toString().trim());
    }
}
