// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.delegate.BpmnError;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.JavaDelegate;

import org.sangam.core.layer.CapabilityClass;
import org.sangam.packs.port.ConnectorGateway;
import org.sangam.packs.port.ConnectorRequest;
import org.sangam.packs.port.ConnectorResult;
import org.sangam.packs.port.OperationStatus;
import org.sangam.packs.port.PackRuntime;
import org.sangam.packs.port.ReconciliationRequest;

/**
 * EXECUTION, ACKNOWLEDGED, RECONCILED and UNKNOWN_OUTCOME of the approval core (IX.3, VI.3, A-2, AC-03), used by
 * {@code sangam-core-execution} and {@code sangam-core-cancellation}. Field {@code op}:
 * <ul>
 *   <li>{@code PREPARE}: re-verifies the approval hash against the current material payload (K4; a mismatch writes
 *       {@code APPROVAL_INVALIDATED} and throws BPMN error {@code APPROVAL_INVALIDATED}, J-P1-E01), then derives
 *       {@code operationId = UUIDv5(businessKey | step | attempt)}; a new attempt number (new operationId) only after
 *       a confirmed failure or a cancellation (A-2, J-P1-E12);</li>
 *   <li>{@code SUBMIT}: {@link ConnectorGateway#submit} (outbox in the production adapter, K13); sets
 *       {@code opStatus} COMPLETED / FAILED / PENDING / UNKNOWN;</li>
 *   <li>{@code APPLY_RESULT}: applies an inbox result message ({@code resultStatus}, {@code resultReasonCode},
 *       {@code resultExternalRef});</li>
 *   <li>{@code LOOKUP}: outcome lookup after the acknowledgement window (class a/b); class (c) and exhausted lookups
 *       become UNKNOWN;</li>
 *   <li>{@code OPEN_RECON}: opens a reconciliation item (UNKNOWN_OUTCOME or a statement MISMATCH, OJ-02);</li>
 *   <li>{@code APPLY_RESOLUTION}: applies the operator's resolution ({@code resolution} = CONFIRMED_SUCCESS with
 *       {@code resolutionEvidence}, CONFIRMED_FAILURE, or RETRY_SAME_OPERATION, which class (c) refuses);</li>
 *   <li>{@code RECONCILE}: matches the acknowledged operation with the bank / treasury statement
 *       ({@code reconStatus} MATCHED / MISMATCH / PENDING);</li>
 *   <li>{@code CANCEL} / {@code APPLY_CANCEL_ACK}: VI.5 cancel of an in-flight operation ({@code cancelStatus}).</li>
 * </ul>
 */
public class ExternalOperationDelegate implements JavaDelegate {

    private Expression op;
    private Expression step;
    private Expression connector;
    private Expression operation;
    private Expression payloadFields;
    private Expression verifyApproval;
    private Expression reconcileConnector;
    private Expression maxLookups;
    private Expression maxReconAttempts;
    private Expression kind;

    @Override
    public void execute(DelegateExecution ex) {
        String o = Vars.field(op, ex, "SUBMIT");
        switch (o) {
            case "PREPARE" -> prepare(ex);
            case "SUBMIT" -> submit(ex);
            case "APPLY_RESULT" -> applyResult(ex);
            case "LOOKUP" -> lookup(ex);
            case "OPEN_RECON" -> openRecon(ex);
            case "APPLY_RESOLUTION" -> applyResolution(ex);
            case "RECONCILE" -> reconcile(ex);
            case "CANCEL" -> cancel(ex);
            case "APPLY_CANCEL_ACK" -> ex.setVariable("cancelStatus", Vars.str(ex, "cancelResult", "CANCELLED"));
            default -> throw new ProcessEngineException("unknown operation op " + o);
        }
    }

    private String conn(DelegateExecution ex) {
        String c = Vars.field(connector, ex, Vars.str(ex, "opConnector"));
        if (c == null) {
            throw new ProcessEngineException("no connector configured for the external operation");
        }
        return c;
    }

