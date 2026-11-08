// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.time.Duration;
import java.util.Set;

import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.StandaloneProcessEngineConfiguration;
import org.sangam.core.engine.SangamClockHolder;
import org.sangam.ports.tenancy.TenantEngineRegistry.TenantContext;

/** FC-34 database-per-tenant routing: one engine per tenant database, a shared job executor, D-10 clock guard. */
class TenantEngineRegistryTest {

    private final TenantEngineRegistry registry = new TenantEngineRegistry((tenant, ds) -> {
        StandaloneProcessEngineConfiguration cfg = new StandaloneProcessEngineConfiguration();
        cfg.setDatabaseSchemaUpdate("true");
        cfg.setEnforceHistoryTimeToLive(false);
        cfg.setJobExecutorActivate(true);   // the registry must override this: the shared executor drives every engine
        return cfg;
    }, 2);

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SangamClockHolder.reset();
        registry.close();
    }

    private static JdbcDataSource db(String name) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + name + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        return ds;
    }

    private static String url(DataSource ds) throws Exception {
        try (Connection c = ds.getConnection()) {
            return c.getMetaData().getURL();
        }
    }

    private static String dbName(JdbcDataSource ds) {
        return ds.getURL().substring(0, ds.getURL().indexOf(';'));
    }

    @Test
    void eachTenantGetsItsOwnEngineOnItsOwnDatabaseAndTheContextRoutesRequests() throws Exception {
        JdbcDataSource dsA = db("tenantA");
        JdbcDataSource dsB = db("tenantB");
        ProcessEngine a = registry.register("a", dsA);
        ProcessEngine b = registry.register("b", dsB);
        assertSame(a, registry.register("a", db("ignored")), "registering again returns the running engine");
        assertNotSame(a, b);
        assertEquals("sangam-a", a.getName());
        assertEquals(Set.of("a", "b"), registry.tenants());

        a.getRepositoryService().createDeployment().addString("p.bpmn", """
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="t">
                  <process id="p" isExecutable="true"><startEvent id="s"/></process></definitions>""").deploy();
        assertEquals(1, a.getRepositoryService().createProcessDefinitionQuery().count());
        assertEquals(0, b.getRepositoryService().createProcessDefinitionQuery().count(), "databases are separate");

        DataSource routing = registry.routingDataSource();
        TenantContext.set("a");
        assertSame(a, registry.current());
        assertEquals(dbName(dsA), url(routing));
        TenantContext.set("b");
        assertSame(b, registry.current());
        assertEquals(dbName(dsB), url(routing));
        assertTrue(routing.isWrapperFor(TenantEngineRegistry.RoutingDataSource.class));

        TenantContext.set("ghost");
        assertThrows(IllegalStateException.class, registry::current);
        assertThrows(IllegalStateException.class, routing::getConnection, "an unknown tenant never falls back to another pool");
        TenantContext.clear();
        assertThrows(IllegalStateException.class, registry::current);
        assertThrows(IllegalStateException.class, routing::getConnection);
    }

    @Test
    void allEnginesShareOneJobExecutorAndUnregisterClosesTheEngine() {
        ProcessEngine a = registry.register("a", db("ja"));
        ProcessEngine b = registry.register("b", db("jb"));
        var ea = ((org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl) a.getProcessEngineConfiguration()).getJobExecutor();
        var eb = ((org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl) b.getProcessEngineConfiguration()).getJobExecutor();
        assertSame(ea, eb);
        assertTrue(ea.isActive(), "started once for all engines");
        assertEquals(java.util.List.of(a, b), ea.getProcessEngines(), "each engine registered exactly once");

        registry.unregister("a");
        assertEquals(Set.of("b"), registry.tenants());
        assertEquals(java.util.List.of(b), ea.getProcessEngines(), "no stale closed engine left for the acquisition loop");
        assertThrows(IllegalStateException.class, () -> registry.engine("a"));
        registry.unregister("a");   // idempotent
    }

    @Test
    void aSecondEngineIsRefusedWhileTheClockIsMoved() {
        registry.register("a", db("ca"));
        SangamClockHolder.set(() -> java.time.Instant.now().plus(Duration.ofDays(3)));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> registry.register("b", db("cb")));
        assertTrue(e.getMessage().contains("D-10"));
        assertEquals(Set.of("a"), registry.tenants());
        assertThrows(IllegalStateException.class, TenantEngineRegistry::assertRealClock);
        SangamClockHolder.reset();
        TenantEngineRegistry.assertRealClock();
        registry.register("b", db("cb"));
        assertEquals(Set.of("a", "b"), registry.tenants());
    }
}
