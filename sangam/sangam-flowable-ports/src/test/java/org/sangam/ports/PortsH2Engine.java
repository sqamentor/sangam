// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.function.Consumer;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.engine.impl.util.ClockUtil;
import org.operaton.bpm.engine.runtime.Job;
import org.sangam.core.layer.Dialect;

/**
 * Test support: an in-memory H2 Operaton engine (PostgreSQL compatibility mode) with the ports plugin and the ports'
 * product tables. Each instance has its own database.
 */
public final class PortsH2Engine implements AutoCloseable {

    public final String jdbcUrl;
    public final ProcessEngine engine;
    public final SangamPortsPlugin plugin;

    private PortsH2Engine(String jdbcUrl, ProcessEngine engine, SangamPortsPlugin plugin) {
        this.jdbcUrl = jdbcUrl;
        this.engine = engine;
        this.plugin = plugin;
    }

    public static PortsH2Engine start(Consumer<PortsSettings> settings, ProcessEnginePlugin... extraBefore) {
        String url = "jdbc:h2:mem:ports" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        PortsSettings s = new PortsSettings();
        s.setDialect(Dialect.POSTGRESQL);
        if (settings != null) {
            settings.accept(s);
        }
        // H2 2.x in PostgreSQL mode does not know BLOB, which Operaton's H2 scripts use (as sangam-engine's DatabaseGuard)
        try (Connection c = DriverManager.getConnection(url, "sa", ""); java.sql.Statement st = c.createStatement()) {
            st.execute("CREATE DOMAIN IF NOT EXISTS BLOB AS BINARY LARGE OBJECT");
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
        SangamPortsPlugin plugin = new SangamPortsPlugin(s, null);
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl(url);
        cfg.setDatabaseType("h2");
        cfg.setJobExecutorActivate(false);
        cfg.setEnforceHistoryTimeToLive(false);
        cfg.setProcessEngineName("ports" + System.nanoTime());
        List<ProcessEnginePlugin> plugins = new java.util.ArrayList<>(List.of(extraBefore));
        plugins.add(plugin);
        cfg.setProcessEnginePlugins(plugins);
        ProcessEngine engine = cfg.buildProcessEngine();
        PortsH2Engine e = new PortsH2Engine(url, engine, plugin);
        try (Connection c = e.connection()) {
            org.sangam.core.layer.Schema.create(c, Dialect.POSTGRESQL);
            PortsSchema.create(c, Dialect.POSTGRESQL);
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
        return e;
    }

    public ProcessEngineConfigurationImpl configuration() {
        return (ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration();
    }

    public Connection connection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, "sa", "");
    }

    /** Executes every due job once per round until none is due (bounded). Returns the number executed. */
    public int executeDueJobs() {
        int n = 0;
        for (int round = 0; round < 20; round++) {
            List<Job> due = engine.getManagementService().createJobQuery().executable().active().list();
            if (due.isEmpty()) {
                break;
            }
            for (Job j : due) {
                engine.getManagementService().executeJob(j.getId());
                n++;
            }
        }
        return n;
    }

    @Override
    public void close() {
        ClockUtil.reset();
        engine.close();
    }
}
