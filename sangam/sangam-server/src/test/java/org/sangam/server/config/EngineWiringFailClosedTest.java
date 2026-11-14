// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.sangam.rest.config.SangamRestAutoConfiguration;
import org.springframework.mock.env.MockEnvironment;

/**
 * Owner decision 75: the engine no longer drops the job identity binding silently when the security module is off;
 * the start is refused unless development is explicit (key, or a development profile, with the switch false; decision
 * 143). Written for the test phase (not run).
 */
class EngineWiringFailClosedTest {

    @Test
    void withTheSecurityModuleTheBindingIsOn() {
        assertTrue(EngineWiring.securityModuleEnabled(new MockEnvironment()));
        assertTrue(EngineWiring.securityModuleEnabled(new MockEnvironment()
                .withProperty("sangam.modules.security.enabled", "true")));
    }

    @Test
    void withoutTheSecurityModuleTheStartIsRefused() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> EngineWiring.securityModuleEnabled(
                new MockEnvironment().withProperty("sangam.modules.security.enabled", "false")));
        assertTrue(e.getMessage().contains(SangamRestAutoConfiguration.ALLOW_WITHOUT_SECURITY), e.getMessage());
    }

    /** Decision 143: an explicit development start states the switch false. */
    @Test
    void anExplicitDevelopmentStartRunsWithoutTheBinding() {
        assertFalse(EngineWiring.securityModuleEnabled(new MockEnvironment()
                .withProperty("sangam.modules.security.enabled", "false")
                .withProperty(org.sangam.core.security.ProductionMode.PROPERTY, "false")
                .withProperty(SangamRestAutoConfiguration.ALLOW_WITHOUT_SECURITY, "true")));
        MockEnvironment dev = new MockEnvironment().withProperty("sangam.modules.security.enabled", "false")
                .withProperty(org.sangam.core.security.ProductionMode.PROPERTY, "false");
        dev.setActiveProfiles("dev");
        assertFalse(EngineWiring.securityModuleEnabled(dev));
    }

    /** Decisions 143 and 198: without the switch, or with true next to a development profile, it is refused. */
    @Test
    void aMissingSwitchOrProductionOverADevelopmentProfileIsRefused() {
        MockEnvironment noSwitch = new MockEnvironment().withProperty("sangam.modules.security.enabled", "false")
                .withProperty(SangamRestAutoConfiguration.ALLOW_WITHOUT_SECURITY, "true");
        noSwitch.setActiveProfiles("dev");
        assertThrows(IllegalStateException.class, () -> EngineWiring.securityModuleEnabled(noSwitch));
        MockEnvironment wins = new MockEnvironment().withProperty("sangam.modules.security.enabled", "false")
                .withProperty(SangamRestAutoConfiguration.ALLOW_WITHOUT_SECURITY, "true")
                .withProperty(org.sangam.core.security.ProductionMode.PROPERTY, "true");
        wins.setActiveProfiles("h2");
        assertThrows(IllegalStateException.class, () -> EngineWiring.securityModuleEnabled(wins));
    }

    /** Decision 92: a production start is refused even with the development key. */
    @Test
    void productionRefusesEvenWithTheDevelopmentKey() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> EngineWiring.securityModuleEnabled(
                new MockEnvironment().withProperty("sangam.modules.security.enabled", "false")
                        .withProperty(SangamRestAutoConfiguration.ALLOW_WITHOUT_SECURITY, "true")
                        .withProperty(org.sangam.core.security.ProductionMode.PROPERTY, "true")));
        assertTrue(e.getMessage().contains("production"), e.getMessage());
        MockEnvironment prod = new MockEnvironment().withProperty("sangam.modules.security.enabled", "false")
                .withProperty(SangamRestAutoConfiguration.ALLOW_WITHOUT_SECURITY, "true");
        prod.setActiveProfiles("prod", "postgres");
        assertThrows(IllegalStateException.class, () -> EngineWiring.securityModuleEnabled(prod));
    }
}
