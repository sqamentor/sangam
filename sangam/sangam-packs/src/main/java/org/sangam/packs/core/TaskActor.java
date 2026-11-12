// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import java.util.Optional;

import org.operaton.bpm.engine.delegate.DelegateTask;
import org.operaton.bpm.engine.task.IdentityLink;

import org.sangam.packs.port.PackRuntime;
import org.sangam.packs.port.SeatHolding;

/**
 * Who is completing a task, and as which seat (IX.5 rules 1 and 4). The REST layer sets the task-local variables
 * {@code actingPerson} (the authenticated person) and {@code actingSeat} (the seat context chosen on S02) when it
 * completes the task; without them the assignee and the first candidate group are used.
 */
public record TaskActor(String person, String seat, SeatHolding holding) {

    public static TaskActor of(DelegateTask t) {
        String person = Vars.str(t, "actingPerson");
        if (person == null) {
            person = t.getAssignee() == null ? "unknown" : t.getAssignee();
        }
        String seat = Vars.str(t, "actingSeat");
        if (seat == null) {
            for (IdentityLink l : t.getCandidates()) {
                if (l.getGroupId() != null) {
                    seat = l.getGroupId();
                    break;
                }
            }
        }
        SeatHolding h = null;
        if (seat != null) {
            Optional<SeatHolding> o = PackRuntime.seats().holding(Vars.tenant(t), person, seat, Vars.today(Vars.tenant(t)));
            h = o.orElse(null);
        }
        return new TaskActor(person, seat, h);
    }

    /** Step id used in {@code actorLog}: the task key, plus {@code #value} of the suffix variable when given. */
    public static String stepId(DelegateTask t, String suffixVar) {
        if (suffixVar == null) {
            return t.getTaskDefinitionKey();
        }
        Object v = t.getVariable(suffixVar);
        return v == null ? t.getTaskDefinitionKey() : t.getTaskDefinitionKey() + "#" + v;
    }

    /**
     * The round of a parallel multi-instance task (a joint approval): the activity instance id of its multi-instance
     * body ({@code <task key>#multiInstanceBody:<id>}), shared by the instances of one round and new on every entry, so
     * a repeat of the step after a return is another round. {@code null} for a task that is not multi-instance.
     */
    public static String multiInstanceRound(DelegateTask t) {
        String parent = t.getExecution() == null ? null : t.getExecution().getParentActivityInstanceId();
        return parent != null && parent.startsWith(t.getTaskDefinitionKey() + "#multiInstanceBody:") ? parent : null;
    }

    public boolean actedFor() {
        return holding != null && holding.actedFor();
    }

    public String authorityBasis() {
        return holding == null ? null : holding.basis() + (holding.authorityRef() == null ? ""
                : ":" + holding.authorityRef());
    }
}