    private String bk(DelegateExecution ex) {
        return ex.getProcessBusinessKey() == null ? ex.getProcessInstanceId() : ex.getProcessBusinessKey();
    }

    private void prepare(DelegateExecution ex) {
        String s = Vars.field(step, ex, Vars.str(ex, "executionStep", "execute"));
        String suffix = Vars.str(ex, "executionStepSuffix");
        if (suffix != null && !suffix.isBlank()) {
            s = s + ":" + suffix; // one operation per instalment / item of the same step
        }
        if (Vars.fieldBool(verifyApproval, ex, true)) {
            String approved = Vars.str(ex, Vars.APPROVED_HASH);
            if (approved == null) {
                Vars.audit(ex, "APPROVAL_INVALIDATED", Vars.map("reason", "no approval recorded", "step", s));
                throw new BpmnError("APPROVAL_INVALIDATED", "no approval bound to this case");
            }
            String current = Vars.materialHash(ex);
            if (!approved.equals(current)) {
                Map<String, Object> was = Vars.jsonMap(ex, "approvedPayload");
                Map<String, Object> now = Vars.materialPayload(ex);
                List<String> changed = new ArrayList<>();
                for (String k : now.keySet()) {
                    if (!Objects.equals(String.valueOf(now.get(k)), String.valueOf(was.get(k)))) {
                        changed.add(k);
                    }
                }
                Vars.audit(ex, "APPROVAL_INVALIDATED", Vars.map("approvedHash", approved, "currentHash", current,
                        "changedFields", changed));
                ex.setVariable(Vars.APPROVED_HASH, null);
                ex.setVariable("approvalInvalidated", true);
                throw new BpmnError("APPROVAL_INVALIDATED", "material payload changed after approval: " + changed);
            }
        }
        int attempt = Vars.intVal(ex, s + "Attempt", 1);
        String last = Vars.str(ex, s + "LastStatus");
        if ("FAILED".equals(last) || "CANCELLED".equals(last)) {
            attempt++;
        }
        ex.setVariable(s + "Attempt", attempt);
        ex.setVariable(s + "LastStatus", "NEW");
        String c = conn(ex);
        String id = Vars.operationId(Vars.tenant(ex), bk(ex), s, attempt);
        ex.setVariable(Vars.OPERATION_ID, id);
        ex.setVariable("openOperationId", id);
        ex.setVariable("opStep", s);
        ex.setVariable("opConnector", c);
        ex.setVariable(Vars.OP_STATUS, "NEW");
        ex.setVariable("opLookups", 0);
        ex.setVariable("reconAttempts", 0);
        CapabilityClass cc = PackRuntime.gateway().capabilityOf(c);
        ex.setVariable("opCapability", cc.name());
        Vars.audit(ex, "EXT_OP_REQUESTED", Vars.map("connector", c, "step", s, "attempt", attempt,
                "capabilityClass", cc.name()));
    }

    private Map<String, Object> payload(DelegateExecution ex) {
        List<String> names = Vars.csv(Vars.field(payloadFields, ex));
        Map<String, Object> p = new LinkedHashMap<>(names.isEmpty() ? Vars.materialPayload(ex)
                : Vars.payloadOf(ex, names));
        String approved = Vars.str(ex, Vars.APPROVED_HASH);
        if (approved != null) {
            p.put("approvedPayloadHash", approved);
        }
        return p;
    }

    private void submit(DelegateExecution ex) {
        String c = conn(ex);
        String id = Vars.str(ex, Vars.OPERATION_ID);
        Map<String, Object> p = payload(ex);
        ConnectorResult r = PackRuntime.gateway().submit(new ConnectorRequest(Vars.tenant(ex), c,
                Vars.field(operation, ex, Vars.str(ex, "executionOperation", "execute")), id, bk(ex),
                ex.getProcessInstanceId(), Vars.withDirective(ex, c, p), Vars.hash(p)));
        apply(ex, r);
    }

