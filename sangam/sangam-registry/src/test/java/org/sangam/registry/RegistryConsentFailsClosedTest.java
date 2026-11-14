// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Dialect;
import org.sangam.core.security.EnvironmentPolicy;
import org.sangam.core.security.EnvironmentPolicy.Signal;

/**
 * RM-06 (decisions 54R, 68 and 143): registry consent fails closed. The permit-all consent default is
 * development-only, so {@code RegistryPorts.defaults().developmentOnlyReasons()} names consent, and a start with it is
 * refused unless the platform's resolved environment is a development one (explicit switch false and a development
 * profile): production, production with a development profile, staging (false without a development profile) and a
 * missing resolution all refuse it. A bound consent port removes only that reason. Written for the test phase (not
 * run here).
 */
class RegistryConsentFailsClosedTest {

    /** A real consent port as the compliance module would bind it. */
    static final RegistryPorts.ConsentPort BOUND = (Connection c, RegistryContext ctx, String party, String purpose,
                                                   String processing) -> RegistryPorts.ConsentDecision.deny("no consent");

    static EnvironmentPolicy.Resolution env(String flag, Map<String, Signal> profiles) {
        return EnvironmentPolicy.resolve(flag, "test", profiles);
    }

    static final EnvironmentPolicy.Resolution DEVELOPMENT = env("false", Map.of("h2", Signal.DEVELOPMENT));
    static final EnvironmentPolicy.Resolution STAGING = env("false", Map.of("postgres", Signal.NEUTRAL));
    static final EnvironmentPolicy.Resolution PRODUCTION = env("true", Map.of("postgres", Signal.NEUTRAL));
    static final EnvironmentPolicy.Resolution PRODUCTION_WITH_DEV = env("true", Map.of("dev", Signal.DEVELOPMENT));

    static boolean namesConsent(List<String> reasons) {
        return reasons.stream().anyMatch(r -> r.startsWith("consent:"));
    }

    @Test
    void theDefaultsNameConsent() {
        assertTrue(namesConsent(RegistryPorts.defaults().developmentOnlyReasons()),
                RegistryPorts.defaults().developmentOnlyReasons().toString());
        assertTrue(RegistryPorts.ConsentPort.PERMIT_ALL instanceof RegistryPorts.DevelopmentOnly);
    }

    @Test
    void aBoundConsentPortIsProductionSafe() {
        assertFalse(namesConsent(RegistryPorts.defaults().withConsent(BOUND).developmentOnlyReasons()));
    }

    @Test
    void aNonDevelopmentStartWithoutConsentIsRefused() {
        RegistryPorts noConsent = RegistryPorts.defaults();
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new RegistryModule(Dialect.POSTGRESQL, noConsent, null, STAGING));
        assertTrue(e.getMessage().contains("consent") && e.getMessage().contains("outside a development environment"),
                e.getMessage());
        IllegalStateException none = assertThrows(IllegalStateException.class,
                () -> new RegistryModule(Dialect.POSTGRESQL, noConsent, null, (EnvironmentPolicy.Resolution) null));
        assertTrue(none.getMessage().contains("without a resolved environment"), none.getMessage());
    }

    @Test
    void productionWinsOverADevelopmentProfile() {
        for (EnvironmentPolicy.Resolution prod : List.of(PRODUCTION, PRODUCTION_WITH_DEV)) {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> new RegistryModule(Dialect.POSTGRESQL, RegistryPorts.defaults(), null, prod));
            assertTrue(e.getMessage().contains("in production") && e.getMessage().contains("consent"), e.getMessage());
        }
    }

    @Test
    void developmentStillStartsWithTheDefaults() {
        assertDoesNotThrow(() -> new RegistryModule(Dialect.POSTGRESQL, RegistryPorts.defaults(), null, DEVELOPMENT));
    }

    /**
     * Decision 143: the deprecated profile-list path no longer invents the switch. A development profile alone (no
     * switch) is refused like every missing switch; with the explicit switch false it is a development installation.
     */
    @Test
    void theDeprecatedProfileListPathFollowsTheSameRules() {
        // the resolution of the path, for each switch (deterministic: no JVM or OS setting involved)
        for (String sw : new String[] {null, " ", "yes"}) {
            assertThrows(EnvironmentPolicy.ConfigurationError.class,
                    () -> RegistryModule.legacy(List.of("test"), sw, "test"), "switch " + sw + " with a test profile");
            assertThrows(EnvironmentPolicy.ConfigurationError.class,
                    () -> RegistryModule.legacy(List.of(), sw, "test"), "switch " + sw + " and no profile");
        }
        assertTrue(RegistryModule.legacy(List.of("test"), "false", "test").developmentDefaultsAllowed());
        assertFalse(RegistryModule.legacy(List.of("staging"), "false", "test").developmentDefaultsAllowed());
        assertTrue(RegistryModule.legacy(List.of("test"), "true", "test").production(), "production wins");
        assertThrows(EnvironmentPolicy.ConfigurationError.class,
                () -> RegistryModule.legacy(List.of("test", "prod"), "false", "test"), "false contradicts prod");
    }

    /** The deprecated constructor itself, in a JVM where the deployment switch is not set (the test default). */
    @Test
    @SuppressWarnings("removal")
    void theDeprecatedConstructorWithoutTheSwitchRefusesEvenADevelopmentProfile() {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("sangam.security.production") == null
                && System.getenv(RegistryModule.SWITCH_ENV) == null, "the deployment switch is set in this JVM");
        for (List<String> profiles : List.of(List.of("test"), List.of("h2"), List.of("staging"), List.of("test", "prod"),
                List.<String>of())) {
            assertThrows(IllegalStateException.class,
                    () -> new RegistryModule(Dialect.POSTGRESQL, RegistryPorts.defaults(), null, profiles),
                    profiles.toString());
        }
    }
}
