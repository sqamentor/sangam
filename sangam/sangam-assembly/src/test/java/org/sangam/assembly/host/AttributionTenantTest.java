// SPDX-License-Identifier: Apache-2.0
package org.sangam.assembly.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * SL-86 on every host (extraction phase 3): the one attribution-tenant rule is plain Java over the config-source seam,
 * so the Quarkus host resolves exactly what the Spring host resolves. The Spring host's
 * {@code org.sangam.server.config.AttributionTenantTest} runs the same cases through Spring's {@code Environment}.
 */
class AttributionTenantTest {

    @Test
    void absentOrBlankFallsBackToDefault() {
        assertEquals("default", AttributionTenant.resolve(SangamProperties.empty()));
        assertEquals("default", AttributionTenant.resolve(SangamProperties.of(Map.of("sangam.tenant.default", ""))));
        assertEquals("default", AttributionTenant.resolve(SangamProperties.of(Map.of("sangam.tenant.default", "  "))));
    }

    @Test
    void theDevTokenTenantIsNotAnAttributionFallback() {
        SangamProperties p = SangamProperties.of(Map.of("sangam.tenant.default", "",
                "sangam.security.dev-token.tenant", "dev"));
        assertEquals("default", AttributionTenant.resolve(p), "the dev token's tenant is for the dev token only");
    }

    @Test
    void aConfiguredTenantIsUsedTrimmedAndMayBeARealTenant() {
        assertEquals("RJ", AttributionTenant.resolve(SangamProperties.of(Map.of("sangam.tenant.default", " RJ "))));
    }

    @Test
    void theReservedSharedKeyIsRefusedNamingTheProperty() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AttributionTenant.resolve(SangamProperties.of(Map.of("sangam.tenant.default", "-"))));
        assertTrue(e.getMessage().startsWith(AttributionTenant.PROPERTY + ": "), e.getMessage());
    }

    @Test
    void theRuleReadsOnlyTheConfigSourceSeam() {
        // the lowest source (sangam-defaults.yaml) is honoured like any other source of the seam
        SangamProperties host = SangamProperties.empty().withDefaults(SangamProperties.of(Map.of(
                AttributionTenant.PROPERTY, "acme")));
        assertEquals("acme", AttributionTenant.resolve(host));
        assertThrows(NullPointerException.class, () -> AttributionTenant.resolve(null));
    }
}
