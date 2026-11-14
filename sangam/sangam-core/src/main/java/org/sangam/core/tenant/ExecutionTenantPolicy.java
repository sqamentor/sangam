// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.tenant;

import org.sangam.core.layer.TenantKey;

/**
 * The one framework-independent tenant-resolution policy of execution work (owner decision 145), called by every
 * execution path: delegates, listeners, jobs, retries, incidents, external-task workers and callbacks, on every engine
 * host. Plain Java with no engine or framework type: the caller reads the engine tenant from its execution, job, task
 * or incident and passes it in.
 *
 * <p>Rules:
 * <ul>
 *   <li>The engine tenant of the execution is the only tenant source. It is set by authenticated, authorised process
 *       creation. Null, empty, whitespace and the reserved shared key ({@link TenantKey#SHARED}) all read as "no
 *       engine tenant" ({@link #normalise}).</li>
 *   <li>A tenant found in process or case variables (the {@code claimedTenant}) is untrusted business data. It never
 *       substitutes for a missing engine tenant and never overrides one; a claim that differs from the resolved tenant
 *       is refused ({@link TenantRefusal#CONFLICTING_TENANT}) before any connector call or side effect. Callers pass
 *       every variable they used to read as a tenant, so a forged one is detected instead of ignored.</li>
 *   <li>{@link TenantOperation#TENANT_OWNED} work without an engine tenant fails closed
 *       ({@link TenantRefusal#MISSING_ENGINE_TENANT}).</li>
 *   <li>{@link TenantOperation#SHARED} work resolves to the system scope (owner decision 144) only when neither the
 *       engine nor a claim names a tenant: absence alone does not prove system ownership, so the caller classifies the
 *       work from authoritative metadata, and a tenant on either side contradicts that classification. The system
 *       scope never grants a tenant connector, credential or tenant data ({@link ResolvedTenant#requireTenant}).</li>
 *   <li>{@link TenantOperation#SHARED_ON_TENANT_RESOURCE} work needs an explicitly authorised
 *       {@link ResourceTenantGrant} from resource metadata, which every other identity present must agree with; without
 *       one it is blocked ({@link TenantRefusal#SHARED_SCOPE_NOT_ALLOWED}).</li>
 *   <li>Async jobs, retries, incidents and callbacks capture the {@link ResolvedTenant} of the original work and check
 *       it again on continuation ({@link #requireContinuation}): the tenant context is preserved or the work stops.</li>
 * </ul>
 * Refusals are {@link TenantResolutionException}s (403, stable {@link TenantRefusal#code()}); their messages never carry
 * a tenant id or variable value.
 */
public final class ExecutionTenantPolicy {

    private ExecutionTenantPolicy() {
    }

    /** Tenant-owned work: the engine tenant, which must be present and agree with any claimed tenant. */
    public static ResolvedTenant tenantOwned(String engineTenant, String claimedTenant, String context) {
        return resolve(TenantOperation.TENANT_OWNED, engineTenant, claimedTenant, null, context);
    }

    /** Genuinely shared work: the system scope, refused when the engine or a claim names a tenant. */
    public static ResolvedTenant shared(String engineTenant, String claimedTenant, String context) {
        return resolve(TenantOperation.SHARED, engineTenant, claimedTenant, null, context);
    }

    /** Shared work on a tenant-owned resource: the authorised resource-metadata tenant, which all identities must match. */
    public static ResolvedTenant onTenantResource(String engineTenant, String claimedTenant, ResourceTenantGrant grant,
                                                 String context) {
        return resolve(TenantOperation.SHARED_ON_TENANT_RESOURCE, engineTenant, claimedTenant, grant, context);
    }

