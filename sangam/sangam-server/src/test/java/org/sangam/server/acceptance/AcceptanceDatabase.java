// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.Locale;
import java.util.function.UnaryOperator;

import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.mssqlserver.MSSQLServerContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The one database of an acceptance run, chosen by {@code -Dsangam.db=<postgres|oracle|sqlserver|mysql>} (plus the
 * lanes {@code sqlserver2025}, {@code postgres18}, {@code mariadb} and {@code db2}, see {@link #lane()})
 * (or {@code SANGAM_IT_DB}). One container per JVM, started on first use and stopped by Ryuk. The images are the
 * Stage B ones (SANGAM.md XI): PostgreSQL 17, Oracle Database Free 23, SQL Server 2022 (RCSI on), MySQL 8.4 (READ
 * COMMITTED per session, set by the server's mysql profile).
 */
public final class AcceptanceDatabase {

    private static JdbcDatabaseContainer<?> container;

    private AcceptanceDatabase() {
    }

    /**
     * {@code postgres}, {@code oracle}, {@code sqlserver} or {@code mysql}; also the Spring profile name. The lane
     * {@code sqlserver2025} is the SQL Server dialect and profile on the SQL Server 2025 image, and the lane
     * {@code mariadb} (Tier 2, ADR-D-15) is the {@code mysql} profile on a MariaDB server (see {@link #lane()}).
     */
    public static String name() {
        return nameOf(lane());
    }

    /**
     * The Spring profile and dialect family of a lane. SL-112: MariaDB is the MySQL family everywhere in the product
     * (SangamSchema, SecurityDialect, ResourcesDialect, LeaseTable, TxSupport, IsolationGuard detect "mysql" or
     * "mariadb" alike; DatabaseGuard pins Operaton's own MariaDB databaseType on a MariaDB server), and the server's
     * {@code mysql} profile already connects through MariaDB Connector/J.
     */
    static String nameOf(String lane) {
        return switch (lane) {
            case "sqlserver2025" -> "sqlserver";
            case "postgres18" -> "postgres";
            case "mariadb" -> "mysql";
            default -> lane;
        };
    }

    /** The raw lane: {@link #name()} values plus {@code sqlserver2025}, {@code mariadb} and {@code db2}; selects the image. */
    public static String lane() {
        String v = System.getProperty("sangam.db");
        if (v == null || v.isBlank()) {
            v = System.getenv("SANGAM_IT_DB");
        }
        if (v == null || v.isBlank()) {
            throw new IllegalStateException(
                    "acceptance tests need -Dsangam.db=<postgres|postgres18|oracle|sqlserver|sqlserver2025|mysql|mariadb|db2>");
        }
        return v.trim().toLowerCase(Locale.ROOT);
    }

    /** The IBM Db2 JCC driver class: customer-supplied, never a dependency of this build (LIC-3). */
    static final String DB2_DRIVER = "com.ibm.db2.jcc.DB2Driver";

    /**
     * SL-112: the {@code db2} lane always refuses, with the reason. Without the driver: LIC-3 (customer-supplied, so
     * public CI never has it). With the driver: Db2 is Tier-2 experimental (ADR-D-15) and the server has neither a
     * {@code db2} Spring profile nor a Db2 Liquibase changelog, so there is nothing to run the acceptance suite against.
     */
    static IllegalStateException db2Refusal(ClassLoader loader) {
        boolean driver;
        try {
            loader.loadClass(DB2_DRIVER); // loads without initialising
            driver = true;
        } catch (ClassNotFoundException | LinkageError e) {
            driver = false;
        }
        return new IllegalStateException(driver
                ? "sangam.db=db2: Db2 is Tier-2 experimental (ADR-D-15); the server has no db2 profile and no Db2 "
                        + "Liquibase changelog yet, so the acceptance suite cannot run on it (SL-112)"
                : "sangam.db=db2: the IBM Db2 JCC driver (" + DB2_DRIVER + ") is customer-supplied and is never "
                        + "fetched or shipped by this build (LIC-3); put it on the test class path to use this lane");
    }

    /** Decision 122 (RM-35): the operator's acceptance of the Microsoft SQL Server container image EULA. */
    static final String MSSQL_EULA_VARIABLE = "SANGAM_ACCEPT_MSSQL_EULA";

    /** The one value that accepts, the same value the compose file passes on as ACCEPT_EULA. */
    static final String MSSQL_EULA_ACCEPTED = "Y";

    /**
     * Decision 122 (RM-35): this code never accepts the SQL Server EULA by itself. Only when the operator has set
     * {@value #MSSQL_EULA_VARIABLE} to exactly {@value #MSSQL_EULA_ACCEPTED} does it pass that acceptance on to the
     * container ({@code acceptLicense()}). Otherwise the lane refuses before any container exists, with a NOT-RUN
     * reason: the acceptance tests fail loudly and are never counted as a pass.
     */
    static MSSQLServerContainer sqlServer(DockerImageName image, UnaryOperator<String> env) {
        if (!mssqlEulaAccepted(env)) {
            throw mssqlEulaRefusal(env.apply(MSSQL_EULA_VARIABLE));
        }
        return new MSSQLServerContainer(image).acceptLicense();
    }

    static boolean mssqlEulaAccepted(UnaryOperator<String> env) {
        return MSSQL_EULA_ACCEPTED.equals(env.apply(MSSQL_EULA_VARIABLE));
    }

    static IllegalStateException mssqlEulaRefusal(String value) {
        return new IllegalStateException("NOT-RUN: the SQL Server lane needs the operator's acceptance of the Microsoft SQL "
                + "Server container image EULA: set " + MSSQL_EULA_VARIABLE + "=" + MSSQL_EULA_ACCEPTED + " (it is "
                + (value == null ? "not set" : "'" + value + "'") + "); the build never accepts it by itself (decision 122)");
    }

    public static synchronized JdbcDatabaseContainer<?> container() {
        if (container == null) {
            container = create(lane());
            // a loaded shared machine can take minutes to boot a database; the per-image 60 s default wait is not
            // a product signal (PostgreSQL timed out at "database system is ready" under load)
            container.withStartupTimeout(Duration.ofMinutes(5));
            container.start();
            if ("sqlserver".equals(name())) {
                createSqlServerDatabase(container);
            }
        }
        return container;
    }

    /** The JDBC URL the server uses (SQL Server: the {@code sangam} database with RCSI on). */
    public static String jdbcUrl() {
        return jdbcUrl("sangam");
    }

    /**
     * The server's JDBC URL with another database of the same server (SQL Server and MySQL, where a database is the
     * namespace unqualified names resolve in); same driver options. PostgreSQL and Oracle take only {@code sangam}: their
     * isolation unit is a schema/user of the one database.
     */
    public static String jdbcUrl(String database) {
        JdbcDatabaseContainer<?> c = container();
        return switch (name()) {
            case "sqlserver" -> c.getJdbcUrl() + ";databaseName=" + database + ";encrypt=true;trustServerCertificate=true;"
                    + "sendStringParametersAsUnicode=true";
            case "mysql" -> "jdbc:mariadb://" + c.getHost() + ":" + c.getMappedPort(3306) + "/" + database
                    + "?useServerPrepStmts=true&sessionVariables=transaction_isolation='READ-COMMITTED'"
                    + "&connectionCollation=utf8mb4_bin&permitMysqlScheme" + MYSQL_TLS;
            default -> {
                if (!"sangam".equals(database)) {
                    throw new IllegalArgumentException(name() + ": one database per container, isolate with a schema/user");
                }
                yield c.getJdbcUrl();
            }
        };
    }

    public static String username() {
        return container().getUsername();
    }

    public static String password() {
        return container().getPassword();
    }

    /**
     * The lane's administrator account, for test-harness set-up only (never the server): PostgreSQL's image user is the
     * superuser and SQL Server's is {@code sa} (the container user); MySQL {@code root} and Oracle {@code system} get the
     * container password (Testcontainers sets MYSQL_ROOT_PASSWORD and ORACLE_PASSWORD to it).
     */
    public static String adminUsername() {
        return switch (name()) {
            case "mysql" -> "root";
            case "oracle" -> "system";
            default -> username();
        };
    }

    public static String adminPassword() {
        return password();
    }

    private static JdbcDatabaseContainer<?> create(String db) {
        return switch (db) {
            case "postgres" -> new PostgreSQLContainer(DockerImageName.parse("postgres:17"))
                    .withDatabaseName("sangam").withUsername("sangam").withPassword("sangam");
            // Decision 416: deployments stay on postgres 17.12; the postgres18 lane tests 18 for new installs (the CI leg
            // pulls the digest pinned in tools/ci/ci_plan.py and tags it with this name)
            case "postgres18" -> new PostgreSQLContainer(DockerImageName.parse("postgres:18.7"))
                    .withDatabaseName("sangam").withUsername("sangam").withPassword("sangam");
            case "oracle" -> new OracleContainer(DockerImageName.parse("gvenzl/oracle-free:23-slim-faststart"))
                    .withUsername("sangam").withPassword("sangam").withStartupTimeout(Duration.ofMinutes(5));
            case "sqlserver" -> sqlServer(DockerImageName.parse("mcr.microsoft.com/mssql/server:2022-latest"),
                    System::getenv);
            case "sqlserver2025" -> sqlServer(DockerImageName.parse("mcr.microsoft.com/mssql/server:2025-CU10-ubuntu-24.04")
                    .asCompatibleSubstituteFor("mcr.microsoft.com/mssql/server"), System::getenv);
            case "mysql" -> new MariaDbDriverMySqlContainer()
                    .withDatabaseName("sangam").withUsername("sangam").withPassword("sangam")
                    .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_bin",
                            "--transaction-isolation=READ-COMMITTED");
            // SL-112: the same server settings as the mysql lane, on the Tier-2 MariaDB image of db-matrix.yml
            case "mariadb" -> new MariaDbContainer()
                    .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_bin",
                            "--transaction-isolation=READ-COMMITTED");
            case "db2" -> throw db2Refusal(AcceptanceDatabase.class.getClassLoader());
            default -> throw new IllegalStateException("unknown sangam.db '" + db + "'");
        };
    }

    /**
     * SL-112: MariaDB 11.4 LTS (the db-matrix Tier-2 image) through MariaDB Connector/J, the server's own MySQL-family
     * driver. Built on Testcontainers' JDBC base so no further Testcontainers module is needed; the official image's
     * own defaults apply (no MySQL configuration file is mounted). Database, user and password are {@code sangam},
     * like the other lanes; root gets the same password for the test-harness administrator account.
     */
    static final class MariaDbContainer extends JdbcDatabaseContainer<MariaDbContainer> {
        static final String IMAGE = "mariadb:11.4";
        static final int PORT = 3306;

        MariaDbContainer() {
            super(DockerImageName.parse(IMAGE));
            addExposedPort(PORT);
            addEnv("MARIADB_DATABASE", "sangam");
            addEnv("MARIADB_USER", "sangam");
            addEnv("MARIADB_PASSWORD", "sangam");
            addEnv("MARIADB_ROOT_PASSWORD", "sangam");
        }

        @Override
        public String getDriverClassName() {
            return "org.mariadb.jdbc.Driver";
        }

        @Override
        public String getJdbcUrl() {
            return "jdbc:mariadb://" + getHost() + ":" + getMappedPort(PORT) + "/" + getDatabaseName();
        }

        @Override
        public String getDatabaseName() {
            return "sangam";
        }

        @Override
        public String getUsername() {
            return "sangam";
        }

        @Override
        public String getPassword() {
            return "sangam";
        }

        @Override
        protected String getTestQueryString() {
            return "SELECT 1";
        }
    }

    /**
     * MySQL 8.4 checked through MariaDB Connector/J, the driver the server itself uses on MySQL (D-05, LIC-4), so the
     * readiness check and the server go through the same driver and no second MySQL driver is on the class path.
     */
    static final class MariaDbDriverMySqlContainer extends MySQLContainer {
        MariaDbDriverMySqlContainer() {
            super(DockerImageName.parse("mysql:8.4"));
        }

        @Override
        public String getDriverClassName() {
            return "org.mariadb.jdbc.Driver";
        }

        @Override
        public String getJdbcUrl() {
            return "jdbc:mariadb://" + getHost() + ":" + getMappedPort(MYSQL_PORT) + "/" + getDatabaseName()
                    + "?permitMysqlScheme" + MYSQL_TLS;
        }
    }

    /**
     * MySQL 8.4 accounts use caching_sha2_password: the first login of an account the server has not cached needs either
     * TLS or the server's RSA key, so a plain-text login of {@code root} (the harness administrator, never cached) failed
     * with "RSA public key is not available client side" (probe r7-it-mysql-auth-probe-20261116-171629.log: plain fails,
     * {@code sslMode=trust} succeeds). The harness talks TLS to the container's own self-signed certificate, as a
     * production MySQL connection does over TLS; it does not fetch the RSA key over an unauthenticated channel.
     */
    public static final String MYSQL_TLS = "&sslMode=trust";

    private static void createSqlServerDatabase(JdbcDatabaseContainer<?> c) {
        try (Connection conn = DriverManager.getConnection(c.getJdbcUrl() + ";encrypt=true;trustServerCertificate=true",
                c.getUsername(), c.getPassword());
             Statement s = conn.createStatement()) {
            s.execute("IF DB_ID('sangam') IS NULL CREATE DATABASE sangam");
            s.execute("ALTER DATABASE sangam SET READ_COMMITTED_SNAPSHOT ON WITH ROLLBACK IMMEDIATE");
            s.execute("ALTER DATABASE sangam SET ALLOW_SNAPSHOT_ISOLATION ON");
        } catch (Exception e) {
            throw new IllegalStateException("cannot prepare the SQL Server database", e);
        }
    }
}
