// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.zaxxer.hikari.HikariDataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.sangam.config.store.ConfigSchema;
import org.sangam.config.store.JdbcConfigStore;
import org.sangam.core.layer.Dialect;
import org.sangam.rest.config.SpringTxSupport;
import org.sangam.rest.support.TxSupport;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;

/**
 * The pool-locking deadlock of the config store (SL-43 shape): a process start holds a main-pool connection (the
 * engine command) and {@code ConfigCaseBindingPlugin} reads the profile change marker through
 * {@code JdbcConfigStore.inTx}, which took a SECOND connection of the main pool. With as many concurrent starts as the
 * pool has connections, every connection was held by a start waiting for one more. The server's config store now
 * works on its own leaf pool ({@code sangam-config}, {@link ConfigWiring.ConfigStorePool}), derived by the one
 * leaf-pool helper.
 */
class ConfigStoreLeafPoolH2Test {

    private static final int POOL = 4;

    private static HikariDataSource main(String name, int size) {
        HikariDataSource main = new HikariDataSource();
        main.setJdbcUrl("jdbc:h2:mem:" + name + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        main.setUsername("sa");
        main.setPassword("");
        main.setMaximumPoolSize(size);
        main.setConnectionTimeout(3_000);
        main.setPoolName("sangam");
        return main;
    }

    private static TxSupport tx(HikariDataSource main) {
        return new SpringTxSupport(main, new DataSourceTransactionManager(main), Dialect.POSTGRESQL);
    }

    @Test
    void theConfigStorePoolIsALeafPoolOfTheMainPool() {
        try (HikariDataSource main = main("cfgleaf", 5)) {
            ConfigWiring wiring = new ConfigWiring();
            ConfigWiring.ConfigStorePool byDefault = wiring.sangamConfigStorePool(tx(main), new MockEnvironment());
            try {
                HikariDataSource leaf = byDefault.poolOrNull();
                assertNotNull(leaf);
                assertNotSame(main, leaf);
                assertEquals(ConfigWiring.STORE_POOL_NAME, leaf.getPoolName());
                assertEquals("sangam-config", leaf.getPoolName());
                assertEquals(4, leaf.getMaximumPoolSize(), "default size, as the quota pool");
                assertEquals(main.getJdbcUrl(), leaf.getJdbcUrl(), "same database and settings");
                assertSame(leaf, byDefault.dataSource(main), "the store works on the leaf pool");
            } finally {
                byDefault.destroy();
            }
            ConfigWiring.ConfigStorePool sized = wiring.sangamConfigStorePool(tx(main),
                    new MockEnvironment().withProperty("sangam.config.store.pool.maximum-size", "2"));
            try {
                assertEquals(2, sized.poolOrNull().getMaximumPoolSize());
            } finally {
                sized.destroy();
            }
        }
    }

    @Test
    void aMainPoolThatIsNotHikariKeepsTheStoreOnTheMainPool() {
        JdbcDataSource plain = new JdbcDataSource();
        plain.setURL("jdbc:h2:mem:cfgleaf-plain" + UUID.randomUUID());
        ConfigWiring.ConfigStorePool none = ConfigWiring.storePool(plain, 4);
        assertNull(none.poolOrNull(), "an embedding host's own pool: no leaf pool to derive");
        assertSame(plain, none.dataSource(plain));
        none.destroy();
    }

    /**
     * N concurrent starts on a main pool of N: each holds its main-pool connection (the engine command's) and then
     * reads the change marker through the server's config store, as {@code ConfigCaseBindingPlugin.onStart} does. On the
     * main pool none of them could get its second connection (every one failed after the pool's connection timeout);
     * on the leaf pool all finish.
     */
    @Test
    void asManyConcurrentStartsAsTheMainPoolHasConnectionsDoNotDeadlock() throws Exception {
        try (HikariDataSource main = main("cfgdeadlock", POOL)) {
            try (Connection c = main.getConnection()) {
                ConfigSchema.create(c, Dialect.POSTGRESQL);
            }
            TxSupport tx = tx(main);
            ConfigWiring wiring = new ConfigWiring();
            MockEnvironment env = new MockEnvironment();
            ConfigWiring.ConfigStorePool pool = wiring.sangamConfigStorePool(tx, env);
            ExecutorService starts = Executors.newFixedThreadPool(POOL);
            try {
                JdbcConfigStore store = wiring.sangamConfigStore(tx, env, pool);
                CyclicBarrier allHoldTheirMainConnection = new CyclicBarrier(POOL);
                List<Future<String>> markers = new ArrayList<>();
                for (int i = 0; i < POOL; i++) {
                    Callable<String> start = () -> {
                        try (Connection held = main.getConnection()) {
                            held.setAutoCommit(false);
                            allHoldTheirMainConnection.await(10, TimeUnit.SECONDS);
                            String marker = store.changeMarker();
                            held.rollback();
                            return marker;
                        }
                    };
                    markers.add(starts.submit(start));
                }
                for (Future<String> f : markers) {
                    assertEquals("0:0", f.get(30, TimeUnit.SECONDS), "the change marker of an empty register");
                }
                assertEquals(0, main.getHikariPoolMXBean().getThreadsAwaitingConnection(), "nobody waits for the main pool");
                assertTrue(main.getHikariPoolMXBean().getTotalConnections() <= POOL);
            } finally {
                starts.shutdownNow();
                pool.destroy();
            }
        }
    }
}
