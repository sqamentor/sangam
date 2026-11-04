// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.delegate.DelegateTask;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.TaskListener;

/**
 * Segregation of duties at task completion, inside the command transaction (K5, IX.5 rule 5, CX-8):
 * the initiator, the checker and the approver must differ <b>by person and by seat</b>, including through additional
 * charge. A violation aborts the completion with {@code 422 SOD_VIOLATION} and writes {@code SOD_VIOLATION_BLOCKED}.
 *
 * <p>Fields: {@code steps} (comma list of earlier task keys to compare with; empty = every earlier actor, including
 * the initiator), {@code excludeVar} (a variable holding a comma list of person ids that must not act, e.g. the
 * original verifier on appeal, J-P3-E04). A controlled exception is the variable {@code sodExceptionId}, granted by a
 * higher authority through the admin route (AJ), which is recorded as {@code SOD_EXCEPTION_GRANTED}.
 */
public class SodGuardListener implements TaskListener {

    private Expression steps;
    private Expression excludeVar;
    private Expression stepSuffixVar;

    @Override
    public void notify(DelegateTask t) {
        TaskActor a = TaskActor.of(t);
        Set<String> only = Set.copyOf(Vars.csv(Vars.field(steps, t)));
        String exception = Vars.str(t, "sodExceptionId");
        String conflict = null;
        if (only.isEmpty() || only.contains("initiate")) {
            if (a.person().equals(Vars.str(t, Vars.INITIATOR_PERSON))
                    || (a.seat() != null && a.seat().equals(Vars.str(t, Vars.INITIATOR_SEAT)))) {
                conflict = "initiator";
            }
        }
        String current = TaskActor.stepId(t, Vars.field(stepSuffixVar, t));
        if (conflict == null) {
            List<Map<String, Object>> log = Vars.jsonList(t, Vars.ACTOR_LOG);
            for (Map<String, Object> e : log) {
                String step = String.valueOf(e.get("step"));
                if (step.equals(current) || "initiate".equals(step)) {
                    continue; // the same step repeated after a return is the same role
                }
                if (!only.isEmpty() && !only.contains(step) && !only.contains(step.split("#")[0])) {
                    continue;
                }
                if (a.person().equals(e.get("person")) || (a.seat() != null && a.seat().equals(e.get("seat")))) {
                    conflict = step;
                    break;
                }
            }
        }
        String ex = Vars.field(excludeVar, t);
        if (conflict == null && ex != null && Vars.csv(Vars.str(t, ex)).contains(a.person())) {
            conflict = ex;
        }
        if (conflict == null) {
            return;
        }
        if (exception != null) {
            Vars.auditTask(t, "SOD_EXCEPTION_GRANTED", a.person(), a.seat(), null, exception,
                    Vars.map("task", t.getTaskDefinitionKey(), "conflictsWith", conflict));
            return;
        }
        Vars.auditTask(t, "SOD_VIOLATION_BLOCKED", a.person(), a.seat(), null, a.authorityBasis(),
                Vars.map("task", t.getTaskDefinitionKey(), "conflictsWith", conflict));
        throw new ProcessEngineException("422 SOD_VIOLATION: " + a.person() + " / " + a.seat()
                + " already acted on this case as " + conflict + " (IX.5 rule 5)");
    }
}
