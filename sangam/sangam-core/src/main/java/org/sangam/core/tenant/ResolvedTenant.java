// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.tenant;

import java.util.Objects;

/**
 * The outcome of {@link ExecutionTenantPolicy}: the tenant execution work acts for, or the system scope of genuinely
 * shared work. The original engine tenant is kept apart from the resolved scope (owner decision 144), and the reason
 * plus the resource it was read from are part of the result so audit can record them.
 *
 * @param scope        {@link Scope#TENANT} or {@link Scope#SYSTEM}
 * @param tenantId     the resolved tenant; null exactly when the scope is {@link Scope#SYSTEM}
 * @param engineTenant the execution's engine tenant as given (normalised: null when absent), never replaced
 * @param reason       how the tenant was resolved
 * @param resourceType the resource the ownership came from ({@link Reason#RESOURCE_METADATA} only), else null
 * @param resourceId   that resource's id, else null
 */
public record ResolvedTenant(Scope scope, String tenantId, String engineTenant, Reason reason, String resourceType,
                             String resourceId) {

    /** The scope the work runs in. */
    public enum Scope {
        /** A real tenant: connectors, credentials and data of that tenant only. */
        TENANT,
        /** Genuinely shared work: no tenant connector, credential, data or caller (owner decision 144 system scope). */
        SYSTEM
    }

    /** Where the resolved tenant came from. */
    public enum Reason {
        /** The execution's engine tenant (the only source for tenant-owned work). */
        ENGINE_TENANT,
        /** An explicitly authorised {@link ResourceTenantGrant} for shared work on a tenant-owned resource. */
        RESOURCE_METADATA,
        /** Shared work with no tenant: the system scope. */
        SHARED_WORK
    }

    public ResolvedTenant {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(reason, "reason");
        if ((scope == Scope.SYSTEM) != (tenantId == null)) {
            throw new IllegalArgumentException("a resolved tenant has a tenant id exactly when its scope is TENANT");
        }
        if ((scope == Scope.SYSTEM) != (reason == Reason.SHARED_WORK)) {
            throw new IllegalArgumentException("the system scope is resolved only from shared work");
        }
    }

    public boolean isSystem() {
        return scope == Scope.SYSTEM;
    }

    /**
     * The tenant for connector selection, credential lookup and data access. The system scope never grants a tenant
     * connector, credential or tenant data: it is refused with {@link TenantRefusal#SHARED_SCOPE_NOT_ALLOWED}.
     *
     * @param context a fixed label of the calling operation (never data)
     */
    public String requireTenant(String context) {
        if (isSystem()) {
            throw refuse(TenantRefusal.SHARED_SCOPE_NOT_ALLOWED, context,
                    "the system scope has no tenant connector, credential or tenant data");
        }
        return tenantId;
    }

    /**
     * Verifies that a connector configuration or credential belongs to this tenant before it is used. An owner that is
     * absent or different is refused with {@link TenantRefusal#CONFLICTING_TENANT}; the system scope is refused first.
     */
    public void requireOwnedBy(String ownerTenant, String context) {
        String tenant = requireTenant(context);
        String owner = ExecutionTenantPolicy.normalise(ownerTenant);
        if (!tenant.equals(owner)) {
            throw refuse(TenantRefusal.CONFLICTING_TENANT, context,
                    "the configuration or credential is not owned by the execution's tenant");
        }
    }

    /**
     * Verifies that the authenticated caller (start, correlation, callback) acts for this tenant. A caller with no
     * tenant or another tenant is refused with {@link TenantRefusal#CONFLICTING_TENANT}; the system scope has no tenant
     * caller ({@link TenantRefusal#SHARED_SCOPE_NOT_ALLOWED}).
     */
    public void requireCaller(String callerTenant, String context) {
        String tenant = requireTenant(context);
        if (!tenant.equals(ExecutionTenantPolicy.normalise(callerTenant))) {
            throw refuse(TenantRefusal.CONFLICTING_TENANT, context, "the caller does not act for the execution's tenant");
        }
    }

    private static TenantResolutionException refuse(TenantRefusal refusal, String context, String reason) {
        return new TenantResolutionException(refusal, null, ExecutionTenantPolicy.label(context), reason);
    }
}
