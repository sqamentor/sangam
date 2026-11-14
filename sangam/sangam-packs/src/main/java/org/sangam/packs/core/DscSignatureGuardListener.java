// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import java.util.Objects;

import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.delegate.DelegateTask;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.TaskListener;

import org.sangam.packs.port.PackRuntime;
import org.sangam.packs.port.SignatureVerifications;

/**
 * A task that needs a DSC signature completes only over a verified signature of exactly the required document by the
 * person completing it (decisions 140, 165, 227, 236 and 237: only {@code VERIFIED_VALID} allows a signed transition).
 *
 * <p>Packgen names the required document per DSC step: {@code documentSlot} is the case variable that holds its
 * document id and {@code documentType} its type (decision 236). On {@code create} the listener records the slot's
 * document id task-locally ({@value #REQUIRED_DOCUMENT}, with the slot name in {@value #SLOT}); on {@code complete}
 * it checks that document only: the slot must be unchanged since the task was created, a client-named
 * {@code dscDocumentId} must be the same document, and
 * {@link org.sangam.packs.port.SignatureVerifications#status} must answer {@code VERIFIED} for the engine tenant,
 * this task, that document and the completing person. {@code ENROLMENT_REVOKED} (signed, but the signing enrolment
 * was revoked afterwards) refuses with {@code 422 DSC_ENROLMENT_REVOKED} (decision 241); anything else, a failing
 * check included, refuses with {@code 422 DSC_SIGNATURE_NOT_VERIFIED} (fail closed); a signing outcome in a variable
 * never counts.
 *
 * <p>On success the verified evidence is written to the case ({@value #EVIDENCE_TASK}, {@value #EVIDENCE_DOCUMENT},
 * {@value #EVIDENCE_TYPE}, {@value #EVIDENCE_SIGNER}); {@link SignatureDelegate} records it instead of calling
 * {@code dsc.sign} (decision 238).
 *
 * <p>The step needs a DSC signature when the decision ({@code decisionVar}) is one of {@code approveValues}, the
 * boolean {@code requiredVar} is true, the method ({@code methodVar}, default {@code signMethod}) is {@code dsc} and
 * the optional {@code skipVar} (a deemed decision, which the process never signs) is not true.
 */
public class DscSignatureGuardListener implements TaskListener {

    public static final String METHOD_DSC = "dsc";
    public static final String REFUSAL = "422 DSC_SIGNATURE_NOT_VERIFIED";
    /** Decision 241: the document was signed, but the signing enrolment was revoked afterwards. */
    public static final String REFUSAL_REVOKED = "422 DSC_ENROLMENT_REVOKED";
    public static final String REQUIRED_DOCUMENT = "dscRequiredDocumentId";
    public static final String SLOT = "dscDocumentSlot";
    public static final String CLIENT_DOCUMENT = "dscDocumentId";
    public static final String EVIDENCE_TASK = "dscEvidenceTaskId";
    public static final String EVIDENCE_DOCUMENT = "dscEvidenceDocumentId";
    public static final String EVIDENCE_TYPE = "dscEvidenceDocumentType";
    public static final String EVIDENCE_SIGNER = "dscEvidenceSigner";

    private Expression decisionVar;
    private Expression approveValues;
    private Expression requiredVar;
    private Expression methodVar;
    private Expression skipVar;
    private Expression documentSlot;
    private Expression documentType;

    @Override
    public void notify(DelegateTask t) {
        String slot = Vars.field(documentSlot, t, null);
        if (EVENTNAME_CREATE.equals(t.getEventName())) {
            if (slot != null) {
                t.setVariableLocal(SLOT, slot);
                Object doc = t.getExecution().getVariable(slot);
                t.setVariableLocal(REQUIRED_DOCUMENT, doc == null || doc.toString().isBlank() ? null : doc.toString());
            }
            return;
        }
        if (!EVENTNAME_COMPLETE.equals(t.getEventName()) || !needsDsc(t)) {
            return;
        }
        TaskActor a = TaskActor.of(t);
        String type = Vars.field(documentType, t, "document");
        String tenant = Vars.tenant(t);
        Object captured = t.getVariableLocal(REQUIRED_DOCUMENT);
        String documentId = captured == null ? null : captured.toString();
        Object named = t.getVariableLocal(CLIENT_DOCUMENT);
        String reason = null;
        boolean revoked = false;
        if (slot == null) {
            reason = "the step names no document slot";
        } else if (documentId == null) {
            reason = "no " + type + " document in " + slot + " when the task was created";
        } else if (!Objects.equals(documentId, Vars.str(t, slot))) {
            reason = "the " + type + " document in " + slot + " changed after the task was created";
        } else if (named != null && !named.toString().isBlank() && !documentId.equals(named.toString())) {
            reason = "the signed document is not the required " + type + " document";
        } else {
            try {
                SignatureVerifications.Status status = PackRuntime.signatures().status(tenant, t.getId(), documentId,
                        a.person());
                if (status == SignatureVerifications.Status.ENROLMENT_REVOKED) {
                    revoked = true;
                    reason = "the DSC enrolment that signed the " + type + " document was revoked after signing";
                } else if (status != SignatureVerifications.Status.VERIFIED) {
                    reason = "no verified DSC signature of the " + type + " document by " + a.person() + " at this task";
                }
            } catch (RuntimeException e) {
                reason = "the signature check failed: " + e.getClass().getSimpleName();
            }
        }
        if (reason != null) {
            String code = revoked ? "DSC_ENROLMENT_REVOKED" : "DSC_SIGNATURE_NOT_VERIFIED";
            Vars.auditTask(t, code, a.person(), a.seat(), null, a.authorityBasis(),
                    Vars.map("taskId", t.getId(), "documentId", documentId, "documentType", type, "reason", reason));
            throw new ProcessEngineException((revoked ? REFUSAL_REVOKED : REFUSAL) + ": " + reason + (revoked
                    ? ". Sign the required document again with your currently approved DSC at this task, then complete it."
                    : ". Sign the required document with your enrolled DSC at this task, then complete it."));
        }
        t.getExecution().setVariable(EVIDENCE_TASK, t.getId());
        t.getExecution().setVariable(EVIDENCE_DOCUMENT, documentId);
        t.getExecution().setVariable(EVIDENCE_TYPE, type);
        t.getExecution().setVariable(EVIDENCE_SIGNER, a.person());
        Vars.auditTask(t, "DSC_SIGNATURE_VERIFIED", a.person(), a.seat(), null, a.authorityBasis(),
                Vars.map("taskId", t.getId(), "documentId", documentId, "documentType", type));
    }

    private boolean needsDsc(DelegateTask t) {
        String dv = Vars.field(decisionVar, t, "decision");
        Object decision = t.getVariable(dv);
        if (decision == null || !Vars.csv(Vars.field(approveValues, t, "approve")).contains(decision.toString())) {
            return false;
        }
        if (!Vars.bool(t, Vars.field(requiredVar, t, "signatureRequired"))) {
            return false;
        }
        if (!METHOD_DSC.equalsIgnoreCase(Vars.str(t, Vars.field(methodVar, t, "signMethod"), ""))) {
            return false;
        }
        String skip = Vars.field(skipVar, t, null);
        return skip == null || !Vars.bool(t, skip);
    }
}
