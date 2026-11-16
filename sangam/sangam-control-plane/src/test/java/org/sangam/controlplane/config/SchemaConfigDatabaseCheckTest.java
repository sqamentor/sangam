// SPDX-License-Identifier: Apache-2.0
package org.sangam.controlplane.config;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.function.Function;

import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.sangam.core.db.DatabaseIdentity;

/**
 * Decision 384: sangam-control-plane refuses a database its changelog does not apply on, by a stable code, before Liquibase
 * runs (the schema bean is not built), instead of starting on an empty schema. One test per refused database.
 */
class SchemaConfigDatabaseCheckTest {

    @Test
    void refusesMariaDb() {
        assertRefused(fake("MariaDB", "11.4.5-MariaDB-ubu2404", null), DatabaseIdentity.SCALE_DB_MARIADB_REFUSED);
    }

    @Test
    void refusesCockroachDb() {
        assertRefused(fake("PostgreSQL", "13.0.0", "CockroachDB CCL v24.3.6 (x86_64-pc-linux-gnu, built 2025/02/24)"),
                DatabaseIdentity.SCALE_DB_COCKROACHDB_REFUSED);
    }

    @Test
    void refusesYugabyteDb() {
        assertRefused(fake("PostgreSQL", "11.2-YB-2.20.2.0-b0", "PostgreSQL 11.2-YB-2.20.2.0-b0 on x86_64-pc-linux-gnu"),
                DatabaseIdentity.SCALE_DB_YUGABYTEDB_REFUSED);
    }

    @Test
    void refusesAnyOtherDatabase() {
        assertRefused(fake("Apache Derby", "10.17.1.0", null), DatabaseIdentity.SCALE_DB_UNSUPPORTED);
    }

    /** D7 (R84): a MySQL server older than the supported 8.4 line is refused before Liquibase; 8.4 is not. */
    @Test
    void refusesMysqlOlderThanTheSupportedLine() {
        for (String old : new String[]{"8.0.36", "8.3.0", "5.7.44-log"}) {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> new SchemaConfig().controlPlaneLiquibase(fake("MySQL", old, null), false), old);
            assertTrue(e.getMessage().startsWith(DatabaseIdentity.MYSQL_VERSION_REFUSED
                    + ": R84: sangam-control-plane"), e.getMessage());
        }
        assertNotNull(DatabaseIdentity.requireScaleDatabase(fake("MySQL", "8.4.5", null), "sangam-control-plane"));
    }

    @Test
    void buildsTheSchemaBeanOnH2() {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:SchemaConfigDatabaseCheckTest;MODE=PostgreSQL");
        assertNotNull(new SchemaConfig().controlPlaneLiquibase(h2, true));
    }

    @Test
    void checksEvenWhenTheSchemaIsNotApplied() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new SchemaConfig().controlPlaneLiquibase(fake("MariaDB", "11.4.5-MariaDB", null), false));
        assertTrue(e.getMessage().startsWith(DatabaseIdentity.SCALE_DB_MARIADB_REFUSED + ": "), e.getMessage());
    }

    private static void assertRefused(DataSource ds, String code) {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new SchemaConfig().controlPlaneLiquibase(ds, true));
        assertTrue(e.getMessage().startsWith(code + ": decision 384: sangam-control-plane"), e.getMessage());
    }

    private static DataSource fake(String product, String productVersion, String version) {
        ClassLoader cl = SchemaConfigDatabaseCheckTest.class.getClassLoader();
        DatabaseMetaData md = proxy(cl, DatabaseMetaData.class, n -> switch (n) {
            case "getDatabaseProductName" -> product;
            case "getDatabaseProductVersion" -> productVersion;
            default -> null;
        });
        boolean[] read = {false};
        ResultSet rs = proxy(cl, ResultSet.class, n -> switch (n) {
            case "next" -> {
                boolean first = !read[0];
                read[0] = true;
                yield first && version != null;
            }
            case "getString" -> version;
            default -> null;
        });
        Statement st = proxy(cl, Statement.class, n -> n.equals("executeQuery") ? rs : null);
        Connection c = proxy(cl, Connection.class, n -> switch (n) {
            case "getMetaData" -> md;
            case "createStatement" -> st;
            default -> null;
        });
        return proxy(cl, DataSource.class, n -> n.equals("getConnection") ? c : null);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(ClassLoader cl, Class<T> type, Function<String, Object> answer) {
        return (T) Proxy.newProxyInstance(cl, new Class<?>[]{type}, (p, m, a) -> {
            Object r = answer.apply(m.getName());
            return r == null && m.getReturnType() == boolean.class ? Boolean.FALSE : r;
        });
    }
}