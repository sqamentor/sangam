// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine;

import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.ProcessEngineConfiguration;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.sangam.core.engine.SangamClockHolder;
import org.sangam.engine.clock.OperatonClock;
import org.sangam.engine.db.DatabaseGuard;
import org.sangam.engine.db.EngineDialect;
import org.sangam.engine.db.IsolationGuardDataSource;
import org.sangam.persistence.SangamSchema;

/**
 * Applies Sangam to any Operaton {@link ProcessEngineConfigurationImpl}, so it can be used from plain Java
 * ({@link SangamEngineFactory}) or from Spring, where the application owns the configuration object (for example a
 * {@code SpringProcessEngineConfiguration} bean, or a {@code StandaloneProcessEngineConfiguration} built in a
 * {@code @Bean} method). No Spring or Spring Boot type is used here.
 *
 * <pre>
 *   SangamEngineConfigurer c = new SangamEngineConfigurer(settings);
 *   DataSource ds = c.prepare(rawDataSource);       // isolation guard, start-up checks, product schema
 *   c.configure(cfg, ds);                           // engine settings + SangamProcessEnginePlugin
 *   ProcessEngine engine = cfg.buildProcessEngine();
 *   OperatonEngineAdapter adapter = c.afterBuild(engine, ds, jdbcUrl); // P-1/P-2, adapter
 * </pre>
 */
public final class SangamEngineConfigurer {

    private final SangamEngineSettings settings;
    private DatabaseGuard.Findings findings;
    private SangamEngineComponents components;
    private SangamProcessEnginePlugin plugin;

    public SangamEngineConfigurer(SangamEngineSettings settings) {
        this.settings = settings == null ? new SangamEngineSettings() : settings;
    }

    /**
     * Wraps {@code raw} in the READ COMMITTED guard (D-56), runs the start-up checks (dialect, isolation, SQL Server
     * RCSI, MySQL server identity: D-05) and, when enabled, applies the product changelog (R12).
     */
    public DataSource prepare(DataSource raw) {
        if (settings.isApplyEnvironment()) {
            settings.applyEnvironment(System.getenv()); // IR-7 SANGAM_JOB_* overrides
        }
        DataSource ds = raw instanceof IsolationGuardDataSource ? raw : new IsolationGuardDataSource(raw);
        findings = DatabaseGuard.check(ds, settings.isRequireSqlServerRcsi(), settings.getMysqlDatabaseType());
        if (findings.dialect() == EngineDialect.H2) {
            DatabaseGuard.prepareH2(ds);
        }
        if (settings.isApplyProductSchema()) {
            SangamSchema.apply(ds);
        }
        components = new SangamEngineComponents(settings, findings.dialect());
        plugin = new SangamProcessEnginePlugin(components);
        return ds;
    }

    /** Sets the Sangam engine options on {@code cfg} and registers the plugin. Call {@link #prepare} first. */
    public void configure(ProcessEngineConfigurationImpl cfg, DataSource preparedDataSource) {
        configure(cfg, preparedDataSource, List.of());
    }

    /**
     * As {@link #configure(ProcessEngineConfigurationImpl, DataSource)}, then registers {@code extraPlugins} in the
     * given order. <b>Plugin order is enforced:</b> {@link SangamProcessEnginePlugin} is always first, ahead of any
     * plugin already on {@code cfg} and of the extras, so dependent plugins (for example WS-PORTS'
     * {@code SangamPortsPlugin}, which builds on the Sangam calendars, job handlers and parse listeners) run their
     * preInit/postInit after it.
     */
    public void configure(ProcessEngineConfigurationImpl cfg, DataSource preparedDataSource,
                          List<? extends ProcessEnginePlugin> extraPlugins) {
        if (plugin == null) {
            throw new IllegalStateException("call prepare(DataSource) first");
        }
        OperatonClock.register(settings.isAllowMultipleEnginesPerJvm());
        cfg.setProcessEngineName(settings.getProcessEngineName());
        cfg.setDataSource(preparedDataSource);
        cfg.setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE);
        if (findings.operatonDatabaseType() != null) {
            cfg.setDatabaseType(findings.operatonDatabaseType()); // D-05: pinned, never inferred from the driver
        }
        cfg.setHistory(settings.getHistory());
        cfg.setJobExecutorActivate(settings.isJobExecutorActivate());
        cfg.setDmnEnabled(true);
        cfg.setCmmnEnabled(true);
        cfg.setTenantCheckEnabled(true);
        List<ProcessEnginePlugin> plugins = new ArrayList<>();
        plugins.add(plugin);
        if (cfg.getProcessEnginePlugins() != null) {
            for (ProcessEnginePlugin p : cfg.getProcessEnginePlugins()) {
                if (p != plugin && !(p instanceof SangamProcessEnginePlugin)) {
                    plugins.add(p);
                }
            }
        }
        for (ProcessEnginePlugin p : extraPlugins) {
            if (!plugins.contains(p)) {
                plugins.add(p);
            }
        }
        cfg.setProcessEnginePlugins(plugins);
        SangamClockHolder.set(components.clock()); // other modules read the engine clock through the holder
    }

    /** After the engine build: engine schema patches (MySQL P-1/P-2) and the adapter. */
    public OperatonEngineAdapter afterBuild(ProcessEngine engine, DataSource preparedDataSource, String jdbcUrl,
                                            Runnable onClose) {
        if (settings.isApplyEnginePatches() && findings.dialect() == EngineDialect.MYSQL) {
            SangamSchema.applyEnginePatches(preparedDataSource);
        }
        if (settings.isPartitionedHistory()) {
            if (findings.dialect() == EngineDialect.MYSQL || findings.dialect() == EngineDialect.H2) {
                org.slf4j.LoggerFactory.getLogger(SangamEngineConfigurer.class).warn(
                        "IR-8: history partitioning is not provided on {} (see sangam-persistence README)", findings.dialect());
            } else {
                SangamSchema.applyHistoryPartitioning(preparedDataSource);
            }
        }
        return new OperatonEngineAdapter(engine, components, jdbcUrl, onClose);
    }

    public SangamEngineComponents components() {
        return components;
    }

    public SangamProcessEnginePlugin plugin() {
        return plugin;
    }

    public DatabaseGuard.Findings findings() {
        return findings;
    }
}
