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
 * Start-up checks of the database the engine runs on (R84, D-05, D-56, VI.1.6):
 * <ul>
 *   <li>the dialect is a supported one (Tier 1 + H2);</li>
 *   <li>the session isolation is READ COMMITTED (also forced per connection by {@link IsolationGuardDataSource});</li>
 *   <li>SQL Server: {@code READ_COMMITTED_SNAPSHOT} is ON for the database (VI.1.6 "RCSI required");</li>
 *   <li>MySQL family (D-05): the server is identified from the server, not from the driver. The MariaDB driver makes
 *       Operaton pick its MariaDB dialect (driver name check) even on a MySQL server; Sangam pins
 *       {@code databaseType} to the configured value so create/upgrade scripts always match the server.</li>
 * </ul>
 */
public final class DatabaseGuard {

    private static final Logger LOG = LoggerFactory.getLogger(DatabaseGuard.class);

    /** What the guard found. */
    public record Findings(EngineDialect dialect, String serverProduct, String driverName, String operatonDatabaseType) {
    }

    private DatabaseGuard() {
    }

    public static Findings check(DataSource ds, boolean requireRcsi, String mysqlDatabaseType) {
        try (Connection c = ds.getConnection()) {
            DatabaseMetaData md = c.getMetaData();
            String product = md.getDatabaseProductName();
            String version = md.getDatabaseProductVersion();
            String driver = md.getDriverName();
            EngineDialect d = EngineDialect.fromProductName(product);
            if (c.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
                throw new IllegalStateException("R84/D-56: the engine pool must run READ COMMITTED (found isolation "
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
                        LOG.info("D-05: MariaDB Connector/J on a MySQL server; Operaton databaseType pinned to '{}'", type);
                    }
                }
                case POSTGRESQL -> type = "postgres";
                case ORACLE -> type = "oracle";
                case H2 -> type = "h2";
                default -> {
                }
            }
            return new Findings(d, product + " " + version, driver, type);
        } catch (SQLException e) {
            throw new IllegalStateException("database start-up check failed", e);
        }
    }

    /**
     * H2 (development only). The core product layer needs {@code MODE=PostgreSQL} ({@code INSERT .. ON CONFLICT DO
     * NOTHING}); in that mode H2 2.3 does not know the {@code BLOB} type that Operaton's H2 create scripts use. A
     * domain alias makes both work on one database. Harmless in the default H2 mode.
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

    private static boolean rcsiOn(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT is_read_committed_snapshot_on FROM sys.databases WHERE name = DB_NAME()");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() && rs.getBoolean(1);
        }
    }
}
