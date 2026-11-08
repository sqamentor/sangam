// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.Locale;

import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The one Tier-1 database of an acceptance run, chosen by {@code -Dsangam.db=<postgres|oracle|sqlserver|mysql>}
 * (or {@code SANGAM_IT_DB}). One container per JVM, started on first use and stopped by Ryuk. The images are the
 * Stage B ones (SANGAM.md XI): PostgreSQL 17, Oracle Database Free 23, SQL Server 2022 (RCSI on), MySQL 8.4 (READ
 * COMMITTED per session, set by the server's mysql profile).
 */
public final class AcceptanceDatabase {

    private static JdbcDatabaseContainer<?> container;

    private AcceptanceDatabase() {
    }

    /** {@code postgres}, {@code oracle}, {@code sqlserver} or {@code mysql}; also the Spring profile name. */
    public static String name() {
        String v = System.getProperty("sangam.db");
        if (v == null || v.isBlank()) {
            v = System.getenv("SANGAM_IT_DB");
        }
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("acceptance tests need -Dsangam.db=<postgres|oracle|sqlserver|mysql>");
        }
        return v.trim().toLowerCase(Locale.ROOT);
    }

    public static synchronized JdbcDatabaseContainer<?> container() {
        if (container == null) {
            container = create(name());
            container.start();
            if ("sqlserver".equals(name())) {
                createSqlServerDatabase(container);
            }
        }
        return container;
    }

    /** The JDBC URL the server uses (SQL Server: the {@code sangam} database with RCSI on). */
    public static String jdbcUrl() {
        JdbcDatabaseContainer<?> c = container();
        return switch (name()) {
            case "sqlserver" -> c.getJdbcUrl() + ";databaseName=sangam;encrypt=true;trustServerCertificate=true;"
                    + "sendStringParametersAsUnicode=true";
            case "mysql" -> "jdbc:mariadb://" + c.getHost() + ":" + c.getMappedPort(3306) + "/sangam"
                    + "?useServerPrepStmts=true&sessionVariables=transaction_isolation='READ-COMMITTED'"
                    + "&connectionCollation=utf8mb4_bin&permitMysqlScheme";
            default -> c.getJdbcUrl();
        };
    }

    public static String username() {
        return container().getUsername();
    }

    public static String password() {
        return container().getPassword();
    }

    private static JdbcDatabaseContainer<?> create(String db) {
        return switch (db) {
            case "postgres" -> new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
                    .withDatabaseName("sangam").withUsername("sangam").withPassword("sangam");
            case "oracle" -> new OracleContainer(DockerImageName.parse("gvenzl/oracle-free:23-slim-faststart"))
                    .withUsername("sangam").withPassword("sangam").withStartupTimeout(Duration.ofMinutes(5));
            case "sqlserver" -> new MSSQLServerContainer<>(
                    DockerImageName.parse("mcr.microsoft.com/mssql/server:2022-latest")).acceptLicense();
            case "mysql" -> new MariaDbDriverMySqlContainer()
                    .withDatabaseName("sangam").withUsername("sangam").withPassword("sangam")
                    .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_bin",
                            "--transaction-isolation=READ-COMMITTED");
            default -> throw new IllegalStateException("unknown sangam.db '" + db + "'");
        };
    }

    /**
     * MySQL 8.4 checked through MariaDB Connector/J, the driver the server itself uses on MySQL (D-05, LIC-4), so the
     * readiness check and the server go through the same driver and no second MySQL driver is on the class path.
     */
    static final class MariaDbDriverMySqlContainer extends MySQLContainer<MariaDbDriverMySqlContainer> {
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
                    + "?permitMysqlScheme";
        }
    }

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
