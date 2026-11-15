// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.db;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Start-up checks of the database the engine runs on (R84, FLOWABLE-INTO-OPERATON D-05, FLOWABLE-INTO-OPERATON D-56, VI.1.6):
 * <ul>
 *   <li>the dialect is a supported one (Tier 1 + H2);</li>
 *   <li>the session isolation is READ COMMITTED (also forced per connection by {@link IsolationGuardDataSource});</li>
 *   <li>SQL Server: {@code READ_COMMITTED_SNAPSHOT} is ON for the database (VI.1.6 "RCSI required");</li>
 *   <li>MySQL family (FLOWABLE-INTO-OPERATON D-05): the server is identified from the server, not from the driver. The MariaDB driver makes
 *       Operaton pick its MariaDB dialect (driver name check) even on a MySQL server; Sangam pins
 *       {@code databaseType} to the configured value so create/upgrade scripts always match the server.</li>
 *   <li>PostgreSQL-compatible servers (the PostgreSQL driver always reports the product {@code PostgreSQL}): CockroachDB
 *       (decision 205) and YugabyteDB YSQL (M-108) are told apart by their {@code version()} text and refused unless
 *       the operator enabled their experimental mode.</li>
 * </ul>
 */
public final class DatabaseGuard {

    private static final Logger LOG = LoggerFactory.getLogger(DatabaseGuard.class);

    /** What the guard found. */
    /**
     * @param experimentalCockroachDb decisions 156 and 205: the PostgreSQL-compatible server is CockroachDB (only when
     *        the operator enabled it; experimental, no support claim)
     * @param experimentalYugabyteDb M-108 (decisions 156 and 323): the PostgreSQL-compatible server is YugabyteDB YSQL
     *        (only when the operator enabled it and READ COMMITTED is in effect; experimental, no support claim)
     */
    public record Findings(EngineDialect dialect, String serverProduct, String driverName, String operatonDatabaseType,
                           boolean experimentalCockroachDb, boolean experimentalYugabyteDb) {
        public Findings(EngineDialect dialect, String serverProduct, String driverName, String operatonDatabaseType) {
            this(dialect, serverProduct, driverName, operatonDatabaseType, false, false);
        }

        public Findings(EngineDialect dialect, String serverProduct, String driverName, String operatonDatabaseType,
                        boolean experimentalCockroachDb) {
            this(dialect, serverProduct, driverName, operatonDatabaseType, experimentalCockroachDb, false);
        }
    }

    /** M-108: stable code of the refusal of a YugabyteDB server while the experimental mode is off. */
    public static final String YUGABYTEDB_EXPERIMENTAL_OFF = "YUGABYTEDB_EXPERIMENTAL_OFF";

    /**
     * M-108: stable code of the refusal of a YugabyteDB server that does not prove READ COMMITTED is in effect. Without
     * the yb-tserver flag {@code yb_enable_read_committed_isolation=true} YugabyteDB runs a READ COMMITTED request as
     * snapshot isolation, while the JDBC isolation the driver reports still says READ COMMITTED.
     */
    public static final String YUGABYTEDB_READ_COMMITTED_OFF = "YUGABYTEDB_READ_COMMITTED_OFF";

    /**
     * M-108: the read-only YugabyteDB setting that names the isolation a transaction really runs at; {@code true}
     * (missing_ok) makes a server without it answer NULL, which refuses.
     */
    static final String YB_EFFECTIVE_ISOLATION_SQL =
            "SELECT current_setting('yb_effective_transaction_isolation_level', true)";

    private DatabaseGuard() {
    }

    public static Findings check(DataSource ds, boolean requireRcsi, String mysqlDatabaseType) {
        return check(ds, requireRcsi, mysqlDatabaseType, false);
    }

    /**
     * As {@link #check(DataSource, boolean, String)}; a CockroachDB server is refused unless
     * {@code allowExperimentalCockroachDb} (decisions 156 and 205: experimental and non-gating, so never picked up
     * silently as PostgreSQL).
     */
    public static Findings check(DataSource ds, boolean requireRcsi, String mysqlDatabaseType,
                                 boolean allowExperimentalCockroachDb) {
        return check(ds, requireRcsi, mysqlDatabaseType, allowExperimentalCockroachDb, false);
    }

