// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.sangam.core.db.DatabaseIdentity;

/**
 * D7 (R84, support matrix Tier 1 "MySQL 8.4"): the engine start-up check refuses a MySQL server older than the
 * supported line with {@code MYSQL_VERSION_REFUSED} before the engine creates or upgrades its schema; it used to accept
 * any MySQL version. MariaDB (its own support row) and the supported line still pass.
 */
class DatabaseGuardMysqlVersionTest {

    /** A MySQL-family connection through MariaDB Connector/J (the shipped driver) reporting {@code productVersion}. */
    private static DataSource mysql(String productName, String productVersion) {
        ClassLoader cl = DatabaseGuardMysqlVersionTest.class.getClassLoader();
        DatabaseMetaData md = (DatabaseMetaData) Proxy.newProxyInstance(cl, new Class<?>[]{DatabaseMetaData.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getDatabaseProductName" -> productName;
                    case "getDatabaseProductVersion" -> productVersion;
                    case "getDriverName" -> "MariaDB Connector/J";
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        Connection c = (Connection) Proxy.newProxyInstance(cl, new Class<?>[]{Connection.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getMetaData" -> md;
                    case "getTransactionIsolation" -> Connection.TRANSACTION_READ_COMMITTED;
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        return (DataSource) Proxy.newProxyInstance(cl, new Class<?>[]{DataSource.class}, (p, m, a) -> {
            if ("getConnection".equals(m.getName())) {
                return c;
            }
            throw new UnsupportedOperationException(m.getName());
        });
    }

    @Test
    void mysqlOlderThanTheSupportedLineIsRefused() {
        for (String old : new String[]{"8.0.36", "8.3.0", "5.7.44-log"}) {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> DatabaseGuard.check(mysql("MySQL", old), true, "mysql"), old);
            assertTrue(e.getMessage().startsWith(DatabaseIdentity.MYSQL_VERSION_REFUSED + ": R84: the engine"),
                    e.getMessage());
        }
    }

    @Test
    void anUnreadableMysqlVersionFailsClosed() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> DatabaseGuard.check(mysql("MySQL", null), true, "mysql"));
        assertTrue(e.getMessage().startsWith(DatabaseIdentity.MYSQL_VERSION_REFUSED + ": "), e.getMessage());
    }

    @Test
    void theSupportedLineAndMariaDbPass() {
        assertEquals("mysql", DatabaseGuard.check(mysql("MySQL", "8.4.5"), true, "mysql").operatonDatabaseType());
        assertEquals("mariadb", DatabaseGuard.check(mysql("MariaDB", "10.6.18-MariaDB"), true, "mysql")
                .operatonDatabaseType());
        assertEquals("mariadb", DatabaseGuard.check(mysql("MySQL", "11.4.5-MariaDB-ubu2404"), true, "mysql")
                .operatonDatabaseType());
    }
}
