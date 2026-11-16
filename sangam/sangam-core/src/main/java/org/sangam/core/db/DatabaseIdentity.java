// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.db;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

import javax.sql.DataSource;

/**
 * Which database server a connection reaches, told from the server and not from the driver alone, and the start-up
 * check of the scale modules (decision 384).
 *
 * <p>The JDBC metadata cannot tell PostgreSQL-compatible servers apart: the PostgreSQL driver returns the constant
 * {@code PostgreSQL} as the product name whatever the server is (pgjdbc 42.7.12
 * {@code PgDatabaseMetaData.getDatabaseProductName}). CockroachDB and YugabyteDB YSQL are therefore told by their
 * {@code SELECT version()} text (decisions 205, 323 and 379). The engine's {@code EngineDialect} uses the same tests.
 * MariaDB Connector/J reports a MariaDB server as {@code MariaDB}, or as {@code MySQL} with {@code MariaDB} in the
 * product version when the URL sets {@code useMysqlMetadata}; both are a MariaDB server.
 */
public final class DatabaseIdentity {

    /** The server behind a connection. */
    public enum Server { POSTGRESQL, ORACLE, SQLSERVER, MYSQL, H2, MARIADB, COCKROACHDB, YUGABYTEDB, OTHER }

    /** Decision 384: stable code of the refusal of a MariaDB server by a scale module. */
    public static final String SCALE_DB_MARIADB_REFUSED = "SCALE_DB_MARIADB_REFUSED";
    /** Decision 384: stable code of the refusal of a CockroachDB server by a scale module. */
    public static final String SCALE_DB_COCKROACHDB_REFUSED = "SCALE_DB_COCKROACHDB_REFUSED";
    /** Decision 384: stable code of the refusal of a YugabyteDB server by a scale module. */
    public static final String SCALE_DB_YUGABYTEDB_REFUSED = "SCALE_DB_YUGABYTEDB_REFUSED";
    /** Decision 384: stable code of the refusal of any other unsupported database by a scale module. */
    public static final String SCALE_DB_UNSUPPORTED = "SCALE_DB_UNSUPPORTED";
    /** Decision 384: stable code when a scale module cannot identify its database (fails closed). */
    public static final String SCALE_DB_CHECK_FAILED = "SCALE_DB_CHECK_FAILED";

    /**
     * R84 and the support matrix ({@code deploy/SUPPORT-MATRIX.md}, Tier 1 "MySQL 8.4"): the oldest MySQL release line
     * Sangam runs on, as {@code major.minor}. The one place this minimum lives: the server's product-schema step, the
     * engine start-up check, the server's pool check and both scale modules read it from here.
     */
    public static final String MYSQL_MINIMUM_VERSION = "8.4";

    /**
     * Stable code of the refusal of a MySQL server older than {@link #MYSQL_MINIMUM_VERSION}, or of one whose version
     * cannot be read (fails closed: an unreadable version does not prove the supported line).
     */
    public static final String MYSQL_VERSION_REFUSED = "MYSQL_VERSION_REFUSED";

    private static final java.util.regex.Pattern MAJOR_MINOR = java.util.regex.Pattern.compile("^\\s*(\\d+)\\.(\\d+)");

    private DatabaseIdentity() {
    }

    /**
     * True when a MySQL server version text ({@code DatabaseMetaData.getDatabaseProductVersion()} or
     * {@code @@version}, for example {@code 8.4.5} or {@code 8.0.36-log}) is at least {@link #MYSQL_MINIMUM_VERSION};
     * false for an older or unreadable text.
     */
    public static boolean isSupportedMysqlVersion(String version) {
        int[] have = majorMinor(version);
        int[] min = majorMinor(MYSQL_MINIMUM_VERSION);
        if (have == null) {
            return false;
        }
        return have[0] != min[0] ? have[0] > min[0] : have[1] >= min[1];
    }

    /**
     * Refuses a MySQL server older than {@link #MYSQL_MINIMUM_VERSION} (or with an unreadable version) with
     * {@value #MYSQL_VERSION_REFUSED}; {@code who} names the component that does not start.
     */
    public static void requireSupportedMysqlVersion(String version, String who) {
        if (!isSupportedMysqlVersion(version)) {
            throw new IllegalStateException(MYSQL_VERSION_REFUSED + ": R84: " + who + " runs on MySQL "
                    + MYSQL_MINIMUM_VERSION + " or later only; the server reports "
                    + (version == null ? "no version" : "MySQL " + version.trim()));
        }
    }

