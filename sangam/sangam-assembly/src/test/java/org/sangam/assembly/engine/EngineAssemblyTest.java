// SPDX-License-Identifier: Apache-2.0
package org.sangam.assembly.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.operaton.bpm.engine.impl.cfg.CompositeProcessEnginePlugin;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.sangam.assembly.host.Capabilities;
import org.sangam.assembly.host.CapabilityMissingException;
import org.sangam.assembly.host.SangamProperties;
import org.sangam.engine.SangamEngineSettings;

/**
 * M-141b DoD: the plugin list and order the Spring customizer produced before, now from host-neutral code; the
 * settings read once; a missing required capability refuses the engine start. Written for the test phase (not run).
 */
class EngineAssemblyTest {

    static class HostDefault extends AbstractProcessEnginePlugin {
    }

    static class First extends AbstractProcessEnginePlugin {
    }

    static class Second extends AbstractProcessEnginePlugin {
    }

    static class Other extends AbstractProcessEnginePlugin {
    }

    /** Stands in for this engine's SangamProcessEnginePlugin (matched by identity). */
    static class SangamStandIn extends AbstractProcessEnginePlugin {
    }

    static final List<String> ORDER = List.of(First.class.getName(), Second.class.getName());

    @Test
    void thePluginOrderIsTheOneTheSpringCustomizerUsed() {
        // EngineConfigurationCustomizer.ORDER at checkpoint 928c3e9e, unchanged
        assertEquals(List.of(
                "org.sangam.security.assignment.SangamSecurityEnginePlugin",
                "org.sangam.integration.bridge.operaton.SangamIntegrationEnginePlugin",
                "org.sangam.ports.SangamPortsPlugin",
                "org.sangam.casefile.cmmn.CaseFileEnginePlugin",
                "org.sangam.geo.feel.GeoEnginePlugin",
                "org.sangam.server.bridge.CompliancePurposePlugin",
                "org.sangam.server.bridge.ConfigCaseBindingPlugin",
                "org.sangam.server.bridge.OpsTaskListenerPlugin"), EngineAssembly.PLUGIN_ORDER);
        assertEquals(EngineAssembly.SECURITY_PLUGIN, EngineAssembly.PLUGIN_ORDER.get(0));
    }

    @Test
    void hostDefaultsFirstThenSangamThenTheOrderThenOthersThenTheReapplyStep() {
        HostDefault hostDefault = new HostDefault();
        Other inComposite = new Other();
        Second second = new Second();
        First first = new First();
        Other loose = new Other();
        ProcessEnginePlugin sangam = new SangamStandIn();
        CompositeProcessEnginePlugin composite = new CompositeProcessEnginePlugin(List.of(hostDefault, inComposite));
        List<ProcessEnginePlugin> current = List.of(composite, second, loose, sangam, second);

        List<ProcessEnginePlugin> out = EngineAssembly.orderPlugins(ORDER, current, sangam,
                name -> name.equals(HostDefault.class.getName()),
                name -> name.equals(First.class.getName()) ? List.of(first) : List.of(), "postgres");

        assertEquals(7, out.size(), out.toString());
        CompositeProcessEnginePlugin host = (CompositeProcessEnginePlugin) out.get(0);
        assertEquals(List.of(hostDefault), host.getPlugins());
        assertSame(sangam, out.get(1));
        assertSame(first, out.get(2), "a registered plugin the composite did not collect is added by type");
        assertSame(second, out.get(3), "listed once although it came twice");
        assertSame(inComposite, out.get(4), "a non-default plugin of the host composite is an other plugin");
        assertSame(loose, out.get(5));
        assertTrue(out.get(6) instanceof EngineSettingsReapplyPlugin r && "postgres".equals(r.databaseType()));
    }

