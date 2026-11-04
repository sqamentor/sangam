// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.util.LinkedHashMap;
import java.util.Map;

import org.sangam.ports.dynamic.CrossInstanceStateChangeService;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.HistoryPort;
import org.sangam.rest.port.InstanceControlPort;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.ProcessPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.TxSupport;

/**
 * {@link InstanceControlPort} where the cross-boundary move (FC-17, move into or out of a call activity's instance)
 * is sangam-flowable-ports' {@link CrossInstanceStateChangeService} (one engine command, same root tree enforced by
 * the service). Previews, suspension and the external-task log stay with the default adapter.
 */
public final class PortsInstanceControlBridge implements InstanceControlPort {

    private final InstanceControlPort base;
    private final CrossInstanceStateChangeService moves;
    private final ProcessPort processes;
    private final TxSupport tx;
    private final AuditPort audit;

    public PortsInstanceControlBridge(InstanceControlPort base, CrossInstanceStateChangeService moves, ProcessPort processes,
                                      TxSupport tx, AuditPort audit) {
        this.base = base;
        this.moves = moves;
        this.processes = processes;
        this.tx = tx;
        this.audit = audit;
    }

    @Override
    public ConsequencePreview cancelPreview(RequestContext ctx, String processInstanceId, String mode) {
        return base.cancelPreview(ctx, processInstanceId, mode);
    }

    @Override
    public ProcessPort.ProcessInstance setSuspension(RequestContext ctx, String processInstanceId, SuspensionRequest request) {
        return base.setSuspension(ctx, processInstanceId, request);
    }

    @Override
    public ProcessPort.ProcessDefinition setDefinitionSuspension(RequestContext ctx, String definitionId,
                                                                 SuspensionRequest request) {
        return base.setDefinitionSuspension(ctx, definitionId, request);
    }

    @Override
    public ConsequencePreview modifyPreview(RequestContext ctx, String processInstanceId, ModifyPreviewRequest request) {
        return base.modifyPreview(ctx, processInstanceId, request);
    }

    @Override
    public ProcessPort.ProcessInstance crossBoundaryMove(RequestContext ctx, String processInstanceId,
                                                         CrossBoundaryMoveRequest r) {
        if (r == null || r.fromActivityInstanceId() == null || r.targetInstanceId() == null || r.targetActivityId() == null) {
            throw ApiProblem.badRequest("VALIDATION_FAILED",
                    "fromActivityInstanceId, targetInstanceId and targetActivityId are required");
        }
        PortsErrors.requireReason(r.reason());
        ProcessPort.ProcessInstance source = processes.get(ctx, processInstanceId);
        ProcessPort.ProcessInstance target = processes.get(ctx, r.targetInstanceId());
        if (source.rootProcessInstanceId() != null && target.rootProcessInstanceId() != null
                && !source.rootProcessInstanceId().equals(target.rootProcessInstanceId())) {
            throw ApiProblem.unprocessable("NOT_SAME_ROOT", "Both instances must belong to the same root instance tree");
        }
        tx.runInTx(() -> {
            PortsErrors.run(() -> moves.move(processInstanceId, r.fromActivityInstanceId(), r.targetInstanceId(),
                    r.targetActivityId(), null, r.reason()));
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("kind", "CROSS_BOUNDARY_MOVE");
            p.put("from_activity_instance_id", r.fromActivityInstanceId());
            p.put("target_instance_id", r.targetInstanceId());
            p.put("target_activity_id", r.targetActivityId());
            p.put("reason", r.reason());
            audit.record(ctx, processInstanceId, "INSTANCE_MODIFIED", p);
            audit.record(ctx, r.targetInstanceId(), "INSTANCE_MODIFIED", p);
        });
        return processes.get(ctx, processInstanceId);
    }

    @Override
    public Page<ExternalTaskLogEntry> externalTaskLog(RequestContext ctx, HistoryPort.HistoryFilter filter,
                                                      Page.PageRequest page) {
        return base.externalTaskLog(ctx, filter, page);
    }
}
