// SPDX-License-Identifier: Apache-2.0
package org.sangam.quarkus.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.operaton.bpm.engine.impl.cfg.JakartaTransactionProcessEngineConfiguration;
import org.operaton.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.sangam.assembly.engine.EngineAssembly;
import org.sangam.assembly.host.Capabilities;
import org.sangam.assembly.host.Capability;
import org.sangam.assembly.host.CapabilityMissingException;
import org.sangam.assembly.host.SangamProperties;
import org.sangam.assembly.host.SangamRuntime;

/**
 * What the Quarkus host supplies and what it refuses (M-143, decision 186), without booting Quarkus: exactly the five
 * seams, every other required capability CAPABILITY_MISSING at start-up and already at the engine build, and the
 * shared-transaction seam withdrawn when the data source is not JTA-integrated or the engine is pointed elsewhere.
 * Written for the test phase (not run).
 */
class QuarkusHostTest {

    /** The required capabilities this host supplies through its seams. */
    static final Set<Capability> SEAMS = Set.of(Capabilities.CONFIG_SOURCE, Capabilities.LIFECYCLE,
            Capabilities.SCHEDULING, Capabilities.SECURITY_CONTEXT, Capabilities.SHARED_TRANSACTION);

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

    /** A SangamProcessEnginePlugin as SangamEngineConfigurer builds it (the guard requires one in the list). */
    static org.sangam.engine.SangamProcessEnginePlugin sangamPlugin() {
        org.sangam.engine.SangamEngineSettings settings = new org.sangam.engine.SangamEngineSettings();
        settings.setSystemAuditScope(QuarkusEngineSettings.DEFAULT_SYSTEM_AUDIT_SCOPE);
        return new org.sangam.engine.SangamProcessEnginePlugin(new org.sangam.engine.SangamEngineComponents(settings,
                org.sangam.engine.db.EngineDialect.H2));
    }

