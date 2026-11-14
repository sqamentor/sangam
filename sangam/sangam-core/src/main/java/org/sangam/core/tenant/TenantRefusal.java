// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.tenant;

/** Why the tenant of execution work could not be resolved (owner decision 145). Each refusal has a stable code. */
public enum TenantRefusal {

    /** Tenant-owned work whose execution has no engine tenant (null, empty, whitespace or the reserved shared key). */
    MISSING_ENGINE_TENANT("TENANT_CONTEXT_MISSING"),

    /**
     * Two tenant identities disagree: a case-variable tenant, a resource-metadata tenant, a captured continuation
     * context, a caller or an owner differs from the authoritative tenant, or shared work carries a tenant.
     */
    CONFLICTING_TENANT("TENANT_CONTEXT_CONFLICT"),

    /**
     * The system scope was asked for something only a tenant may have (a tenant connector, credential, tenant data or
     * a tenant caller), or shared work on a tenant-owned resource has no authorised resource-metadata tenant.
     */
    SHARED_SCOPE_NOT_ALLOWED("TENANT_SHARED_SCOPE_NOT_ALLOWED");

    private final String code;

    TenantRefusal(String code) {
        this.code = code;
    }

    /** The stable error code (REST problem code and audit reason). */
    public String code() {
        return code;
    }
}
