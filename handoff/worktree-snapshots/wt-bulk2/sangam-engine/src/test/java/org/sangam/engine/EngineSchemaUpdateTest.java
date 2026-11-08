// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngineConfiguration;
import org.operaton.bpm.engine.impl.cfg.StandaloneProcessEngineConfiguration;

/**
 * Regression for SG-19: Sangam forced {@code databaseSchemaUpdate=true}, overriding the production profiles'
 * {@code false}, so the engine changed the schema itself at start-up. Engine DDL must follow the configuration;
 * Sangam changes it only when its settings say so explicitly.
 */
class EngineSchemaUpdateTest {

    private final java.util.List<SangamEngineConfigurer> configurers = new java.util.ArrayList<>();

    @AfterEach
    void clock() {
        configurers.forEach(SangamEngineConfigurer::releaseEngineSlot);
    }

    private SangamEngineConfigurer track(SangamEngineConfigurer c) {
        configurers.add(c);
        return c;
    }

    private static JdbcDataSource h2(String name) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + name + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        return ds;
    }

    private static SangamEngineSettings settings() {
        SangamEngineSettings s = new SangamEngineSettings();
        s.setAllowMultipleEnginesPerJvm(true);
        return s;
    }

    @Test
    void keepsTheConfiguredFalse() {
        SangamEngineConfigurer c = track(new SangamEngineConfigurer(settings()));
        var ds = c.prepare(h2("sg19a"));
        StandaloneProcessEngineConfiguration cfg = new StandaloneProcessEngineConfiguration();
        cfg.setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_FALSE);
        c.configure(cfg, ds);
        assertEquals(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_FALSE, cfg.getDatabaseSchemaUpdate());
    }

    @Test
    void appliesAnExplicitSetting() {
        SangamEngineSettings s = settings();
        s.setEngineSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_CREATE_DROP);
        SangamEngineConfigurer c = track(new SangamEngineConfigurer(s));
        var ds = c.prepare(h2("sg19b"));
        StandaloneProcessEngineConfiguration cfg = new StandaloneProcessEngineConfiguration();
        cfg.setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_FALSE);
        c.configure(cfg, ds);
        assertEquals(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_CREATE_DROP, cfg.getDatabaseSchemaUpdate());
    }
}
