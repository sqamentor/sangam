// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sangam.core.layer.TenantKey;
import org.sangam.core.security.SecurityIdentity;

/**
 * Decision 144: no authenticated identity may carry the shared key or a system audit scope as its tenant, whatever the
 * authentication method, so a tenant path never runs as, or reads audit of, the system scope. Written for the test
 * phase (not run here).
 */
class ReservedTenantIdentityTest {

    private static SecurityIdentity identity(String tenant, String authMethod) {
        return new SecurityIdentity(tenant, "p1", "sub-p1", Set.of(), Set.of(), Set.of(), Set.of(), authMethod,
                "10.0.0.1", null, false, Map.of());
    }

    @Test
    void reservedTenantsAreRefusedForEveryAuthenticationMethod() {
        for (String method : new String[] {"OIDC", "SAML", "API_KEY", "DEV_TOKEN"}) {
            assertTrue(SecurityIdentityFilter.reservedTenant(identity("system:audit", method)), method);
            assertTrue(SecurityIdentityFilter.reservedTenant(identity("system:anything-else", method)), method);
            assertTrue(SecurityIdentityFilter.reservedTenant(identity("system:Not Valid", method)), method);
            assertTrue(SecurityIdentityFilter.reservedTenant(identity(TenantKey.SHARED, method)), method);
        }
    }

    @Test
    void ordinaryTenantsPass() {
        for (String tenant : new String[] {"acme", "system", "system-audit", "t1"}) {
            assertFalse(SecurityIdentityFilter.reservedTenant(identity(tenant, "OIDC")), tenant);
        }
        assertFalse(SecurityIdentityFilter.reservedTenant(null));
        assertEquals("RESERVED_TENANT_REFUSED", SecurityIdentityFilter.RESERVED_TENANT_REFUSED);
    }
}
