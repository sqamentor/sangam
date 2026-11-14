// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.tenant;

import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.operaton.bpm.engine.impl.batch.BatchMonitorJobHandler;
import org.operaton.bpm.engine.impl.batch.BatchSeedJobHandler;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.operaton.bpm.engine.impl.jobexecutor.historycleanup.HistoryCleanupJobHandler;
import org.operaton.bpm.engine.impl.persistence.entity.JobEntity;
import org.operaton.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.sangam.core.tenant.ExecutionTenantPolicy;
import org.sangam.core.tenant.ResolvedTenant;
import org.sangam.core.tenant.SystemAuditScope;
import org.sangam.core.tenant.TenantResolutionException;

/**
 * Decision 144 with owner answer 197 (engine contract 2026-11-13T01:02:45Z, W4-B ACK 01:44:35Z): the tenant of engine
 * work at the four engine sites ({@code JobIdentitySupport.identityFor}, {@code JobSideRows},
 * {@code SangamIncidentHandler.handleIncident}, {@code RoutingIncidentHandler}), through
 * {@link ExecutionTenantPolicy} only:
 * <ol>
 *   <li>a non-blank engine tenant: {@code tenantOwned(engineTenant, claim)}, so a {@code sangamTenantId} variable that
 *       differs is refused ({@code CONFLICTING_TENANT}); the variable is never a fallback;</li>
 *   <li>a blank engine tenant (null, empty, whitespace, the shared key) and an active {@link SharedDeployments} row
 *       for the work's deployment: {@code shared(...)}, attributed to the configured system audit scope;</li>
 *   <li>a blank engine tenant on an engine maintenance job ({@link #MAINTENANCE_JOB_TYPES}: no execution, process
 *       instance, process definition or deployment; created by the engine itself, so its job type is authoritative
 *       metadata): {@code shared(...)} as well, recorded as {@link Outcome#ENGINE_MAINTENANCE} (W4-B ACK
 *       2026-11-13T03:40:12Z condition 3), otherwise history cleanup and untenanted batch bookkeeping would stop. A
 *       batch's seed and monitor jobs are maintenance only when the batch itself has no tenant (condition 2): Operaton
 *       gives them the tenant of their job definition, which {@code BatchEntity.createSeedJobDefinition} /
 *       {@code createMonitorJobDefinition} take from the batch ({@code JobDeclaration.createJobInstance}, javap 2.2.0),
 *       so a tenanted batch's jobs resolve by rule 1 to that tenant before this rule is looked at;</li>
 *   <li>otherwise {@code tenantOwned(null, ...)}, which refuses {@code MISSING_ENGINE_TENANT}:
 *       {@link Outcome#UNRESOLVED_TENANT}. The work is never routed to a customer tenant or to the old attribution
 *       default ({@code defaultTenantId}, SL-86 meaning change): a job is not executed, an incident is recorded under
 *       the system audit scope only.</li>
 * </ol>
 * Continuations (retries, incidents of the same job) re-resolve from the job's own tenant and deployment, which the
 * engine never changes on a job, so the captured and the current resolution can differ only when the registry row was
 * revoked in between, and then the work fails closed.
 */
public final class WorkTenants {

    /** The engine setting key of the system audit scope. */
    public static final String SETTING = "sangam.engine.system-audit-scope";

    /** Audit event of work whose tenant could not be resolved (class B, under the system audit scope). */
    public static final String UNRESOLVED_EVENT = "TENANT_UNRESOLVED";

    /**
     * Operaton job types the engine creates for itself: history cleanup and batch seed and monitor jobs, by Operaton's
     * own handler {@code TYPE} constants (W4-B ACK 2026-11-13T03:40:12Z condition 1).
     */
    public static final Set<String> MAINTENANCE_JOB_TYPES = Set.of(HistoryCleanupJobHandler.TYPE,
            BatchSeedJobHandler.TYPE, BatchMonitorJobHandler.TYPE);

    /**
     * The {@code resolutionReason} of the audit payload: tenant-owned work, untenanted work of a registered shared
     * deployment, an untenanted engine maintenance job, or a refusal.
     */
    public enum Outcome { TENANT_OWNED, SHARED_REGISTERED, ENGINE_MAINTENANCE, UNRESOLVED_TENANT }

