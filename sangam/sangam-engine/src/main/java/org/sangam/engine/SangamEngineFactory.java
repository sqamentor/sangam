// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine;

import javax.sql.DataSource;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.StandaloneProcessEngineConfiguration;

/**
 * Builds a plain Operaton engine (standalone JDBC transactions, no Spring) on a caller-owned DataSource, with Sangam
 * applied. Usable directly from a Spring {@code @Bean} method:
 *
 * <pre>
 *   &#64;Bean(destroyMethod = "close")
 *   OperatonEngineAdapter sangamEngine(DataSource ds, SangamEngineSettings settings) {
 *       return SangamEngineFactory.build(ds, settings, "jdbc-url-for-reporting");
 *   }
 * </pre>
 * The DataSource stays owned by the caller (the pool is not closed by the adapter).
 */
public final class SangamEngineFactory {

    private SangamEngineFactory() {
    }

    public static OperatonEngineAdapter build(DataSource dataSource, SangamEngineSettings settings, String jdbcUrl) {
        return build(dataSource, settings, jdbcUrl, java.util.List.of());
    }

    /**
     * Builds the engine with further plugins, registered in the given order <b>after</b>
     * {@link SangamProcessEnginePlugin} (for example {@code new SangamPortsPlugin(...)} from sangam-flowable-ports).
     */
    public static OperatonEngineAdapter build(DataSource dataSource, SangamEngineSettings settings, String jdbcUrl,
                                              java.util.List<? extends org.operaton.bpm.engine.impl.cfg.ProcessEnginePlugin> extraPlugins) {
        SangamEngineConfigurer configurer = new SangamEngineConfigurer(settings);
        DataSource ds = configurer.prepare(dataSource);
        StandaloneProcessEngineConfiguration cfg = new StandaloneProcessEngineConfiguration();
        configurer.configure(cfg, ds, extraPlugins);
        ProcessEngine engine;
        try {
            engine = cfg.buildProcessEngine();
        } catch (RuntimeException e) {
            org.sangam.engine.clock.OperatonClock.unregister();
            throw e;
        }
        return configurer.afterBuild(engine, ds, jdbcUrl, null);
    }
}
