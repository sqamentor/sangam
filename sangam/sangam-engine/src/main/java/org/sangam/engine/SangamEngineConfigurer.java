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
import org.sangam.core.time.EngineTimeZone;
import org.sangam.engine.clock.OperatonClock;
import org.sangam.engine.db.DatabaseGuard;
import org.sangam.engine.db.EngineDialect;
import org.sangam.engine.db.IsolationGuardDataSource;
import org.sangam.persistence.SangamSchema;

/**
 * Applies Sangam to any Operaton {@link ProcessEngineConfigurationImpl}, so it can be used from plain Java
 * ({@link SangamEngineFactory}) or from Spring, where the application owns the configuration object (for example a
 * {@code SpringProcessEngineConfiguration} bean, or a {@code StandaloneProcessEngineConfiguration} built in a
 * {@code @Bean} method). No Spring or Spring Boot type is used here. The JVM must run in UTC (SL-53): the constructor
 * refuses any other default time zone, an unknown one included, before the first database connection
 * ({@link EngineTimeZone#requireUtcJvm()}).
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
    private OperatonClock.Registration engineSlot;

    /**
     * @throws IllegalStateException when the JVM default time zone is not UTC (SL-53, {@link EngineTimeZone}): the
     *                               engine refuses to start before it touches the database
     */
    public SangamEngineConfigurer(SangamEngineSettings settings) {
        EngineTimeZone.requireUtcJvm();
        this.settings = settings == null ? new SangamEngineSettings() : settings;
    }

    /**
     * Wraps {@code raw} in the READ COMMITTED guard (FLOWABLE-INTO-OPERATON D-56), runs the start-up checks (dialect, isolation, SQL Server
     * RCSI, MySQL server identity: FLOWABLE-INTO-OPERATON D-05) and, when enabled, applies the product changelog (R12).
     */
    public DataSource prepare(DataSource raw) {
        // IR-7 SANGAM_JOB_* overrides. Frozen settings (M-098: a configure() already ran on them) had the overrides
        // applied by that run's prepare, before the freeze, and must not change again.
        if (settings.isApplyEnvironment() && !settings.isFrozen()) {
            settings.applyEnvironment(System.getenv());
        }
        // SL-59: the guard also bounds socket reads on every checked-out connection without a network timeout
        DataSource ds = raw instanceof IsolationGuardDataSource ? raw
                : new IsolationGuardDataSource(raw, settings.getNetworkTimeout());
        findings = DatabaseGuard.check(ds, settings.isRequireSqlServerRcsi(), settings.getMysqlDatabaseType(),
                settings.isExperimentalCockroachDb(), settings.isExperimentalYugabyteDb());
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
        synchronized (this) {
            if (engineSlot == null || engineSlot.released()) {
                engineSlot = OperatonClock.register(settings.isAllowMultipleEnginesPerJvm());
            }
        }
        cfg.setProcessEngineName(settings.getProcessEngineName());
        cfg.setDataSource(preparedDataSource);
        // SG-19: never force engine DDL; keep the configuration's own value unless the settings say otherwise
        if (settings.getEngineSchemaUpdate() != null) {
            cfg.setDatabaseSchemaUpdate(settings.getEngineSchemaUpdate());
        }
        if (findings.operatonDatabaseType() != null) {
            cfg.setDatabaseType(findings.operatonDatabaseType()); // FLOWABLE-INTO-OPERATON D-05: pinned, never inferred from the driver
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
        // M-098: bootstrap is complete for the settings; the plugin reads them during the build, unchanged from here
        settings.freeze();
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
        Runnable closed = () -> {
            try {
                if (onClose != null) {
                    onClose.run();
                }
            } finally {
                releaseEngineSlot();
            }
        };
        return new OperatonEngineAdapter(engine, components, jdbcUrl, closed);
    }

    /**
     * Frees this configurer's engine slot (FLOWABLE-INTO-OPERATON D-10 guard: one engine per JVM). Idempotent and thread-safe. Called when the
     * adapter closes the engine, when a build fails, and by containers that own the engine (the server releases it
     * when its Spring context closes).
     */
    public void releaseEngineSlot() {
        OperatonClock.Registration slot;
        synchronized (this) {
            slot = engineSlot;
        }
        if (slot != null) {
            slot.release();
        }
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
