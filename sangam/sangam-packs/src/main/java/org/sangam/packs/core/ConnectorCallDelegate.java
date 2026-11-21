// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import java.util.Map;

import org.operaton.bpm.engine.delegate.BpmnError;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.JavaDelegate;

import org.sangam.packs.port.ConnectorRequest;
import org.sangam.packs.port.ConnectorResult;
import org.sangam.packs.port.OperationStatus;
import org.sangam.packs.port.PackRuntime;

/**
 * Generic connector call through {@link org.sangam.packs.port.ConnectorGateway} for pack steps that are not the
 * core EXECUTION effect: budget availability, duplicate checks, registry and DigiLocker pulls, e-procurement
 * publication, referral to another department, court-portal lookups, AI extraction, provisioning steps.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code connector}, {@code operation};</li>
 *   <li>{@code mode}: {@code query} (read-only, default) or {@code submit} (effect, exactly once by operationId derived
 *       from the case, {@code step} and the step attempt);</li>
 *   <li>{@code inputs}: comma list of variables sent as the payload (default: the material fields); an entry
 *       {@code variable>parameter} sends the variable under the connector's contract name;</li>
 *   <li>{@code resultPrefix}: result data entries are written as {@code <prefix><Key>} and the status as
 *       {@code <prefix>Status} (default prefix = last segment of the connector name); a returned field named like an
 *       outcome variable (status, reasonCode, ref, attempt, operationId) is written as {@code <prefix>Returned<Key>},
 *       so it never replaces the step's outcome;</li>
 *   <li>{@code errorOnFailure}: BPMN error code thrown on a FAILED result (else the status is only recorded);</li>
 *   <li>{@code failedWhenInvalid} ({@code true}): for a validation query, whose answer is {@code valid} with
 *       {@code findings}: an answer {@code valid=false} is a FAILED result of the step, with the code of its first
 *       error finding as the reason code (else {@code INVALID}); the answer's data is still written. Without it a
 *       check that says "invalid" is recorded as COMPLETED, and under a result prefix shared with an earlier step it
 *       replaced that step's FAILED status and reason code (a bill of an unknown type read COMPLETED after its check);</li>
 *   <li>{@code documents} (decision 223): comma list of result data keys stored as a bytes JSON document
 *       ({@link Vars#setJsonDocument}) instead of a string variable, for data that can exceed the engine's
 *       4000-character string limit (a frozen wage register); a list or map is written as JSON, a text answer is
 *       stored as given and must be JSON ({@link Vars#jsonList} checks it when read).</li>
 * </ul>
 */
public class ConnectorCallDelegate implements JavaDelegate {

    private Expression connector;
    private Expression operation;
    private Expression mode;
    private Expression inputs;
    private Expression resultPrefix;
    private Expression step;
    private Expression errorOnFailure;
    private Expression failedWhenInvalid;
    private Expression documents;

