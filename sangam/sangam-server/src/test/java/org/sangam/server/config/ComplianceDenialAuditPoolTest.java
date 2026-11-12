// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * M-009: the compliance denial-audit pool is a leaf pool, separate from the main pool (SL-43 pattern excluded): a
 * denial can always get its connection while callers hold every main-pool connection.
 */
class ComplianceDenialAuditPoolTest {

    static HikariDataSource main(int size) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:h2:mem:cmpaudit" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        cfg.setUsername("sa");
        cfg.setPassword("");
        cfg.setPoolName("main");
        cfg.setMaximumPoolSize(size);
        cfg.setConnectionTimeout(2_000);
        return new HikariDataSource(cfg);
    }

    @Test
    void leafPoolIsSeparateAndNamed() {
        try (HikariDataSource ds = main(3); HikariDataSource leaf = ComplianceWiring.leafPool(ds, 2)) {
            assertNotSame(ds, leaf);
            assertEquals(ComplianceWiring.AUDIT_POOL_NAME, leaf.getPoolName());
            assertEquals(2, leaf.getMaximumPoolSize());
            assertEquals(ds.getJdbcUrl(), leaf.getJdbcUrl(), "same database as the main pool");
            assertTrue(leaf.isAutoCommit());
        }
    }

    @Test
    void denialConnectionIsAvailableWhileCallersHoldEveryMainConnection() throws Exception {
        try (HikariDataSource ds = main(3); HikariDataSource leaf = ComplianceWiring.leafPool(ds, 1)) {
            List<Connection> held = new ArrayList<>();
            try {
                for (int i = 0; i < 3; i++) {
                    held.add(ds.getConnection());
                }
                assertEquals(0, ds.getHikariPoolMXBean().getIdleConnections(), "every main connection is held");
                assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                    try (Connection c = leaf.getConnection()) {
                        assertTrue(c.isValid(1));
                    }
                });
            } finally {
                for (Connection c : held) {
                    c.close();
                }
            }
        }
    }

    @Test
    void sizeIsAtLeastOne() {
        try (HikariDataSource ds = main(2); HikariDataSource leaf = ComplianceWiring.leafPool(ds, 0)) {
            assertEquals(1, leaf.getMaximumPoolSize());
        }
    }

    @Test
    void aNonHikariMainPoolIsRefused() {
        JdbcDataSource plain = new JdbcDataSource();
        plain.setURL("jdbc:h2:mem:cmpaudit-plain");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> ComplianceWiring.leafPool(plain, 2));
        assertTrue(e.getMessage().contains("HikariCP"));
    }
}