    /**
     * The result of one resolution: the raw engine tenant and the resolved scope stay distinct.
     *
     * @param attribution the tenant id rows and audit are written under: the tenant, or the system audit scope for
     *                    shared and unresolved work
     * @param refusal     the policy refusal of {@link Outcome#UNRESOLVED_TENANT}, else null
     */
    public record Resolution(String engineTenant, ResolvedTenant resolved, Outcome outcome, String attribution,
                             TenantResolutionException refusal) {

        /** The resolved tenant, or the refusal thrown (fail closed). */
        public ResolvedTenant require() {
            if (refusal != null) {
                throw refusal;
            }
            return resolved;
        }

        public boolean unresolved() {
            return outcome == Outcome.UNRESOLVED_TENANT;
        }

        /** The decision 144 audit fields: engineTenant, resolvedScope, resolutionReason (and the refusal code). */
        public Map<String, Object> auditFields() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("engineTenant", engineTenant);
            m.put("resolvedScope", attribution);
            m.put("resolutionReason", outcome.name());
            if (refusal != null) {
                m.put("refusal", refusal.refusal().code());
            }
            return m;
        }
    }

    private final String systemAuditScope;

    /** @throws IllegalStateException when the configured scope is missing, blank or not a {@code system:} scope */
    public WorkTenants(String configuredSystemAuditScope) {
        this.systemAuditScope = SystemAuditScope.validate(configuredSystemAuditScope, SETTING);
    }

    public String systemAuditScope() {
        return systemAuditScope;
    }

    /**
     * Resolves without throwing: a refusal is returned as {@link Outcome#UNRESOLVED_TENANT} (or, for a conflicting
     * claim, with the conflicting refusal) attributed to the system audit scope.
     *
     * @param c            the command's connection (registry lookup)
     * @param engineTenant the tenant id on the job, execution or incident
     * @param claim        a tenant read from variables (untrusted), or null
     * @param deploymentId the deployment of the work, or null
     * @param maintenance  an engine maintenance job ({@link #isMaintenance})
     * @param label        fixed label of the site
     */
    public Resolution resolve(Connection c, String engineTenant, String claim, String deploymentId, boolean maintenance,
                              String label) {
        try {
            if (ExecutionTenantPolicy.normalise(engineTenant) != null) {
                ResolvedTenant r = ExecutionTenantPolicy.tenantOwned(engineTenant, claim, label);
                return new Resolution(engineTenant, r, Outcome.TENANT_OWNED, r.tenantId(), null);
            }
            if (maintenance) {
                return new Resolution(engineTenant, ExecutionTenantPolicy.shared(engineTenant, claim, label),
                        Outcome.ENGINE_MAINTENANCE, systemAuditScope, null);
            }
            if (SharedDeployments.isActive(c, deploymentId)) {
                return new Resolution(engineTenant, ExecutionTenantPolicy.shared(engineTenant, claim, label),
                        Outcome.SHARED_REGISTERED, systemAuditScope, null);
            }
            ExecutionTenantPolicy.tenantOwned(engineTenant, claim, label); // refuses MISSING_ENGINE_TENANT
            throw new IllegalStateException("decision 144: an untenanted, unregistered work item was not refused");
        } catch (TenantResolutionException e) {
            return new Resolution(engineTenant, null, Outcome.UNRESOLVED_TENANT, systemAuditScope, e);
        }
    }

    /** Whether {@code job} is an engine maintenance job (see the class comment, rule 3). */
    public static boolean isMaintenance(JobEntity job) {
        return job != null && job.getExecutionId() == null && job.getProcessInstanceId() == null
                && job.getProcessDefinitionId() == null && job.getDeploymentId() == null
                && MAINTENANCE_JOB_TYPES.contains(job.getJobHandlerType());
    }

    /** The deployment of a job: its own, else its process definition's. */
    public static String deploymentOf(CommandContext cc, JobEntity job) {
        if (job == null) {
            return null;
        }
        return job.getDeploymentId() != null ? job.getDeploymentId() : deploymentOf(cc, job.getProcessDefinitionId());
    }

    /** The deployment of a process definition id, or null when there is none. */
    public static String deploymentOf(CommandContext cc, String processDefinitionId) {
        if (cc == null || processDefinitionId == null) {
            return null;
        }
        ProcessDefinitionEntity d = cc.getProcessDefinitionManager().findLatestProcessDefinitionById(processDefinitionId);
        return d == null ? null : d.getDeploymentId();
    }
}
