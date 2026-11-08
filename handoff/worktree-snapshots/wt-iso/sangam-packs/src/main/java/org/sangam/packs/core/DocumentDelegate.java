// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import java.util.LinkedHashMap;
import java.util.Map;

import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.JavaDelegate;

import org.sangam.packs.port.ConnectorRequest;
import org.sangam.packs.port.ConnectorResult;
import org.sangam.packs.port.OperationStatus;
import org.sangam.packs.port.PackRuntime;

/**
 * Issues an output document from the <b>approved</b> data only (K4): payment advice and voucher (P1), approved note
 * and dispatch letter (P2), sanction order (P3), licence certificate (P4), rejection order with reasons and appeal
 * route, appellate order, work order, LoA, PPO, admit cards. Bilingual Hindi + English rendering is the template's
 * job (R54, amount in words in both languages). The rendering connector {@code docs.issue} is idempotent per
 * operationId (class a), so a retried step returns the same document number.
 *
 * <p>Fields: {@code template}; {@code resultVar} (document number variable, default {@code <template>No});
 * {@code requireApproval} (true: refuse when the current payload no longer matches {@code approvedPayloadHash}).
 */
public class DocumentDelegate implements JavaDelegate {

    private Expression template;
    private Expression resultVar;
    private Expression requireApproval;

    @Override
    public void execute(DelegateExecution ex) {
        String tpl = Vars.field(template, ex, "generic-order");
        String rv = Vars.field(resultVar, ex, tpl.replace("-", "") + "No");
        if (Vars.fieldBool(requireApproval, ex, false)) {
            String approved = Vars.str(ex, Vars.APPROVED_HASH);
            if (approved == null || !approved.equals(Vars.materialHash(ex))) {
                throw new ProcessEngineException("409 APPROVAL_INVALIDATED: " + tpl
                        + " is rendered only from the approved version");
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>(Vars.materialPayload(ex));
        payload.put("template", tpl);
        payload.put("languages", "hi,en");
        payload.put("caseState", Vars.str(ex, Vars.CASE_STATE));
        payload.put("reasons", Vars.str(ex, "rejectionReasonCodes"));
        payload.put("approvedPayloadHash", Vars.str(ex, Vars.APPROVED_HASH));
        String bk = ex.getProcessBusinessKey() == null ? ex.getProcessInstanceId() : ex.getProcessBusinessKey();
        String opId = Vars.operationId(Vars.tenant(ex), bk, "doc:" + tpl, Vars.intVal(ex, Vars.CASE_VERSION, 1));
        ConnectorResult r = PackRuntime.gateway().submit(new ConnectorRequest(Vars.tenant(ex), "docs.issue", tpl,
                opId, bk, ex.getProcessInstanceId(), Vars.withDirective(ex, "docs.issue", payload),
                Vars.hash(payload)));
        if (r.status() == OperationStatus.FAILED) {
            throw new ProcessEngineException("document " + tpl + " could not be issued: " + r.reasonCode());
        }
        Object no = r.data().getOrDefault("documentNo", r.externalRef());
        ex.setVariable(rv, no == null ? opId : no.toString());
        Vars.audit(ex, "DOCUMENT_ISSUED", Vars.map("template", tpl, "documentNo", no));
    }
}