    /**
     * As {@link #check(DataSource, boolean, String, boolean)}; a YugabyteDB YSQL server is refused with
     * {@value #YUGABYTEDB_EXPERIMENTAL_OFF} unless {@code allowExperimentalYugabyteDb} (M-108, decisions 156 and 323:
     * experimental and non-gating, never picked up silently as PostgreSQL), and, when allowed, with
     * {@value #YUGABYTEDB_READ_COMMITTED_OFF} unless the server itself names READ COMMITTED as the isolation in effect.
     */
    public static Findings check(DataSource ds, boolean requireRcsi, String mysqlDatabaseType,
                                 boolean allowExperimentalCockroachDb, boolean allowExperimentalYugabyteDb) {
        try (Connection c = ds.getConnection()) {
            DatabaseMetaData md = c.getMetaData();
            String product = md.getDatabaseProductName();
            String version = md.getDatabaseProductVersion();
            String driver = md.getDriverName();
            EngineDialect d = EngineDialect.fromProductName(product);
            String serverVersion = d == EngineDialect.POSTGRESQL ? EngineDialect.serverVersion(c) : null;
            boolean cockroach = EngineDialect.isCockroachDbVersion(serverVersion);
            boolean yugabyte = !cockroach && EngineDialect.isYugabyteDbVersion(serverVersion);
            if (yugabyte) {
                requireExperimentalYugabyteDb(c, serverVersion, allowExperimentalYugabyteDb);
            }
            if (cockroach) {
                if (!allowExperimentalCockroachDb) {
                    throw new IllegalStateException("decision 205: the server is CockroachDB, which is experimental "
                            + "and not supported; set SangamEngineSettings experimentalCockroachDb (environment "
                            + "SANGAM_EXPERIMENTAL_COCKROACHDB=true) to run on it for testing");
                }
                LOG.warn("decision 205: EXPERIMENTAL CockroachDB server ({} {}), mapped to the PostgreSQL dialect and "
                        + "Operaton databaseType 'postgres'; serialisation failures (40001) are retried for engine "
                        + "commands; not supported, no support claim", product, version);
            }
            if (c.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
                throw new IllegalStateException("R84/FLOWABLE-INTO-OPERATON D-56: the engine pool must run READ COMMITTED (found isolation "
                        + c.getTransactionIsolation() + "); wrap the DataSource in IsolationGuardDataSource");
            }
            String type = null;
            switch (d) {
                case SQLSERVER -> {
                    if (requireRcsi && !rcsiOn(c)) {
                        throw new IllegalStateException("VI.1.6: SQL Server database must have READ_COMMITTED_SNAPSHOT ON "
                                + "(ALTER DATABASE <db> SET READ_COMMITTED_SNAPSHOT ON)");
                    }
                    type = "mssql";
                }
                case MYSQL -> {
                    boolean mariaServer = String.valueOf(version).toLowerCase(Locale.ROOT).contains("mariadb");
                    type = mariaServer ? "mariadb" : mysqlDatabaseType;
                    if (!mariaServer && String.valueOf(driver).toLowerCase(Locale.ROOT).contains("mariadb")) {
                        LOG.info("FLOWABLE-INTO-OPERATON D-05: MariaDB Connector/J on a MySQL server; Operaton databaseType pinned to '{}'", type);
                    }
                }
                case POSTGRESQL -> type = "postgres";
                case ORACLE -> type = "oracle";
                case H2 -> {
                    requireH2PostgresMode(c);
                    type = "h2";
                }
                default -> {
                }
            }
            return new Findings(d, product + " " + version, driver, type, cockroach, yugabyte);
        } catch (SQLException e) {
            throw new IllegalStateException("database start-up check failed", e);
        }
    }

