// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.job;

import java.util.Optional;
import java.util.function.Supplier;

import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.operaton.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.operaton.bpm.engine.impl.persistence.entity.JobEntity;
import org.sangam.core.engine.JobIdentityBinder;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.engine.tx.CommandState;

/**
 * VI.4 rule 7 for job execution (timers, async continuations, retries, batch and cleanup jobs): every job runs as the
 * <b>system identity of its instance's tenant</b>, never without an identity. The tenant is, in order: the tenant id
 * on the job, the {@code sangamTenantId} variable of the execution, the engine's default tenant. {@code onBehalfOf}
 * records the job ({@code job:<id>}).
 *
 * <p>C3-05 (M-097): the identity is passed explicitly. It is attached to the job's own engine command
 * ({@link CommandState#jobIdentity}, read through {@link #current()}) and handed to the installed binder
 * (sangam-security's {@code TenantContext::runAs}). sangam-core keeps no thread-bound copy.
 */
public final class JobIdentitySupport {

    /** Process variable that carries the tenant for instances started without a deployment tenant. */
    public static final String TENANT_VARIABLE = "sangamTenantId";

    private final JobIdentityBinder binder;
    private final String defaultTenant;

    public JobIdentitySupport(JobIdentityBinder binder, String defaultTenant) {
        this.binder = binder == null ? JobIdentityBinder.LOCAL_ONLY : binder;
        this.defaultTenant = defaultTenant;
    }

    public SecurityIdentity identityFor(JobEntity job, ExecutionEntity execution, String tenantId) {
        String tenant = tenantId;
        if (tenant == null && job != null) {
            tenant = job.getTenantId();
        }
        if (tenant == null && execution != null) {
            Object v = execution.getVariable(TENANT_VARIABLE);
            if (v != null && !String.valueOf(v).isBlank()) {
                tenant = String.valueOf(v);
            }
        }
        if (tenant == null) {
            tenant = defaultTenant;
        }
        return SecurityIdentity.system(tenant, job == null ? "job" : "job:" + job.getId());
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
