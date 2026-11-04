// SPDX-License-Identifier: Apache-2.0
package org.sangam.casefile.delegate;

import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.sangam.casefile.CaseModule;
import org.sangam.casefile.file.CaseFileService;
import org.sangam.casefile.file.PartyService;

import java.sql.Connection;
import java.util.List;

import static org.sangam.casefile.delegate.DelegateSupport.*;

/**
 * BPMN service task on the case file (N04). Field {@code op}:
 * <ul>
 *   <li>{@code OPEN}: {@code caseType}, {@code caseTitle}, optional {@code caseAttributes} (map / JSON), {@code ownerSeat},
 *       {@code orgUnit}, {@code orgCode}, {@code jurisdiction}, {@code priority}; binds the current process; sets
 *       {@code caseFileId}, {@code caseNumber}, {@code caseStage};</li>
 *   <li>{@code BIND}: binds the current process instance to {@code caseFileId};</li>
 *   <li>{@code STAGE}: moves {@code caseFileId} to {@code caseStage} ({@code stageReason});</li>
 *   <li>{@code MILESTONE}: records {@code caseMilestone};</li>
 *   <li>{@code UPDATE}: merges {@code caseAttributes} into the file's fields;</li>
 *   <li>{@code ADD_PARTY}: {@code partyId}, or registers/matches {@code partyName} + {@code partyIdentifiers} (map) of
 *       {@code partyKind}; adds it in {@code partyRole}; sets {@code casePartyId} and {@code partyConflicts} (count of
 *       cross-case conflicts found);</li>
 *   <li>{@code LINK_RECORD}: {@code recordType}, {@code recordRef}, {@code recordRelation}, {@code recordTitle};</li>
 *   <li>{@code NOTE}: {@code noteText}, {@code noteVisibility};</li>
 *   <li>{@code ASSIGN}: {@code ownerSeat};</li>
 *   <li>{@code DUPLICATES}: sets {@code caseDuplicates} (count) for the file's parties;</li>
 *   <li>{@code CLOSE}: {@code closeReason}.</li>
 * </ul>
 * Refusals raise the BPMN error {@code CASEFILE_<CODE>}.
 */
public class CaseFileDelegate implements JavaDelegate {

    private Expression op;

    @Override
    public void execute(DelegateExecution ex) {
        String operation = field(op, ex, "OPEN");
        run(() -> perform(ex, operation));
    }

    private void perform(DelegateExecution ex, String operation) {
        CaseModule m = module();
        Connection c = connection();
        String t = tenant(ex);
        String actor = actor(ex);
        switch (operation) {
            case "OPEN" -> {
                CaseFileService.CaseFile cf = m.cases().open(c, t, new CaseFileService.OpenRequest(req(ex, "caseType"),
                        req(ex, "caseTitle"), str(ex, "jurisdiction"), str(ex, "orgUnit"), str(ex, "orgCode"), str(ex, "ownerSeat"),
                        str(ex, "priority"), str(ex, "confidentiality"), ex.getProcessBusinessKey(), map(ex, "caseAttributes"), null,
                        ex.getProcessInstanceId()), actor);
                ex.setVariable("caseFileId", cf.caseId());
                ex.setVariable("caseNumber", cf.caseNumber());
                ex.setVariable("caseStage", cf.stage());
            }
            case "BIND" -> m.cases().bindInstance(c, t, req(ex, "caseFileId"), "bpmn", ex.getProcessInstanceId(), actor);
            case "STAGE" -> {
                CaseFileService.CaseFile cf = m.cases().moveStage(c, t, req(ex, "caseFileId"), req(ex, "caseStage"), str(ex, "stageReason"),
                        -1, actor);
                ex.setVariable("caseStatus", cf.status().name());
            }
            case "MILESTONE" -> m.cases().milestone(c, t, req(ex, "caseFileId"), req(ex, "caseMilestone"), null, actor);
            case "UPDATE" -> m.cases().updateAttributes(c, t, req(ex, "caseFileId"), map(ex, "caseAttributes"), false, -1, actor);
            case "ADD_PARTY" -> {
                String caseId = req(ex, "caseFileId");
                String partyId = str(ex, "partyId");
                if (partyId == null) {
                    PartyService.Party p = m.parties().registerOrMatch(c, t, new PartyService.PartyInput(str(ex, "partyKind"),
                            req(ex, "partyName"), map(ex, "partyIdentifiers"), map(ex, "partyContact"), str(ex, "partyExternalRef")), actor);
                    partyId = p.partyId();
                }
                String role = req(ex, "partyRole");
                CaseFileService.CaseFile cf = m.cases().get(c, t, caseId);
                List<PartyService.Conflict> conflicts = m.parties().conflictCheck(c, t, cf.caseType(), caseId, partyId, role);
                PartyService.CaseParty cp = m.parties().addToCase(c, t, caseId, partyId, role, str(ex, "partyCapacity"),
                        str(ex, "representedBy"), bool(ex, "partyMasked"), actor);
                ex.setVariable("partyId", partyId);
                ex.setVariable("casePartyId", cp.casePartyId());
                ex.setVariable("partyConflicts", (int) conflicts.stream().filter(x -> x.kind().equals("CROSS_CASE_CONFLICT")).count());
            }
            case "LINK_RECORD" -> m.contents().linkRecord(c, t, req(ex, "caseFileId"), req(ex, "recordType"), req(ex, "recordRef"),
                    str(ex, "recordRelation"), str(ex, "recordTitle"), actor);
            case "NOTE" -> m.contents().addNote(c, t, req(ex, "caseFileId"), req(ex, "noteText"), str(ex, "noteVisibility"), actor);
            case "ASSIGN" -> m.cases().assign(c, t, req(ex, "caseFileId"), req(ex, "ownerSeat"), str(ex, "orgUnit"), -1, actor);
            case "DUPLICATES" -> {
                String caseId = req(ex, "caseFileId");
                CaseFileService.CaseFile cf = m.cases().get(c, t, caseId);
                List<String> partyIds = m.parties().caseParties(c, t, caseId, false).stream().map(PartyService.CaseParty::partyId).toList();
                ex.setVariable("caseDuplicates", m.parties().duplicates(c, t, cf.caseType(), caseId, partyIds).size());
            }
            case "CLOSE" -> m.cases().close(c, t, req(ex, "caseFileId"), req(ex, "closeReason"), -1, actor);
            default -> throw new ProcessEngineException("unknown case file operation " + operation);
        }
    }
}
