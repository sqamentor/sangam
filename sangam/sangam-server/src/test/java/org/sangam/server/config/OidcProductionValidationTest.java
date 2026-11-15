// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * RM-09 (decisions 75 and 92): in production the OIDC decoder refuses to start when the issuer is unset (JWKS only, so
 * {@code iss} is unchecked) or the audience is blank (so {@code aud} is unchecked). Outside production the same gaps
 * are only reported (WARN), and a complete configuration starts in both. Written for the test phase (not run here).
 */
class OidcProductionValidationTest {

    static final String ISSUER = "https://idp.example.org/realms/tenant";
    static final String JWKS = "https://idp.example.org/realms/tenant/protocol/openid-connect/certs";

    static SangamSecurityProperties props(String audience) {
        SangamSecurityProperties p = new SangamSecurityProperties();
        p.getClaims().setAudience(audience);
        return p;
    }

    static MockEnvironment production() {
        return new MockEnvironment().withProperty("sangam.security.production", "true");
    }

    @Test
    void productionRefusesJwksWithoutAnIssuer() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new OidcConfig().sangamJwtDecoder(
                production().withProperty(OidcConfig.JWKS, JWKS), props("sangam-server")));
        assertTrue(e.getMessage().contains("RM-09") && e.getMessage().contains("iss"), e.getMessage());
    }

    @Test
    void productionRefusesABlankAudience() {
        for (String blank : new String[]{"", "  ", null}) {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> new OidcConfig().sangamJwtDecoder(
                    production().withProperty(OidcConfig.ISSUER, ISSUER), props(blank)));
            assertTrue(e.getMessage().contains("aud"), e.getMessage());
        }
    }

    @Test
    void aProductionProfileCountsAsProduction() {
        MockEnvironment env = new MockEnvironment().withProperty(OidcConfig.JWKS, JWKS);
        env.setActiveProfiles("prod");
        assertThrows(IllegalStateException.class, () -> new OidcConfig().sangamJwtDecoder(env, props("sangam-server")));
    }

    @Test
    void productionStartsWithIssuerAndAudience() {
        assertDoesNotThrow(() -> new OidcConfig().sangamJwtDecoder(
                production().withProperty(OidcConfig.ISSUER, ISSUER).withProperty(OidcConfig.JWKS, JWKS),
                props("sangam-server")));
    }

    @Test
    void outsideProductionTheGapsAreOnlyReported() {
        assertDoesNotThrow(() -> new OidcConfig().sangamJwtDecoder(
                new MockEnvironment().withProperty("sangam.security.production", "false").withProperty(OidcConfig.JWKS, JWKS),
                props("")));
        assertEquals(2, OidcConfig.requireCompleteValidation(false, null, " ").size(), "both gaps are named in the WARN");
        assertEquals(0, OidcConfig.requireCompleteValidation(true, ISSUER, "sangam-server").size());
    }
}
