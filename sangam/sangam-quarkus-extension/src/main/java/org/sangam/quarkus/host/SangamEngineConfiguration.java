// SPDX-License-Identifier: Apache-2.0
package org.sangam.quarkus.host;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

import javax.sql.DataSource;

import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.operaton.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.operaton.bpm.quarkus.engine.extension.QuarkusProcessEngineConfiguration;
import org.sangam.assembly.engine.EngineAssembly;
import org.sangam.core.time.EngineTimeZone;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.SangamEngineConfigurer;

/**
 * The engine configuration of the Quarkus host. The Operaton Quarkus extension builds its engine from the
 * {@link QuarkusProcessEngineConfiguration} bean and keeps a data source and a transaction manager that are already
 * set. This bean:
 *
 * <ol>
 *   <li>refuses first, before any database access, outside UTC (the core gate), and in one refusal when the host lacks
 *       a required capability or an engine check that needs no database fails (extraction phase 1b:
 *       {@link EngineAssembly#checkBeforeDatabase}: Jackson line, product schema step, job leases while partitioning
 *       is on; this host has no schema step of its own, so {@code sangam.engine.apply-product-schema=false} is
 *       refused, decision 287 b);</li>
 *   <li>then runs the database checks on the very data source the engine is handed
 *       ({@link EngineAssembly#checkDatabase}, decision 287 c), refused with the database guard's own message;</li>
 *   <li>applies Sangam exactly as every other embedding does, through {@link SangamEngineConfigurer}: the UTC gate,
 *       the database checks and the product schema ({@code prepare}), the engine options and
 *       {@code SangamProcessEnginePlugin} first in the plugin list ({@code configure}), then
 *       {@link EngineAssembly#orderPlugins} with {@code EngineSettingsReapplyPlugin} last;</li>
 *   <li>hands the engine the very data source and JTA transaction manager the host's shared-transaction seam holds
 *       ({@link QuarkusHost#transactions()}), so engine commands and product rows run in one transaction;</li>
 *   <li>runs {@code SangamEngineConfigurer.afterBuild} once the engine is built (MySQL engine patches, history
 *       partitioning, the engine adapter) and frees the engine slot on shutdown;</li>
 *   <li>puts {@link EngineCapabilityGuard} in the plugin list, which refuses an engine without the Sangam plugin and
 *       re-checks every capability on the final configuration;</li>
 *   <li>puts {@link QuarkusHost#startOrder()} in the plugin list (owner decision 376): the engine is built with the job
 *       executor off, the services that need the engine are assembled once it is built, and the job executor starts
 *       last, as the runtime's last component, after every capability check passed.</li>
 * </ol>
 */
@Singleton
public class SangamEngineConfiguration extends QuarkusProcessEngineConfiguration {

    private final transient SangamEngineConfigurer configurer;
    private final transient AfterBuild afterBuild;

    @Inject
    public SangamEngineConfiguration(QuarkusHost host) {
        // the core UTC gate (SL-53) first, as SangamEngineConfigurer's constructor runs it: a JVM outside UTC is refused
        // for that reason, whatever else is missing; like the host-gap refusal it touches no database
        EngineTimeZone.requireUtcJvm();
        EngineCapabilityGuard guard = new EngineCapabilityGuard(host);
        guard.refuseHostGaps(EngineAssembly.checkBeforeDatabase(host.engineSettings(), false));
        EngineAssembly.EngineChecks database = EngineAssembly.checkDatabase(host.engineSettings(),
                host.transactions().dataSource());
        guard.engineChecked(database);
        database.refuseIfMissing("engine start-up");
        this.afterBuild = new AfterBuild(host.transactions().dataSource());
        this.configurer = apply(this, host, guard, afterBuild);
    }

    /**
     * Applies Sangam to {@code cfg} (steps 2 to 5 of the class comment); returns the configurer, whose engine slot the
     * caller releases.
     */
    static SangamEngineConfigurer apply(QuarkusProcessEngineConfiguration cfg, QuarkusHost host,
                                        EngineCapabilityGuard guard, AfterBuild afterBuild) {
        DataSource dataSource = host.transactions().dataSource();
        SangamEngineConfigurer configurer = new SangamEngineConfigurer(host.engineSettings());
        configurer.prepare(dataSource);
        afterBuild.configurer = configurer;
        // the host's own data source, not the prepared wrapper: the shared transaction is identity-checked
        configurer.configure(cfg, dataSource);
        cfg.setTransactionManager(host.transactions().transactionManager());
        List<ProcessEnginePlugin> plugins = EngineAssembly.orderPlugins(cfg.getProcessEnginePlugins(),
                configurer.plugin(), name -> false, name -> List.of(),
                configurer.findings().operatonDatabaseType());
        // EngineSettingsReapplyPlugin stays last; the start order (decision 376) runs after every plugin that sets the
        // job executor activation and after afterBuild, so the after-build services see the engine adapter
        plugins.add(plugins.size() - 1, afterBuild);
        plugins.add(plugins.size() - 1, guard);
        plugins.add(plugins.size() - 1, host.startOrder());
        cfg.setProcessEnginePlugins(plugins);
        return configurer;
    }

    public SangamEngineConfigurer configurer() {
        return configurer;
    }

    /** The engine adapter, once the engine is built; {@code null} before. */
    public OperatonEngineAdapter adapter() {
        return afterBuild.adapter;
    }

    @PreDestroy
    void releaseEngineSlot() {
        configurer.releaseEngineSlot();
    }

    /** {@code SangamEngineConfigurer.afterBuild} on the built engine (MySQL patches, history partitioning, adapter). */
    static final class AfterBuild extends AbstractProcessEnginePlugin {

        private final DataSource dataSource;
        SangamEngineConfigurer configurer;
        volatile OperatonEngineAdapter adapter;

        AfterBuild(DataSource dataSource) {
            this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        }

        @Override
        public void postProcessEngineBuild(ProcessEngine processEngine) {
            adapter = configurer.afterBuild(processEngine, dataSource, jdbcUrl(dataSource), null);
        }

        private static String jdbcUrl(DataSource ds) {
            try (Connection c = ds.getConnection()) {
                return c.getMetaData().getURL();
            } catch (SQLException e) {
                return null;
            }
        }
    }
}