    @Test
    void othersKeepTheirIncomingOrderAndNoHostCompositeWhenThereAreNoHostDefaults() {
        Other a = new Other();
        Other b = new Other();
        ProcessEnginePlugin sangam = new SangamStandIn();
        List<ProcessEnginePlugin> out = EngineAssembly.orderPlugins(ORDER, List.of(a, b), sangam, n -> false,
                n -> List.of(), null);
        assertEquals(4, out.size());
        assertSame(sangam, out.get(0));
        assertSame(a, out.get(1));
        assertSame(b, out.get(2));
        assertTrue(out.get(3) instanceof EngineSettingsReapplyPlugin);
        assertEquals("SangamEngineSettingsReapply[null]", out.get(3).toString());
    }

    @Test
    void aNonDefaultPluginInsideTheHostCompositeIsAnOtherPlugin() {
        Other inside = new Other();
        ProcessEnginePlugin sangam = new SangamStandIn();
        List<ProcessEnginePlugin> out = EngineAssembly.orderPlugins(ORDER,
                List.of(new CompositeProcessEnginePlugin(List.of(inside))), sangam, n -> false, n -> List.of(), "h2");
        assertEquals(List.of(sangam, inside), out.subList(0, 2));
    }

    @Test
    void theReapplyStepPinsTheDatabaseTypeAndKeepsOperatonAuthorizationOff() {
        ProcessEngineConfigurationImpl cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setDatabaseType("mysql");
        cfg.setAuthorizationEnabled(true);
        new EngineSettingsReapplyPlugin("postgres").preInit(cfg);
        assertEquals("postgres", cfg.getDatabaseType());
        assertFalse(cfg.isAuthorizationEnabled());
        new EngineSettingsReapplyPlugin(null).preInit(cfg);
        assertEquals("postgres", cfg.getDatabaseType(), "no detected type: left as it is");
    }

    @Test
    void settingsAreReadWithTheDefaultsTheSpringWiringHad() {
        SangamEngineSettings s = new SangamEngineSettings();
        EngineAssembly.applyConfiguration(s, SangamProperties.empty(), List.of());
        assertTrue(s.isJobExecutorActivate());
        assertTrue(s.isRequireSqlServerRcsi());
        assertTrue(s.isCmmnEventListenersEnabled());
        assertEquals("system:platform", s.getSystemAuditScope(), "decision 144: the hosts' default system audit scope");

        SangamEngineSettings off = new SangamEngineSettings();
        EngineAssembly.applyConfiguration(off, SangamProperties.of(Map.of(
                "operaton.bpm.job-execution.enabled", "false", "sangam.db.require-rcsi", "false",
                "sangam.ports.cmmn-event-listeners", "false")), List.of("h2"));
        assertFalse(off.isJobExecutorActivate());
        assertFalse(off.isRequireSqlServerRcsi());
        assertFalse(off.isCmmnEventListenersEnabled());
    }

    /** Decision 144: hosts other than the Spring server get the system audit scope from the same key. */
    @Test
    void theSystemAuditScopeIsConfigurableWithThePlatformDefault() {
        SangamEngineSettings s = new SangamEngineSettings();
        EngineAssembly.applyConfiguration(s, SangamProperties.of(Map.of(EngineAssembly.SYSTEM_AUDIT_SCOPE_PROPERTY,
                " system:cell-a ")), List.of());
        assertEquals("system:cell-a", s.getSystemAuditScope());
        assertEquals("system:platform", EngineAssembly.systemAuditScope(SangamProperties.of(Map.of(
                EngineAssembly.SYSTEM_AUDIT_SCOPE_PROPERTY, " "))), "blank: the default");
        // the value is not validated here: the engine start refuses an invalid one (SystemAuditScope.validate)
        SangamEngineSettings bad = new SangamEngineSettings();
        EngineAssembly.applyConfiguration(bad, SangamProperties.of(Map.of(EngineAssembly.SYSTEM_AUDIT_SCOPE_PROPERTY,
                "platform")), List.of());
        assertEquals("platform", bad.getSystemAuditScope());
    }

