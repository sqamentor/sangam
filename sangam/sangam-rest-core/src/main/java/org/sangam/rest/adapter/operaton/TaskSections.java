// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.operaton.bpm.engine.task.TaskQuery;
import org.sangam.rest.port.RequestContext;

/**
 * The one predicate of each inbox section (SANGAM.md S03 "counts from same predicate", register E-32(e), joint decision
 * EV-M-0962 Q-B): {@code GET /tasks/counts} ({@link OperatonWorkspaceAdapter#taskCounts}) counts with it and
 * {@code GET /tasks?section=} ({@link OperatonTaskAdapter}) lists with it, so a list and its count never disagree.
 * <ul>
 *   <li>{@code MY_SEAT}: assigned to the caller, or a candidate task of the request's seat (the acted-for seat when one
 *       is named, else the own seat);</li>
 *   <li>{@code ACTING_FOR}: a candidate task of a seat the caller holds now by additional charge or leave substitute
 *       (under either seat context; acting on it still needs that seat as the request's seat context, K7);</li>
 *   <li>{@code POOL}, {@code ESCALATED}, {@code RETURNED}, {@code ALL}: the default visibility ({@link #visible}),
 *       unassigned, escalated, returned, or all.</li>
 * </ul>
 * {@code MY_SEAT} and {@code ACTING_FOR} are themselves the caller's predicate; the other sections narrow the default
 * visibility. Every criterion is put on the given query, which already carries the tenant.
 */
final class TaskSections {

    private TaskSections() {
    }

    /** True when the section's predicate stands in place of the default visibility. */
    static boolean replacesVisibility(String section) {
        return "MY_SEAT".equals(section) || "ACTING_FOR".equals(section);
    }

    /**
     * Puts the predicate of {@code section} on {@code q}.
     *
     * @param actingSeats the seats the caller holds now by additional charge or leave substitute (read only for
     *                    {@code ACTING_FOR})
     * @return false when the section is empty by definition (no acting seat), so the caller lists or counts nothing
     */
    static boolean apply(RequestContext ctx, TaskQuery q, String section, Supplier<List<String>> actingSeats) {
        switch (section) {
            case "MY_SEAT" -> {
                if (ctx.effectiveSeatId() != null) {
                    q.or().taskAssignee(ctx.principalId()).taskCandidateGroup(ctx.effectiveSeatId()).endOr();
                } else {
                    q.taskAssignee(ctx.principalId());
                }
            }
            case "ACTING_FOR" -> {
                List<String> seats = actingSeats.get();
                if (seats.isEmpty()) {
                    return false;
                }
                // in an OR branch, so a candidateGroup/seatId filter on the query does not clash (Operaton refuses
                // candidateGroup together with candidateGroupIn on one query)
                q.or().taskCandidateGroupIn(seats).endOr();
            }
            case "POOL" -> visible(ctx, q).taskUnassigned();
            case "ESCALATED" -> visible(ctx, q).taskVariableValueEquals(OperatonWorkspaceAdapter.ESCALATED, true);
            case "RETURNED" -> visible(ctx, q).processVariableValueEquals(OperatonTaskAdapter.DECISION, "RETURNED");
            case "ALL" -> visible(ctx, q);
            default -> throw new IllegalArgumentException("unknown inbox section " + section);
        }
        return true;
    }

    /**
     * The default visibility rule (VI.4 rules 1-3, K2): administrators, operators and the system see the tenant's tasks;
     * everyone else the tasks assigned to them, offered to them, or offered to one of their groups or to the seat acted
     * in (the acted-for seat when one is named).
     */
    static TaskQuery visible(RequestContext ctx, TaskQuery q) {
        if (seesAll(ctx)) {
            return q;
        }
        List<String> groups = new ArrayList<>(ctx.groups());
        if (ctx.effectiveSeatId() != null && !groups.contains(ctx.effectiveSeatId())) {
            groups.add(ctx.effectiveSeatId());
        }
        // Operaton's or() returns the OR branch: every criterion of the branch and endOr() go on that object (on q the
        // engine refuses "cannot set endOr() before or()", R7-ui sweep).
        TaskQuery any = q.or().taskAssignee(ctx.principalId()).taskCandidateUser(ctx.principalId());
        if (!groups.isEmpty()) {
            any.taskCandidateGroupIn(groups);
        }
        return any.endOr();
    }

    static boolean seesAll(RequestContext ctx) {
        return ctx.inGroup("sangam-admin") || ctx.inGroup("sangam-operator") || ctx.inGroup("sangam-system");
    }
}
