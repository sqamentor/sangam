package org.sangam.spike.experiments.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import org.sangam.spike.sim.db.Databases;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Databases for the experiments, selected with {@code -Dspike.db=postgres|oracle|sqlserver} (the pom maps it to
 * {@code exp.db}; {@code -Dexp.db=h2} is the quick pre-run; {@code mysql} is Tier 1 as well).
 *
 * <ul>
 *   <li>{@code postgres} (default): one {@code postgres:17-alpine} container per JVM (= per engine execution), a fresh
 *       database per engine instance. Unchanged from Stage B, so the PostgreSQL results stay comparable.</li>
 *   <li>{@code oracle}: one Oracle Free 23 container per JVM, started through {@link Databases} (simulators); a fresh
 *       user/schema per engine instance, created as SYSTEM (gvenzl image: SYSTEM has the container password).</li>
 *   <li>{@code sqlserver}: one SQL Server 2022 container per JVM through {@link Databases}; a fresh database per engine
 *       instance with {@code READ_COMMITTED_SNAPSHOT ON} (VI.1.6).</li>
 *   <li>{@code mysql}: one MySQL 8.4 container per JVM through {@link Databases}; a fresh database per engine instance
 *       (utf8mb4_bin, READ-COMMITTED). Driver: {@code -Dspike.mysql.driver=mysql} (Connector/J, default) or
 *       {@code mariadb} (MariaDB Connector/J, the driver planned for shipping, LIC-4).</li>
 *   <li>{@code h2}: in-memory H2 for a quick pre-run.</li>
 * </ul>
 * Containers are stopped by a JVM shutdown hook (and by Testcontainers' Ryuk if the JVM dies). The Oracle and SQL
 * Server JDBC drivers are test-scope dependencies of this module only (LIC-2).
 */
public final class Db {

    public record Target(String url, String user, String password) {
    }

    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static PostgreSQLContainer<?> postgres;
    private static Databases.Running server;

    private Db() {
    }

    /** {@code postgres}, {@code oracle}, {@code sqlserver} or {@code h2}. */
    public static String kind() {
        String k = System.getProperty("exp.db", System.getProperty("spike.db", "postgres")).trim().toLowerCase(Locale.ROOT);
        return switch (k) {
            case "postgres", "postgresql", "pg" -> "postgres";
            case "oracle" -> "oracle";
            case "sqlserver", "mssql" -> "sqlserver";
            case "mysql" -> "mysql";
            case "h2" -> "h2";
            default -> throw new IllegalStateException("unknown -Dspike.db / -Dexp.db '" + k + "' (postgres|oracle|sqlserver|mysql|h2)");
        };
    }

    public static boolean isPostgres() {
        return "postgres".equals(kind());
    }

    /**
     * Suffix of the result files: empty for PostgreSQL (the Stage B reference files), {@code -oracle},
     * {@code -sqlserver} or {@code -h2} otherwise, so another database never overwrites the PostgreSQL evidence.
     */
    public static String resultSuffix() {
        if ("mysql".equals(kind()) && "mariadb".equalsIgnoreCase(System.getProperty(Databases.MYSQL_DRIVER_PROPERTY, "mysql"))) {
            return "-mysql-mariadb-driver";
        }
        return isPostgres() ? "" : "-" + kind();
    }

    public static String description() {
        return switch (kind()) {
            case "h2" -> "H2 2.3.232 in-memory (pre-run)";
            case "postgres" -> "PostgreSQL (Testcontainers postgres:17-alpine" + (postgres != null ? ", " + serverVersion() : "") + ")";
            case "oracle" -> "Oracle (Testcontainers " + Databases.ORACLE_IMAGE + ", one user/schema per engine instance)";
            case "sqlserver" -> "SQL Server (Testcontainers " + Databases.SQLSERVER_IMAGE
                    + ", one database per engine instance, READ_COMMITTED_SNAPSHOT ON)";
            case "mysql" -> "MySQL (Testcontainers " + Databases.MYSQL_IMAGE + ", one database per engine instance, driver "
                    + System.getProperty(Databases.MYSQL_DRIVER_PROPERTY, "mysql") + ")";
            default -> kind();
        };
    }

    public static synchronized Target newDatabase(String prefix) {
        String name = (prefix + COUNTER.incrementAndGet()).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        return switch (kind()) {
            case "h2" -> new Target("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1", "sa", "");
            case "postgres" -> newPostgresDatabase(name);
            case "oracle" -> newOracleSchema(name);
            case "sqlserver" -> newSqlServerDatabase(name);
            case "mysql" -> newMySqlDatabase(name);
            default -> throw new IllegalStateException(kind());
        };
    }

    private static Target newPostgresDatabase(String name) {
        if (postgres == null) {
            postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"))
                    .withCommand("postgres", "-c", "max_connections=300");
            postgres.start();
            PostgreSQLContainer<?> c = postgres;
            Runtime.getRuntime().addShutdownHook(new Thread(c::stop, "stop-postgres"));
        }
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement s = c.createStatement()) {
            s.execute("create database " + name);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        String base = postgres.getJdbcUrl();
        int q = base.indexOf('?');
        String url = base.substring(0, base.lastIndexOf('/', q < 0 ? base.length() : q) + 1) + name;
        return new Target(url, postgres.getUsername(), postgres.getPassword());
    }

    private static Databases.Running server(String name) {
        if (server == null) {
            Databases.Running r = Databases.start(name);
            server = r;
            Runtime.getRuntime().addShutdownHook(new Thread(r::close, "stop-" + name));
        }
        return server;
    }

    private static Target newOracleSchema(String name) {
        Databases.Running db = server("oracle");
        String user = ("SPK_" + name).toUpperCase(Locale.ROOT);
        if (user.length() > 30) {
            user = user.substring(0, 24) + "_" + COUNTER.get();
        }
        String password = "spike";
        try (Connection c = DriverManager.getConnection(db.jdbcUrl(), "system", db.password());
             Statement s = c.createStatement()) {
            s.execute("create user " + user + " identified by " + password + " default tablespace users quota unlimited on users");
            s.execute("grant create session, create table, create sequence, create view, create procedure, create trigger to " + user);
        } catch (SQLException e) {
            throw new IllegalStateException("cannot create Oracle user " + user, e);
        }
        return new Target(db.jdbcUrl(), user, password);
    }

    private static Target newSqlServerDatabase(String name) {
        Databases.Running db = server("sqlserver");
        try (Connection c = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password());
             Statement s = c.createStatement()) {
            s.execute("create database " + name);
            s.execute("alter database " + name + " set read_committed_snapshot on with rollback immediate");
            s.execute("alter database " + name + " set allow_snapshot_isolation on");
        } catch (SQLException e) {
            throw new IllegalStateException("cannot create SQL Server database " + name, e);
        }
        return new Target(db.jdbcUrl() + ";databaseName=" + name, db.username(), db.password());
    }

    private static Target newMySqlDatabase(String name) {
        Databases.Running db = server("mysql");
        Databases.MySqlContainer c = (Databases.MySqlContainer) db.container();
        try (Connection conn = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password());
             Statement s = conn.createStatement()) {
            s.execute("create database " + name + " character set utf8mb4 collate utf8mb4_bin");
        } catch (SQLException e) {
            throw new IllegalStateException("cannot create MySQL database " + name, e);
        }
        return new Target(c.jdbcUrl(name), db.username(), db.password());
    }

    private static String serverVersion() {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement s = c.createStatement();
             var rs = s.executeQuery("show server_version")) {
            rs.next();
            return "server " + rs.getString(1);
        } catch (SQLException e) {
            return "server version unknown";
        }
    }
}