    /**
     * Checks the server behind {@code ds}: a MySQL server (not MariaDB, which has its own support row) must be at least
     * {@link #MYSQL_MINIMUM_VERSION}; every other server passes. Fails closed with {@value #MYSQL_VERSION_REFUSED} when
     * the database cannot be read.
     */
    public static void requireSupportedMysql(DataSource ds, String who) {
        String product;
        String version;
        try (Connection c = ds.getConnection()) {
            DatabaseMetaData md = c.getMetaData();
            product = lower(md.getDatabaseProductName());
            version = md.getDatabaseProductVersion();
        } catch (SQLException | RuntimeException e) {
            throw new IllegalStateException(MYSQL_VERSION_REFUSED + ": R84: " + who
                    + " cannot read its database version, so it does not start", e);
        }
        // The MySQL family is told from the metadata alone, as in identify(): MariaDB is never a MySQL server here.
        if (product.contains("mysql") && !lower(version).contains("mariadb")) {
            requireSupportedMysqlVersion(version, who);
        }
    }

    private static int[] majorMinor(String version) {
        if (version == null) {
            return null;
        }
        java.util.regex.Matcher m = MAJOR_MINOR.matcher(version);
        if (!m.find()) {
            return null;
        }
        try {
            return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The server's own {@code SELECT version()} text (null when the server returns no row). */
    public static String serverVersion(Connection c) {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT version()")) {
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException("cannot read the server version", e);
        }
    }

    /** True when a {@code version()} text is CockroachDB's (it starts with {@code CockroachDB}). */
    public static boolean isCockroachDbVersion(String version) {
        return version != null && version.trim().toLowerCase(Locale.ROOT).startsWith("cockroachdb");
    }

    /**
     * True when a {@code version()} text is YugabyteDB YSQL's, which carries the YugabyteDB release after {@code -YB-}
     * (for example {@code PostgreSQL 11.2-YB-2.20.2.0-b0 on x86_64-pc-linux-gnu, ...}).
     */
    public static boolean isYugabyteDbVersion(String version) {
        return version != null && version.toUpperCase(Locale.ROOT).contains("-YB-");
    }

    /** Identifies the server behind {@code c}. */
    public static Server identify(Connection c) throws SQLException {
        DatabaseMetaData md = c.getMetaData();
        String product = lower(md.getDatabaseProductName());
        if (product.contains("postgres")) {
            String version = serverVersion(c);
            if (isCockroachDbVersion(version)) {
                return Server.COCKROACHDB;
            }
            return isYugabyteDbVersion(version) ? Server.YUGABYTEDB : Server.POSTGRESQL;
        }
        if (product.contains("mariadb")) {
            return Server.MARIADB;
        }
        if (product.contains("mysql")) {
            return lower(md.getDatabaseProductVersion()).contains("mariadb") ? Server.MARIADB : Server.MYSQL;
        }
        if (product.contains("oracle")) {
            return Server.ORACLE;
        }
        if (product.contains("sql server")) {
            return Server.SQLSERVER;
        }
        return product.startsWith("h2") ? Server.H2 : Server.OTHER;
    }

    /**
     * Decision 384: the start-up check of a scale module (control plane, roll-up), run before its Liquibase changelog.
     * Their changelogs apply on PostgreSQL, Oracle, SQL Server, MySQL 8.4 and H2 only; any other server would start
     * on an empty schema (Liquibase matches {@code dbms:} by its own short name: {@code mariadb}, {@code cockroachdb}),
     * and YugabyteDB is not a database of these modules. Refuses with a stable code, fails closed when the database
     * cannot be identified, and returns the accepted server. A MySQL server older than {@link #MYSQL_MINIMUM_VERSION}
     * is refused with {@value #MYSQL_VERSION_REFUSED} (R84).
     */
    public static Server requireScaleDatabase(DataSource ds, String module) {
        Server server;
        String seen;
        String productVersion;
        try (Connection c = ds.getConnection()) {
            DatabaseMetaData md = c.getMetaData();
            productVersion = md.getDatabaseProductVersion();
            seen = md.getDatabaseProductName() + " " + productVersion;
            server = identify(c);
        } catch (SQLException | RuntimeException e) {
            throw new IllegalStateException(SCALE_DB_CHECK_FAILED + ": decision 384: " + module
                    + " cannot identify its database, so it does not start", e);
        }
        String code = switch (server) {
            case POSTGRESQL, ORACLE, SQLSERVER, MYSQL, H2 -> null;
            case MARIADB -> SCALE_DB_MARIADB_REFUSED;
            case COCKROACHDB -> SCALE_DB_COCKROACHDB_REFUSED;
            case YUGABYTEDB -> SCALE_DB_YUGABYTEDB_REFUSED;
            case OTHER -> SCALE_DB_UNSUPPORTED;
        };
        if (code != null) {
            throw new IllegalStateException(code + ": decision 384: " + module + " applies its schema only on "
                    + "PostgreSQL, Oracle, SQL Server, MySQL " + MYSQL_MINIMUM_VERSION + " and H2; the server is "
                    + server + " (" + seen + ")");
        }
        if (server == Server.MYSQL) {
            requireSupportedMysqlVersion(productVersion, module);
        }
        return server;
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
