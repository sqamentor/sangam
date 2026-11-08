// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.incident;

import java.util.LinkedHashMap;
import java.util.Map;

import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.impl.context.Context;
import org.operaton.bpm.engine.impl.incident.IncidentContext;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.operaton.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.operaton.bpm.engine.runtime.Incident;
import org.sangam.core.engine.AuditSink;
import org.sangam.core.engine.IncidentSink;
import org.sangam.core.engine.RouteDecision;
import org.sangam.engine.tx.CommandState;

/**
 * <b>SG-35 / ADR-D-25</b>: the incident handler of the strict exclusive gateway ({@code sangamRouting}). On top of
 * {@link SangamIncidentHandler} (Operaton incident plus the reconciliation row), every routing incident is written to
 * the class-B audit chain as a {@value #AUDIT_EVENT} event carrying the stable code ({@code NO_MATCHING_ROUTE} /
 * {@code AMBIGUOUS_ROUTE}), the gateway and the instance, in the same command. A gateway that cannot route therefore
 * never parks an instance silently: the parked execution always has an open incident and an audited reason, and the
 * recovery ({@code ROUTE_CORRECTED}) closes the pair.
 *
 * <p>The code is read from the incident message, which {@link RouteDecision#message()} always prefixes with it; a
 * message without a known code is a programming error and fails the command (fail closed, nothing parks unaudited).
 */
public final class RoutingIncidentHandler extends SangamIncidentHandler {

    /** Audit event type of an opened routing incident. */
    public static final String AUDIT_EVENT = "ROUTING_INCIDENT";

    private final AuditSink audit;
    private final String defaultTenant;

    public RoutingIncidentHandler(String type, IncidentSink sink, AuditSink audit, String defaultTenant) {
        super(type, sink, defaultTenant);
        this.audit = audit;
        this.defaultTenant = defaultTenant;
    }

    @Override
    public Incident handleIncident(IncidentContext context, String message) {
        String code = codeOf(message);
        Incident incident = super.handleIncident(context, message);
        CommandContext cc = Context.hasActiveCommandContext() ? Context.getCommandContext() : null;
        if (incident == null || cc == null) {
            throw new ProcessEngineException("SG-35/ADR-D-25: routing incident " + code + " outside an engine command");
        }
        String root = incident.getProcessInstanceId();
        if (incident.getExecutionId() != null) {
            ExecutionEntity e = cc.getExecutionManager().findExecutionById(incident.getExecutionId());
            if (e != null && e.getRootProcessInstanceId() != null) {
                root = e.getRootProcessInstanceId();
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code);
        payload.put("incidentId", incident.getId());
        payload.put("processInstanceId", incident.getProcessInstanceId());
        payload.put("processDefinitionId", incident.getProcessDefinitionId());
        payload.put("gateway", incident.getActivityId());
        payload.put("message", message);
        audit.append(CommandState.connection(cc), incident.getTenantId() == null ? defaultTenant : incident.getTenantId(),
                root, AUDIT_EVENT, payload);
        return incident;
    }

    /** The stable code that prefixes every routing incident message. */
    static String codeOf(String message) {
        int i = message == null ? -1 : message.indexOf(':');
        String code = i < 0 ? null : message.substring(0, i);
        if (RouteDecision.Outcome.AMBIGUOUS_ROUTE.name().equals(code)
                || RouteDecision.Outcome.NO_MATCHING_ROUTE.name().equals(code)) {
            return code;
        }
        throw new ProcessEngineException("SG-35/ADR-D-25: routing incident without a stable code: " + message);
    }
}
