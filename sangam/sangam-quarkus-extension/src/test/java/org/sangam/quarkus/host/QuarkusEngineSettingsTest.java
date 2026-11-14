// SPDX-License-Identifier: Apache-2.0
package org.sangam.quarkus.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sangam.assembly.host.SangamProperties;
import org.sangam.engine.SangamEngineSettings;

/**
 * The Quarkus host's engine settings: the system audit scope (default {@code system:platform}, decision 144), the
 * product schema switch (decision 287 b) and executor partitioning (decision 286), the host-neutral settings of
 * EngineAssembly, and a refusal for every {@code sangam.engine.*} key it does not bind.
 * Written for the test phase (not run).
 */
class QuarkusEngineSettingsTest {

    @Test
    void theSystemAuditScopeDefaultsToThePlatformScope() {
        SangamEngineSettings s = QuarkusEngineSettings.from(SangamProperties.empty(), List.of(), List.of());
        assertEquals("system:platform", s.getSystemAuditScope());
        assertTrue(s.isJobExecutorActivate());
    }

    @Test
    void theHostNeutralSettingsAreRead() {
        Map<String, String> config = Map.of("sangam.engine.system-audit-scope", "system:ops",
                "operaton.bpm.job-execution.enabled", "false");
        SangamEngineSettings s = QuarkusEngineSettings.from(SangamProperties.of(config), config.keySet(), List.of());
        assertEquals("system:ops", s.getSystemAuditScope());
        assertFalse(s.isJobExecutorActivate());
    }

    @Test
    void anUnboundEngineSettingIsRefusedNotIgnored() {
        Map<String, String> config = Map.of("sangam.engine.history", "audit", "sangam.engine.executor-slots", "8");
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> QuarkusEngineSettings.from(SangamProperties.of(config), config.keySet(), List.of()));
        assertTrue(refused.getMessage().contains("sangam.engine.executor-slots"), refused.getMessage());
        assertEquals(Set.of("sangam.engine.executor-slots", "sangam.engine.history"),
                QuarkusEngineSettings.unsupported(config.keySet()));
    }

    @Test
    void anUnboundEnvironmentFormIsRefusedByName() {
        // as SmallRye's environment source lists them: the variable names themselves
        List<String> names = List.of("SANGAM_ENGINE_HISTORY", "sangam_engine_executor_slots", "PATH", "SANGAM_DB_URL",
                "SANGAM_ENGINE_SYSTEM_AUDIT_SCOPE");
        assertEquals(Set.of("SANGAM_ENGINE_HISTORY", "sangam_engine_executor_slots"),
                QuarkusEngineSettings.unsupported(names));
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> QuarkusEngineSettings.from(SangamProperties.empty(), names, List.of()));
        assertTrue(refused.getMessage().contains("SANGAM_ENGINE_HISTORY"), refused.getMessage());
    }

    @Test
    void theSupportedKeyIsAcceptedInEveryEnvironmentForm() {
        assertEquals("SANGAM_ENGINE_SYSTEM_AUDIT_SCOPE",
                QuarkusEngineSettings.environmentForm("sangam.engine.system-audit-scope"));
        assertEquals(Set.of(), QuarkusEngineSettings.unsupported(List.of("SANGAM_ENGINE_SYSTEM_AUDIT_SCOPE",
                "sangam_engine_system_audit_scope", "sangam.engine.system-audit-scope")));
    }

    @Test
    void theProductSchemaAndExecutorPartitioningKeepTheirDefaultsUnlessSet() {
        // decisions 286 and 287 (b): default behaviour unchanged (schema applied, partitioning off)
        SangamEngineSettings defaults = QuarkusEngineSettings.from(SangamProperties.empty(), List.of(), List.of());
        assertTrue(defaults.isApplyProductSchema());
        assertFalse(defaults.isExecutorPartitioning());
        Map<String, String> config = Map.of("sangam.engine.apply-product-schema", "false",
                "sangam.engine.executor-partitioning", "true");
        SangamEngineSettings set = QuarkusEngineSettings.from(SangamProperties.of(config), config.keySet(), List.of());
        assertFalse(set.isApplyProductSchema());
        assertTrue(set.isExecutorPartitioning());
        assertEquals(Set.of(), QuarkusEngineSettings.unsupported(List.of("SANGAM_ENGINE_APPLY_PRODUCT_SCHEMA",
                "SANGAM_ENGINE_EXECUTOR_PARTITIONING")));
    }

    @Test
    void testModeIsRefusedInProduction() {
        Map<String, String> config = Map.of("sangam.test-mode.enabled", "true");
        assertThrows(IllegalStateException.class,
                () -> QuarkusEngineSettings.from(SangamProperties.of(config), config.keySet(), List.of("prod")));
    }
}
