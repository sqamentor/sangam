// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.job;

import java.util.function.Supplier;

import org.operaton.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.operaton.bpm.engine.impl.persistence.entity.JobEntity;
import org.sangam.core.engine.JobIdentityBinder;
import org.sangam.core.security.SecurityIdentity;

/**
 * VI.4 rule 7 for job execution (timers, async continuations, retries, batch and cleanup jobs): every job runs as the
 * <b>system identity of its instance's tenant</b>, never without an identity. The tenant is, in order: the tenant id
 * on the job, the {@code sangamTenantId} variable of the execution, the engine's default tenant. {@code onBehalfOf}
 * records the job ({@code job:<id>}). The identity is bound engine-locally ({@link JobIdentityBinder#CURRENT}) and
 * through the installed binder (sangam-security's {@code TenantContext::runAs}).
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

    /** Runs {@code work} with {@code identity} bound (engine-local and through the binder), restoring the previous one. */
    public <T> T runAs(SecurityIdentity identity, Supplier<T> work) {
        SecurityIdentity previous = JobIdentityBinder.CURRENT.get();
        JobIdentityBinder.CURRENT.set(identity);
        try {
            return binder.runAs(identity, work);
        } finally {
            if (previous == null) {
                JobIdentityBinder.CURRENT.remove();
            } else {
                JobIdentityBinder.CURRENT.set(previous);
            }
        }
    }
}
