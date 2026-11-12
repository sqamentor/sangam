// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import com.zaxxer.hikari.HikariDataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

/**
 * X1-05: the SL-43 quota pool is derived by the one leaf-pool helper ({@code ComplianceWiring.leafPool}): its own
 * pool, named, sized from {@code sangam.quotas.pool.maximum-size}, on the main pool's database; a main pool that is not
 * HikariCP is refused as before.
 */
class QuotaLeafPoolTest {

    @Test
    void theQuotaPoolIsALeafPoolOfTheMainPool() {
        try (HikariDataSource main = new HikariDataSource()) {
            main.setJdbcUrl("jdbc:h2:mem:quotaleaf" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
            main.setUsername("sa");
            main.setPassword("");
            main.setMaximumPoolSize(5);
            try (HikariDataSource quota = QuotaWiring.quotaPool(main, 4)) {
                assertNotSame(main, quota);
                assertEquals(QuotaWiring.QUOTA_POOL_NAME, quota.getPoolName());
                assertEquals(4, quota.getMaximumPoolSize());
                assertEquals(main.getJdbcUrl(), quota.getJdbcUrl(), "same database and settings");
                assertTrue(quota.isAutoCommit());
            }
            try (HikariDataSource tiny = QuotaWiring.quotaPool(main, 0)) {
                assertEquals(1, tiny.getMaximumPoolSize(), "at least one connection");
            }
        }
    }

    @Test
    void aMainPoolThatIsNotHikariIsRefused() {
        JdbcDataSource plain = new JdbcDataSource();
        plain.setURL("jdbc:h2:mem:quotaleaf-plain");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> QuotaWiring.quotaPool(plain, 4));
        assertTrue(e.getMessage().contains("HikariCP") && e.getMessage().contains("sangam.quotas.enabled=false"),
                e.getMessage());
    }
}