    /**
     * Resolves the tenant of execution work.
     *
     * @param operation     the caller's classification from authoritative metadata, never from a variable
     * @param engineTenant  the tenant id on the execution, task, job or incident (null when the engine has none)
     * @param claimedTenant a tenant read from process or case variables (untrusted), or null
     * @param grant         the authorised resource-metadata tenant ({@link TenantOperation#SHARED_ON_TENANT_RESOURCE}
     *                      only; must be null otherwise)
     * @param context       a fixed label of the calling operation for the refusal message (never data)
     */
    public static ResolvedTenant resolve(TenantOperation operation, String engineTenant, String claimedTenant,
                                         ResourceTenantGrant grant, String context) {
        if (operation == null) {
            throw new IllegalArgumentException("tenant resolution: the operation kind is required");
        }
        String label = label(context);
        if (grant != null && operation != TenantOperation.SHARED_ON_TENANT_RESOURCE) {
            throw new IllegalArgumentException("tenant resolution: a resource grant applies only to "
                    + TenantOperation.SHARED_ON_TENANT_RESOURCE);
        }
        String engine = normalise(engineTenant);
        String claim = normalise(claimedTenant);
        return switch (operation) {
            case TENANT_OWNED -> {
                if (engine == null) {
                    throw refuse(TenantRefusal.MISSING_ENGINE_TENANT, operation, label,
                            "tenant-owned work has no engine tenant; a variable tenant is never used instead");
                }
                requireAgrees(engine, claim, operation, label, "a case-variable tenant differs from the engine tenant");
                yield new ResolvedTenant(ResolvedTenant.Scope.TENANT, engine, engine, ResolvedTenant.Reason.ENGINE_TENANT,
                        null, null);
            }
            case SHARED -> {
                if (engine != null) {
                    throw refuse(TenantRefusal.CONFLICTING_TENANT, operation, label,
                            "work classified as shared runs under an engine tenant");
                }
                if (claim != null) {
                    throw refuse(TenantRefusal.CONFLICTING_TENANT, operation, label,
                            "shared work carries a case-variable tenant");
                }
                yield new ResolvedTenant(ResolvedTenant.Scope.SYSTEM, null, null, ResolvedTenant.Reason.SHARED_WORK,
                        null, null);
            }
            case SHARED_ON_TENANT_RESOURCE -> {
                if (grant == null) {
                    throw refuse(TenantRefusal.SHARED_SCOPE_NOT_ALLOWED, operation, label,
                            "shared work on a tenant-owned resource has no authorised resource-metadata tenant");
                }
                String owner = grant.tenantId();
                requireAgrees(owner, engine, operation, label, "the engine tenant differs from the resource owner");
                requireAgrees(owner, claim, operation, label, "a case-variable tenant differs from the resource owner");
                yield new ResolvedTenant(ResolvedTenant.Scope.TENANT, owner, engine,
                        ResolvedTenant.Reason.RESOURCE_METADATA, grant.resourceType(), grant.resourceId());
            }
        };
    }

    /**
     * Continuation check for async jobs, retries, incidents and callbacks: the tenant context resolved now must be the
     * one captured when the work was created. A missing capture fails closed ({@link TenantRefusal#MISSING_ENGINE_TENANT});
     * a different scope or tenant is refused ({@link TenantRefusal#CONFLICTING_TENANT}).
     *
     * @return {@code current}, for chaining
     */
    public static ResolvedTenant requireContinuation(ResolvedTenant captured, ResolvedTenant current, String context) {
        String label = label(context);
        if (current == null) {
            throw new IllegalArgumentException("tenant continuation: the current resolution is required");
        }
        if (captured == null) {
            throw refuse(TenantRefusal.MISSING_ENGINE_TENANT, null, label,
                    "the continuation has no captured tenant context");
        }
        if (captured.scope() != current.scope() || !java.util.Objects.equals(captured.tenantId(), current.tenantId())) {
            throw refuse(TenantRefusal.CONFLICTING_TENANT, null, label,
                    "the tenant context changed between the original work and its continuation");
        }
        return current;
    }

    /**
     * The tenant as the policy reads it: null for null, empty, whitespace-only and the reserved shared key; otherwise
     * the value unchanged (a non-blank id is never trimmed into another id).
     */
    public static String normalise(String tenant) {
        return TenantKey.toEngine(tenant);
    }

    static String label(String context) {
        if (context == null || context.isBlank()) {
            throw new IllegalArgumentException("tenant resolution: a context label is required");
        }
        return context;
    }

    private static void requireAgrees(String authoritative, String other, TenantOperation operation, String label,
                                      String reason) {
        if (other != null && !other.equals(authoritative)) {
            throw refuse(TenantRefusal.CONFLICTING_TENANT, operation, label, reason);
        }
    }

    private static TenantResolutionException refuse(TenantRefusal refusal, TenantOperation operation, String label,
                                                    String reason) {
        return new TenantResolutionException(refusal, operation, label, reason);
    }
}
