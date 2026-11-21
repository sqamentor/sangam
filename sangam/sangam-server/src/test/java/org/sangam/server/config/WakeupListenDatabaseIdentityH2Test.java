// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Dialect;
import org.sangam.integration.relay.WakeupListener;
import org.sangam.rest.support.TxSupport;

/**
 * R7UI-S11: the development server on H2 logged a LISTEN failure again and again. {@code TxSupport.detectDialect}
 * deliberately treats H2 as the PostgreSQL product dialect (its SQL runs in {@code MODE=PostgreSQL}), and the wake-up
 * wiring took that SQL dialect as proof that the database has {@code LISTEN/NOTIFY}. H2 has none, so the listener thread
 * failed, backed off and retried for as long as the node ran. The decision now comes from the database identity
 * (the JDBC product name), so H2 gets the in-JVM wake-up and no listener thread; PostgreSQL is unchanged.
 */
class WakeupListenDatabaseIdentityH2Test {

    private static HikariDataSource h2Pool() {
        HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl("jdbc:h2:mem:wakeupidentity" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        pool.setUsername("sa");
        pool.setPassword("");
        return pool;
    }

    private static TxSupport tx(DataSource ds) {
        return new TxSupport(ds, TxSupport.detectDialect(ds)) {
            @Override
            public <T> T inTxWithStatus(Function<TxStatus, T> work) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <T> T inReadTx(org.sangam.core.api.ReadSupplier<T> work) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <T> T withReadConnection(org.sangam.core.api.ReadWork<T> work) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <T> T inNewTx(Supplier<T> work) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <T> T inNewReadTx(org.sangam.core.api.ReadWork<T> work) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Connection connection() {
                throw new UnsupportedOperationException();
            }

            @Override
            public void afterCommit(Runnable work) {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Test
    void onH2InPostgresModeTheProductDialectIsPostgresButTheWakeupListenerHasNoListen() {
        try (HikariDataSource pool = h2Pool()) {
            TxSupport tx = tx(pool);
            assertEquals(Dialect.POSTGRESQL, tx.dialect(), "precondition: H2 runs the PostgreSQL product SQL");
            assertFalse(IntegrationWiring.databaseHasListen(pool), "H2 has no LISTEN/NOTIFY");
            WakeupListener listener = new IntegrationWiring().sangamWakeupListener(tx, pool);
            assertFalse(listener.usesListen(), "no LISTEN connection on H2: in-JVM wake-up only");
            assertNull(IntegrationWiring.listenSource(tx.dialect(), pool));
        }
    }

    @Test
    void theChannelsLiveFanoutOnH2IsInJvmOnly() {
        try (HikariDataSource pool = h2Pool()) {
            WakeupListener listener = WakeupListener.forDialect(Dialect.POSTGRESQL,
                    IntegrationWiring.listenSource(Dialect.POSTGRESQL, pool), ChannelsWiring.LIVE_CHANNEL);
            assertFalse(listener.usesListen());
        }
    }

    @Test
    void aDatabaseThatAnswersPostgresqlKeepsTheListenConnection() throws Exception {
        try (HikariDataSource pool = h2Pool()) {
            DataSource postgres = renamedProduct(pool, "PostgreSQL");
            assertTrue(IntegrationWiring.databaseHasListen(postgres));
            // the physical connection source is still built from the pool (unchanged PostgreSQL path)
            assertNotNull(IntegrationWiring.listenSourceFor(true, pool));
            assertNull(IntegrationWiring.listenSourceFor(false, pool));
        }
    }

    @Test
    void aDatabaseThatCannotBeReachedIsTreatedAsHavingNoListen() {
        DataSource broken = (DataSource) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DataSource.class}, (p, m, args) -> {
                    if (m.getName().equals("getConnection")) {
                        throw new java.sql.SQLException("down");
                    }
                    return null;
                });
        assertFalse(IntegrationWiring.databaseHasListen(broken));
    }

    /** {@code ds} whose connections report {@code product} as the database product name. */
    private static DataSource renamedProduct(DataSource ds, String product) throws Exception {
        return (DataSource) java.lang.reflect.Proxy.newProxyInstance(WakeupListenDatabaseIdentityH2Test.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (p, m, args) -> {
                    if (!m.getName().equals("getConnection")) {
                        return m.invoke(ds, args);
                    }
                    Connection real = ds.getConnection();
                    return java.lang.reflect.Proxy.newProxyInstance(WakeupListenDatabaseIdentityH2Test.class.getClassLoader(),
                            new Class<?>[]{Connection.class}, (cp, cm, cargs) -> {
                                if (cm.getName().equals("getMetaData")) {
                                    java.sql.DatabaseMetaData md = real.getMetaData();
                                    return java.lang.reflect.Proxy.newProxyInstance(
                                            WakeupListenDatabaseIdentityH2Test.class.getClassLoader(),
                                            new Class<?>[]{java.sql.DatabaseMetaData.class},
                                            (mp, mm, margs) -> mm.getName().equals("getDatabaseProductName") ? product
                                                    : mm.invoke(md, margs));
                                }
                                return cm.invoke(real, cargs);
                            });
                });
    }
}
