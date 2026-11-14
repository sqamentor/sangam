// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.incident;

import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;

import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.impl.context.Context;
import org.operaton.bpm.engine.impl.incident.IncidentContext;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.operaton.bpm.engine.runtime.Incident;
import org.sangam.core.engine.AuditSink;
import org.sangam.core.engine.IncidentSink;
import org.sangam.core.engine.RouteDecision;
import org.sangam.engine.tenant.WorkTenants;

/**
 * <b>SG-35 / ADR-D-25</b>: the incident handler of the strict exclusive gateway ({@code sangamRouting}). On top of
 * {@link SangamIncidentHandler} (Operaton incident plus the reconciliation row), every routing incident is written to
 * the class-B audit chain as a {@value #AUDIT_EVENT} event carrying the stable code ({@code NO_MATCHING_ROUTE} /
 * {@code AMBIGUOUS_ROUTE}), the gateway and the instance, in the same command. A gateway that cannot route therefore
 * never parks an instance silently: the parked execution always has an open incident and an audited reason, and the
 * recovery ({@code ROUTE_CORRECTED}) closes the pair. The generic {@code INCIDENT_OPENED} (errorClass config) is
 * written by the superclass as well (owner decision 301: both events).
 *
 * <p>The code is read from the incident message, which {@link RouteDecision#message()} always prefixes with it; a
 * message without a known code is a programming error and fails the command (fail closed, nothing parks unaudited).
 */
public final class RoutingIncidentHandler extends SangamIncidentHandler {

    /** Audit event type of an opened routing incident. */
    public static final String AUDIT_EVENT = "ROUTING_INCIDENT";

    private final AuditSink audit;

    /** @param tenants decision 144: the audit row is written under the resolved tenant (system audit scope if unresolved) */
    public RoutingIncidentHandler(String type, IncidentSink sink, AuditSink audit, WorkTenants tenants) {
        super(type, sink, audit, tenants);
        this.audit = audit;
    }

    @Override
    public Incident handleIncident(IncidentContext context, String message) {
        codeOf(message); // fail closed before anything is written
        Incident incident = super.handleIncident(context, message);
        if (incident == null || !Context.hasActiveCommandContext()) {
            throw new ProcessEngineException("SG-35/ADR-D-25: routing incident " + codeOf(message)
                    + " outside an engine command");
        }
        return incident;
    }

    /** Decision 302: a routing incident is a configuration error. */
    @Override
    protected boolean routing() {
        return true;
    }

    @Override
    protected void opened(CommandContext cc, Connection c, Incident incident, String message, WorkTenants.Resolution r,
                          String root) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", codeOf(message));
        payload.put("incidentId", incident.getId());
        payload.put("processInstanceId", incident.getProcessInstanceId());
        payload.put("processDefinitionId", incident.getProcessDefinitionId());
        payload.put("gateway", incident.getActivityId());
        payload.put("message", message);
        payload.putAll(r.auditFields()); // decision 144: engineTenant, resolvedScope, resolutionReason
        audit.append(c, r.attribution(), root, AUDIT_EVENT, payload);
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
