// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.core.security.EnvironmentPolicy;
import org.sangam.core.security.EnvironmentPolicy.Mode;
import org.sangam.core.security.EnvironmentPolicy.Signal;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockEnvironment;

/**
 * Decision 143 (RM-06): the Spring adapter of the environment policy. Spring profiles are classified here (dev, h2,
 * test, local: development; a name with prod: production; others neutral; the default profiles when none is active),
 * the configuration source of the switch is named, and the start-up check refuses a missing, invalid or contradictory
 * configuration while the guard predicate answers "production" for it. Mixed profiles, missing switch, contradictions
 * and production plus a development profile are covered. Written for the test phase (not run here).
 */
class ProductionEnvironmentPolicyTest {

    static MockEnvironment env(String flag, String... profiles) {
        MockEnvironment e = new MockEnvironment();
        if (flag != null) {
            e.setProperty("sangam.security.production", flag);
        }
        e.setActiveProfiles(profiles);
        return e;
    }

    @Test
    void profilesAreClassifiedByTheAdapter() {
        assertEquals(Map.of("postgres", Signal.NEUTRAL, "oidc", Signal.NEUTRAL, "h2", Signal.DEVELOPMENT,
                        "prod-eu", Signal.PRODUCTION, "TEST", Signal.DEVELOPMENT),
                ProductionEnvironment.profileSignals(env("false", "postgres", "oidc", "h2", "prod-eu", "TEST")));
        MockEnvironment none = env("false");
        none.setDefaultProfiles("h2");
        assertEquals(Map.of("h2", Signal.DEVELOPMENT), ProductionEnvironment.profileSignals(none),
                "without an active profile the default profiles are in effect");
    }

    @Test
    void theMatrixThroughTheAdapter() {
        assertEquals(Mode.DEVELOPMENT, ProductionEnvironment.resolve(env("false", "h2")).mode());
        assertEquals(Mode.NON_PRODUCTION, ProductionEnvironment.resolve(env("false", "postgres", "oidc")).mode());
        assertEquals(Mode.PRODUCTION, ProductionEnvironment.resolve(env("true", "postgres", "oidc")).mode());
        assertEquals(Mode.PRODUCTION, ProductionEnvironment.resolve(env("true", "h2")).mode(),
                "production plus a development profile");
        assertFalse(ProductionEnvironment.developmentDefaultsAllowed(env("true", "h2")));
        assertTrue(ProductionEnvironment.developmentDefaultsAllowed(env("false", "test", "postgres")));
        assertFalse(ProductionEnvironment.developmentDefaultsAllowed(env("false", "postgres")));
    }

    @Test
    void refusedConfigurationsFailClosed() {
        for (MockEnvironment bad : new MockEnvironment[] {env(null, "h2"), env(null), env("yes", "h2"),
                env("false", "prod"), env("false", "h2", "prod")}) {
            assertThrows(EnvironmentPolicy.ConfigurationError.class, () -> ProductionEnvironment.resolve(bad));
            assertThrows(EnvironmentPolicy.ConfigurationError.class,
                    () -> EnvironmentPolicyWiring.StartupCheck.check(bad), "the start is refused");
            assertTrue(ProductionEnvironment.isProduction(bad), "guards treat it as production");
            assertFalse(ProductionEnvironment.developmentDefaultsAllowed(bad));
        }
    }

    @Test
    void theSourceOfTheSwitchIsNamedWithoutItsValue() {
        StandardEnvironment e = new StandardEnvironment();
        e.getPropertySources().addFirst(new MapPropertySource("deployment-config",
                Map.of("sangam.security.production", "true")));
        e.setActiveProfiles("postgres");
        EnvironmentPolicy.Resolution r = EnvironmentPolicyWiring.StartupCheck.check(e);
        assertEquals("deployment-config", r.flagSource());
        assertTrue(r.describe().contains("deployment-config") && r.describe().contains("PRODUCTION"), r.describe());
    }

    @Test
    void productionWithADevelopmentProfileStartsAsProductionAndNamesTheContradiction() {
        // decision 198: production wins, every guard on; the start-up check warns naming the ignored profiles
        EnvironmentPolicy.Resolution r = EnvironmentPolicyWiring.StartupCheck.check(env("true", "h2", "postgres"));
        assertEquals(Mode.PRODUCTION, r.mode());
        assertTrue(ProductionEnvironment.isProduction(env("true", "h2")));
        assertFalse(ProductionEnvironment.developmentDefaultsAllowed(env("true", "h2")));
        assertEquals(java.util.List.of("h2"), r.ignoredDevelopmentProfiles());
    }
}
