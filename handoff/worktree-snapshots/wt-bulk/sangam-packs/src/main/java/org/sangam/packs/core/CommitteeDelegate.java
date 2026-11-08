// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import java.util.List;
import java.util.Map;

import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.DelegateTask;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.operaton.bpm.engine.delegate.TaskListener;

/**
 * Committees (GP-03 all members sign, GP-04 quorum n of m, GP-23 opening committee, survey / condemnation boards,
 * selection boards, CAB). Members are seats in {@code committeeSeats} (comma list, at most
 * {@link org.sangam.packs.spi.ModelGuard#MAX_MULTI_INSTANCE}); the member task is a small parallel multi-instance
 * whose candidate group is {@code ${committeeSeats.split(',')[loopCounter]}}.
 *
 * <p>As a service task, {@code op=PREPARE} sets {@code committeeSize}, {@code quorum} ({@code quorumRule} ALL,
 * MAJORITY or a number) and clears the votes; {@code op=TALLY} sets {@code committeeApproved} (approvals reach the
 * quorum) and {@code committeeDissent}. As a task {@code complete} listener it records each member's {@code vote}
 * (approve / reject / abstain) and {@code voteRemarks} in {@code committeeVotes}.
 */
public class CommitteeDelegate implements JavaDelegate, TaskListener {

    private Expression op;

    @Override
    public void execute(DelegateExecution ex) {
        List<String> seats = Vars.csv(Vars.str(ex, "committeeSeats"));
        if ("TALLY".equals(Vars.field(op, ex, "PREPARE"))) {
            List<Map<String, Object>> votes = Vars.jsonList(ex, "committeeVotes");
            long approve = votes.stream().filter(v -> "approve".equals(v.get("vote"))).count();
            long reject = votes.stream().filter(v -> "reject".equals(v.get("vote"))).count();
            int quorum = Vars.intVal(ex, "quorum", seats.size());
            ex.setVariable("committeeApproved", approve >= quorum);
            ex.setVariable("committeeDissent", reject > 0);
            ex.setVariable("committeeApprovals", (int) approve);
            Vars.audit(ex, "COMMITTEE_DECIDED", Vars.map("approvals", approve, "rejections", reject,
                    "quorum", quorum, "members", seats.size()));
            return;
        }
        if (seats.isEmpty()) {
            throw new ProcessEngineException("committeeSeats is empty");
        }
        if (seats.size() > org.sangam.packs.spi.ModelGuard.MAX_MULTI_INSTANCE) {
            throw new ProcessEngineException("committee larger than " + org.sangam.packs.spi.ModelGuard.MAX_MULTI_INSTANCE
                    + " members: use the per-item batch pattern (GP-09)");
        }
        String rule = Vars.str(ex, "quorumRule", "ALL");
        int quorum = switch (rule) {
            case "ALL" -> seats.size();
            case "MAJORITY" -> seats.size() / 2 + 1;
            default -> Math.min(seats.size(), Integer.parseInt(rule.trim()));
        };
        ex.setVariable("committeeSize", seats.size());
        ex.setVariable("quorum", quorum);
        ex.setVariable("committeeVotes", "[]");
        ex.setVariable("committeeApprovals", 0);
    }

    @Override
    public void notify(DelegateTask t) {
        TaskActor a = TaskActor.of(t);
        List<Map<String, Object>> votes = Vars.jsonList(t, "committeeVotes");
        Object vote = t.getVariable("vote");
        votes.add(Vars.map("seat", a.seat(), "person", a.person(), "vote", vote == null ? "abstain" : vote.toString(),
                "remarks", Vars.str(t, "voteRemarks"), "at", Vars.now().toString()));
        t.getExecution().getProcessInstance().setVariable("committeeVotes", Vars.toJson(votes));
        long approvals = votes.stream().filter(v -> "approve".equals(v.get("vote"))).count();
        t.getExecution().getProcessInstance().setVariable("committeeApprovals", (int) approvals);
        Vars.auditTask(t, "COMMITTEE_VOTE", a.person(), a.seat(), a.actedFor() ? a.seat() : null, a.authorityBasis(),
                Vars.map("vote", vote));
    }
}
