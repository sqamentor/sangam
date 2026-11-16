// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.sangam.core.db.DatabaseIdentity.Server;

/**
 * Decision 384: the scale modules' start-up check identifies the server from the server (version text, product
 * version), refuses MariaDB, CockroachDB, YugabyteDB and any other database with a stable code, fails closed when the
 * database cannot be read, and accepts the five databases of their changelogs.
 */
class DatabaseIdentityTest {

    static final String MARIADB_VERSION = "11.4.5-MariaDB-ubu2404";
    static final String COCKROACH_VERSION = "CockroachDB CCL v24.3.6 (x86_64-pc-linux-gnu, built 2025/02/24, go1.22.8)";
    static final String YUGABYTE_VERSION = "PostgreSQL 11.2-YB-2.20.2.0-b0 on x86_64-pc-linux-gnu, compiled by clang "
            + "version 16.0.6, 64-bit";

    @Test
    void refusesMariaDbByProductName() {
        assertRefused(fake("MariaDB", MARIADB_VERSION, null), DatabaseIdentity.SCALE_DB_MARIADB_REFUSED);
    }

    @Test
    void refusesMariaDbReportedAsMysqlThroughUseMysqlMetadata() {
        assertRefused(fake("MySQL", MARIADB_VERSION, null), DatabaseIdentity.SCALE_DB_MARIADB_REFUSED);
    }

    @Test
    void refusesCockroachDbThatThePostgresqlDriverReportsAsPostgresql() {
        assertRefused(fake("PostgreSQL", "13.0.0", COCKROACH_VERSION), DatabaseIdentity.SCALE_DB_COCKROACHDB_REFUSED);
    }

    @Test
    void refusesYugabyteDbThatThePostgresqlDriverReportsAsPostgresql() {
        assertRefused(fake("PostgreSQL", "11.2-YB-2.20.2.0-b0", YUGABYTE_VERSION),
                DatabaseIdentity.SCALE_DB_YUGABYTEDB_REFUSED);
    }

    @Test
    void refusesAnyOtherDatabase() {
        assertRefused(fake("Apache Derby", "10.17.1.0", null), DatabaseIdentity.SCALE_DB_UNSUPPORTED);
    }

