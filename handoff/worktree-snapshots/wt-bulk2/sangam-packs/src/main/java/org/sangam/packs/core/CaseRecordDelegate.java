// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import java.util.List;
import java.util.Map;

import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.JavaDelegate;

/**
 * Case record steps of the approval core. Field {@code op}:
 * <ul>
 *   <li>{@code REJECT}: requires coded reasons {@code rejectionReasonCodes} (comma list) and, optionally,
 *       {@code rejectionRemarks}; moves to REJECTED and writes {@code CASE_REJECTED} (J-P3-E01);</li>
 *   <li>{@code RESUBMIT}: {@code caseVersion} n to n+1 with the payload hash in {@code versionHistory} (R43,
 *       J-P1-E10); a material change after an approval invalidates it ({@code APPROVAL_INVALIDATED}, K4);</li>
 *   <li>{@code RETENTION}: classifies the closed record (R61, CSMOP 2022 category A permanent / C up to 10 years,
 *       review year; a legal hold keeps it from weeding) and writes {@code RETENTION_CLASSIFIED};</li>
 *   <li>{@code APPEAL}: records {@code APPEAL_FILED} and the persons excluded from deciding the appeal (the original
 *       deciders, K5 extended, J-P3-E04 and J-P4-E06) in {@code appealExcludedPersons};</li>
 *   <li>{@code BIND_DEEMED}: binds a deemed approval (system principal on behalf of the competent seat) to the
 *       current payload hash, exactly as a human approval is bound (J-P4-E07, IX.5 rule 6);</li>
 *   <li>{@code LEGAL_HOLD} / {@code RELEASE_HOLD}: GP-40 court stay and R61 legal hold markers.</li>
 * </ul>
 */
public class CaseRecordDelegate implements JavaDelegate {

    private Expression op;
    private Expression category;
    private Expression years;

    @Override
    public void execute(DelegateExecution ex) {
        switch (Vars.field(op, ex, "")) {
            case "REJECT" -> reject(ex);
            case "RESUBMIT" -> resubmit(ex);
            case "RETENTION" -> retention(ex);
            case "APPEAL" -> appeal(ex);
            case "BIND_DEEMED" -> {
                String hash = Vars.materialHash(ex);
                ex.setVariable(Vars.APPROVED_HASH, hash);
                ex.setVariable("approvedPayload", Vars.toJson(Vars.materialPayload(ex)));
                ex.setVariable("approvedBy", "system");
                ex.setVariable("approvalInvalidated", false);
                Vars.audit(ex, "APPROVAL_RECORDED", Vars.map("payloadHash", hash, "deemed", true,
                        "onBehalfOf", Vars.str(ex, "lastActorSeat"), "rule", Vars.str(ex, "deemedRule")));
            }
            case "LEGAL_HOLD" -> {
                ex.setVariable("legalHold", true);
                Vars.audit(ex, "LEGAL_HOLD_SET", Vars.map("reason", Vars.str(ex, "holdReason"),
                        "order", Vars.str(ex, "holdOrderRef")));
            }
            case "RELEASE_HOLD" -> {
                ex.setVariable("legalHold", false);
                Vars.audit(ex, "LEGAL_HOLD_RELEASED", Vars.map("order", Vars.str(ex, "releaseOrderRef")));
            }
            default -> throw new ProcessEngineException("unknown case record op");
        }
    }

    private void reject(DelegateExecution ex) {
        List<String> reasons = Vars.csv(Vars.str(ex, "rejectionReasonCodes"));
        if (reasons.isEmpty() && !Vars.bool(ex, "deemedDecision")) {
            throw new ProcessEngineException("422 REASONS_REQUIRED: a rejection needs at least one coded reason");
        }
        if (reasons.isEmpty()) {
            ex.setVariable("rejectionReasonCodes", "DEEMED_REFUSAL");
        }
        Vars.setCaseState(ex, CaseState.REJECTED.name());
        Vars.audit(ex, "CASE_REJECTED", Vars.map("reasons", Vars.str(ex, "rejectionReasonCodes"),
                "remarks", Vars.str(ex, "rejectionRemarks"), "deemed", Vars.bool(ex, "deemedDecision")));
    }

    private void resubmit(DelegateExecution ex) {
        int v = Vars.intVal(ex, Vars.CASE_VERSION, 1);
        String hash = Vars.materialHash(ex);
        List<Map<String, Object>> hist = Vars.jsonList(ex, "versionHistory");
        if (hist.isEmpty()) {
            hist.add(Vars.map("version", v, "hash", Vars.str(ex, "submittedPayloadHash")));
        }
        hist.add(Vars.map("version", v + 1, "hash", hash, "at", Vars.now().toString()));
        Vars.setJsonDocument(ex, "versionHistory", hist);
        ex.setVariable(Vars.CASE_VERSION, v + 1);
        String approved = Vars.str(ex, Vars.APPROVED_HASH);
        if (approved != null && !approved.equals(hash)) {
            ex.setVariable(Vars.APPROVED_HASH, null);
            ex.setVariable("approvalInvalidated", true);
            Vars.audit(ex, "APPROVAL_INVALIDATED", Vars.map("reason", "material change on resubmission",
                    "fromVersion", v, "toVersion", v + 1));
        }
        Vars.audit(ex, "CASE_RESUBMITTED", Vars.map("fromVersion", v, "toVersion", v + 1, "hash", hash));
    }

    private void retention(DelegateExecution ex) {
        String cat = Vars.field(category, ex, Vars.str(ex, "retentionCategory", "C"));
        int y = Vars.fieldInt(years, ex, Vars.intVal(ex, "retentionYears", "A".equals(cat) ? 25 : 10));
        int review = Vars.today().getYear() + y;
        ex.setVariable("retentionCategory", cat);
        ex.setVariable("retentionReviewYear", review);
        Vars.audit(ex, "RETENTION_CLASSIFIED", Vars.map("category", cat, "reviewYear", review,
                "legalHold", Vars.bool(ex, "legalHold")));
    }

    private void appeal(DelegateExecution ex) {
        List<Map<String, Object>> log = Vars.jsonList(ex, Vars.ACTOR_LOG);
        StringBuilder excluded = new StringBuilder();
        for (Map<String, Object> e : log) {
            if (!"initiate".equals(e.get("step")) && e.get("person") != null) {
                if (excluded.length() > 0) {
                    excluded.append(',');
                }
                excluded.append(e.get("person"));
            }
        }
        ex.setVariable("appealExcludedPersons", excluded.toString());
        ex.setVariable("appealCount", Vars.intVal(ex, "appealCount", 0) + 1);
        Vars.audit(ex, "APPEAL_FILED", Vars.map("grounds", Vars.str(ex, "appealGrounds"),
                "excluded", excluded.toString()));
    }
}