    @Test
    void testModeIsOffByDefaultAndRefusedInProduction() {
        assertFalse(EngineAssembly.testMode(SangamProperties.empty(), List.of()));
        SangamProperties on = SangamProperties.of(Map.of(EngineAssembly.TEST_MODE_PROPERTY, "true",
                "sangam.security.production", "false"));
        assertTrue(EngineAssembly.testMode(on, List.of("h2")));
        assertThrows(IllegalStateException.class, () -> EngineAssembly.testMode(on, List.of("prod")));
        assertThrows(IllegalStateException.class, () -> EngineAssembly.testMode(SangamProperties.of(Map.of(
                EngineAssembly.TEST_MODE_PROPERTY, "true", "sangam.security.production", "true")), List.of()));
    }

    /** Decisions 143 and 198 through the shared resolver: fail closed on the switch, production wins over h2. */
    @Test
    void testModeFollowsTheSharedEnvironmentResolution() {
        // the switch missing or invalid counts as production (decision 143)
        SangamProperties noSwitch = SangamProperties.of(Map.of(EngineAssembly.TEST_MODE_PROPERTY, "true"));
        assertThrows(IllegalStateException.class, () -> EngineAssembly.testMode(noSwitch, List.of("h2")));
        assertThrows(IllegalStateException.class, () -> EngineAssembly.testMode(SangamProperties.of(Map.of(
                EngineAssembly.TEST_MODE_PROPERTY, "true", "sangam.security.production", "maybe")), List.of("dev")));
        // true with a development profile is production (decision 198)
        IllegalStateException wins = assertThrows(IllegalStateException.class, () -> EngineAssembly.testMode(
                SangamProperties.of(Map.of(EngineAssembly.TEST_MODE_PROPERTY, "true",
                        "sangam.security.production", "true")), List.of("h2", "dev")));
        assertTrue(wins.getMessage().contains("decision 143"), wins.getMessage());
        // false with a production profile is contradictory, so refused as well
        assertThrows(IllegalStateException.class, () -> EngineAssembly.testMode(SangamProperties.of(Map.of(
                EngineAssembly.TEST_MODE_PROPERTY, "true", "sangam.security.production", "false")), List.of("prod-eu")));
        // explicit false outside production: allowed (staging-like NON_PRODUCTION and development)
        SangamProperties staging = SangamProperties.of(Map.of(EngineAssembly.TEST_MODE_PROPERTY, "true",
                "sangam.security.production", "FALSE"));
        assertTrue(EngineAssembly.testMode(staging, List.of("postgres")));
        assertTrue(EngineAssembly.testMode(staging, List.of("test")));
        // off: no environment question asked, so no switch needed
        assertFalse(EngineAssembly.testMode(SangamProperties.empty(), List.of("prod")));
    }

    @Test
    void modulePortsAreHandedToTheEngineAndCalendarsDoNotReplaceConfiguredOnes() {
        SangamEngineSettings s = new SangamEngineSettings();
        EngineAssembly.applyModulePorts(s, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Map.of());
        assertTrue(s.getWorkCalendars().isEmpty());
    }

    @Test
    void theSecurityModuleNeedsItsEnginePluginInTheFinalList() {
        assertTrue(EngineAssembly.securityModuleOn(SangamProperties.empty()));
        assertFalse(EngineAssembly.securityModuleOn(SangamProperties.of(Map.of("sangam.modules.security.enabled",
                "false"))));
        List<org.sangam.assembly.host.Capability> host = List.of(Capabilities.CONFIG_SOURCE,
                Capabilities.SHARED_TRANSACTION);
        CapabilityMissingException e = assertThrows(CapabilityMissingException.class,
                () -> EngineAssembly.requireCapabilities(true, List.of(new Other()), host));
        assertEquals(List.of(Capabilities.TENANT_SECURITY_PLUGIN), e.missing());
        // development without the security module (decision 75 allowed it): nothing missing
        EngineAssembly.requireCapabilities(false, List.of(new Other()), host);
        // P-1 is never optional
        CapabilityMissingException p1 = assertThrows(CapabilityMissingException.class,
                () -> EngineAssembly.requireCapabilities(false, List.of(), List.of(Capabilities.CONFIG_SOURCE)));
        assertEquals(List.of(Capabilities.SHARED_TRANSACTION), p1.missing());
    }
}