    @Test
    void failsClosedWhenTheDatabaseCannotBeRead() {
        DataSource broken = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DataSource.class}, (p, m, a) -> {
                    if (m.getName().equals("getConnection")) {
                        throw new SQLException("connection refused");
                    }
                    return null;
                });
        assertRefused(broken, DatabaseIdentity.SCALE_DB_CHECK_FAILED);
    }

    @Test
    void acceptsTheFiveDatabasesOfTheScaleChangelogs() {
        assertEquals(Server.POSTGRESQL, DatabaseIdentity.requireScaleDatabase(
                fake("PostgreSQL", "17.5", "PostgreSQL 17.5 on x86_64-pc-linux-gnu, compiled by gcc, 64-bit"), "m"));
        assertEquals(Server.ORACLE, DatabaseIdentity.requireScaleDatabase(fake("Oracle", "23.0", null), "m"));
        assertEquals(Server.SQLSERVER,
                DatabaseIdentity.requireScaleDatabase(fake("Microsoft SQL Server", "16.00.4175", null), "m"));
        assertEquals(Server.MYSQL, DatabaseIdentity.requireScaleDatabase(fake("MySQL", "8.4.5", null), "m"));
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:identity;MODE=PostgreSQL");
        assertEquals(Server.H2, DatabaseIdentity.requireScaleDatabase(h2, "m"));
    }

    /** D7 (R84): the supported MySQL line is data in one place; older or unreadable versions are not supported. */
    @Test
    void mysqlVersionsBelowTheSupportedLineOrUnreadableAreNotSupported() {
        assertEquals("8.4", DatabaseIdentity.MYSQL_MINIMUM_VERSION);
        for (String ok : new String[]{"8.4.0", "8.4.5", "8.4.5-log", " 8.4.7 ", "8.10.1", "9.1.0", "10.0.0"}) {
            assertTrue(DatabaseIdentity.isSupportedMysqlVersion(ok), ok);
        }
        for (String old : new String[]{"8.0.36", "8.3.0", "8.0.40-log", "5.7.44-log", "7.9.9"}) {
            assertTrue(!DatabaseIdentity.isSupportedMysqlVersion(old), old);
        }
        for (String unreadable : new String[]{null, "", "MySQL", "eight", "8"}) {
            assertTrue(!DatabaseIdentity.isSupportedMysqlVersion(unreadable), String.valueOf(unreadable));
        }
    }

    @Test
    void scaleCheckRefusesMysqlOlderThanTheSupportedLine() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> DatabaseIdentity.requireScaleDatabase(fake("MySQL", "8.0.36", null), "sangam-test-module"));
        assertTrue(e.getMessage().startsWith(DatabaseIdentity.MYSQL_VERSION_REFUSED + ": R84: sangam-test-module"),
                e.getMessage());
        assertTrue(e.getMessage().contains("MySQL 8.0.36"), e.getMessage());
    }

    @Test
    void serverCheckRefusesOnlyOldMysqlAndFailsClosed() {
        IllegalStateException old = assertThrows(IllegalStateException.class,
                () -> DatabaseIdentity.requireSupportedMysql(fake("MySQL", "8.0.36", null), "sangam-server"));
        assertTrue(old.getMessage().startsWith(DatabaseIdentity.MYSQL_VERSION_REFUSED + ": R84: sangam-server"),
                old.getMessage());
        IllegalStateException noVersion = assertThrows(IllegalStateException.class,
                () -> DatabaseIdentity.requireSupportedMysql(fake("MySQL", null, null), "sangam-server"));
        assertTrue(noVersion.getMessage().startsWith(DatabaseIdentity.MYSQL_VERSION_REFUSED + ": "),
                noVersion.getMessage());
        DataSource broken = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DataSource.class}, (p, m, a) -> {
                    throw new SQLException("connection refused");
                });
        IllegalStateException unreadable = assertThrows(IllegalStateException.class,
                () -> DatabaseIdentity.requireSupportedMysql(broken, "sangam-server"));
        assertTrue(unreadable.getMessage().startsWith(DatabaseIdentity.MYSQL_VERSION_REFUSED + ": "),
                unreadable.getMessage());
        // The supported line, MariaDB (its own support row, also when reported as MySQL) and other servers pass.
        DatabaseIdentity.requireSupportedMysql(fake("MySQL", "8.4.5", null), "sangam-server");
        DatabaseIdentity.requireSupportedMysql(fake("MySQL", MARIADB_VERSION, null), "sangam-server");
        DatabaseIdentity.requireSupportedMysql(fake("MariaDB", "10.6.18-MariaDB", null), "sangam-server");
        DatabaseIdentity.requireSupportedMysql(fake("PostgreSQL", "17.5", null), "sangam-server");
        DatabaseIdentity.requireSupportedMysql(fake("Oracle", "Oracle Database 19c", null), "sangam-server");
    }

    private static void assertRefused(DataSource ds, String code) {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> DatabaseIdentity.requireScaleDatabase(ds, "sangam-test-module"));
        assertTrue(e.getMessage().startsWith(code + ": decision 384: sangam-test-module"), e.getMessage());
    }

    /** A DataSource whose metadata reports {@code product}/{@code productVersion} and whose version() is {@code version}. */
    static DataSource fake(String product, String productVersion, String version) {
        ClassLoader cl = DatabaseIdentityTest.class.getClassLoader();
        DatabaseMetaData md = proxy(cl, DatabaseMetaData.class, name -> switch (name) {
            case "getDatabaseProductName" -> product;
            case "getDatabaseProductVersion" -> productVersion;
            default -> null;
        });
        boolean[] read = {false};
        ResultSet rs = proxy(cl, ResultSet.class, name -> switch (name) {
            case "next" -> {
                boolean first = !read[0];
                read[0] = true;
                yield first && version != null;
            }
            case "getString" -> version;
            default -> null;
        });
        Statement st = proxy(cl, Statement.class, name -> name.equals("executeQuery") ? rs : null);
        Connection c = proxy(cl, Connection.class, name -> switch (name) {
            case "getMetaData" -> md;
            case "createStatement" -> st;
            default -> null;
        });
        return proxy(cl, DataSource.class, name -> name.equals("getConnection") ? c : null);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(ClassLoader cl, Class<T> type, java.util.function.Function<String, Object> answer) {
        return (T) Proxy.newProxyInstance(cl, new Class<?>[]{type}, (p, m, a) -> {
            Object r = answer.apply(m.getName());
            if (r == null && m.getReturnType() == boolean.class) {
                return false;
            }
            return r;
        });
    }
}
