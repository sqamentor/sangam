// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.sangam.server.security.PlatformIssuers;
import org.springframework.mock.env.MockEnvironment;

/**
 * Decision 152 (SL-175 DEV_STATIC bounds), start-up side: the static development token and the development token
 * issuer are accepted only in a development environment resolved by decision 143 ({@code sangam.security.production=
 * false} with a dev, h2, test or local profile). Staging ({@code false} without a development profile) and production
 * refuse both at start-up. Written for the test phase, not run.
 */
class DevelopmentIdentityStartupBoundsTest {

    private static MockEnvironment env(String flag, String... profiles) {
        MockEnvironment env = new MockEnvironment().withProperty("sangam.security.production", flag);
        env.setActiveProfiles(profiles);
        return env;
    }

    private static SangamSecurityProperties devTokenEnabled() {
        SangamSecurityProperties props = new SangamSecurityProperties();
        props.getDevToken().setEnabled(true);
        props.getDevToken().setToken("a-development-token-long-enough-for-the-check");
        props.getDevToken().setTenant("dev");
        return props;
    }

    @Test
    void theDevTokenIsRefusedInStaging() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> SecurityConfig.devTokenFilter(devTokenEnabled(), env("false", "postgres")));
        assertTrue(e.getMessage().contains("allowed only in a development environment"), e.getMessage());
    }

    @Test
    void theDevTokenIsRefusedWithoutAnyProfile() {
        assertThrows(IllegalStateException.class,
                () -> SecurityConfig.devTokenFilter(devTokenEnabled(), env("false")));
    }

    @Test
    void theDevTokenIsRefusedInProduction() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> SecurityConfig.devTokenFilter(devTokenEnabled(), env("true", "h2")));
        assertTrue(e.getMessage().contains("not allowed in production"), e.getMessage());
    }

    @Test
    void theDevTokenIsAllowedInADevelopmentEnvironment() {
        assertNotNull(SecurityConfig.devTokenFilter(devTokenEnabled(), env("false", "h2")));
        assertNotNull(SecurityConfig.devTokenFilter(devTokenEnabled(), env("false", "dev")));
    }

    @Test
    void theDevelopmentIssuerIsNeverAPlatformIssuerOutsideDevelopment() {
        SangamSecurityProperties props = new SangamSecurityProperties();
        props.setPlatformIssuer(List.of("https://idp.example.org/realms/platform", PlatformIssuers.DEV_TOKEN_ISSUER));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> SecurityConfig.requireNoDevelopmentIssuer(props, env("false", "postgres")));
        assertTrue(e.getMessage().contains("decision 152"), e.getMessage());
        assertThrows(IllegalStateException.class,
                () -> SecurityConfig.requireNoDevelopmentIssuer(props, env("true")));
        assertDoesNotThrow(() -> SecurityConfig.requireNoDevelopmentIssuer(props, env("false", "h2")));
    }

    @Test
    void ordinaryPlatformIssuersAreUnaffected() {
        SangamSecurityProperties props = new SangamSecurityProperties();
        props.setPlatformIssuer(List.of("https://idp.example.org/realms/platform"));
        assertDoesNotThrow(() -> SecurityConfig.requireNoDevelopmentIssuer(props, env("false", "postgres")));
        assertDoesNotThrow(() -> SecurityConfig.requireNoDevelopmentIssuer(props, env("true")));
    }
}