    @Test
    void anEngineWithoutSangamsPluginIsRefused() {
        QuarkusHost host = host(Map.of());
        EngineCapabilityGuard guard = new EngineCapabilityGuard(host);
        JakartaTransactionProcessEngineConfiguration config = new JakartaTransactionProcessEngineConfiguration();
        config.setProcessEnginePlugins(new ArrayList<>(List.of(guard)));
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> guard.preInit(config));
        assertEquals(EngineCapabilityGuard.NO_SANGAM_PLUGIN, refused.getMessage());
    }

    @Test
    void theHostGapsAreRefusedBeforeAnythingElse() {
        CapabilityMissingException refused = assertThrows(CapabilityMissingException.class,
                () -> new EngineCapabilityGuard(host(Map.of())).refuseHostGaps());
        List<Capability> expected = new ArrayList<>(Capabilities.required());
        expected.removeAll(SEAMS);
        expected.removeAll(EngineAssembly.ENGINE_AREA); // phase 1b: the engine step checks those itself
        assertEquals(Set.copyOf(expected), Set.copyOf(refused.missing()));
    }

    private QuarkusHost host(Map<String, String> config) {
        return new QuarkusHost(SangamProperties.of(config), jta.dataSource, jta.transactionManager,
                new BoundPrincipal());
    }

    @Test
    void suppliesExactlyTheFiveSeams() {
        Set<Capability> supplied = host(Map.of()).seams().capabilities();
        for (Capability c : SEAMS) {
            assertTrue(supplied.contains(c), c.id());
        }
        List<Capability> requiredSupplied = supplied.stream().filter(Capability::required).toList();
        assertEquals(SEAMS, Set.copyOf(requiredSupplied), "no required capability is claimed beyond the seams");
        assertFalse(supplied.contains(Capabilities.DEFAULTS_SOURCE), "no sangam-defaults.yaml source on Quarkus");
    }

    @Test
    void startRefusesWithCapabilityMissingNamingEveryOtherRequiredCapability() {
        QuarkusHost host = host(Map.of());
        CapabilityMissingException refused = assertThrows(CapabilityMissingException.class, host.runtime()::start);
        List<Capability> expected = new ArrayList<>(Capabilities.required());
        expected.removeAll(SEAMS);
        expected.removeAll(EngineAssembly.ENGINE_AREA);
        assertEquals(Set.copyOf(expected), Set.copyOf(refused.missing()));
        assertTrue(refused.getMessage().startsWith(CapabilityMissingException.CODE), refused.getMessage());
        assertEquals(SangamRuntime.State.FAILED, host.runtime().state());
        host.runtime().stop(java.time.Duration.ZERO); // the ShutdownEvent path after a refused start
    }

    @Test
    void sharedTransactionIsWithdrawnWithoutJtaOrWithAnotherEngineDataSource() {
        assertTrue(QuarkusHost.sharesEngineConnection(SangamProperties.of(Map.of())));
        assertTrue(QuarkusHost.sharesEngineConnection(SangamProperties.of(Map.of(
                QuarkusHost.DATASOURCE_TRANSACTIONS, "xa", QuarkusHost.OPERATON_DATASOURCE, "<default>"))));
        assertFalse(QuarkusHost.sharesEngineConnection(SangamProperties.of(Map.of(
                QuarkusHost.DATASOURCE_TRANSACTIONS, "disabled"))));
        assertFalse(QuarkusHost.sharesEngineConnection(SangamProperties.of(Map.of(
                QuarkusHost.OPERATON_DATASOURCE, "engine"))));
        assertFalse(host(Map.of(QuarkusHost.DATASOURCE_TRANSACTIONS, "disabled")).seams().capabilities()
                .contains(Capabilities.SHARED_TRANSACTION));
    }

    @Test
    void theEngineBuildIsRefusedBeforeTheEngineExists() {
        QuarkusHost host = host(Map.of());
        EngineCapabilityGuard guard = new EngineCapabilityGuard(host);
        JakartaTransactionProcessEngineConfiguration config = new JakartaTransactionProcessEngineConfiguration();
        config.setTransactionManager(host.transactions().transactionManager());
        config.setDataSource(host.transactions().dataSource());
        List<ProcessEnginePlugin> plugins = new ArrayList<>(List.of(sangamPlugin(), guard));
        config.setProcessEnginePlugins(plugins);
        // security module on (default) and no tenant security plugin: one refusal names the engine's and the host's
        // missing capabilities together, sorted by id; the engine area's checks did not run, so they count as missing,
        // except the job leases, which nothing needs while executor partitioning is off (decision 286)
        CapabilityMissingException engine = assertThrows(CapabilityMissingException.class, () -> guard.preInit(config));
        List<Capability> expected = new ArrayList<>(Capabilities.required());
        expected.removeAll(SEAMS);
        expected.remove(Capabilities.JOB_LEASES);
        expected.sort(java.util.Comparator.comparing(Capability::id));
        assertEquals(expected, engine.missing());
        assertTrue(engine.missing().contains(Capabilities.TENANT_SECURITY_PLUGIN));

        QuarkusHost noSecurity = host(Map.of("sangam.modules.security.enabled", "false"));
        EngineCapabilityGuard guard2 = new EngineCapabilityGuard(noSecurity);
        JakartaTransactionProcessEngineConfiguration config2 = new JakartaTransactionProcessEngineConfiguration();
        config2.setTransactionManager(noSecurity.transactions().transactionManager());
        config2.setDataSource(noSecurity.transactions().dataSource());
        config2.setProcessEnginePlugins(new ArrayList<>(List.of(sangamPlugin(), guard2)));
        // the engine's own needs are met, the host's are not: still refused, before the engine is built
        CapabilityMissingException host2 = assertThrows(CapabilityMissingException.class, () -> guard2.preInit(config2));
        assertTrue(host2.missing().contains(Capabilities.AUDIT_CHAIN), host2.getMessage());
    }

    @Test
    void theJobLeasesAreNeededOnlyWhileExecutorPartitioningIsOn() {
        // decision 286, both cases on this host: the default (off) never needs them, on needs them
        EngineCapabilityGuard off = new EngineCapabilityGuard(host(Map.of()));
        assertFalse(off.engineChecks().needed().contains(Capabilities.JOB_LEASES));
        EngineCapabilityGuard on = new EngineCapabilityGuard(host(Map.of("sangam.engine.executor-partitioning", "true")));
        assertTrue(on.engineChecks().needed().contains(Capabilities.JOB_LEASES));
        assertFalse(QuarkusHost.hostChecked().contains(Capabilities.JOB_LEASES), "the engine step checks it, not the host");
    }

    @Test
    void theEngineChecksThatRanCountAtPreInit() {
        QuarkusHost host = host(Map.of("sangam.modules.security.enabled", "false"));
        EngineCapabilityGuard guard = new EngineCapabilityGuard(host);
        guard.engineChecked(EngineAssembly.checkBeforeDatabase(host.engineSettings(), false));
        guard.engineChecked(EngineAssembly.checkDatabase(host.engineSettings(), host.transactions().dataSource()));
        JakartaTransactionProcessEngineConfiguration config = new JakartaTransactionProcessEngineConfiguration();
        config.setTransactionManager(host.transactions().transactionManager());
        config.setDataSource(host.transactions().dataSource());
        config.setProcessEnginePlugins(new ArrayList<>(List.of(sangamPlugin(), guard)));
        CapabilityMissingException refused = assertThrows(CapabilityMissingException.class, () -> guard.preInit(config));
        // only the host's later phases are missing now (audit chain, authorization, ...), no engine-area capability
        assertTrue(refused.missing().stream().noneMatch(EngineAssembly.ENGINE_AREA::contains), refused.getMessage());
    }

    @Test
    void anEngineOnAnotherTransactionManagerLacksTheSharedTransaction() {
        QuarkusHost host = host(Map.of("sangam.modules.security.enabled", "false"));
        EngineCapabilityGuard guard = new EngineCapabilityGuard(host);
        JakartaTransactionProcessEngineConfiguration config = new JakartaTransactionProcessEngineConfiguration();
        config.setDataSource(host.transactions().dataSource());
        config.setProcessEnginePlugins(new ArrayList<>(List.of(sangamPlugin(), guard)));
        CapabilityMissingException refused = assertThrows(CapabilityMissingException.class, () -> guard.preInit(config));
        assertTrue(refused.missing().contains(Capabilities.SHARED_TRANSACTION), refused.getMessage());
        assertTrue(refused.getMessage().contains(Capabilities.SHARED_TRANSACTION.id() + " ("
                + Capabilities.SHARED_TRANSACTION.whenMissing() + ")"), "the refusal names the withdrawn seam");
    }
}
