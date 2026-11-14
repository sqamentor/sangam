// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.config;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.rest.adapter.memory.GroupPermissionAdapter;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * Owner decision 75: without sangam-security the group-only permission fallback refuses the start-up unless
 * development is explicit (the key, or a development profile with the switch false). Decisions 143 and 198: production
 * is the shared EnvironmentPolicy resolution (a missing or invalid switch counts as production; true wins over a
 * development profile). Written for the test phase (not run here).
 */
class PermissionFallbackFailClosedTest {

    private final SangamRestAutoConfiguration config = new SangamRestAutoConfiguration();

    static StandardEnvironment env(Map<String, Object> props, String... profiles) {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test", props));
        env.setActiveProfiles(profiles);
        return env;
    }

    /** Decision 143: every installation states its mode; these cases are explicit non-production starts. */
    static final String SWITCH = org.sangam.core.security.ProductionMode.PROPERTY;

    @Test
    void theFallbackRefusesTheStartWithoutTheDevelopmentFlag() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> config.sangamPermissionPort(env(Map.of(SWITCH, "false"))));
        assertTrue(e.getMessage().contains(SangamRestAutoConfiguration.ALLOW_WITHOUT_SECURITY), e.getMessage());
        assertThrows(IllegalStateException.class, () -> config.sangamPermissionPort(
                env(Map.of(SangamRestAutoConfiguration.ALLOW_WITHOUT_SECURITY, "false", SWITCH, "false"))));
        assertThrows(IllegalStateException.class, () -> config.sangamPermissionPort(env(Map.of(), "prod", "postgres")));
        // a database profile with false is NON_PRODUCTION, not development
        assertThrows(IllegalStateException.class,
                () -> config.sangamPermissionPort(env(Map.of(SWITCH, "false"), "postgres")));
    }

    @Test
    void theFlagOrADevelopmentProfileAllowsItExplicitly() {
        assertInstanceOf(GroupPermissionAdapter.class, config.sangamPermissionPort(
                env(Map.of(SangamRestAutoConfiguration.ALLOW_WITHOUT_SECURITY, "true", SWITCH, "false"))));
        assertInstanceOf(GroupPermissionAdapter.class, config.sangamPermissionPort(env(Map.of(SWITCH, "false"), "dev")));
        assertInstanceOf(GroupPermissionAdapter.class, config.sangamPermissionPort(env(Map.of(SWITCH, "false"), "h2")));
        // decision 143: the platform convention, shared with the server, also knows test and local
        assertInstanceOf(GroupPermissionAdapter.class,
                config.sangamPermissionPort(env(Map.of(SWITCH, "false"), "test", "postgres")));
        assertInstanceOf(GroupPermissionAdapter.class, config.sangamPermissionPort(env(Map.of(SWITCH, "false"), "local")));
    }

    /** Decision 143: a missing or invalid switch counts as production here too (fail closed), flag or not. */
    @Test
    void aMissingOrInvalidSwitchRefusesEvenWithTheFlagOrADevelopmentProfile() {
        String flag = SangamRestAutoConfiguration.ALLOW_WITHOUT_SECURITY;
        IllegalStateException missing = assertThrows(IllegalStateException.class,
                () -> config.sangamPermissionPort(env(Map.of(flag, "true"), "dev")));
        assertTrue(missing.getMessage().contains("production") && missing.getMessage().contains("decision 143"),
                missing.getMessage());
        assertThrows(IllegalStateException.class, () -> config.sangamPermissionPort(env(Map.of(), "h2")));
        assertThrows(IllegalStateException.class,
                () -> config.sangamPermissionPort(env(Map.of(flag, "true", SWITCH, "yes-please"), "dev")));
    }

    /** Decision 198: the switch true wins over a development profile. */
    @Test
    void productionWinsOverADevelopmentProfile() {
        String flag = SangamRestAutoConfiguration.ALLOW_WITHOUT_SECURITY;
        assertThrows(IllegalStateException.class,
                () -> config.sangamPermissionPort(env(Map.of(flag, "true", SWITCH, "true"), "h2", "dev")));
        assertThrows(IllegalStateException.class,
                () -> config.sangamPermissionPort(env(Map.of(flag, "true", SWITCH, " TRUE "), "local")));
    }

    /** Decision 92: production refuses even with the flag, by the property or by a prod profile. */
    @Test
    void productionRefusesEvenWithTheFlagOrADevelopmentProfile() {
        String flag = SangamRestAutoConfiguration.ALLOW_WITHOUT_SECURITY;
        String production = org.sangam.core.security.ProductionMode.PROPERTY;
        IllegalStateException byProperty = assertThrows(IllegalStateException.class, () -> config.sangamPermissionPort(
                env(Map.of(flag, "true", production, "true"))));
        assertTrue(byProperty.getMessage().contains("production"), byProperty.getMessage());
        assertThrows(IllegalStateException.class, () -> config.sangamPermissionPort(env(Map.of(flag, "true"), "prod")));
        assertThrows(IllegalStateException.class,
                () -> config.sangamPermissionPort(env(Map.of(flag, "true"), "PROD-eu", "postgres")));
        assertThrows(IllegalStateException.class,
                () -> config.sangamPermissionPort(env(Map.of(production, "true"), "dev")));
        // the explicit production=false with the flag is still a development start
        assertInstanceOf(GroupPermissionAdapter.class, config.sangamPermissionPort(
                env(Map.of(flag, "true", production, "false"))));
    }
}
