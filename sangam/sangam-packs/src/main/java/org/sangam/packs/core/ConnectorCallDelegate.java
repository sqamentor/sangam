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
 *   <li>{@code inputs}: comma list of variables sent as the payload (default: the material fields);</li>
 *   <li>{@code resultPrefix}: result data entries are written as {@code <prefix><Key>} and the status as
 *       {@code <prefix>Status} (default prefix = last segment of the connector name);</li>
 *   <li>{@code errorOnFailure}: BPMN error code thrown on a FAILED result (else the status is only recorded).</li>
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
            String s = Vars.field(step, ex, c + ":" + opName);
            int attempt = Vars.intVal(ex, prefix + "Attempt", 1);
            if ("FAILED".equals(Vars.str(ex, prefix + "Status"))) {
                attempt++;
            }
            ex.setVariable(prefix + "Attempt", attempt);
            String id = Vars.operationId(bk, s, attempt);
            ex.setVariable(prefix + "OperationId", id);
            r = PackRuntime.gateway().submit(new ConnectorRequest(Vars.tenant(ex), c, opName, id, bk,
                    ex.getProcessInstanceId(), Vars.withDirective(ex, c, payload), Vars.hash(payload)));
            Vars.audit(ex, r.status() == OperationStatus.FAILED ? "EXT_OP_FAILED" : "EXT_OP_REQUESTED",
                    Vars.map("connector", c, "operation", opName, "status", r.status().name(),
                            "reasonCode", r.reasonCode()));
        } else {
            r = PackRuntime.gateway().query(new ConnectorRequest(Vars.tenant(ex), c, opName, null, bk,
                    ex.getProcessInstanceId(), Vars.withDirective(ex, c, payload), Vars.hash(payload)));
        }
        ex.setVariable(prefix + "Status", r.status().name());
        ex.setVariable(prefix + "ReasonCode", r.reasonCode());
        if (r.externalRef() != null) {
            ex.setVariable(prefix + "Ref", r.externalRef());
        }
        r.data().forEach((k, v) -> ex.setVariable(prefix + Character.toUpperCase(k.charAt(0)) + k.substring(1),
                Vars.engineValue(v)));
        String err = Vars.field(errorOnFailure, ex);
        if (err != null && r.status() == OperationStatus.FAILED) {
            throw new BpmnError(err, c + " failed: " + r.reasonCode());
        }
    }
}