    /**
     * M-108: YugabyteDB YSQL runs only in the operator's experimental mode, and only with READ COMMITTED really in
     * effect (R84, FLOWABLE-INTO-OPERATON D-56): the setting must read exactly {@code read committed}; a missing
     * setting (NULL), any other level or a failed read refuses. Neither Sangam nor Operaton 2.2's mappings use
     * advisory locks or SERIALIZABLE, so nothing else is checked here; serialisation failures (40001) are retried by
     * {@link Conflicts} as on every database.
     */
    static void requireExperimentalYugabyteDb(Connection c, String serverVersion, boolean allowed) throws SQLException {
        if (!allowed) {
            throw new IllegalStateException(YUGABYTEDB_EXPERIMENTAL_OFF + ": M-108: the server is YugabyteDB ("
                    + serverVersion + "), which is experimental and not supported; set SangamEngineSettings "
                    + "experimentalYugabyteDb (environment SANGAM_EXPERIMENTAL_YUGABYTEDB=true) to run on it for testing");
        }
        String effective;
        try (PreparedStatement ps = c.prepareStatement(YB_EFFECTIVE_ISOLATION_SQL); ResultSet rs = ps.executeQuery()) {
            effective = rs.next() ? rs.getString(1) : null;
        }
        if (effective == null || !effective.trim().equalsIgnoreCase("read committed")) {
            throw new IllegalStateException(YUGABYTEDB_READ_COMMITTED_OFF + ": R84/FLOWABLE-INTO-OPERATON D-56: the "
                    + "engine pool must run READ COMMITTED, but YugabyteDB reports the isolation in effect as "
                    + (effective == null ? "unknown (no yb_effective_transaction_isolation_level)" : "'" + effective + "'")
                    + "; start every yb-tserver with --yb_enable_read_committed_isolation=true");
        }
        LOG.warn("M-108: EXPERIMENTAL YugabyteDB server ({}), mapped to the PostgreSQL dialect and Operaton "
                + "databaseType 'postgres'; READ COMMITTED in effect; serialisation failures (40001) are retried for "
                + "engine commands; not supported, no support claim", serverVersion);
    }

    /**
     * H2 (development only). The core product layer needs {@code MODE=PostgreSQL} ({@code INSERT .. ON CONFLICT DO
     * NOTHING}); in that mode H2 2.3 does not know the {@code BLOB} type that Operaton's H2 create scripts use. A
     * domain alias makes both work on one database. Harmless in the default H2 mode.
     *
     * <p><b>Order.</b> H2 creates a domain named like a data type only while the database has no table ("Domain BLOB
     * (PUBLIC.SOME_TABLE) already exists" otherwise, in every mode; the failure is only logged here). So this runs on
     * the empty database, before the product changelog: {@code SangamEngineConfigurer.prepare} and the server's
     * {@code SchemaBootstrap} call it first. A caller that applies {@code SangamSchema} itself and only then builds an
     * engine that creates its tables gets Operaton's "Unknown data type: BLOB" ({@code H2BlobDomainOrderH2Test} pins the
     * H2 rule and the product order).
     */
    public static void prepareH2(DataSource ds) {
        try (Connection c = ds.getConnection(); java.sql.Statement s = c.createStatement()) {
            s.execute("CREATE DOMAIN IF NOT EXISTS BLOB AS BINARY LARGE OBJECT");
            if (!c.getAutoCommit()) {
                c.commit();
            }
        } catch (SQLException e) {
            LOG.debug("H2: BLOB domain alias not created ({}); default mode assumed", e.getMessage());
        }
    }

    /**
     * H2 is a development database for Sangam and is supported only in {@code MODE=PostgreSQL}: the product layer
     * uses the PostgreSQL dialect on H2 ({@code INSERT .. ON CONFLICT DO NOTHING}, interval arithmetic). In any other
     * mode those statements fail later with an obscure syntax error, so refuse at start-up with the exact fix.
     */
    static void requireH2PostgresMode(Connection c) throws SQLException {
        String mode = null;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT SETTING_VALUE FROM INFORMATION_SCHEMA.SETTINGS WHERE SETTING_NAME = 'MODE'");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                mode = rs.getString(1);
            }
        }
        if (mode == null || !mode.trim().equalsIgnoreCase("PostgreSQL")) {
            throw new IllegalStateException("H2 must run in PostgreSQL compatibility mode for Sangam (found MODE="
                    + mode + "); add ;MODE=PostgreSQL to the JDBC URL, e.g. jdbc:h2:mem:sangam;MODE=PostgreSQL");
        }
    }

    private static boolean rcsiOn(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT is_read_committed_snapshot_on FROM sys.databases WHERE name = DB_NAME()");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() && rs.getBoolean(1);
        }
    }
}
