// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import java.util.ArrayList;
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
 * original verifier on appeal, J-P3-E04). Inside one round of a parallel multi-instance step (a joint approval) one
 * person completes at most one instance ({@link #actedInRound}). A controlled exception is the variable {@code sodExceptionId}, granted by a
 * higher authority through the admin route (AJ), which is recorded as {@code SOD_EXCEPTION_GRANTED}.
 */
public class SodGuardListener implements TaskListener {

    private Expression steps;
    private Expression excludeVar;
    private Expression stepSuffixVar;

    /** One earlier actor of the case that a segregation-of-duties check compares with: the step it acted in. */
    record EarlierActor(String step, String person, String seat) {
    }

    /**
     * The earlier actors a completion of step {@code current} must differ from, in the order the check reads them:
     * the initiator (as step {@code initiator}) when {@code only} is empty or names {@code initiate}, then every
     * {@code actorLog} entry of another step ({@code only} empty = every step; a step matches by its id or by its task
     * key before {@code #}). The same step repeated after a return is the same role and is not an earlier actor. Shared
     * with {@link SeatResolutionDelegate} op {@code EXCLUDE_ACTORS}, so that a seat is never offered for a step whose
     * completion this check would refuse (SL-32, J-P1-E09).
     */
    static List<EarlierActor> earlierActors(Set<String> only, String current, String initiatorPerson,
                                            String initiatorSeat, List<Map<String, Object>> log) {
        List<EarlierActor> out = new ArrayList<>();
        if (only.isEmpty() || only.contains("initiate")) {
            out.add(new EarlierActor("initiator", initiatorPerson, initiatorSeat));
        }
        for (Map<String, Object> e : log) {
            String step = String.valueOf(e.get("step"));
            if (step.equals(current) || "initiate".equals(step)) {
                continue; // the same step repeated after a return is the same role
            }
            if (!only.isEmpty() && !only.contains(step) && !only.contains(step.split("#")[0])) {
                continue;
            }
            out.add(new EarlierActor(step, e.get("person") == null ? null : String.valueOf(e.get("person")),
                    e.get("seat") == null ? null : String.valueOf(e.get("seat"))));
        }
        return out;
    }

    /**
     * True when {@code person} already completed another instance of the same round of the parallel step
     * {@code current} (the {@code round} of {@link ActorRecorderListener}): one person is one approval of a joint
     * approval, even through two seats held by additional charge or delegation (the joint-approval rule: one seat, or
     * one person, must not count as two approvals). Another person on another seat of the round, and the same person
     * in a later round (after a return), are not refused.
     */
    static boolean actedInRound(String person, String current, String round, List<Map<String, Object>> log) {
        if (round == null || person == null) {
            return false;
        }
        for (Map<String, Object> e : log) {
            if (current.equals(String.valueOf(e.get("step"))) && round.equals(e.get("round"))
                    && person.equals(e.get("person"))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void notify(DelegateTask t) {
        TaskActor a = TaskActor.of(t);
        Set<String> only = Set.copyOf(Vars.csv(Vars.field(steps, t)));
        String exception = Vars.str(t, "sodExceptionId");
        String conflict = null;
        String current = TaskActor.stepId(t, Vars.field(stepSuffixVar, t));
        List<Map<String, Object>> log = Vars.jsonList(t, Vars.ACTOR_LOG);
        for (EarlierActor e : earlierActors(only, current, Vars.str(t, Vars.INITIATOR_PERSON),
                Vars.str(t, Vars.INITIATOR_SEAT), log)) {
            if (a.person().equals(e.person()) || (a.seat() != null && a.seat().equals(e.seat()))) {
                conflict = e.step();
                break;
            }
        }
        if (conflict == null && actedInRound(a.person(), current, TaskActor.multiInstanceRound(t), log)) {
            conflict = current + " (another instance of the same round)";
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
