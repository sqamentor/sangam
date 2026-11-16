// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.runtime.ActivityInstance;
import org.operaton.bpm.engine.runtime.ProcessInstanceModificationBuilder;
import org.operaton.bpm.engine.runtime.TransitionInstance;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.IncidentActionPort;
import org.sangam.rest.port.OpsPort;
import org.sangam.rest.port.ProcessPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.TxSupport;

/**
 * {@link IncidentActionPort} on Operaton, as the incident preview describes the two actions.
 *
 * <ul>
 *   <li><b>skip</b>: the token of the failed activity moves past it. The incident's own activity instance (or the
 *       transition instance of an asynchronous continuation) is found in the instance's activity tree by the
 *       incident's execution; an instance modification starts after the activity under the same parent and then
 *       cancels that one instance (start first, so the parent scope is never left empty and ended). Other instances of
 *       the activity are untouched. Audited {@code INCIDENT_SKIPPED}.</li>
 *   <li><b>cancel</b>: the incident's instance is cancelled through {@link ProcessPort#cancel} (VI.5: the model's cancel
 *       paths, open external work CANCEL_REQUESTED, its own {@code INSTANCE_CANCELLED}); audited
 *       {@code INCIDENT_CANCELLED}.</li>
 * </ul>
 * The incident is read through {@link OpsPort#incident} (404 for another tenant's incident); a reason is required
 * ({@code REASON_REQUIRED}); the engine change and the audit event share one transaction.
 */
public final class OperatonIncidentActions implements IncidentActionPort {

    public static final String SKIPPED = "INCIDENT_SKIPPED";
    public static final String CANCELLED = "INCIDENT_CANCELLED";

    private final RuntimeService runtime;
    private final OpsPort ops;
    private final ProcessPort processes;
    private final TxSupport tx;
    private final AuditPort audit;

    public OperatonIncidentActions(RuntimeService runtime, OpsPort ops, ProcessPort processes, TxSupport tx,
                                   AuditPort audit) {
        this.runtime = runtime;
        this.ops = ops;
        this.processes = processes;
        this.tx = tx;
        this.audit = audit;
    }

    @Override
    public OpsPort.Incident skipIncident(RequestContext ctx, String id, OpsPort.IncidentAction action) {
        String reason = reason(action);
        OpsPort.Incident inc = ops.incident(ctx, id);
        tx.runInTx(() -> {
            org.operaton.bpm.engine.runtime.Incident engineIncident = OperatonSupport.call(
                    () -> runtime.createIncidentQuery().incidentId(id).singleResult());
            if (engineIncident == null) {
                throw ApiProblem.notFound("incident", id);
            }
            ActivityInstance tree = OperatonSupport.call(() -> runtime.getActivityInstance(inc.processInstanceId()));
            Target target = tree == null ? null : find(tree, engineIncident.getExecutionId(), inc.activityId());
            if (target == null) {
                throw ApiProblem.unprocessable("INCIDENT_NOT_SKIPPABLE", "incident " + id + " has no active token at "
                        + "activity " + inc.activityId() + " to move past");
            }
            OperatonSupport.run(() -> {
                ProcessInstanceModificationBuilder b = runtime.createProcessInstanceModification(
                        inc.processInstanceId()).startAfterActivity(inc.activityId(), target.parentId());
                b = target.transition() ? b.cancelTransitionInstance(target.id()) : b.cancelActivityInstance(target.id());
                b.setAnnotation(reason).execute();
            });
            Map<String, Object> p = payload(inc, reason);
            p.put(target.transition() ? "transitionInstanceId" : "activityInstanceId", target.id());
            audit.record(ctx, chain(ctx, inc.processInstanceId()), SKIPPED, p);
        });
        return inc;
    }

    @Override
    public OpsPort.Incident cancelIncident(RequestContext ctx, String id, OpsPort.IncidentAction action) {
        String reason = reason(action);
        OpsPort.Incident inc = ops.incident(ctx, id);
        tx.runInTx(() -> {
            ProcessPort.TerminationResult r = processes.cancel(ctx, inc.processInstanceId(),
                    new ProcessPort.TerminateRequest(reason, null));
            Map<String, Object> p = payload(inc, reason);
            p.put("openOperationIds", r.openOperationIds() == null ? List.of() : r.openOperationIds());
            audit.record(ctx, chain(ctx, inc.processInstanceId()), CANCELLED, p);
        });
        return inc;
    }

    /** The instance holding {@code executionId} at {@code activityId}, with its parent; null when there is none. */
    record Target(String id, String parentId, boolean transition) {
    }

    static Target find(ActivityInstance node, String executionId, String activityId) {
        for (TransitionInstance t : node.getChildTransitionInstances()) {
            if (executionId != null && executionId.equals(t.getExecutionId()) && activityId.equals(t.getActivityId())) {
                return new Target(t.getId(), node.getId(), true);
            }
        }
        for (ActivityInstance child : node.getChildActivityInstances()) {
            if (activityId.equals(child.getActivityId()) && executionId != null
                    && List.of(child.getExecutionIds()).contains(executionId)) {
                return new Target(child.getId(), node.getId(), false);
            }
            Target deeper = find(child, executionId, activityId);
            if (deeper != null) {
                return deeper;
            }
        }
        return null;
    }

    private static String reason(OpsPort.IncidentAction action) {
        String reason = action == null ? null : action.reason();
        if (reason == null || reason.isBlank()) {
            throw ApiProblem.badRequest("REASON_REQUIRED", "This action requires a reason (R63, R42)");
        }
        return reason;
    }

    private static Map<String, Object> payload(OpsPort.Incident inc, String reason) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("incidentId", inc.id());
        p.put("incidentType", inc.type());
        p.put("processInstanceId", inc.processInstanceId());
        p.put("activityId", inc.activityId());
        p.put("reason", reason);
        return p;
    }

    private static String chain(RequestContext ctx, String rootId) {
        return rootId != null ? rootId : "sec:" + ctx.tenantId();
    }
}