    private void apply(DelegateExecution ex, ConnectorResult r) {
        ex.setVariable(Vars.OP_STATUS, r.status().name());
        ex.setVariable("opReasonCode", r.reasonCode());
        if (r.externalRef() != null) {
            ex.setVariable("opExternalRef", r.externalRef());
        }
        r.data().forEach((k, v) -> ex.setVariable("op_" + k, Vars.engineValue(v)));
        String s = Vars.str(ex, "opStep", "execute");
        ex.setVariable(s + "LastStatus", r.status().name());
        switch (r.status()) {
            case COMPLETED -> Vars.audit(ex, "EXT_OP_ACKNOWLEDGED", Vars.map("connector", Vars.str(ex, "opConnector"),
                    "externalRef", r.externalRef()));
            case FAILED -> Vars.audit(ex, "EXT_OP_FAILED", Vars.map("connector", Vars.str(ex, "opConnector"),
                    "reasonCode", r.reasonCode(), "message", r.message()));
            case UNKNOWN -> Vars.audit(ex, "EXT_OP_UNKNOWN", Vars.map("connector", Vars.str(ex, "opConnector"),
                    "message", r.message()));
            default -> {
            }
        }
    }

    private void applyResult(DelegateExecution ex) {
        String st = Vars.str(ex, "resultStatus", "UNKNOWN");
        OperationStatus s = OperationStatus.valueOf(st);
        apply(ex, new ConnectorResult(s, Vars.str(ex, "resultReasonCode"), null, Vars.str(ex, "resultExternalRef"),
                Map.of()));
    }

    private void lookup(DelegateExecution ex) {
        String c = conn(ex);
        int n = Vars.intVal(ex, "opLookups", 0) + 1;
        ex.setVariable("opLookups", n);
        int max = Vars.fieldInt(maxLookups, ex, 3);
        if (PackRuntime.gateway().capabilityOf(c) == CapabilityClass.C_NEITHER) {
            ConnectorResult r = PackRuntime.gateway().lookup(Vars.tenant(ex), c, Vars.str(ex, Vars.OPERATION_ID));
            apply(ex, r.status() == OperationStatus.PENDING ? ConnectorResult.unknown("class (c), no answer") : r);
            return;
        }
        ConnectorResult r = PackRuntime.gateway().lookup(Vars.tenant(ex), c, Vars.str(ex, Vars.OPERATION_ID));
        if (r.status() == OperationStatus.PENDING && n >= max) {
            r = ConnectorResult.unknown("no outcome after " + n + " lookups");
        }
        if (r.status() == OperationStatus.FAILED && "NOT_FOUND".equals(r.reasonCode())) {
            // nothing reached the external system: a controlled retry with the same operationId is safe
            ex.setVariable(Vars.OP_STATUS, "RETRY");
            return;
        }
        apply(ex, r);
    }

    private void openRecon(DelegateExecution ex) {
        String k = Vars.field(kind, ex, "UNKNOWN_OUTCOME");
        if ("UNKNOWN_OUTCOME".equals(k)) {
            ex.setVariable(Vars.CASE_STATE, CaseState.UNKNOWN_OUTCOME.name());
        }
        Map<String, Object> evidence = Vars.map("connector", Vars.str(ex, "opConnector"),
                "externalRef", Vars.str(ex, "opExternalRef"), "attempt", Vars.str(ex, "opStep") == null ? null
                        : ex.getVariable(Vars.str(ex, "opStep") + "Attempt"),
                "capabilityClass", Vars.str(ex, "opCapability"), "reconStatus", Vars.str(ex, "reconStatus"));
        String item = PackRuntime.gateway().openReconciliation(new ReconciliationRequest(Vars.tenant(ex),
                Vars.str(ex, "opConnector"), Vars.str(ex, Vars.OPERATION_ID), bk(ex), ex.getProcessInstanceId(), k,
                evidence));
        ex.setVariable("reconItemId", item);
        ex.setVariable("resolution", null);
        Vars.audit(ex, "RECON_ITEM_OPENED", Vars.map("item", item, "kind", k));
    }

