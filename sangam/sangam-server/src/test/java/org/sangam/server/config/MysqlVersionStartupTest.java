// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.sangam.core.db.DatabaseIdentity;
import org.springframework.mock.env.MockEnvironment;

/**
 * D7 (R84, support matrix Tier 1 "MySQL 8.4"): the server refuses a MySQL server older than the supported line with
 * {@code MYSQL_VERSION_REFUSED}: the product-schema step before Liquibase touches the database (every Liquibase mode),
 * and the start-up pool check, which used to log a warning and start.
 */
class MysqlVersionStartupTest {

    /**
     * A MySQL-family data source: metadata {@code product}/{@code version}, READ COMMITTED, and the IsolationGuard query
     * answering READ-COMMITTED, utf8mb4 and {@code version}; counts connections and statements.
     */
    static DataSource mysql(String product, String version, AtomicInteger statements) {
        ClassLoader cl = MysqlVersionStartupTest.class.getClassLoader();
        DatabaseMetaData md = (DatabaseMetaData) Proxy.newProxyInstance(cl, new Class<?>[]{DatabaseMetaData.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getDatabaseProductName" -> product;
                    case "getDatabaseProductVersion" -> version;
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        boolean[] read = {false};
        ResultSet rs = (ResultSet) Proxy.newProxyInstance(cl, new Class<?>[]{ResultSet.class},
                (p, m, a) -> switch (m.getName()) {
                    case "next" -> {
                        boolean first = !read[0];
                        read[0] = true;
                        yield first;
                    }
                    case "getString" -> switch ((Integer) a[0]) {
                        case 1 -> "READ-COMMITTED";
                        case 2 -> "utf8mb4";
                        default -> version;
                    };
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        Statement st = (Statement) Proxy.newProxyInstance(cl, new Class<?>[]{Statement.class},
                (p, m, a) -> switch (m.getName()) {
                    case "executeQuery" -> {
                        statements.incrementAndGet();
                        yield rs;
                    }
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        Connection c = (Connection) Proxy.newProxyInstance(cl, new Class<?>[]{Connection.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getMetaData" -> md;
                    case "getTransactionIsolation" -> Connection.TRANSACTION_READ_COMMITTED;
                    case "createStatement" -> {
                        statements.incrementAndGet();
                        yield st;
                    }
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
    void theProductSchemaStepRefusesOldMysqlBeforeAnySqlInEveryMode() {
        for (String mode : new String[]{null, "migrate-only", "validate", "update-sql"}) {
            MockEnvironment env = new MockEnvironment();
            if (mode != null) {
                env.setProperty(LiquibaseMode.PROPERTY, mode);
            }
            AtomicInteger statements = new AtomicInteger();
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> new SchemaBootstrap().sangamLiquibase(mysql("MySQL", "8.0.36", statements), env),
                    "mode " + mode);
            assertTrue(e.getMessage().startsWith(DatabaseIdentity.MYSQL_VERSION_REFUSED + ": R84: sangam-server"),
                    e.getMessage());
            assertEquals(0, statements.get(), "refused from the metadata, before any statement");
        }
    }

    @Test
    void theDevelopmentSchemaStepRefusesOldMysqlToo() {
        AtomicInteger statements = new AtomicInteger();
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new SchemaBootstrap().sangamDevSchema(mysql("MySQL", "5.7.44-log", statements),
                        new MockEnvironment()).afterPropertiesSet());
        assertTrue(e.getMessage().startsWith(DatabaseIdentity.MYSQL_VERSION_REFUSED + ": "), e.getMessage());
        assertEquals(0, statements.get());
    }

    @Test
    void thePoolCheckRefusesOldMysqlInsteadOfOnlyWarning() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> IsolationGuard.verify("sangamLeaf", mysql("MySQL", "8.0.36", new AtomicInteger()), true));
        assertTrue(e.getMessage().startsWith(DatabaseIdentity.MYSQL_VERSION_REFUSED + ": R84: DataSource 'sangamLeaf'"),
                e.getMessage());
    }

    @Test
    void thePoolCheckAcceptsTheSupportedLineANewerLineAndMariaDb() {
        assertDoesNotThrow(() -> IsolationGuard.verify("main", mysql("MySQL", "8.4.5", new AtomicInteger()), true));
        assertDoesNotThrow(() -> IsolationGuard.verify("main", mysql("MySQL", "9.1.0", new AtomicInteger()), true));
        assertDoesNotThrow(() -> IsolationGuard.verify("main",
                mysql("MariaDB", "11.4.5-MariaDB-ubu2404", new AtomicInteger()), true));
    }
}
