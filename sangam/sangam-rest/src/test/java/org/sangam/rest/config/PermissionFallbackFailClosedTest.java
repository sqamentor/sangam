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
 * development is explicit (the key or a dev/h2 profile). Written for the test phase (not run here).
 */
class PermissionFallbackFailClosedTest {

    private final SangamRestAutoConfiguration config = new SangamRestAutoConfiguration();

    static StandardEnvironment env(Map<String, Object> props, String... profiles) {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test", props));
        env.setActiveProfiles(profiles);
        return env;
    }

    @Test
    void theFallbackRefusesTheStartWithoutTheDevelopmentFlag() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> config.sangamPermissionPort(env(Map.of())));
        assertTrue(e.getMessage().contains(SangamRestAutoConfiguration.ALLOW_WITHOUT_SECURITY), e.getMessage());
        assertThrows(IllegalStateException.class, () -> config.sangamPermissionPort(
                env(Map.of(SangamRestAutoConfiguration.ALLOW_WITHOUT_SECURITY, "false"))));
        assertThrows(IllegalStateException.class, () -> config.sangamPermissionPort(env(Map.of(), "prod", "postgres")));
    }

    @Test
    void theFlagOrADevelopmentProfileAllowsItExplicitly() {
        assertInstanceOf(GroupPermissionAdapter.class, config.sangamPermissionPort(
                env(Map.of(SangamRestAutoConfiguration.ALLOW_WITHOUT_SECURITY, "true"))));
        assertInstanceOf(GroupPermissionAdapter.class, config.sangamPermissionPort(env(Map.of(), "dev")));
        assertInstanceOf(GroupPermissionAdapter.class, config.sangamPermissionPort(env(Map.of(), "h2")));
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
