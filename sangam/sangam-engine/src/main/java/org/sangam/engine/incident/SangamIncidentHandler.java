// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.incident;

import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.operaton.bpm.engine.impl.context.Context;
import org.operaton.bpm.engine.impl.incident.DefaultIncidentHandler;
import org.operaton.bpm.engine.impl.incident.IncidentContext;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.operaton.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.operaton.bpm.engine.runtime.Incident;
import org.sangam.core.engine.AuditSink;
import org.sangam.core.engine.IncidentSink;
import org.sangam.engine.tenant.WorkTenants;
import org.sangam.engine.tx.CommandState;

/**
 * Incident handler feeding reconciliation (VI.3, R21, R36): keeps Operaton's incident behaviour (it extends the
 * default handler, so Cockpit and REST see the same incidents) and, in the same command, reports every incident
 * opened, resolved or deleted to the {@link IncidentSink} (default: {@code SANGAM_ENGINE_INCIDENT}). Registered for
 * {@code failedJob}, {@code failedExternalTask} and {@code JOB_RECOVERY_LOOP} ({@code PartitionLeaseManager.RECOVERY_LOOP},
 * X.2.1.6); a {@code LEASE_EXPIRED_HELD} incident (A-10 hold) arrives here too.
 *
 * <p>Decision 144 (owner 197): the incident's tenant is resolved by {@link WorkTenants} (engine tenant; else the system
 * audit scope for a registered shared deployment or an engine maintenance job). An incident whose tenant cannot be
 * resolved is recorded under the system audit scope only, never under a customer tenant or the old attribution
 * default, and audited as {@value WorkTenants#UNRESOLVED_EVENT} with the resolution and the resource ids.
 *
 * <p>Owner decisions 299 to 302: every incident opened here is audited once as {@value #OPENED_EVENT} (class B), in the
 * same command, under the resolved attribution (tenant, or the system audit scope for shared, maintenance and
 * unresolved work), on the chain of the root process instance, else {@code incident:<id>}. It is written beside
 * {@value WorkTenants#UNRESOLVED_EVENT} (unresolved tenant) and beside {@code ROUTING_INCIDENT} (routing incidents),
 * never instead of them. Payload: incidentId, incidentType, processInstanceId, rootProcessInstanceId, executionId,
 * activityId, processDefinitionId, configuration, errorClass ({@link IncidentErrorClass}), message, engineTenant,
 * resolvedScope, resolutionReason (and the refusal code when unresolved).
 */
public class SangamIncidentHandler extends DefaultIncidentHandler {

    /** Audit event of every opened incident (decisions 299 to 302). */
    public static final String OPENED_EVENT = "INCIDENT_OPENED";

    /** Site label of the policy call (fixed text). */
    static final String SITE = "incident";

    private final IncidentSink sink;
    private final AuditSink audit;
    private final WorkTenants tenants;

    public SangamIncidentHandler(String type, IncidentSink sink, AuditSink audit, WorkTenants tenants) {
        super(type);
        this.sink = sink;
        this.audit = audit == null ? AuditSink.NONE : audit;
        this.tenants = java.util.Objects.requireNonNull(tenants, "tenants");
    }

