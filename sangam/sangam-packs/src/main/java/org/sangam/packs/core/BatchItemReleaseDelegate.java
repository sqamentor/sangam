// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.JavaDelegate;

/**
 * Owner decision E1, in a batch child before its payment: binds the payment to the batch parent's sanction only when
 * the item this child carries is the one the sanction approved, unchanged (its fingerprint), and the sanction was a
 * person's or a delegated power's (E2). Otherwise the payment is held with the reason (an incident), and the item
 * needs a fresh sanction. On success the child's own material payload is bound exactly as a human approval binds it,
 * so core execution re-verifies it like any other approval (A1, E3).
 */
public class BatchItemReleaseDelegate implements JavaDelegate {

    @Override
    public void execute(DelegateExecution ex) {
        String by = Vars.str(ex, "batchApprovedBy");
        String approvedFp = Vars.str(ex, "batchApprovedItemFingerprint");
        if (by == null || approvedFp == null || Vars.str(ex, "batchApprovedHash") == null) {
            throw BatchSeal.held("item " + Vars.str(ex, "itemKey") + " carries no batch sanction");
        }
        if (MoneyOutApproval.DEEMED_PRINCIPAL.equals(by)) {
            throw BatchSeal.held("a deemed sanction never releases money out (E2)");
        }
        if (!approvedFp.equals(BatchSeal.childFingerprint(ex::getVariable))) {
            throw BatchSeal.held("item " + Vars.str(ex, "itemKey")
                    + " changed after the batch sanction; a fresh sanction is needed");
        }
        java.util.List<String> missing = MoneyOutApproval.missingMoneyFields(ex::getVariable);
        if (!missing.isEmpty()) {
            throw BatchSeal.held("item " + Vars.str(ex, "itemKey") + " lacks the material fields " + missing + " (E3)");
        }
        String hash = Vars.materialHash(ex);
        ex.setVariable(Vars.APPROVED_HASH, hash);
        ex.setVariable("approvedPayload", Vars.toJson(Vars.materialPayload(ex)));
        ex.setVariable("approvedBy", by);
        ex.setVariable("approvalInvalidated", false);
        Vars.audit(ex, "BATCH_ITEM_RELEASED", Vars.map("item", Vars.str(ex, "itemKey"), "payloadHash", hash,
                "batchApprovedHash", Vars.str(ex, "batchApprovedHash"), "approvedBy", by));
    }
}
