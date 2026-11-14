// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.job;

import java.util.Optional;
import java.util.function.Supplier;

import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.operaton.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.operaton.bpm.engine.impl.persistence.entity.JobEntity;
import org.sangam.core.engine.JobIdentityBinder;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.core.tenant.TenantResolutionException;
import org.sangam.engine.tenant.WorkTenants;
import org.sangam.engine.tx.CommandState;

/**
 * VI.4 rule 7 for job execution (timers, async continuations, retries, batch and cleanup jobs): every job runs as the
 * <b>system identity of its resolved tenant</b>, never without an identity. Decision 144 (owner 197): the tenant is
 * resolved by {@link WorkTenants} only: the engine tenant of the job (tenant-owned; a {@code sangamTenantId} variable is
 * an untrusted claim that must agree and is never a fallback), or the system audit scope for an untenanted job of a
 * registered shared deployment or an engine maintenance job. Anything else fails closed: the job is not executed
 * ({@link TenantResolutionException}, so it fails, is retried and ends as an incident under the system audit scope).
 * The old attribution default ({@code defaultTenantId}) is no longer used here (SL-86 meaning change, PATCHES.md).
 * {@code onBehalfOf} records the job ({@code job:<id>}).
 *
 * <p>C3-05 (M-097): the identity is passed explicitly. It is attached to the job's own engine command
 * ({@link CommandState#jobIdentity}, read through {@link #current()}) and handed to the installed binder
 * (sangam-security's {@code TenantContext::runAs}). sangam-core keeps no thread-bound copy.
 */
public final class JobIdentitySupport {

    /** Process variable some models set to a tenant; decision 145/144: an untrusted claim, checked, never used. */
    public static final String TENANT_VARIABLE = "sangamTenantId";

    /** Site label of the policy calls (decision 145: fixed text, never data). */
    static final String SITE = "job execution";

    private final JobIdentityBinder binder;
    private final WorkTenants tenants;

    public JobIdentitySupport(JobIdentityBinder binder, WorkTenants tenants) {
        this.binder = binder == null ? JobIdentityBinder.LOCAL_ONLY : binder;
        this.tenants = java.util.Objects.requireNonNull(tenants, "tenants");
    }

    /**
     * The resolution of {@code job} in command {@code cc}: engine tenant {@code tenantId} as the engine passes it to the
     * handler (else the job's), claim from the execution's {@value #TENANT_VARIABLE} variable.
     */
    public WorkTenants.Resolution resolve(CommandContext cc, JobEntity job, ExecutionEntity execution, String tenantId) {
        String engineTenant = tenantId != null ? tenantId : job == null ? null : job.getTenantId();
        String claim = null;
        if (execution != null) {
            Object v = execution.getVariable(TENANT_VARIABLE);
            claim = v == null ? null : String.valueOf(v);
        }
        String deployment = WorkTenants.deploymentOf(cc, job);
        if (deployment == null && execution != null) {
            deployment = WorkTenants.deploymentOf(cc, execution.getProcessDefinitionId());
        }
        return tenants.resolve(CommandState.connection(cc), engineTenant, claim, deployment,
                WorkTenants.isMaintenance(job), SITE);
    }

    /**
     * The system identity the job runs as.
     *
     * @throws TenantResolutionException when the tenant cannot be resolved (the job is not executed)
     */
    public SecurityIdentity identityFor(CommandContext cc, JobEntity job, ExecutionEntity execution, String tenantId) {
        WorkTenants.Resolution r = resolve(cc, job, execution, tenantId);
        r.require();
        return SecurityIdentity.system(r.attribution(), job == null ? "job" : "job:" + job.getId());
    }

    /**
     * Runs {@code work} with {@code identity} bound to the job's command {@code cc} and through the binder, restoring
     * the command's previous job identity afterwards.
     */
    public <T> T runAs(CommandContext cc, SecurityIdentity identity, Supplier<T> work) {
        CommandState state = CommandState.of(cc);
        SecurityIdentity previous = state.jobIdentity(identity);
        try {
            return binder.runAs(identity, work);
        } finally {
            state.jobIdentity(previous);
        }
    }

    /** The identity of the job whose engine command is the current one, if any. */
    public static Optional<SecurityIdentity> current() {
        CommandState s = CommandState.current();
        return s == null ? Optional.empty() : Optional.ofNullable(s.jobIdentity());
    }
}