    @Override
    public Incident handleIncident(IncidentContext context, String message) {
        Incident incident = super.handleIncident(context, message);
        CommandContext cc = Context.hasActiveCommandContext() ? Context.getCommandContext() : null;
        if (incident != null && cc != null) {
            String root = rootOf(cc, incident);
            String deployment = WorkTenants.deploymentOf(cc, incident.getProcessDefinitionId());
            Connection c = CommandState.connection(cc);
            WorkTenants.Resolution r = tenants.resolve(c, incident.getTenantId(), null, deployment,
                    maintenance(cc, incident), SITE);
            sink.opened(c, new IncidentSink.Incident(incident.getId(), getIncidentHandlerType(), r.attribution(), root,
                    incident.getProcessInstanceId(), incident.getExecutionId(), incident.getActivityId(),
                    incident.getProcessDefinitionId(), incident.getConfiguration(), incident.getConfiguration(), message));
            if (r.unresolved()) {
                Map<String, Object> payload = new LinkedHashMap<>(r.auditFields());
                payload.put("resourceIds", resourceIds(incident, deployment));
                payload.put("actor", actor());
                payload.put("correlationId", incident.getConfiguration() != null ? incident.getConfiguration()
                        : incident.getId());
                audit.append(c, r.attribution(), root != null ? root : "incident:" + incident.getId(),
                        WorkTenants.UNRESOLVED_EVENT, payload);
            }
            Throwable failure = "failedJob".equals(incident.getIncidentType())
                    ? CommandState.of(cc).jobFailure() : null;
            audit.append(c, r.attribution(), root != null ? root : "incident:" + incident.getId(), OPENED_EVENT,
                    openedPayload(incident, root, message, IncidentErrorClass.of(routing(), failure), r));
            opened(cc, c, incident, message, r, root);
        }
        return incident;
    }

    /** The {@value #OPENED_EVENT} payload (decision 300 to 302). */
    static Map<String, Object> openedPayload(Incident incident, String root, String message, String errorClass,
                                             WorkTenants.Resolution r) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("incidentId", incident.getId());
        p.put("incidentType", incident.getIncidentType());
        p.put("processInstanceId", incident.getProcessInstanceId());
        p.put("rootProcessInstanceId", root);
        p.put("executionId", incident.getExecutionId());
        p.put("activityId", incident.getActivityId());
        p.put("processDefinitionId", incident.getProcessDefinitionId());
        p.put("configuration", incident.getConfiguration());
        p.put("errorClass", errorClass);
        p.put("message", message);
        p.putAll(r.auditFields()); // engineTenant, resolvedScope, resolutionReason (and refusal)
        return p;
    }

    /** Whether this handler opens strict-gateway routing incidents ({@code errorClass} config). */
    protected boolean routing() {
        return false;
    }

    /** Hook for subclasses, after the reconciliation row and the audit events above are written. */
    protected void opened(CommandContext cc, Connection c, Incident incident, String message, WorkTenants.Resolution r,
                          String root) {
    }

    /** The root process instance of the incident, else its process instance, else null. */
    static String rootOf(CommandContext cc, Incident incident) {
        String root = incident.getProcessInstanceId();
        if (incident.getExecutionId() != null) {
            ExecutionEntity e = cc.getExecutionManager().findExecutionById(incident.getExecutionId());
            if (e != null && e.getRootProcessInstanceId() != null) {
                root = e.getRootProcessInstanceId();
            }
        }
        return root;
    }

    /** A failed engine maintenance job (history cleanup, untenanted batch bookkeeping). */
    private static boolean maintenance(CommandContext cc, Incident incident) {
        if (incident.getProcessDefinitionId() != null || incident.getExecutionId() != null
                || incident.getConfiguration() == null || !"failedJob".equals(incident.getIncidentType())) {
            return false;
        }
        return WorkTenants.isMaintenance(cc.getJobManager().findJobById(incident.getConfiguration()));
    }

    static Map<String, Object> resourceIds(Incident incident, String deploymentId) {
        Map<String, Object> ids = new LinkedHashMap<>();
        ids.put("incidentId", incident.getId());
        ids.put("incidentType", incident.getIncidentType());
        ids.put("configuration", incident.getConfiguration());
        ids.put("processInstanceId", incident.getProcessInstanceId());
        ids.put("executionId", incident.getExecutionId());
        ids.put("activityId", incident.getActivityId());
        ids.put("processDefinitionId", incident.getProcessDefinitionId());
        ids.put("deploymentId", deploymentId);
        return ids;
    }

    /** The service identity of the work: the job's system identity when a job command is running, else the engine. */
    static String actor() {
        return org.sangam.engine.job.JobIdentitySupport.current().map(i -> i.personId() + "/" + i.onBehalfOf())
                .orElse("engine");
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
