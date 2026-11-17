// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine;

import java.util.Properties;

import javax.sql.DataSource;

import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.operaton.bpm.engine.impl.util.ReflectUtil;

/**
 * <b>R8-pnp PNP-1: Sangam as one drop-in Operaton {@link ProcessEnginePlugin}.</b> A host that already builds a plain
 * Operaton engine (its own {@code ProcessEngineConfiguration}, its own data source, its own listeners and beans)
 * switches to Sangam by adding {@code sangam-engine} and registering this class as a process engine plugin, the way
 * any Operaton plugin is registered (a plugin list in configuration, {@code operaton.cfg.xml}, or a Spring bean). No
 * Sangam-specific assembly code: the plugin runs the same steps as {@link SangamEngineConfigurer}
 * ({@code prepare}, the engine options, {@link SangamProcessEnginePlugin}, {@code afterBuild}) inside Operaton's own
 * plugin lifecycle.
 *
 * <p>Settings: the public no-argument constructor binds {@code sangam.engine.*} from the JVM system properties
 * ({@link SangamEngineSettingsBinder}; unknown keys are refused); a host may pass its own {@link SangamEngineSettings}
 * instead. What the host already set on its Operaton configuration stays the host's: the engine name, the history
 * level, whether the job executor runs and the schema-update mode are read from the configuration into the settings
 * before the build, never overwritten. Every Sangam guard stays on exactly as in the other routes (UTC JVM, one
 * engine per JVM unless allowed, the database checks, SL-160, frozen settings).
 *
 * <p>Register it <b>first</b> in the plugin list: Sangam's plugin must run before plugins that build on it (the same
 * order {@link SangamEngineConfigurer#configure} enforces). The engine slot is released when the host closes the
 * engine ({@code ProcessEngine.close()}; {@link org.sangam.engine.clock.OperatonClock.Registration#bindTo}).
 */
public class SangamEnginePlugin implements ProcessEnginePlugin {

    private final SangamEngineSettings settings;
    private SangamEngineConfigurer configurer;
    private DataSource prepared;
    private String jdbcUrl;
    private OperatonEngineAdapter adapter;

    /** Settings from the JVM system properties {@code sangam.engine.*}. */
    public SangamEnginePlugin() {
        this(SangamEngineSettingsBinder.bind(new SangamEngineSettings(), System.getProperties()));
    }

    /** Settings from {@code props} ({@code sangam.engine.*} keys). */
    public SangamEnginePlugin(Properties props) {
        this(SangamEngineSettingsBinder.bind(new SangamEngineSettings(), props));
    }

    public SangamEnginePlugin(SangamEngineSettings settings) {
        this.settings = settings == null ? new SangamEngineSettings() : settings;
    }

    @Override
    public void preInit(ProcessEngineConfigurationImpl cfg) {
        if (configurer != null) {
            throw new IllegalStateException("SangamEnginePlugin: one plugin instance per engine build");
        }
        // the host's own engine choices stay the host's
        if (!settings.isFrozen()) {
            if (cfg.getProcessEngineName() != null) {
                settings.setProcessEngineName(cfg.getProcessEngineName());
            }
            if (cfg.getHistory() != null) {
                settings.setHistory(cfg.getHistory());
            }
            settings.setJobExecutorActivate(cfg.isJobExecutorActivate());
            if (cfg.getDatabaseSchemaUpdate() != null && settings.getEngineSchemaUpdate() == null) {
                settings.setEngineSchemaUpdate(cfg.getDatabaseSchemaUpdate());
            }
        }
        configurer = new SangamEngineConfigurer(settings); // refuses a non-UTC JVM before any connection
        DataSource raw = cfg.getDataSource();
        jdbcUrl = cfg.getJdbcUrl();
        if (raw == null) { // the host gave JDBC properties only: the pool Operaton itself would have built
            raw = new PooledDataSource(ReflectUtil.getClassLoader(), cfg.getJdbcDriver(), cfg.getJdbcUrl(),
                    cfg.getJdbcUsername(), cfg.getJdbcPassword());
        }
        prepared = configurer.prepare(raw);
        try {
            configurer.configureInPlace(cfg, prepared);
            configurer.plugin().preInit(cfg);
        } catch (RuntimeException e) {
            configurer.releaseEngineSlot(); // a refused start never keeps the JVM's engine slot
            throw e;
        }
    }

    @Override
    public void postInit(ProcessEngineConfigurationImpl cfg) {
        configurer.plugin().postInit(cfg);
    }

    @Override
    public void postProcessEngineBuild(ProcessEngine processEngine) {
        configurer.plugin().postProcessEngineBuild(processEngine);
        org.sangam.engine.clock.OperatonClock.Registration slot = configurer.engineSlot();
        if (slot != null) {
            slot.bindTo(processEngine);
        }
        adapter = configurer.afterBuild(processEngine, prepared, jdbcUrl, null);
    }

    /** The Sangam adapter of the built engine (null before the build). */
    public OperatonEngineAdapter adapter() {
        return adapter;
    }

    public SangamEngineSettings settings() {
        return settings;
    }

    /** The configurer of this build (null before preInit), for hosts that read components or findings. */
    public SangamEngineConfigurer configurer() {
        return configurer;
    }
}
