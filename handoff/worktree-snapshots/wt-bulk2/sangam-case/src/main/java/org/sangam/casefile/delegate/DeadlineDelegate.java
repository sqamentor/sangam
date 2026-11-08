// SPDX-License-Identifier: Apache-2.0
package org.sangam.casefile.delegate;

import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.sangam.casefile.CaseModule;
import org.sangam.casefile.config.CaseConfig;
import org.sangam.casefile.deadline.DeadlineService;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;

import static org.sangam.casefile.delegate.DelegateSupport.*;

/**
 * BPMN service task for deadlines and action items. Field {@code op}:
 * <ul>
 *   <li>{@code SWEEP}: runs sweep rounds of up to {@code sweepBatch} (default 200) until fewer are found, at most
 *       {@code sweepRounds} (default 10); sets {@code deadlinesProcessed}. Model it in a timer-started process for a
 *       periodic sweep, or call {@link DeadlineService#sweep} from a scheduler;</li>
 *   <li>{@code START}: {@code scopeType}, {@code scopeId}, {@code deadlineKind}, {@code period} or {@code dueAt},
 *       {@code calendar}, {@code warnBefore}, {@code ownerSeat}; sets {@code deadlineId}, {@code deadlineDueAt};</li>
 *   <li>{@code MEET}: {@code deadlineId}; {@code STATE}: sets {@code deadlineState}, {@code deadlineLate};</li>
 *   <li>{@code ATR}: {@code actionId}, {@code atrText} (the owner's action-taken report);
 *       {@code VERIFY_ATR}: {@code actionId}, {@code atrAccepted}, {@code atrReason}.</li>
 * </ul>
 */
public class DeadlineDelegate implements JavaDelegate {

    private Expression op;

    @Override
    public void execute(DelegateExecution ex) {
        String operation = field(op, ex, "SWEEP");
        run(() -> perform(ex, operation));
    }

    private void perform(DelegateExecution ex, String operation) {
        CaseModule m = module();
        String actor = actor(ex);
        String t = tenant(ex);
        switch (operation) {
            case "SWEEP" -> {
                int batch = ex.getVariable("sweepBatch") == null ? 200 : Integer.parseInt(str(ex, "sweepBatch"));
                int rounds = ex.getVariable("sweepRounds") == null ? 10 : Integer.parseInt(str(ex, "sweepRounds"));
                int total = 0;
                for (int i = 0; i < rounds; i++) {
                    int n = m.deadlines().sweep(batch);
                    total += n;
                    if (n < batch) {
                        break;
                    }
                }
                ex.setVariable("deadlinesProcessed", total);
            }
            case "START" -> {
                Connection c = connection();
                Instant due = instant(ex, "dueAt");
                List<CaseConfig.EscalationStep> ladder = List.of();
                DeadlineService.Deadline d = due != null
                        ? m.deadlines().startAt(c, t, req(ex, "scopeType"), req(ex, "scopeId"), req(ex, "deadlineKind"), str(ex, "ownerSeat"),
                        str(ex, "ownerPerson"), due, str(ex, "calendar"), str(ex, "warnBefore"), ladder)
                        : m.deadlines().start(c, t, req(ex, "scopeType"), req(ex, "scopeId"), req(ex, "deadlineKind"), str(ex, "ownerSeat"),
                        str(ex, "ownerPerson"), req(ex, "period"), str(ex, "calendar"), str(ex, "warnBefore"), ladder);
                ex.setVariable("deadlineId", d.id());
                ex.setVariable("deadlineDueAt", java.util.Date.from(d.dueAt()));
            }
            case "MEET" -> m.deadlines().meet(connection(), t, req(ex, "deadlineId"), actor);
            case "STATE" -> {
                DeadlineService.Deadline d = m.deadlines().get(connection(), t, req(ex, "deadlineId"))
                        .orElseThrow(() -> new ProcessEngineException("unknown deadline"));
                ex.setVariable("deadlineState", d.state().name());
                ex.setVariable("deadlineLate", d.late());
            }
            case "ATR" -> m.actions().submitAtr(connection(), t, req(ex, "actionId"), req(ex, "atrText"), actor);
            case "VERIFY_ATR" -> m.actions().verify(connection(), t, req(ex, "actionId"), bool(ex, "atrAccepted"), str(ex, "atrReason"), actor);
            default -> throw new ProcessEngineException("unknown deadline operation " + operation);
        }
    }
}
