// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.tenant;

import org.sangam.core.security.SangamSecurityException;
import org.sangam.core.security.SecurityIdentity;

/**
 * Tenant isolation (VI.4 rule 6, K1, S9). The tenant always comes from the authenticated identity; a tenant id in a
 * request is only a filter inside the allowed set and never a grant.
 */
public final class TenantGuard {

    private TenantGuard() {
    }

    /** True if a request tenant filter is absent or equals the identity's tenant (otherwise the result set is empty). */
    public static boolean requestTenantAllowed(SecurityIdentity who, String requestTenant) {
        return requestTenant == null || requestTenant.isBlank() || who.tenantId().equals(requestTenant);
    }

    /** For writes: a request naming another tenant is rejected outright. */
    public static void requireSameTenant(SecurityIdentity who, String requestTenant) {
        if (!requestTenantAllowed(who, requestTenant)) {
            throw new SangamSecurityException.TenantMismatch("request tenant is outside the authenticated tenant");
        }
    }

    /** A row read by id must belong to the caller's tenant (defence in depth on top of the predicate). */
    public static void requireRowTenant(SecurityIdentity who, String rowTenant) {
        if (rowTenant == null || !who.tenantId().equals(rowTenant)) {
            throw new SangamSecurityException.TenantMismatch("resource is not in the authenticated tenant");
        }
    }

    /** The tenant to write into: always the identity's. */
    public static String writeTenant(SecurityIdentity who) {
        return who.tenantId();
    }
}
