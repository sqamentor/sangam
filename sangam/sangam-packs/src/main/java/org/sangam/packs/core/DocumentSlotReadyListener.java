// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import java.util.ArrayList;
import java.util.List;

import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.delegate.DelegateTask;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.TaskListener;
import org.sangam.packs.port.PackRuntime;

/**
 * Owner decisions 387 (9) and 390: the prepare task before a call into a DSC gate process (packgen {@code prepare.py})
 * completes only when each slot the called gates sign holds a document that the document route bound and that no DSC
 * gate instance has consumed yet ({@link org.sangam.packs.port.DocumentSlotBindings#isBound}, asked with this task's
 * instance and its callers, so every consumption by a gate instance counts). A slot left from an earlier round (signed
 * at a gate that returned the file, or at an earlier call) is refused with {@value #REFUSAL}, as is an empty slot, a
 * slot the route did not bind, or a failing check (fail closed): the officer uploads the document again (a new
 * document, or a new version of it) through the document route on this task, then completes it.
 *
 * <p>{@code slots} names the pairs, {@code slot:TYPE} separated by commas (written by packgen from the called gates).
 */
public class DocumentSlotReadyListener implements TaskListener {

    public static final String REFUSAL = "422 DOCUMENT_SLOT_NOT_READY";

    private Expression slots;

    @Override
    public void notify(DelegateTask t) {
        if (!EVENTNAME_COMPLETE.equals(t.getEventName())) {
            return;
        }
        List<String[]> pairs = pairs(Vars.field(slots, t, null));
        if (pairs.isEmpty()) {
            throw new ProcessEngineException(REFUSAL + ": the prepare task names no document slot (packgen)");
        }
        String tenant = Vars.tenant(t);
        List<String> chain = DscSignatureGuardListener.callChain(t);
        for (String[] p : pairs) {
            String slot = p[0];
            String type = p[1];
            Object v = t.getExecution().getVariable(slot);
            String documentId = v == null || v.toString().isBlank() ? null : v.toString();
            String reason = null;
            if (documentId == null) {
                reason = "no " + type + " document in " + slot;
            } else {
                boolean bound;
                try {
                    bound = PackRuntime.slotBindings().isBound(tenant, chain, slot, type, documentId);
                } catch (RuntimeException e) {
                    bound = false;
                }
                if (!bound) {
                    reason = "the " + type + " document in " + slot + " was already signed at a DSC step of an earlier"
                            + " round, or was not uploaded through the document route for this case";
                }
            }
            if (reason != null) {
                TaskActor a = TaskActor.of(t);
                Vars.auditTask(t, "DOCUMENT_SLOT_NOT_READY", a.person(), a.seat(), null, a.authorityBasis(),
                        Vars.map("taskId", t.getId(), "slot", slot, "documentId", documentId, "documentType", type,
                                "reason", reason));
                throw new ProcessEngineException(REFUSAL + ": " + reason + " (decisions 267, 385 and 390). Upload the "
                        + type + " document to sign through the document route on this task (a new document, or a new"
                        + " version of it), then complete it.");
            }
        }
    }

    /** {@code slot:TYPE[,slot:TYPE...]}; a malformed pair is a broken model and refuses. */
    static List<String[]> pairs(String csv) {
        List<String[]> out = new ArrayList<>();
        for (String part : Vars.csv(csv)) {
            int i = part.indexOf(':');
            if (i <= 0 || i == part.length() - 1) {
                throw new ProcessEngineException(REFUSAL + ": malformed slot pair '" + part + "' (slot:TYPE)");
            }
            out.add(new String[] {part.substring(0, i).trim(), part.substring(i + 1).trim()});
        }
        return out;
    }
}
