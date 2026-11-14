// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import org.sangam.rest.config.SpringTxSupport;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.UUID;

import com.zaxxer.hikari.HikariDataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.sangam.core.layer.Transactions;
import org.sangam.rest.adapter.jdbc.DenialAudit;
import org.sangam.rest.adapter.operaton.OperatonTaskAdapter;
import org.sangam.rest.adapter.operaton.ReadTransactions;
import org.sangam.rest.config.SangamRestAutoConfiguration;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * SL-132 / SG-15 go live: security denial audits (sangam-security's {@code denialTx} and the REST task adapter's SoD
 * refusal) commit on the {@code sangam-security-audit} leaf pool, never on a second main-pool connection (SL-43); the
 * pool exists whether or not the security module is on; task reads run in one read transaction.
 */
class SecurityDenialAuditWiringTest {

    static HikariDataSource main(int size) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl("jdbc:h2:mem:secaudit" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        ds.setMaximumPoolSize(size);
        return ds;
    }

    static Object field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    @Test
    void theLeafPoolIsSeparateNamedAndSized() throws Exception {
        try (HikariDataSource ds = main(5)) {
            SecurityAuditPoolWiring.SecurityAuditPool pool = SecurityAuditPoolWiring.pool(ds, 2);
            try {
                HikariDataSource leaf = pool.poolOrNull();
                assertNotNull(leaf);
                assertNotSame(ds, leaf);
                assertEquals(SecurityAuditPoolWiring.POOL_NAME, leaf.getPoolName());
                assertEquals(2, leaf.getMaximumPoolSize());
                try (Connection c = leaf.getConnection()) {
                    assertEquals(0, ds.getHikariPoolMXBean() == null ? 0 : ds.getHikariPoolMXBean().getActiveConnections(),
                            "a leaf connection is not a main-pool connection");
                }
            } finally {
                pool.destroy();
            }
        }
    }

    @Test
    void theSecurityModuleAndTheTaskAdapterBothUseTheLeafPool() throws Exception {
        try (HikariDataSource ds = main(3)) {
            SecurityAuditPoolWiring.SecurityAuditPool pool = SecurityAuditPoolWiring.pool(ds, 2);
            try {
                Transactions product = Transactions.of(ds);
                assertNotSame(product, SecurityWiring.denialTransactions(pool, product), "sangam-security denialTx");

                TxSupport tx = new SpringTxSupport(ds, new DataSourceTransactionManager(ds), TxSupport.detectDialect(ds));
                DenialAudit restDenials = new SecurityAuditPoolWiring().sangamRestDenialAudit(pool, tx);
                DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
                bf.registerSingleton("sangamRestDenialAudit", restDenials);
                ProcessEngine engine = (ProcessEngine) Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[] {ProcessEngine.class}, (p, m, a) -> null);
                OperatonTaskAdapter adapter = assertInstanceOf(OperatonTaskAdapter.class, new SangamRestAutoConfiguration()
                        .sangamTaskPort(engine, tx, null, new RestProperties(), new DataSourceTransactionManager(ds),
                                bf.getBeanProvider(DenialAudit.class)));
                assertSame(restDenials, field(adapter, "denialAudit"), "SL-132: SoD refusals on the leaf pool");
                assertInstanceOf(ReadTransactions.class, field(adapter, "readTransactions"), "SG-15 read transaction");
            } finally {
                pool.destroy();
            }
        }
    }

    /** SL-161: the tenant key store works on the leaf pool, never on a second main-pool connection. */
    @Test
    void theFieldEncryptorKeyStoreUsesTheLeafPool() throws Exception {
        try (HikariDataSource ds = main(3)) {
            SecurityAuditPoolWiring.SecurityAuditPool pool = SecurityAuditPoolWiring.pool(ds, 2);
            try {
                int[] active = SecurityKeysWiring.keyStoreTransactions(pool, ds).inTransaction(c -> new int[] {
                        pool.poolOrNull().getHikariPoolMXBean().getActiveConnections(),
                        ds.getHikariPoolMXBean() == null ? 0 : ds.getHikariPoolMXBean().getActiveConnections()});
                assertEquals(1, active[0], "the key-store transaction holds a leaf-pool connection");
                assertEquals(0, active[1], "and no main-pool connection");
            } finally {
                pool.destroy();
            }
        }
        JdbcDataSource plain = new JdbcDataSource();
        plain.setURL("jdbc:h2:mem:keystore-plain" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        assertNotNull(SecurityKeysWiring.keyStoreTransactions(SecurityAuditPoolWiring.pool(plain, 2), plain),
                "without a leaf pool: the main DataSource, as before");
    }

    @Test
    void aMainPoolThatIsNotHikariGetsTheMainPoolFallback() {
        JdbcDataSource plain = new JdbcDataSource();
        plain.setURL("jdbc:h2:mem:secaudit-plain" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        SecurityAuditPoolWiring.SecurityAuditPool pool = SecurityAuditPoolWiring.pool(plain, 2);
        assertNull(pool.transactionsOrNull());
        Transactions product = Transactions.of(plain);
        assertSame(product, SecurityWiring.denialTransactions(pool, product), "as before SL-132");
    }

    @Test
    void theLeafPoolWiringIsAlwaysOn() {
        assertNull(SecurityAuditPoolWiring.class.getAnnotation(
                org.springframework.boot.autoconfigure.condition.ConditionalOnProperty.class),
                "needed by the REST task adapter even with sangam.modules.security.enabled=false");
    }
}
