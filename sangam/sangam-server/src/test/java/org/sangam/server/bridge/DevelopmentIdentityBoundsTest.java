// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.server.security.PlatformIssuers;
import org.springframework.mock.env.MockEnvironment;

/**
 * Decision 152 (SL-175 DEV_STATIC bounds), identity side: which identities count as development-issued. A static
 * development token's identity (auth method {@code DEV_TOKEN} or {@code DEV_STATIC}) and any token naming the
 * development token issuer are development identities; an OIDC login or an API key is not. The start-up side
 * (dev-token filter and platform-issuer refusal outside development) is in
 * {@code org.sangam.server.config.DevelopmentIdentityStartupBoundsTest}. Written for the test phase, not run.
 */
class DevelopmentIdentityBoundsTest {

    private static SecurityIdentity identity(String authMethod, Map<String, String> attributes) {
        return new SecurityIdentity("T-152", "sub-1", "sub-1", Set.of("sangam-user"), Set.of(), Set.of(), Set.of(),
                authMethod, "10.0.0.1", null, false, attributes);
    }

    @Test
    void staticDevelopmentTokensAreDevelopmentIssued() {
        assertTrue(SecurityIdentityFilter.isDevelopmentIssued(identity("DEV_TOKEN", Map.of())));
        assertTrue(SecurityIdentityFilter.isDevelopmentIssued(identity("DEV_STATIC", Map.of())));
    }

    @Test
    void theDevelopmentIssuerClaimMarksAnyAuthenticationPath() {
        assertTrue(SecurityIdentityFilter.isDevelopmentIssued(
                identity("OIDC", Map.of("iss", PlatformIssuers.DEV_TOKEN_ISSUER))),
                "a JWT carrying the development issuer is a development identity whatever its auth method");
    }

    @Test
    void verifiedIdentitiesAreNotDevelopmentIssued() {
        assertFalse(SecurityIdentityFilter.isDevelopmentIssued(
                identity("OIDC", Map.of("iss", "https://idp.example.org/realms/sangam"))));
        assertFalse(SecurityIdentityFilter.isDevelopmentIssued(identity("API_KEY", Map.of())));
        assertFalse(SecurityIdentityFilter.isDevelopmentIssued(identity("OIDC", null)),
                "no attributes: decided by the auth method alone, without a NullPointerException");
    }

    @Test
    void theRefusalCodeIsStable() {
        assertNotNull(SecurityIdentityFilter.DEVELOPMENT_IDENTITY_REFUSED);
        assertTrue(SecurityIdentityFilter.DEVELOPMENT_IDENTITY_REFUSED.equals("DEVELOPMENT_IDENTITY_REFUSED"));
    }

    @Test
    void stagingIsNotADevelopmentEnvironment() {
        MockEnvironment staging = new MockEnvironment().withProperty("sangam.security.production", "false");
        staging.setActiveProfiles("postgres");
        assertFalse(org.sangam.server.config.ProductionEnvironment.developmentDefaultsAllowed(staging));
        MockEnvironment dev = new MockEnvironment().withProperty("sangam.security.production", "false");
        dev.setActiveProfiles("h2");
        assertTrue(org.sangam.server.config.ProductionEnvironment.developmentDefaultsAllowed(dev));
    }
}
