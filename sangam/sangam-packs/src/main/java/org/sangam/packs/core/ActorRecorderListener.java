// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import java.util.List;
import java.util.Map;

import org.operaton.bpm.engine.delegate.DelegateTask;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.TaskListener;

/**
 * Task {@code complete} listener: appends {step, person, seat, actedFor, decision} to {@code actorLog} (the input of
 * the segregation-of-duties check, K5), sets {@code lastActor*} for the audit of the following automatic steps, and
 * writes {@code TASK_COMPLETED} and, for a substitute, {@code ACTED_FOR} (IX.5 rule 4). Field {@code decisionVar}
 * names the decision variable of the task;
 * {@code stepSuffixVar} names a variable appended to the step id (the level of an n-level recommendation), so that
 * the same task definition at two levels counts as two roles.
 */
public class ActorRecorderListener implements TaskListener {

    private Expression decisionVar;
    private Expression stepSuffixVar;

    @Override
    public void notify(DelegateTask t) {
        TaskActor a = TaskActor.of(t);
        String dv = Vars.field(decisionVar, t);
        Object decision = dv == null ? null : t.getVariable(dv);
        List<Map<String, Object>> log = Vars.jsonList(t, Vars.ACTOR_LOG);
        log.add(Vars.map("step", TaskActor.stepId(t, Vars.field(stepSuffixVar, t)), "person", a.person(), "seat", a.seat(),
                "actedFor", a.actedFor(), "decision", decision == null ? null : decision.toString(),
                "at", Vars.now().toString()));
        Vars.setJsonDocument(t.getExecution(), Vars.ACTOR_LOG, log);
        t.getExecution().setVariable("lastActorPerson", a.person());
        t.getExecution().setVariable("lastActorSeat", a.seat());
        t.getExecution().setVariable("lastActedForSeat", a.actedFor() ? a.seat() : null);
        t.getExecution().setVariable("lastAuthorityBasis", a.authorityBasis());
        if (a.actedFor()) {
            Vars.auditTask(t, "ACTED_FOR", a.person(), a.seat(), a.seat(), a.authorityBasis(),
                    Vars.map("task", t.getTaskDefinitionKey()));
        }
        Vars.auditTask(t, "TASK_COMPLETED", a.person(), a.seat(), a.actedFor() ? a.seat() : null,
                a.authorityBasis(), Vars.map("task", t.getTaskDefinitionKey(), "decision",
                        decision == null ? null : decision.toString()));
    }
}
