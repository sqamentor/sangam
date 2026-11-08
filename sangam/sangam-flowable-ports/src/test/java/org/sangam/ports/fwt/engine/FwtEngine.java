// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.fwt.engine;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.ports.PortsSchema;
import org.sangam.ports.PortsSettings;
import org.sangam.ports.SangamPortsPlugin;
import org.sangam.ports.support.EngineTx;

/**
 * FWT test fixture: an in-memory H2 (PostgreSQL mode) Operaton engine with {@link SangamPortsPlugin} registered and
 * the ports' product tables ({@link PortsSchema}, PostgreSQL dialect). Every JDBC statement the engine prepares is
 * recorded in {@link #sql} (used by FWT-017 to prove no {@code COUNT(*)} is issued).
 */
final class FwtEngine implements AutoCloseable {

    static final Dialect D = Dialect.POSTGRESQL;

    final ProcessEngine engine;
    final SangamPortsPlugin plugin;
    final List<String> sql = new CopyOnWriteArrayList<>();
    final String url;

    FwtEngine() {
        this(s -> { }, c -> { });
    }

    FwtEngine(Consumer<PortsSettings> settings, Consumer<ProcessEngineConfigurationImpl> cfgHook) {
        this("jdbc:h2:mem:fwt" + System.nanoTime() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", true, settings, cfgHook);
    }

    FwtEngine(String url, boolean createSchema, Consumer<PortsSettings> settings, Consumer<ProcessEngineConfigurationImpl> cfgHook) {
        this.url = url;
        PortsSettings ps = new PortsSettings();
        ps.setDialect(D);
        settings.accept(ps);
        plugin = new SangamPortsPlugin(ps, null);
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setDataSource(recording(url));
        cfg.setDatabaseSchemaUpdate(createSchema ? "true" : "false");
        cfg.setJobExecutorActivate(false);
        cfg.setEnforceHistoryTimeToLive(false);
        cfg.setHistory("full");
        cfg.setProcessEngineName("fwt" + System.nanoTime());
        cfg.getProcessEnginePlugins().add(plugin);
        cfgHook.accept(cfg);
        engine = cfg.buildProcessEngine();
        // the engine creates its schema in H2 regular mode (its H2 DDL uses BLOB); the ports' PostgreSQL-dialect SQL
        // (ON CONFLICT DO NOTHING) then runs in H2's PostgreSQL compatibility mode
        EngineTx.execute(engine, cc -> {
            Jdbc.update(EngineTx.connection(), "SET MODE PostgreSQL");
            return null;
        });
        if (createSchema) {
            EngineTx.execute(engine, cc -> {
                PortsSchema.create(EngineTx.connection(), D);
                return null;
            });
        }
    }

    private DataSource recording(String url) {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL(url);
        h2.setUser("sa");
        InvocationHandler dsh = (p, m, a) -> {
            Object r = m.invoke(h2, a);
            if (r instanceof Connection c) {
                return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class}, (p2, m2, a2) -> {
                    if (m2.getName().startsWith("prepare") && a2 != null && a2.length > 0 && a2[0] instanceof String s) {
                        sql.add(s);
                    }
                    try {
                        return m2.invoke(c, a2);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
            }
            return r;
        };
        return (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {DataSource.class}, (p, m, a) -> {
            try {
                return dsh.invoke(p, m, a);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        });
    }

    String deploy(String name, String xml) {
        return deploy(name, xml, null);
    }

    String deploy(String name, String xml, String tenant) {
        var b = engine.getRepositoryService().createDeployment().addString(name, xml);
        if (tenant != null) {
            b.tenantId(tenant);
        }
        return b.deploy().getId();
    }

    byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    long count(String q, Object... params) {
        return EngineTx.execute(engine, cc -> Jdbc.one(EngineTx.connection(), q, rs -> rs.getLong(1), params).orElse(0L));
    }

    java.util.List<String> strings(String q, Object... params) {
        return EngineTx.execute(engine, cc -> Jdbc.query(EngineTx.connection(), q, rs -> rs.getString(1), params));
    }

    @Override
    public void close() {
        engine.close();
    }

    static String defs(String body) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                             xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="t">
                """ + body + "\n</definitions>\n";
    }
}
