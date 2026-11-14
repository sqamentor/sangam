// SPDX-License-Identifier: Apache-2.0
package org.sangam.quarkus.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.operaton.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.operaton.bpm.quarkus.engine.extension.QuarkusProcessEngineConfiguration;
import org.sangam.assembly.engine.EngineAssembly;
import org.sangam.assembly.host.Capabilities;
import org.sangam.assembly.host.CapabilityMissingException;
import org.sangam.assembly.host.SangamProperties;
import org.sangam.engine.SangamEngineConfigurer;
import org.sangam.engine.SangamProcessEnginePlugin;

/**
 * INT-H2 finding (coordinator 2026-11-13): the Quarkus engine configuration carries {@link SangamProcessEnginePlugin}
 * (Sangam's engine rules), first, applied through {@link SangamEngineConfigurer} as in every other embedding, with the
 * mandatory system audit scope from the Quarkus configuration, the host's own data source and JTA transaction manager,
 * the after-build step, the capability guard and {@code EngineSettingsReapplyPlugin} last. And the bean refuses before
 * any database access while the host lacks a required capability. Written for the test phase (not run).
 */
class SangamEngineConfigurationTest {

    @TempDir
    Path objectStore;

    private JtaFixture jta;

    @BeforeEach
    void start() throws SQLException {
        jta = JtaFixture.start(objectStore);
    }

    @AfterEach
    void stop() {
        jta.close();
    }

    private QuarkusHost host(Map<String, String> config) {
        return new QuarkusHost(SangamProperties.of(config), jta.dataSource, jta.transactionManager,
                new BoundPrincipal());
    }

    @Test
    void theQuarkusEngineConfigurationCarriesSangamsPlugin() {
        QuarkusHost host = host(Map.of());
        QuarkusProcessEngineConfiguration cfg = new QuarkusProcessEngineConfiguration();
        EngineCapabilityGuard guard = new EngineCapabilityGuard(host);
        SangamEngineConfiguration.AfterBuild afterBuild = new SangamEngineConfiguration.AfterBuild(jta.dataSource);
        SangamEngineConfigurer configurer = SangamEngineConfiguration.apply(cfg, host, guard, afterBuild);
        try {
            List<ProcessEnginePlugin> plugins = cfg.getProcessEnginePlugins();
            assertSame(configurer.plugin(), plugins.get(0), "SangamProcessEnginePlugin is first: " + plugins);
            assertEquals(1, plugins.stream().filter(SangamProcessEnginePlugin.class::isInstance).count());
            assertTrue(plugins.contains(guard), "the capability guard is registered");
            assertTrue(plugins.contains(afterBuild), "afterBuild (MySQL patches, adapter) is registered");
            assertEquals("org.sangam.assembly.engine.EngineSettingsReapplyPlugin",
                    plugins.get(plugins.size() - 1).getClass().getName(), "the settings re-apply stays last");
            assertSame(jta.dataSource, cfg.getDataSource(), "the engine holds the host's own data source");
            assertSame(jta.transactionManager, cfg.getTransactionManager());
            assertTrue(guard.sameTransaction(cfg), "the shared-transaction identity check holds");
            assertEquals(QuarkusEngineSettings.DEFAULT_SYSTEM_AUDIT_SCOPE,
                    configurer.components().workTenants().systemAuditScope(), "the mandatory system audit scope");
        } finally {
            configurer.releaseEngineSlot();
        }
    }

    @Test
    void theSystemAuditScopeComesFromTheQuarkusConfiguration() {
        QuarkusHost host = host(Map.of("sangam.engine.system-audit-scope", "system:operations"));
        QuarkusProcessEngineConfiguration cfg = new QuarkusProcessEngineConfiguration();
        SangamEngineConfigurer configurer = SangamEngineConfiguration.apply(cfg, host, new EngineCapabilityGuard(host),
                new SangamEngineConfiguration.AfterBuild(jta.dataSource));
        try {
            assertEquals("system:operations", configurer.components().workTenants().systemAuditScope());
        } finally {
            configurer.releaseEngineSlot();
        }
    }

    @Test
    void theBeanRefusesBeforeAnyDatabaseAccess() {
        DataSource untouchable = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
                    throw new AssertionError("the database was touched: " + method.getName());
                });
        QuarkusHost host = new QuarkusHost(SangamProperties.empty(), untouchable, jta.transactionManager,
                new BoundPrincipal());
        CapabilityMissingException refused = assertThrows(CapabilityMissingException.class,
                () -> new SangamEngineConfiguration(host));
        // phase 1b: the engine area is not the host's to claim; with the defaults its database-free checks pass
        assertTrue(refused.missing().stream().noneMatch(EngineAssembly.ENGINE_AREA::contains), refused.getMessage());
    }

    @Test
    void withoutTheProductSchemaTheBeanRefusesBeforeAnyDatabaseAccess() {
        // decision 287 (b): this host has no schema step of its own, so apply-product-schema=false is refused
        DataSource untouchable = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
                    throw new AssertionError("the database was touched: " + method.getName());
                });
        QuarkusHost host = new QuarkusHost(SangamProperties.of(Map.of("sangam.engine.apply-product-schema", "false")),
                untouchable, jta.transactionManager, new BoundPrincipal());
        CapabilityMissingException refused = assertThrows(CapabilityMissingException.class,
                () -> new SangamEngineConfiguration(host));
        assertTrue(refused.missing().contains(Capabilities.SCHEMA_BEFORE_ENGINE), refused.getMessage());
        assertTrue(Arrays.stream(refused.getSuppressed())
                .anyMatch(t -> String.valueOf(t.getMessage()).contains("apply-product-schema=false")));
    }
}