    @Override
    public void execute(DelegateExecution ex) {
        String c = Vars.field(connector, ex);
        String opName = Vars.field(operation, ex, "call");
        String prefix = Vars.field(resultPrefix, ex, c.substring(c.lastIndexOf('.') + 1));
        Map<String, Object> payload = Vars.csv(Vars.field(inputs, ex)).isEmpty() ? Vars.materialPayload(ex)
                : Vars.payloadOf(ex, Vars.csv(Vars.field(inputs, ex)));
        String bk = ex.getProcessBusinessKey() == null ? ex.getProcessInstanceId() : ex.getProcessBusinessKey();
        ConnectorResult r;
        if ("submit".equalsIgnoreCase(Vars.field(mode, ex, "query"))) {
            String held = MoneyOutApproval.refusal(c, ex::getVariable);
            if (held != null) {
                Vars.audit(ex, MoneyOutApproval.ERROR, Vars.map("reason", held, "connector", c));
                // held as an incident with the reason, never a BPMN error a model could leave uncaught
                throw new org.operaton.bpm.engine.ProcessEngineException(MoneyOutApproval.HELD + ": " + held);
            }
            String s = Vars.field(step, ex, c + ":" + opName);
            int attempt = Vars.intVal(ex, prefix + "Attempt", 1);
            if ("FAILED".equals(Vars.str(ex, prefix + "Status"))) {
                attempt++;
            }
            ex.setVariable(prefix + "Attempt", attempt);
            String id = Vars.operationId(Vars.tenant(ex), bk, s, attempt);
            ex.setVariable(prefix + "OperationId", id);
            r = PackRuntime.gateway().submit(new ConnectorRequest(Vars.tenant(ex), c, opName, id, bk,
                    ex.getProcessInstanceId(), Vars.withDirective(ex, c, payload), Vars.hash(payload)));
            Vars.audit(ex, r.status() == OperationStatus.FAILED ? "EXT_OP_FAILED" : "EXT_OP_REQUESTED",
                    Vars.map("connector", c, "operation", opName, "status", r.status().name(),
                            "reasonCode", r.reasonCode()));
        } else {
            r = PackRuntime.gateway().query(new ConnectorRequest(Vars.tenant(ex), c, opName, null, bk,
                    ex.getProcessInstanceId(), Vars.withDirective(ex, c, payload), Vars.hash(payload)));
            if ("true".equalsIgnoreCase(Vars.field(failedWhenInvalid, ex))) {
                r = failedWhenInvalid(r);
            }
        }
        ex.setVariable(prefix + "Status", r.status().name());
        ex.setVariable(prefix + "ReasonCode", r.reasonCode());
        if (r.externalRef() != null) {
            ex.setVariable(prefix + "Ref", r.externalRef());
        }
        java.util.List<String> docs = Vars.csv(Vars.field(documents, ex));
        r.data().forEach((k, v) -> {
            String name = dataVariable(prefix, k);
            if (!docs.contains(k)) {
                Vars.setDynamic(ex, name, Vars.engineValue(v));
            } else if (v instanceof CharSequence text) {
                Vars.setDynamic(ex, name, org.operaton.bpm.engine.variable.Variables.byteArrayValue(
                        text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            } else {
                Vars.setJsonDocument(ex, name, v);
            }
        });
        String err = Vars.field(errorOnFailure, ex);
        if (err != null && r.status() == OperationStatus.FAILED) {
            throw new BpmnError(err, c + " failed: " + r.reasonCode());
        }
    }

    /**
     * The step's own outcome variables, {@code <prefix><Name>}: never written from a returned field (R7-it, 62ee53db:
     * the party registry's {@code status} replaced {@code payeeStatus}, so every payee check failed).
     */
    static final java.util.Set<String> OUTCOME = java.util.Set.of("Status", "ReasonCode", "Ref", "Attempt",
            "OperationId");

    /**
     * The variable a returned field {@code key} is written to: {@code <prefix><Key>}, or {@code <prefix>Returned<Key>}
     * when that name is one of the step's {@link #OUTCOME} variables.
     */
    static String dataVariable(String prefix, String key) {
        String cap = Character.toUpperCase(key.charAt(0)) + key.substring(1);
        return prefix + (OUTCOME.contains(cap) ? "Returned" + cap : cap);
    }

    /** The reason code of an invalid answer that names no error finding. */
    static final String INVALID = "INVALID";

    /**
     * {@code failedWhenInvalid}: a completed validation answer that says {@code valid=false} as a FAILED result with
     * the code of its first error finding (a finding without a severity counts as an error), else {@link #INVALID};
     * the data is kept. Every other result is returned unchanged.
     */
    static ConnectorResult failedWhenInvalid(ConnectorResult r) {
        if (r.status() != OperationStatus.COMPLETED || !Boolean.FALSE.equals(r.data().get("valid"))) {
            return r;
        }
        String code = INVALID;
        if (r.data().get("findings") instanceof Iterable<?> findings) {
            for (Object f : findings) {
                if (f instanceof Map<?, ?> m && m.get("code") != null
                        && (m.get("severity") == null || "ERROR".equalsIgnoreCase(String.valueOf(m.get("severity"))))) {
                    code = String.valueOf(m.get("code"));
                    break;
                }
            }
        }
        return new ConnectorResult(OperationStatus.FAILED, code, "the answer is valid=false", r.externalRef(), r.data());
    }
}