    private void applyResolution(DelegateExecution ex) {
        String res = Vars.str(ex, "resolution", "");
        String evidence = Vars.str(ex, "resolutionEvidence");
        switch (res) {
            case "CONFIRMED_SUCCESS" -> {
                if (evidence == null) {
                    throw new ProcessEngineException("422 EVIDENCE_REQUIRED: success needs evidence (UTR, statement)");
                }
                ex.setVariable(Vars.OP_STATUS, "COMPLETED");
                ex.setVariable("reconStatus", "MATCHED");
                ex.setVariable(Vars.str(ex, "opStep", "execute") + "LastStatus", "COMPLETED");
            }
            case "CONFIRMED_FAILURE" -> {
                ex.setVariable(Vars.OP_STATUS, "FAILED");
                ex.setVariable("opReasonCode", Vars.str(ex, "resolutionReasonCode", "CONFIRMED_FAILURE"));
                ex.setVariable(Vars.str(ex, "opStep", "execute") + "LastStatus", "FAILED");
            }
            case "RETRY_SAME_OPERATION" -> {
                if (PackRuntime.gateway().capabilityOf(Vars.str(ex, "opConnector")) == CapabilityClass.C_NEITHER) {
                    throw new ProcessEngineException("422 RETRY_NOT_ALLOWED: class (c) operations are never repeated"
                            + " automatically; confirm success or failure with evidence (VI.3 rule 5)");
                }
                ex.setVariable(Vars.OP_STATUS, "RETRY");
            }
            default -> throw new ProcessEngineException("422 UNKNOWN_RESOLUTION: " + res);
        }
        Vars.audit(ex, "RECON_ITEM_RESOLVED", Vars.map("item", Vars.str(ex, "reconItemId"), "action", res,
                "evidence", evidence, "by", Vars.str(ex, "resolvedBy")));
    }

    private void reconcile(DelegateExecution ex) {
        String c = Vars.field(reconcileConnector, ex, Vars.str(ex, "reconcileConnector", "bank.statement"));
        int n = Vars.intVal(ex, "reconAttempts", 0) + 1;
        ex.setVariable("reconAttempts", n);
        Map<String, Object> p = Vars.map("operationId", Vars.str(ex, Vars.OPERATION_ID),
                "externalRef", Vars.str(ex, "opExternalRef"), "amount", Vars.canonical(ex.getVariable("amount")));
        ConnectorResult r = PackRuntime.gateway().query(new ConnectorRequest(Vars.tenant(ex), c, "match", null,
                bk(ex), ex.getProcessInstanceId(), Vars.withDirective(ex, c, p), Vars.hash(p)));
        String m = r.status() == OperationStatus.COMPLETED ? String.valueOf(r.data().getOrDefault("match", "MATCHED"))
                : "PENDING";
        if ("PENDING".equals(m) && n >= Vars.fieldInt(maxReconAttempts, ex, 24)) {
            m = "MISMATCH";
        }
        ex.setVariable("reconStatus", m);
        if ("MATCHED".equals(m)) {
            Vars.audit(ex, "CASE_RECONCILED", Vars.map("connector", c, "attempts", n));
        }
    }

    private void cancel(DelegateExecution ex) {
        String id = Vars.str(ex, "openOperationId");
        String c = Vars.str(ex, "opConnector");
        if (id == null || c == null) {
            ex.setVariable("cancelStatus", "CANCELLED");
            return;
        }
        ConnectorResult r = PackRuntime.gateway().cancel(Vars.tenant(ex), c, id, Vars.str(ex, "cancelReason"));
        ex.setVariable("cancelStatus", r.status().name());
        if (r.status() == OperationStatus.CANCELLED) {
            ex.setVariable(Vars.str(ex, "opStep", "execute") + "LastStatus", "CANCELLED");
            Vars.audit(ex, "CANCEL_ACKED", Vars.map("connector", c));
        } else if (r.status() == OperationStatus.COMPLETED) {
            Vars.audit(ex, "LATE_RESULT_AFTER_DELETE", Vars.map("connector", c, "note",
                    "effect already completed before the cancel reached the external system"));
        }
    }
}
