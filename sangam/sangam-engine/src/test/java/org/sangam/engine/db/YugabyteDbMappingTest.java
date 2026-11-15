// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngineException;
import org.sangam.engine.SangamEngineSettings;

/**
 * M-108 (decisions 156 and 323): YugabyteDB YSQL is detected from its {@code version()} text (the PostgreSQL driver
 * reports the product {@code PostgreSQL} for it), refused with {@code YUGABYTEDB_EXPERIMENTAL_OFF} unless the operator
 * enables the experimental mode, refused with {@code YUGABYTEDB_READ_COMMITTED_OFF} unless the server itself names
 * READ COMMITTED as the isolation in effect, and then mapped to the PostgreSQL dialect and Operaton databaseType
 * postgres. Unit level with a fake JDBC connection; the real run is {@code YugabyteDbIT} (DB matrix lane yugabytedb,
 * non-gating), NOT_RUN locally.
 */
class YugabyteDbMappingTest {

    private static final String YB = "PostgreSQL 11.2-YB-2.20.2.0-b0 on x86_64-pc-linux-gnu, compiled by clang version "
            + "16.0.6, 64-bit";

    /** The SQL the fake connection was asked to run, in order. */
    private final List<String> asked = new ArrayList<>();

    private static ResultSet oneRow(String value) {
        boolean[] read = {false};
        return (ResultSet) Proxy.newProxyInstance(YugabyteDbMappingTest.class.getClassLoader(),
                new Class<?>[] {ResultSet.class}, (p, m, a) -> switch (m.getName()) {
                    case "next" -> {
                        boolean first = !read[0];
                        read[0] = true;
                        yield first;
                    }
                    case "getString" -> value;
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    /**
     * A connection that answers what the guard reads: product name, {@code version()} text, READ COMMITTED as the
     * JDBC isolation, and {@code effectiveIsolation} for the YugabyteDB setting (null: the setting is absent).
     */
    private DataSource server(String versionText, String effectiveIsolation) {
        Statement st = (Statement) Proxy.newProxyInstance(YugabyteDbMappingTest.class.getClassLoader(),
                new Class<?>[] {Statement.class}, (p, m, a) -> switch (m.getName()) {
                    case "executeQuery" -> {
                        asked.add((String) a[0]);
                        assertEquals("SELECT version()", a[0]);
                        yield oneRow(versionText);
                    }
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        DatabaseMetaData md = (DatabaseMetaData) Proxy.newProxyInstance(YugabyteDbMappingTest.class.getClassLoader(),
                new Class<?>[] {DatabaseMetaData.class}, (p, m, a) -> switch (m.getName()) {
                    case "getDatabaseProductName" -> "PostgreSQL";
                    case "getDatabaseProductVersion" -> "11.2";
                    case "getDriverName" -> "PostgreSQL JDBC Driver";
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        Connection c = (Connection) Proxy.newProxyInstance(YugabyteDbMappingTest.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (p, m, a) -> switch (m.getName()) {
                    case "getMetaData" -> md;
                    case "createStatement" -> st;
                    case "prepareStatement" -> {
                        asked.add((String) a[0]);
                        assertEquals(DatabaseGuard.YB_EFFECTIVE_ISOLATION_SQL, a[0]);
                        yield Proxy.newProxyInstance(YugabyteDbMappingTest.class.getClassLoader(),
                                new Class<?>[] {PreparedStatement.class}, (p2, m2, a2) -> switch (m2.getName()) {
                                    case "executeQuery" -> oneRow(effectiveIsolation);
                                    case "close" -> null;
                                    default -> throw new UnsupportedOperationException(m2.getName());
                                });
                    }
                    case "getTransactionIsolation" -> Connection.TRANSACTION_READ_COMMITTED;
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        return (DataSource) Proxy.newProxyInstance(YugabyteDbMappingTest.class.getClassLoader(),
                new Class<?>[] {DataSource.class}, (p, m, a) -> {
                    if ("getConnection".equals(m.getName())) {
                        return c;
                    }
                    throw new UnsupportedOperationException(m.getName());
                });
    }

    @Test
    void versionTextDecidesNotTheProductName() {
        assertTrue(EngineDialect.isYugabyteDbVersion(YB));
        assertTrue(EngineDialect.isYugabyteDbVersion("PostgreSQL 15.2-YB-2.25.0.0-b0 on x86_64-pc-linux-gnu"));
        assertFalse(EngineDialect.isYugabyteDbVersion("PostgreSQL 17.2 (Debian 17.2-1.pgdg120+1) on x86_64-pc-linux-gnu"));
        assertFalse(EngineDialect.isYugabyteDbVersion("CockroachDB CCL v24.1.0 (x86_64-pc-linux-gnu)"));
        assertFalse(EngineDialect.isYugabyteDbVersion(null));
    }

    @Test
    void yugabyteDbIsRefusedUnlessTheExperimentalModeIsOn() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> DatabaseGuard.check(server(YB, "read committed"), true, "mysql", false, false));
        assertTrue(e.getMessage().startsWith(DatabaseGuard.YUGABYTEDB_EXPERIMENTAL_OFF), e.getMessage());
        assertThrows(IllegalStateException.class, () -> DatabaseGuard.check(server(YB, "read committed"), true, "mysql"),
                "the three-argument check refuses it too");
        assertThrows(IllegalStateException.class,
                () -> DatabaseGuard.check(server(YB, "read committed"), true, "mysql", true),
                "the CockroachDB switch does not admit YugabyteDB");
    }

    @Test
    void experimentalModeNeedsReadCommittedInEffect() {
        for (String effective : new String[] {"repeatable read", "serializable", "", null}) {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> DatabaseGuard.check(server(YB, effective), true, "mysql", false, true), String.valueOf(effective));
            assertTrue(e.getMessage().startsWith(DatabaseGuard.YUGABYTEDB_READ_COMMITTED_OFF), e.getMessage());
            assertTrue(e.getMessage().contains("yb_enable_read_committed_isolation=true"), e.getMessage());
        }
    }

    @Test
    void experimentalModeWithReadCommittedMapsYugabyteDbToThePostgresqlDialect() {
        DatabaseGuard.Findings f = DatabaseGuard.check(server(YB, "read committed"), true, "mysql", false, true);
        assertEquals(EngineDialect.POSTGRESQL, f.dialect());
        assertEquals("postgres", f.operatonDatabaseType());
        assertTrue(f.experimentalYugabyteDb());
        assertFalse(f.experimentalCockroachDb());
        assertEquals(List.of("SELECT version()", DatabaseGuard.YB_EFFECTIVE_ISOLATION_SQL), asked);
    }

    @Test
    void realPostgresqlIsUncheckedForYugabyteDb() {
        DatabaseGuard.Findings f = DatabaseGuard.check(server("PostgreSQL 17.2 on x86_64-pc-linux-gnu", null), true,
                "mysql", false, false);
        assertEquals("postgres", f.operatonDatabaseType());
        assertFalse(f.experimentalYugabyteDb());
        assertEquals(List.of("SELECT version()"), asked, "no YugabyteDB setting is read on PostgreSQL");
    }

    @Test
    void serialisationFailureIsRetryable() {
        // YugabyteDB reports transaction conflicts and read restarts with SQLState 40001, like PostgreSQL
        SQLException restart = new SQLException("Restart read required", "40001");
        assertTrue(Conflicts.isRetryable(new ProcessEngineException("flush failed", restart), false));
    }

    @Test
    void environmentSwitchesTheExperimentalMode() {
        SangamEngineSettings s = new SangamEngineSettings();
        s.setSystemAuditScope("system:platform"); // decision 144: required at engine start
        assertFalse(s.isExperimentalYugabyteDb(), "off by default");
        s.applyEnvironment(Map.of("SANGAM_EXPERIMENTAL_YUGABYTEDB", "true"));
        assertTrue(s.isExperimentalYugabyteDb());
        assertFalse(s.isExperimentalCockroachDb(), "one switch per database");
    }
}
