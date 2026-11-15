// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.restadapter;

import org.sangam.rest.config.SpringTxSupport;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.spring.SpringTransactionsProcessEngineConfiguration;
import org.sangam.core.layer.Dialect;
import org.sangam.rest.support.TxSupport;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * The production shape of the REST adapters' transactions on H2 (PostgreSQL mode): one DataSource and one Spring
 * {@link DataSourceTransactionManager} shared by an Operaton {@link SpringTransactionsProcessEngineConfiguration} engine and
 * {@link TxSupport}, so engine commands join an outer Spring transaction exactly as in sangam-server. The DataSource
 * counts commits, connections handed out and the largest number held at the same time by one thread (a second
 * connection while one is held is the SL-43 pool-deadlock pattern).
 */
final class RestEngineFixture implements AutoCloseable {

    final AtomicInteger commits = new AtomicInteger();
    final AtomicInteger connections = new AtomicInteger();
    final AtomicInteger maxHeld = new AtomicInteger();
    private final ThreadLocal<int[]> held = ThreadLocal.withInitial(() -> new int[1]);

    /** The database without counting (a leaf pool of its own in the tests). */
    final JdbcDataSource raw;
    final DataSource dataSource;
    final DataSourceTransactionManager txManager;
    final TxSupport tx;
    final ProcessEngine engine;

    RestEngineFixture(String name) {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:" + name + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        h2.setUser("sa");
        // first the BLOB alias Operaton's H2 scripts need in MODE=PostgreSQL: H2 refuses to create it once a
        // large-object column exists (the order of the server's SchemaBootstrap)
        org.sangam.engine.db.DatabaseGuard.prepareH2(h2);
        // the product schema the server runs on (AUDIT_EVENT, ROOT_SEQ, WORK_STEP, ...): its OPERATION_LEDGER has the
        // engine_work_id the work adapters read; sangam-core's Schema, the layer's own test DDL, does not
        org.sangam.persistence.SangamSchema.apply(h2);
        this.raw = h2;
        this.dataSource = counting(h2);
        this.txManager = new DataSourceTransactionManager(dataSource);
        this.tx = new SpringTxSupport(dataSource, txManager, Dialect.POSTGRESQL);
        // the engine on Spring transactions only: SpringProcessEngineConfiguration also resolves beans for scripts and
        // expressions and needs an ApplicationContext for that, which this fixture does not have
        SpringTransactionsProcessEngineConfiguration cfg = new SpringTransactionsProcessEngineConfiguration();
        cfg.setProcessEngineName(name + "-" + UUID.randomUUID());
        cfg.setDataSource(dataSource);
        cfg.setTransactionManager(txManager);
        cfg.setDatabaseSchemaUpdate("true");
        cfg.setJobExecutorActivate(false);
        this.engine = cfg.buildProcessEngine();
    }

    void resetCounters() {
        commits.set(0);
        connections.set(0);
        maxHeld.set(0);
    }

    private DataSource counting(DataSource target) {
        return (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, m, args) -> {
                    Object r = invoke(target, m, args);
                    if (r instanceof Connection c && m.getName().equals("getConnection")) {
                        connections.incrementAndGet();
                        int now = ++held.get()[0];
                        maxHeld.accumulateAndGet(now, Math::max);
                        return connection(c);
                    }
                    return r;
                });
    }

    private Connection connection(Connection target) {
        boolean[] closed = {false};
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, m, args) -> {
                    switch (m.getName()) {
                        case "commit" -> commits.incrementAndGet();
                        case "close" -> {
                            if (!closed[0]) {
                                closed[0] = true;
                                held.get()[0]--;
                            }
                        }
                        default -> {
                        }
                    }
                    return invoke(target, m, args);
                });
    }

    private static Object invoke(Object target, java.lang.reflect.Method m, Object[] args) throws Throwable {
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @Override
    public void close() {
        engine.close();
    }
}
