// SPDX-License-Identifier: Apache-2.0
package org.sangam.casefile.delegate;

import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.sangam.casefile.CaseModule;
import org.sangam.casefile.reference.ReferenceService;

import java.sql.Connection;
import java.time.Instant;

import static org.sangam.casefile.delegate.DelegateSupport.*;

/**
 * BPMN service task for references with a deadline (N16). Field {@code op}:
 * <ul>
 *   <li>{@code REGISTER}: {@code referenceKind}, {@code subject}, {@code sourceName}, {@code sourceCategory},
 *       {@code sourceRef}, {@code referenceBody}, {@code receivedAt}, {@code receivedVia}, {@code priority}, {@code orgUnit},
 *       {@code orgCode}, {@code assignedSeat}, {@code fixedDueAt}, {@code referenceAttributes} (map / JSON),
 *       {@code parentReferenceId}, {@code allowDuplicate}; sets {@code referenceId}, {@code referenceNumber},
 *       {@code referenceDueAt}, {@code caseFileId} (when the kind opens a case file);</li>
 *   <li>{@code ASSIGN}: {@code assignedSeat}, {@code assignedPerson}, {@code remarks};
 *       {@code TRANSFER}: {@code toOrgUnit}, {@code assignedSeat}, {@code remarks};</li>
 *   <li>{@code START}; {@code INTERIM}: {@code replyText}, {@code replyRef}; {@code DRAFT}: {@code replyText};
 *       {@code APPROVE}: approves the draft (the current user must not be the drafter);</li>
 *   <li>{@code REPLY}: {@code replyText} (optional when the approved draft is sent), {@code replyRef}; sets
 *       {@code replyLate};</li>
 *   <li>{@code EXTEND}: {@code newDueAt}, {@code extensionReason}; {@code FOLLOW_UP}: {@code followUpSubject},
 *       {@code fixedDueAt}; sets {@code followUpReferenceId};</li>
 *   <li>{@code STATUS}: sets {@code referenceStatus}, {@code referenceOverdue};</li>
 *   <li>{@code CLOSE}: {@code closeReason}.</li>
 * </ul>
 */
public class ReferenceDelegate implements JavaDelegate {

    private Expression op;

    @Override
    public void execute(DelegateExecution ex) {
        String operation = field(op, ex, "REGISTER");
        run(() -> perform(ex, operation));
    }

    private void perform(DelegateExecution ex, String operation) {
        CaseModule m = module();
        Connection c = connection();
        String t = tenant(ex);
        String actor = actor(ex);
        ReferenceService refs = m.references();
        switch (operation) {
            case "REGISTER" -> {
                ReferenceService.Reference r = refs.register(c, t, new ReferenceService.RegisterRequest(req(ex, "referenceKind"),
                        str(ex, "sourceName"), str(ex, "sourceCategory"), str(ex, "sourceRef"), req(ex, "subject"), str(ex, "referenceBody"),
                        instant(ex, "receivedAt"), str(ex, "receivedVia"), str(ex, "priority"), str(ex, "orgUnit"), str(ex, "orgCode"),
                        str(ex, "assignedSeat"), str(ex, "assignedPerson"), instant(ex, "fixedDueAt"), map(ex, "referenceAttributes"),
                        str(ex, "parentReferenceId"), bool(ex, "allowDuplicate")), actor);
                ex.setVariable("referenceId", r.referenceId());
                ex.setVariable("referenceNumber", r.number());
                ex.setVariable("referenceDueAt", java.util.Date.from(r.dueAt()));
                if (r.caseId() != null) {
                    ex.setVariable("caseFileId", r.caseId());
                }
            }
            case "ASSIGN" -> refs.assign(c, t, req(ex, "referenceId"), req(ex, "assignedSeat"), str(ex, "assignedPerson"), str(ex, "remarks"), actor);
            case "TRANSFER" -> refs.transfer(c, t, req(ex, "referenceId"), str(ex, "toOrgUnit"), req(ex, "assignedSeat"), req(ex, "remarks"), actor);
            case "START" -> refs.startWork(c, t, req(ex, "referenceId"), actor);
            case "INTERIM" -> refs.interimReply(c, t, req(ex, "referenceId"), req(ex, "replyText"), str(ex, "replyRef"), actor);
            case "DRAFT" -> refs.draftReply(c, t, req(ex, "referenceId"), req(ex, "replyText"), actor);
            case "APPROVE" -> refs.approveReply(c, t, req(ex, "referenceId"), str(ex, "draftHash"), actor);
            case "REPLY" -> {
                ReferenceService.Reference r = refs.reply(c, t, req(ex, "referenceId"), str(ex, "replyText"), str(ex, "replyRef"), actor);
                ex.setVariable("replyLate", r.late());
            }
            case "EXTEND" -> {
                Instant due = instant(ex, "newDueAt");
                if (due == null) {
                    throw new ProcessEngineException("variable newDueAt is required");
                }
                refs.extend(c, t, req(ex, "referenceId"), due, req(ex, "extensionReason"), actor);
            }
            case "FOLLOW_UP" -> ex.setVariable("followUpReferenceId", refs.spawnFollowUp(c, t, req(ex, "referenceId"),
                    str(ex, "followUpSubject"), instant(ex, "fixedDueAt"), map(ex, "followUpAttributes"), actor).referenceId());
            case "STATUS" -> {
                ReferenceService.Reference r = refs.get(c, t, req(ex, "referenceId"));
                ex.setVariable("referenceStatus", r.status().name());
                ex.setVariable("referenceOverdue", r.dueAt() != null && r.repliedAt() == null
                        && r.dueAt().isBefore(org.sangam.casefile.support.Db.now(c, m.dialect())));
            }
            case "CLOSE" -> refs.close(c, t, req(ex, "referenceId"), req(ex, "closeReason"), actor);
            default -> throw new ProcessEngineException("unknown reference operation " + operation);
        }
    }
}
