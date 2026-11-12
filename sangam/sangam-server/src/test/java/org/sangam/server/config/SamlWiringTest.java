// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.sangam.security.saml.SamlProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.mock.env.MockEnvironment;

/**
 * M-076 host side (decision 73): off unless enabled; an enabled adapter binds its registration and refuses to start
 * when it is incomplete, when the SAML library is absent, and while the filter chain is not built (fail closed).
 * Written for the test phase (not run here).
 */
class SamlWiringTest {

    static MockEnvironment env() {
        MockEnvironment env = new MockEnvironment();
        env.setConversionService(new ApplicationConversionService());
        return env.withProperty("sangam.security.saml.enabled", "true")
                .withProperty("sangam.security.saml.tenant-id", "t1")
                .withProperty("sangam.security.saml.entity-id", "https://sangam.example/saml2/sp")
                .withProperty("sangam.security.saml.asserting-party-metadata-location", "file:/etc/sangam/idp.xml");
    }

    @Test
    void offUnlessEnabled() {
        ConditionalOnProperty c = SamlWiring.class.getAnnotation(ConditionalOnProperty.class);
        assertEquals("sangam.security.saml.enabled", String.join(",", c.name()));
        assertEquals("true", c.havingValue());
        assertFalse(c.matchIfMissing());
    }

    @Test
    void theRegistrationBindsFromTheEnvironment() {
        SamlProperties p = SamlWiring.bind(env().withProperty("sangam.security.saml.claims-profile.preset", "verified-email"));
        assertTrue(p.isEnabled());
        assertEquals("t1", p.getTenantId());
        assertEquals("idp", p.getRegistrationId());
        assertEquals("verified-email", p.getClaimsProfile().get("preset"));
    }

    @Test
    void anIncompleteRegistrationRefusesToStart() {
        SamlProperties p = SamlWiring.bind(env());
        p.setTenantId(null);
        assertThrows(IllegalStateException.class, () -> SamlWiring.checked(p, getClass().getClassLoader()));
    }

    @Test
    void anEnabledAdapterNeverStartsWithoutAWorkingChain() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> SamlWiring.checked(SamlWiring.bind(env()), getClass().getClassLoader()));
        assertTrue(e.getMessage().contains("Keycloak brokering"), e.getMessage());
    }
}
