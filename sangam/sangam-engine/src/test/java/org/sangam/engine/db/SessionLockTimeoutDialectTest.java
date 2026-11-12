// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** SL-99: reading and restoring the session lock wait, per dialect. */
class SessionLockTimeoutDialectTest {

    /** Records every statement text; queries answer one row with {@code answer}. */
    private static Connection recording(List<String> sql, int answer) {
        Statement st = (Statement) Proxy.newProxyInstance(SessionLockTimeoutDialectTest.class.getClassLoader(),
                new Class<?>[]{Statement.class}, (p, m, a) -> switch (m.getName()) {
                    case "executeQuery" -> {
                        sql.add((String) a[0]);
                        boolean[] read = {false};
                        yield Proxy.newProxyInstance(SessionLockTimeoutDialectTest.class.getClassLoader(),
                                new Class<?>[]{ResultSet.class}, (p2, m2, a2) -> switch (m2.getName()) {
                                    case "next" -> { boolean r = !read[0]; read[0] = true; yield r; }
                                    case "getInt" -> answer;
                                    case "close" -> null;
                                    default -> throw new UnsupportedOperationException(m2.getName());
                                });
                    }
                    case "execute" -> { sql.add((String) a[0]); yield false; }
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        return (Connection) Proxy.newProxyInstance(SessionLockTimeoutDialectTest.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (p, m, a) -> switch (m.getName()) {
                    case "createStatement" -> st;
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    @Test
    void sqlServerReadsAndRestoresLockTimeoutInMilliseconds() {
        List<String> sql = new ArrayList<>();
        Connection c = recording(sql, -1);
        assertEquals(-1, EngineDialect.SQLSERVER.sessionLockTimeout(c));
        EngineDialect.SQLSERVER.restoreSessionLockTimeout(c, -1);
        assertEquals(List.of("SELECT @@LOCK_TIMEOUT", "SET LOCK_TIMEOUT -1"), sql);
    }

    @Test
    void mysqlReadsAndRestoresInnodbLockWaitTimeout() {
        List<String> sql = new ArrayList<>();
        Connection c = recording(sql, 50);
        assertEquals(50, EngineDialect.MYSQL.sessionLockTimeout(c));
        EngineDialect.MYSQL.restoreSessionLockTimeout(c, 50);
        assertEquals(List.of("SELECT @@SESSION.innodb_lock_wait_timeout", "SET SESSION innodb_lock_wait_timeout = 50"), sql);
    }

    @Test
    void transactionScopedDialectsNeedNoRestore() {
        List<String> sql = new ArrayList<>();
        Connection c = recording(sql, 0);
        for (EngineDialect d : List.of(EngineDialect.POSTGRESQL, EngineDialect.ORACLE)) {
            assertNull(d.sessionLockTimeout(c), d.name());
            d.restoreSessionLockTimeout(c, 5);
        }
        assertEquals(List.of(), sql, "SET LOCAL (PostgreSQL) and FOR UPDATE WAIT n (Oracle) do not outlive the transaction");
    }

    @Test
    void h2ReadAndRestoreOnARealSession() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:sl99d_" + UUID.randomUUID() + ";MODE=PostgreSQL")) {
            try (Statement s = c.createStatement()) {
                s.execute("SET LOCK_TIMEOUT 4321");
            }
            assertEquals(4321, EngineDialect.H2.sessionLockTimeout(c));
            EngineDialect.H2.applyLockTimeouts(c, java.time.Duration.ofMillis(1234), java.time.Duration.ofSeconds(5));
            assertEquals(1234, EngineDialect.H2.sessionLockTimeout(c));
            EngineDialect.H2.restoreSessionLockTimeout(c, 4321);
            assertEquals(4321, EngineDialect.H2.sessionLockTimeout(c));
        }
    }
}