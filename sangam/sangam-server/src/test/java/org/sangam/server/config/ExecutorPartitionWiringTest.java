// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.sangam.core.layer.Dialect;
import org.sangam.engine.job.PartitionResize;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.ExecutorPartitionPort;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.RequestContext;
import org.sangam.server.bridge.ExecutorPartitionBridge;

/**
 * Decision 146: the four executor routes reach the engine's {@link PartitionResize} (W4-E: every Sangam engine builds
 * one); without one (not a Sangam engine, or not installed yet) every route answers 501 CAPABILITY_NOT_INSTALLED, and
 * the resize is looked up per call, so one installed later is used at once.
 */
class ExecutorPartitionWiringTest {

    static final RequestContext CTX = new RequestContext("platform", "ops-admin", Set.of("sangam-admin"), null, null,
            "key-1", null, "127.0.0.1", "OIDC");

    static ExecutorPartitionPort port(AtomicReference<PartitionResize> current) {
        return new ExecutorPartitionBridge(ExecutorPartitionWiring.resizes(current::get, null), 1000);
    }

    @Test
    void withoutAnEngineResizeEveryRouteIs501() {
        ExecutorPartitionPort p = port(new AtomicReference<>());
        for (Runnable call : new Runnable[]{() -> p.map(CTX),
                () -> p.resize(CTX, new ExecutorPartitionPort.ExecutorPartitionResizeRequest(6, "peak", null)),
                () -> p.list(CTX, null, Page.PageRequest.of(0, 20)), () -> p.get(CTX, "r-1")}) {
            ApiProblem e = assertThrows(ApiProblem.class, call::run);
            assertEquals(501, e.status());
            assertEquals("CAPABILITY_NOT_INSTALLED", e.code());
        }
    }

    @Test
    void theEnginesResizeAnswersOnceInstalled() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:epw" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        try (Connection c = ds.getConnection()) {
            c.createStatement().execute("CREATE TABLE EXEC_MAP (cluster_id VARCHAR(64) NOT NULL PRIMARY KEY,"
                    + " state VARCHAR(16) NOT NULL, current_map_epoch BIGINT NOT NULL, target_map_epoch BIGINT)");
        }
        AtomicReference<PartitionResize> current = new AtomicReference<>();
        ExecutorPartitionPort p = port(current);
        assertEquals(501, assertThrows(ApiProblem.class, () -> p.map(CTX)).status());

        current.set(new PartitionResize(ds, Dialect.POSTGRESQL, "c1", 64, null, "system:platform", 7));
        ApiProblem e = assertThrows(ApiProblem.class, () -> p.map(CTX),
                "no executor map yet: the engine's NOT_INITIALISED, not the 501");
        assertEquals(409, e.status());
        assertEquals("NOT_INITIALISED", e.code());
    }

    @Test
    void anEngineWithoutTheSangamPluginHasNoResize() {
        assertNull(ExecutorPartitionWiring.partitionResize(null));
        ProcessEngine plain = (ProcessEngine) Proxy.newProxyInstance(ProcessEngine.class.getClassLoader(),
                new Class<?>[]{ProcessEngine.class}, (proxy, m, args) -> null);
        assertNull(ExecutorPartitionWiring.partitionResize(plain), "no ProcessEngineConfigurationImpl: no resize");
    }
}
