// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngineException;
import org.sangam.packs.core.RowEvidence;
import org.sangam.packs.port.PackRuntime;
import org.springframework.mock.env.MockEnvironment;

/**
 * Owner decision 292: the server installs its fail-closed production predicate (decision 143) as the packs' production
 * mode; in production a rule row not permitted for PRODUCTION refuses, outside it demonstration rows stay usable.
 * Port shape agreed with W4-K (board ACK 2026-11-13T07:16:41Z). Written NOT_RUN.
 */
class PacksProductionModeTest {

    private static final Map<String, Object> DEMO_ROW =
            Map.of("evidenceStatus", "UNVERIFIED", "permittedUse", "SYNTHETIC_DEMO_ONLY");

    @AfterEach
    void restoreDefaults() {
        PackRuntime.reset();
    }

    /** What the Helm chart and the server image set. */
    private static MockEnvironment helm() {
        MockEnvironment env = new MockEnvironment().withProperty("sangam.security.production", "true");
        env.setActiveProfiles("postgres", "oidc");
        return env;
    }

    /** The older trigger: a prod profile without the property. */
    private static MockEnvironment prodProfile() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("postgres", "prod");
        return env;
    }

    /** A validated development environment (decision 143: the switch set to false with a development profile). */
    private static MockEnvironment development() {
        MockEnvironment env = new MockEnvironment().withProperty("sangam.security.production", "false");
        env.setActiveProfiles("h2");
        return env;
    }

    private static void install(MockEnvironment env) {
        PackRuntime.install(PacksWiring.withProductionMode(PackRuntime.defaults(), env));
    }

    @Test
    void theDefaultIsNonProduction() {
        assertFalse(PackRuntime.defaults().production());
    }

    @Test
    void productionOnRefusesADemonstrationRow() {
        for (MockEnvironment env : new MockEnvironment[] {helm(), prodProfile()}) {
            install(env);
            assertTrue(PackRuntime.production(), String.valueOf(env));
            ProcessEngineException e = assertThrows(ProcessEngineException.class,
                    () -> RowEvidence.evidenceAndUse(DEMO_ROW, "RULES", "test rules row"));
            assertTrue(e.getMessage().startsWith("RULES_NOT_PERMITTED_IN_PRODUCTION:"), e.getMessage());
            ProcessEngineException p = assertThrows(ProcessEngineException.class,
                    () -> RowEvidence.evidenceAndUse(DEMO_ROW, "POLICY", "test rounding policy row"));
            assertTrue(p.getMessage().startsWith("POLICY_NOT_PERMITTED_IN_PRODUCTION:"), p.getMessage());
        }
    }

    @Test
    void productionOffKeepsDemonstrationRowsUsable() {
        install(development());
        assertFalse(PackRuntime.production());
        assertArrayEquals(new String[] {"UNVERIFIED", "SYNTHETIC_DEMO_ONLY"},
                RowEvidence.evidenceAndUse(DEMO_ROW, "RULES", "test rules row"));
    }

    @Test
    void anUnresolvableProductionSettingCountsAsProduction() {
        // decision 143 fail closed: a missing or invalid switch is treated as production by every guard
        MockEnvironment missing = new MockEnvironment();
        missing.setActiveProfiles("h2");
        MockEnvironment invalid = new MockEnvironment().withProperty("sangam.security.production", "yes");
        invalid.setActiveProfiles("h2");
        for (MockEnvironment env : new MockEnvironment[] {missing, invalid}) {
            install(env);
            assertTrue(PackRuntime.production(), String.valueOf(env));
            assertThrows(ProcessEngineException.class,
                    () -> RowEvidence.evidenceAndUse(DEMO_ROW, "RULES", "test rules row"));
        }
    }
}
