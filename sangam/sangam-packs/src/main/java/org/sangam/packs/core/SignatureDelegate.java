// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import java.util.Map;

import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.JavaDelegate;

import org.sangam.packs.port.ConnectorRequest;
import org.sangam.packs.port.ConnectorResult;
import org.sangam.packs.port.PackRuntime;

/**
 * Digital signature bound to the approval (GP-34, R57, G-SIGN, J-P2-E06/E07, J-P4-E09): the signed object is the
 * approved payload hash, so the signature step is keyed by that hash and a repeated request never produces a second
 * business effect. Field {@code op}: {@code REQUEST} (to {@code esign.sign} or {@code dsc.sign} chosen by
 * {@code signMethod}, ESP chosen by {@code espId} for a retry with another ESP) or {@code APPLY_RESULT} (message
 * {@value org.sangam.packs.port.ConnectorGateway#MSG_SIGNATURE_RESULT} with {@code signatureStatus}).
 * Sets {@code signatureStatus} COMPLETED / FAILED / PENDING / UNKNOWN; {@code signatureCertSerial} on success.
 */
public class SignatureDelegate implements JavaDelegate {

    private Expression op;
    private Expression purpose;

    @Override
    public void execute(DelegateExecution ex) {
        if ("APPLY_RESULT".equals(Vars.field(op, ex, "REQUEST"))) {
            String st = Vars.str(ex, "signatureResult", "UNKNOWN");
            ex.setVariable("signatureStatus", st);
            Vars.audit(ex, "COMPLETED".equals(st) ? "SIGNATURE_APPLIED" : "SIGNATURE_FAILED",
                    Vars.map("status", st, "certSerial", Vars.str(ex, "signatureCertSerial")));
            return;
        }
        String method = Vars.str(ex, "signMethod", "esign");
        String connector = "dsc".equalsIgnoreCase(method) ? "dsc.sign" : "esign.sign";
        String hash = Vars.str(ex, Vars.APPROVED_HASH, Vars.materialHash(ex));
        String esp = Vars.str(ex, "espId", "ESP-1");
        String bk = ex.getProcessBusinessKey() == null ? ex.getProcessInstanceId() : ex.getProcessBusinessKey();
        int attempt = Vars.intVal(ex, "signatureAttempt", 0) + 1;
        ex.setVariable("signatureAttempt", attempt);
        String opId = Vars.operationId(bk, "sign:" + Vars.field(purpose, ex, "approval") + ":" + hash + ":" + esp,
                attempt);
        Map<String, Object> payload = Vars.map("hash", hash, "purpose", Vars.field(purpose, ex, "approval"),
                "signer", Vars.str(ex, "lastActorPerson"), "signerSeat", Vars.str(ex, "lastActorSeat"), "esp", esp,
                "format", "PAdES-LTV");
        Vars.audit(ex, "SIGNATURE_REQUESTED", Vars.map("connector", connector, "esp", esp, "hash", hash));
        ConnectorResult r = PackRuntime.gateway().submit(new ConnectorRequest(Vars.tenant(ex), connector, "sign",
                opId, bk, ex.getProcessInstanceId(), Vars.withDirective(ex, connector, payload), Vars.hash(payload)));
        ex.setVariable("signatureStatus", r.status().name());
        ex.setVariable("signatureOperationId", opId);
        Object serial = r.data().get("certSerial");
        if (serial != null) {
            ex.setVariable("signatureCertSerial", serial.toString());
        }
        switch (r.status()) {
            case COMPLETED -> Vars.audit(ex, "SIGNATURE_APPLIED", Vars.map("certSerial", serial, "hash", hash,
                    "connector", connector, "esp", esp));
            case FAILED, UNKNOWN -> Vars.audit(ex, "SIGNATURE_FAILED", Vars.map("reason", r.reasonCode(),
                    "connector", connector, "esp", esp));
            default -> {
            }
        }
    }
}
