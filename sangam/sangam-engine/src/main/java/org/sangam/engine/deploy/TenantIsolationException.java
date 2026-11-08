// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.deploy;

import org.operaton.bpm.engine.ProcessEngineException;

/**
 * A model element tried to resolve a definition of another tenant (SL-13 / SL-45, {@link TenantIsolationGuard}).
 * Stable error code {@value #CODE}; HTTP mapping {@code 403 Forbidden} on {@code /api} and {@code /engine-rest}: the
 * caller is not allowed to use the other tenant's definition, and resending the same request can never succeed.
 * It stays a {@link ProcessEngineException}, so the engine rolls the command back exactly as before.
 */
public final class TenantIsolationException extends ProcessEngineException {

    public static final String CODE = "TENANT_ISOLATION_VIOLATION";
    public static final int HTTP_STATUS = 403;

    private final String element;
    private final String callerTenant;
    private final String targetTenant;

    public TenantIsolationException(String element, String callerTenant, String targetTenant) {
        super("SL-13 tenant isolation: '" + element + "' of tenant " + callerTenant
                + " may not resolve a definition of tenant " + targetTenant + "; only the caller's own tenant and shared"
                + " definitions are allowed (cross-tenant work is a message to the other tenant, J-P2-E09)");
        this.element = element;
        this.callerTenant = callerTenant;
        this.targetTenant = targetTenant;
    }

    public String element() {
        return element;
    }

    public String callerTenant() {
        return callerTenant;
    }

    public String targetTenant() {
        return targetTenant;
    }

    /** The first isolation refusal in a cause chain (the engine and proxies wrap exceptions). */
    public static TenantIsolationException find(Throwable t) {
        for (Throwable x = t; x != null; x = x.getCause() == x ? null : x.getCause()) {
            if (x instanceof TenantIsolationException e) {
                return e;
            }
        }
        return null;
    }
}
