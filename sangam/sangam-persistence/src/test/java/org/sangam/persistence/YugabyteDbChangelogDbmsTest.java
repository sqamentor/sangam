// SPDX-License-Identifier: Apache-2.0
package org.sangam.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;

import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import org.junit.jupiter.api.Test;

/**
 * M-108: YugabyteDB YSQL has no changelog of its own. Through the PostgreSQL driver it reports the product
 * {@code PostgreSQL}, and Liquibase 4.33.0 then picks its {@code postgresql} database (shortName {@code postgresql}),
 * so the {@code dbms:postgresql} changesets are the ones that run (verified on a real server by sangam-engine's
 * {@code YugabyteDbIT}). This pins that choice: Liquibase matches {@code dbms:} by the short name only
 * ({@code DatabaseList.definitionMatches}), so a database that claimed such a server under another short name (a
 * YugabyteDB extension on the class path, for example) would skip every product changeset on it, silently.
 */
class YugabyteDbChangelogDbmsTest {

    private static final String YB = "PostgreSQL 11.2-YB-2.20.2.0-b0 on x86_64-pc-linux-gnu, compiled by clang version "
            + "16.0.6, 64-bit";

    /** One row holding {@code value}, read by index or by column 1. */
    private static ResultSet versionRow(String value) {
        boolean[] read = {false};
        return (ResultSet) Proxy.newProxyInstance(YugabyteDbChangelogDbmsTest.class.getClassLoader(),
                new Class<?>[] {ResultSet.class}, (p, m, a) -> switch (m.getName()) {
                    case "next" -> {
                        boolean first = !read[0];
                        read[0] = true;
                        yield first;
                    }
                    case "getString", "getObject" -> value;
                    case "wasNull" -> false;
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException("ResultSet." + m.getName());
                });
    }

    /** What a YugabyteDB server answers through pgjdbc 42.7.12: product PostgreSQL, version 11.2, the YB version text. */
    private static Connection yugabyteThroughPgjdbc() {
        DatabaseMetaData md = (DatabaseMetaData) Proxy.newProxyInstance(
                YugabyteDbChangelogDbmsTest.class.getClassLoader(), new Class<?>[] {DatabaseMetaData.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getDatabaseProductName" -> "PostgreSQL";
                    case "getDatabaseProductVersion" -> "11.2";
                    case "getDatabaseMajorVersion" -> 11;
                    case "getDatabaseMinorVersion" -> 2;
                    case "getDriverName" -> "PostgreSQL JDBC Driver";
                    case "getURL" -> "jdbc:postgresql://yb.example:5433/yugabyte";
                    case "getUserName" -> "yugabyte";
                    default -> throw new UnsupportedOperationException("DatabaseMetaData." + m.getName());
                });
        Statement st = (Statement) Proxy.newProxyInstance(YugabyteDbChangelogDbmsTest.class.getClassLoader(),
                new Class<?>[] {Statement.class}, (p, m, a) -> switch (m.getName()) {
                    case "executeQuery" -> {
                        assertEquals("select version()", String.valueOf(a[0]).trim().toLowerCase(java.util.Locale.ROOT));
                        yield versionRow(YB);
                    }
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException("Statement." + m.getName());
                });
        return (Connection) Proxy.newProxyInstance(YugabyteDbChangelogDbmsTest.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (p, m, a) -> switch (m.getName()) {
                    case "getMetaData" -> md;
                    case "createStatement" -> st;
                    case "isClosed" -> false;
                    case "getAutoCommit" -> true;
                    case "getCatalog" -> "yugabyte";
                    case "getSchema" -> "public";
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException("Connection." + m.getName());
                });
    }

    @Test
    void liquibaseTakesYugabyteDbForPostgresql() throws Exception {
        JdbcConnection conn = new JdbcConnection(yugabyteThroughPgjdbc());
        List<Database> claimants = new java.util.ArrayList<>();
        for (Database d : DatabaseFactory.getInstance().getImplementedDatabases()) {
            if (d.isCorrectDatabaseImplementation(conn)) {
                claimants.add(d);
            }
        }
        assertFalse(claimants.isEmpty(), "no Liquibase database claims the server");
        Database chosen = claimants.stream().max(Comparator.comparingInt(Database::getPriority)).orElseThrow();
        assertEquals("postgresql", chosen.getShortName(), "claimed by " + claimants.stream()
                .map(Database::getShortName).toList());
        assertTrue(claimants.stream().noneMatch(d -> d.getShortName().equals("cockroachdb")),
                "the CockroachDB database must not claim a YugabyteDB server");
    }
}
