// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import java.util.ArrayList;
import java.util.List;

import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.JavaDelegate;

import org.sangam.packs.port.PackRuntime;

/**
 * n-level recommendation by seat (IX.3 RECOMMENDATION 1..n, GP-02) without multi-instance: the levels are the comma
 * list {@code recommendationSeats} and the position is {@code levelIndex}. Field {@code op}:
 * <ul>
 *   <li>{@code INIT}: start at level 1, or, after a return to the initiator and with {@code resubmitTo =
 *       RETURNING_OFFICER}, straight at the level that returned it (J-P2-E02 package option);</li>
 *   <li>{@code RESOLVE}: set {@code currentSeat} and {@code recLevel}, or {@code recDone = true} past the last level;</li>
 *   <li>{@code ADVANCE}: next level;</li>
 *   <li>{@code RETURN}: back to any earlier level {@code returnToLevel} (1-based) or, with 0, to the initiator
 *       ({@code recReturnedToInitiator}); writes {@code CASE_RETURNED};</li>
 *   <li>{@code COMMENTS}: prepares parallel comments from the seats in {@code commentSeats} (J-P2-E05);</li>
 *   <li>{@code MARK}: out-of-hierarchy marking to {@code markToSeat} with a mandatory {@code markReason} if the package
 *       allows it ({@code outOfHierarchyAllowed}); skipped seats get an FYI copy (J-P2-E01).</li>
 * </ul>
 */
public class RecommendationLevelDelegate implements JavaDelegate {

    private Expression op;

    @Override
    public void execute(DelegateExecution ex) {
        String o = Vars.field(op, ex, "RESOLVE");
        List<String> seats = Vars.csv(Vars.str(ex, "recommendationSeats"));
        int idx = Vars.intVal(ex, "levelIndex", 0);
        switch (o) {
            case "INIT" -> {
                int start = 0;
                int from = Vars.intVal(ex, "returnedFromLevel", -1);
                if (from > 0 && "RETURNING_OFFICER".equals(Vars.str(ex, "resubmitTo"))) {
                    start = Math.max(0, from - 1);
                }
                ex.setVariable("levelIndex", start);
                ex.setVariable("recReturnedToInitiator", false);
                ex.setVariable("recDone", false);
                resolve(ex, seats, start);
            }
            case "RESOLVE" -> resolve(ex, seats, idx);
            case "ADVANCE" -> {
                ex.setVariable("levelIndex", idx + 1);
                resolve(ex, seats, idx + 1);
            }
            case "RETURN" -> {
                int target = Vars.intVal(ex, "returnToLevel", 0);
                int current = idx + 1;
                if (target <= 0) {
                    ex.setVariable("recReturnedToInitiator", true);
                    ex.setVariable("returnedFromLevel", current);
                    Vars.audit(ex, "CASE_RETURNED", Vars.map("fromLevel", current, "toLevel", 0,
                            "reason", Vars.str(ex, "returnReason")));
                    return;
                }
                if (target >= current) {
                    throw new ProcessEngineException("422 INVALID_RETURN: can only return to an earlier level ("
                            + target + " >= " + current + ")");
                }
                ex.setVariable("levelIndex", target - 1);
                Vars.audit(ex, "CASE_RETURNED", Vars.map("fromLevel", current, "toLevel", target,
                        "reason", Vars.str(ex, "returnReason")));
                resolve(ex, seats, target - 1);
            }
            case "MARK" -> mark(ex, seats, idx);
            case "COMMENTS" -> {
                List<String> c = Vars.csv(Vars.str(ex, "commentSeats"));
                if (c.isEmpty() || c.size() > org.sangam.packs.spi.ModelGuard.MAX_MULTI_INSTANCE) {
                    throw new ProcessEngineException("422 COMMENT_SEATS: give 1.."
                            + org.sangam.packs.spi.ModelGuard.MAX_MULTI_INSTANCE + " seats to seek comments from");
                }
                ex.setVariable("commentSeats", Vars.joinCsv(c));
                ex.setVariable("commentCount", c.size());
                Vars.audit(ex, "TASK_CREATED", Vars.map("comments", c, "level", idx + 1));
            }
            default -> throw new ProcessEngineException("unknown recommendation op " + o);
        }
    }

    private static void resolve(DelegateExecution ex, List<String> seats, int idx) {
        if (idx >= seats.size()) {
            ex.setVariable("recDone", true);
            ex.setVariable("currentSeat", null);
            return;
        }
        ex.setVariable("recDone", false);
        ex.setVariable("currentSeat", seats.get(idx));
        ex.setVariable("recLevel", idx + 1);
    }

    private static void mark(DelegateExecution ex, List<String> seats, int idx) {
        String to = Vars.str(ex, "markToSeat");
        String reason = Vars.str(ex, "markReason");
        Object allowed = ex.getVariable("outOfHierarchyAllowed");
        if (to == null) {
            throw new ProcessEngineException("422 MARK_TARGET_REQUIRED");
        }
        if (Boolean.FALSE.equals(allowed)) {
            throw new ProcessEngineException("422 OUT_OF_HIERARCHY_NOT_ALLOWED: allowed seats are " + seats);
        }
        if (reason == null || reason.isBlank()) {
            throw new ProcessEngineException("422 REASON_REQUIRED: out-of-hierarchy marking needs a reason");
        }
        List<String> list = new ArrayList<>(seats);
        int pos = list.indexOf(to);
        List<String> skipped = new ArrayList<>();
        int next;
        if (pos > idx) {
            skipped.addAll(list.subList(idx + 1, pos));
            next = pos;
        } else {
            list.add(Math.min(idx + 1, list.size()), to);
            next = idx + 1;
        }
        ex.setVariable("recommendationSeats", Vars.joinCsv(list));
        ex.setVariable("levelIndex", next);
        Vars.audit(ex, "TASK_MARKED_OUT_OF_HIERARCHY", Vars.map("toSeat", to, "reason", reason,
                "skippedSeats", skipped));
        for (String s : skipped) {
            NotifyDelegate.send(ex, "inapp", "fyi-out-of-hierarchy", s, PackRuntime.gateway());
        }
        resolve(ex, list, next);
    }
}
