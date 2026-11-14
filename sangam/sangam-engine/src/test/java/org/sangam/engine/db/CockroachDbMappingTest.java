// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngineException;
import org.sangam.engine.SangamEngineSettings;

/**
 * Decisions 156 and 205: CockroachDB is detected (never taken silently for PostgreSQL), refused unless the operator
 * enables the experimental mode, then mapped to the PostgreSQL dialect and Operaton databaseType postgres; a
 * serialisation failure (40001) is retryable, an ambiguous result (40003) is not. Unit level with a fake JDBC
 * connection; a real CockroachDB run is NOT_RUN (DB matrix, non-gating). Written, NOT_RUN.
 */
class CockroachDbMappingTest {

    /** A connection that answers what the guard reads: product name, version() text and READ COMMITTED. */
    private static DataSource server(String productName, String versionText) {
        ResultSet rs = (ResultSet) Proxy.newProxyInstance(CockroachDbMappingTest.class.getClassLoader(),
                new Class<?>[] {ResultSet.class}, (p, m, a) -> switch (m.getName()) {
                    case "next" -> true;
                    case "getString" -> versionText;
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        Statement st = (Statement) Proxy.newProxyInstance(CockroachDbMappingTest.class.getClassLoader(),
                new Class<?>[] {Statement.class}, (p, m, a) -> switch (m.getName()) {
                    case "executeQuery" -> {
                        assertEquals("SELECT version()", a[0]);
                        yield rs;
                    }
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        DatabaseMetaData md = (DatabaseMetaData) Proxy.newProxyInstance(CockroachDbMappingTest.class.getClassLoader(),
                new Class<?>[] {DatabaseMetaData.class}, (p, m, a) -> switch (m.getName()) {
                    case "getDatabaseProductName" -> productName;
                    case "getDatabaseProductVersion" -> "13.0.0";
                    case "getDriverName" -> "PostgreSQL JDBC Driver";
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        Connection c = (Connection) Proxy.newProxyInstance(CockroachDbMappingTest.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (p, m, a) -> switch (m.getName()) {
                    case "getMetaData" -> md;
                    case "createStatement" -> st;
                    case "getTransactionIsolation" -> Connection.TRANSACTION_READ_COMMITTED;
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        return (DataSource) Proxy.newProxyInstance(CockroachDbMappingTest.class.getClassLoader(),
                new Class<?>[] {DataSource.class}, (p, m, a) -> {
                    if ("getConnection".equals(m.getName())) {
                        return c;
                    }
                    throw new UnsupportedOperationException(m.getName());
                });
    }

    private static final String CRDB = "CockroachDB CCL v24.1.0 (x86_64-pc-linux-gnu, built 2024/05/15 21:28:29, go1.22.2)";

    @Test
    void versionTextDecidesNotTheProductName() {
        assertTrue(EngineDialect.isCockroachDbVersion(CRDB));
        assertTrue(EngineDialect.isCockroachDbVersion("  cockroachdb OSS v23.2.1"));
        assertFalse(EngineDialect.isCockroachDbVersion("PostgreSQL 16.4 on x86_64-pc-linux-gnu"));
        assertFalse(EngineDialect.isCockroachDbVersion(null));
    }

    @Test
    void cockroachDbIsRefusedUnlessTheExperimentalModeIsOn() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> DatabaseGuard.check(server("PostgreSQL", CRDB), true, "mysql", false));
        assertTrue(e.getMessage().contains("decision 205"), e.getMessage());
        assertThrows(IllegalStateException.class, () -> DatabaseGuard.check(server("PostgreSQL", CRDB), true, "mysql"),
                "the old three-argument check refuses it too");
    }

    @Test
    void experimentalModeMapsCockroachDbToThePostgresqlDialect() {
        DatabaseGuard.Findings f = DatabaseGuard.check(server("PostgreSQL", CRDB), true, "mysql", true);
        assertEquals(EngineDialect.POSTGRESQL, f.dialect());
        assertEquals("postgres", f.operatonDatabaseType());
        assertTrue(f.experimentalCockroachDb());
    }

    @Test
    void realPostgresqlIsUnchanged() {
        DatabaseGuard.Findings f = DatabaseGuard.check(server("PostgreSQL", "PostgreSQL 16.4 on x86_64"), true, "mysql",
                false);
        assertEquals("postgres", f.operatonDatabaseType());
        assertFalse(f.experimentalCockroachDb());
    }

    @Test
    void serialisationFailureIsRetryableAmbiguousResultIsNot() {
        SQLException restart = new SQLException("restart transaction: TransactionRetryWithProtoRefreshError", "40001");
        assertTrue(Conflicts.isRetryable(new ProcessEngineException("flush failed", restart), false));
        SQLException ambiguous = new SQLException("result is ambiguous", "40003");
        assertFalse(Conflicts.isRetryable(new ProcessEngineException("flush failed", ambiguous), false),
                "an ambiguous commit may have committed: never retried");
    }

    @Test
    void environmentSwitchesTheExperimentalMode() {
        SangamEngineSettings s = new SangamEngineSettings();
        s.setSystemAuditScope("system:platform"); // decision 144: required at engine start
        assertFalse(s.isExperimentalCockroachDb(), "off by default");
        s.applyEnvironment(Map.of("SANGAM_EXPERIMENTAL_COCKROACHDB", "true"));
        assertTrue(s.isExperimentalCockroachDb());
    }
}
