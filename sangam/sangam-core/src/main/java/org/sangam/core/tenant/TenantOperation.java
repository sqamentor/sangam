// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.tenant;

/**
 * What a piece of execution work acts on, as classified by the caller from authoritative resource metadata (owner
 * decisions 144 and 145). The classification never comes from a process or case variable.
 */
public enum TenantOperation {

    /**
     * Work on a tenant-owned instance, task, job, incident or callback. The engine tenant of the execution is the only
     * tenant source; without one the work fails closed.
     */
    TENANT_OWNED,

    /**
     * Genuinely shared (system-owned) work: a shared definition, a system job or incident with no tenant. It resolves
     * to the system scope, which never selects a tenant connector, credential or tenant data.
     */
    SHARED,

    /**
     * Shared work (no engine tenant, or one that must agree) acting on a tenant-owned resource. It needs an explicitly
     * authorised tenant from the resource's metadata ({@link ResourceTenantGrant}); without one it is blocked.
     */
    SHARED_ON_TENANT_RESOURCE
}
