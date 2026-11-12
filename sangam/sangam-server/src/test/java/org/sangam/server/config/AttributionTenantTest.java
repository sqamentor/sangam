// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * SL-86: one attribution-tenant resolver with one fallback. application.yaml defines sangam.tenant.default as
 * ${SANGAM_TENANT_DEFAULT:} (empty by default); geo, modeller and packs used to read that empty value as the tenant
 * (their dev-token fallback applied only to an absent property), while the engine and integration used "default".
 */
class AttributionTenantTest {

    @Test
    void absentOrBlankFallsBackToDefault() {
        assertEquals("default", AttributionTenant.resolve(new MockEnvironment()));
        assertEquals("default", AttributionTenant.resolve(new MockEnvironment().withProperty("sangam.tenant.default", "")));
        assertEquals("default", AttributionTenant.resolve(new MockEnvironment().withProperty("sangam.tenant.default", "  ")));
    }

    @Test
    void theDevTokenTenantIsNotAnAttributionFallback() {
        MockEnvironment env = new MockEnvironment().withProperty("sangam.tenant.default", "")
                .withProperty("sangam.security.dev-token.tenant", "dev");
        assertEquals("default", AttributionTenant.resolve(env), "the dev token's tenant is for the dev token only");
    }

    @Test
    void aConfiguredTenantIsUsedAndMayBeARealTenant() {
        assertEquals("RJ", AttributionTenant.resolve(new MockEnvironment().withProperty("sangam.tenant.default", " RJ ")));
    }

    @Test
    void theReservedSharedKeyIsRefused() {
        assertThrows(IllegalStateException.class,
                () -> AttributionTenant.resolve(new MockEnvironment().withProperty("sangam.tenant.default", "-")));
    }
}
