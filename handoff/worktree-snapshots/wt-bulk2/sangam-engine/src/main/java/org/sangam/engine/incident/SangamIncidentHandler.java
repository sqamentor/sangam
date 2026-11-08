// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.incident;

import java.util.List;

import org.operaton.bpm.engine.impl.context.Context;
import org.operaton.bpm.engine.impl.incident.DefaultIncidentHandler;
import org.operaton.bpm.engine.impl.incident.IncidentContext;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.operaton.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.operaton.bpm.engine.runtime.Incident;
import org.sangam.core.engine.IncidentSink;
import org.sangam.engine.tx.CommandState;

/**
 * Incident handler feeding reconciliation (VI.3, R21, R36): keeps Operaton's incident behaviour (it extends the
 * default handler, so Cockpit and REST see the same incidents) and, in the same command, reports every incident
 * opened, resolved or deleted to the {@link IncidentSink} (default: {@code SANGAM_ENGINE_INCIDENT}). Registered for
 * {@code failedJob} and {@code failedExternalTask}; a {@code LEASE_EXPIRED_HELD} incident (A-10 hold) arrives here too.
 */
public class SangamIncidentHandler extends DefaultIncidentHandler {

    private final IncidentSink sink;
    private final String defaultTenant;

    public SangamIncidentHandler(String type, IncidentSink sink, String defaultTenant) {
        super(type);
        this.sink = sink;
        this.defaultTenant = defaultTenant;
    }

    @Override
    public Incident handleIncident(IncidentContext context, String message) {
        Incident incident = super.handleIncident(context, message);
        CommandContext cc = Context.hasActiveCommandContext() ? Context.getCommandContext() : null;
        if (incident != null && cc != null) {
            String root = null;
            if (incident.getExecutionId() != null) {
                ExecutionEntity e = cc.getExecutionManager().findExecutionById(incident.getExecutionId());
                root = e == null ? incident.getProcessInstanceId() : e.getRootProcessInstanceId();
            }
            sink.opened(CommandState.connection(cc), new IncidentSink.Incident(incident.getId(), getIncidentHandlerType(),
                    incident.getTenantId() == null ? defaultTenant : incident.getTenantId(), root,
                    incident.getProcessInstanceId(), incident.getExecutionId(), incident.getActivityId(),
                    incident.getProcessDefinitionId(), incident.getConfiguration(), incident.getConfiguration(), message));
        }
        return incident;
    }

    @Override
    public void resolveIncident(IncidentContext context) {
        List<Incident> open = openIncidents(context);
        super.resolveIncident(context);
        report(open, "RESOLVED");
    }

    @Override
    public void deleteIncident(IncidentContext context) {
        List<Incident> open = openIncidents(context);
        super.deleteIncident(context);
        report(open, "DELETED");
    }

    private List<Incident> openIncidents(IncidentContext context) {
        CommandContext cc = Context.hasActiveCommandContext() ? Context.getCommandContext() : null;
        if (cc == null || context.getConfiguration() == null) {
            return List.of();
        }
        return cc.getIncidentManager().findIncidentByConfigurationAndIncidentType(context.getConfiguration(),
                getIncidentHandlerType());
    }

    private void report(List<Incident> incidents, String reason) {
        CommandContext cc = Context.hasActiveCommandContext() ? Context.getCommandContext() : null;
        if (cc == null || incidents.isEmpty()) {
            return;
        }
        for (Incident i : incidents) {
            sink.closed(CommandState.connection(cc), i.getId(), reason);
        }
    }
}
