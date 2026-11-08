// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.TaskService;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.JavaDelegate;

import org.sangam.packs.port.LedgerStore;
import org.sangam.packs.port.PackRuntime;

/**
 * Declarative assignment strategies (R82, UP-04, UP-10, GP-17): computes the target group on the server from the
 * candidate list; the REST layer re-validates at claim and complete (S07 rule). Field {@code strategy}:
 * {@code ROUND_ROBIN} (counter in the ledger store, per pool), {@code LEAST_LOADED} (fewest open tasks in the
 * engine), {@code SKILLS} (first candidate whose skill list, variable {@code skills_<candidate>} or the seed, covers
 * every skill in {@code requiredSkills}), {@code PRIORITY} (high-priority work to the first, senior pool).
 * Fields {@code candidatesVar} (comma list), {@code resultVar} (default {@code assignedGroup}).
 */
public class AssignmentStrategyDelegate implements JavaDelegate {

    private Expression strategy;
    private Expression candidatesVar;
    private Expression resultVar;

    @Override
    public void execute(DelegateExecution ex) {
        List<String> cands = Vars.csv(Vars.str(ex, Vars.field(candidatesVar, ex, "candidateGroups")));
        if (cands.isEmpty()) {
            throw new ProcessEngineException("no candidates for assignment");
        }
        String s = Vars.field(strategy, ex, "LEAST_LOADED");
        String chosen = switch (s) {
            case "ROUND_ROBIN" -> {
                LedgerStore l = PackRuntime.ledgers();
                String pool = String.join("|", cands);
                String ref = (ex.getProcessBusinessKey() == null ? ex.getProcessInstanceId()
                        : ex.getProcessBusinessKey()) + ":" + ex.getCurrentActivityId();
                LedgerStore.Posting p = l.post(Vars.tenant(ex), "round-robin", pool, "COUNT", BigDecimal.ONE, ref, true);
                yield cands.get(p.balanceAfter().intValue() % cands.size());
            }
            case "SKILLS" -> {
                Set<String> need = Set.copyOf(Vars.csv(Vars.str(ex, "requiredSkills")));
                String pick = null;
                for (String c : cands) {
                    Set<String> has = Set.copyOf(Vars.csv(Vars.str(ex, "skills_" + c)));
                    if (has.containsAll(need)) {
                        pick = c;
                        break;
                    }
                }
                yield pick == null ? cands.get(0) : pick;
            }
            case "PRIORITY" -> "P1".equals(Vars.str(ex, "priority")) || "CRITICAL".equals(Vars.str(ex, "priority"))
                    ? cands.get(0) : cands.get(cands.size() - 1);
            default -> {
                TaskService ts = ex.getProcessEngineServices().getTaskService();
                String best = cands.get(0);
                long min = Long.MAX_VALUE;
                for (String c : cands) {
                    long n = ts.createTaskQuery().taskCandidateGroup(c).count();
                    if (n < min) {
                        min = n;
                        best = c;
                    }
                }
                yield best;
            }
        };
        ex.setVariable(Vars.field(resultVar, ex, "assignedGroup"), chosen);
        Vars.audit(ex, "TASK_ASSIGNED", Vars.map("seat", chosen, "basis", s, "candidates", cands));
    }
}
