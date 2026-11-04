package org.sangam.spike.it.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import org.sangam.spike.layer.Dialect;
import org.sangam.spike.sim.db.Databases;

/**
 * The database of this test JVM, selected with {@code -Dspike.db=postgres|oracle|sqlserver} (default postgres).
 *
 * <ul>
 *   <li>{@code postgres}: the Stage B container ({@link Pg}: {@code postgres:17-alpine}, {@code max_connections=200}),
 *       one database per experiment class. Unchanged, so the PostgreSQL results stay comparable with Stage B.</li>
 *   <li>{@code oracle}: one Oracle Free 23 container per JVM through {@link Databases} (simulators); one user/schema per
 *       experiment class, created as SYSTEM (gvenzl image: SYSTEM has the container password).</li>
 *   <li>{@code sqlserver}: one SQL Server 2022 container per JVM through {@link Databases}; one database per class with
 *       {@code READ_COMMITTED_SNAPSHOT ON} (VI.1.6; both engines' SQL Server guides).</li>
 *   <li>{@code mysql}: one MySQL 8.4 container through {@link Databases}; one database per class (utf8mb4_bin).
 *       Needs a MySQL variant of product-layer {@code Dialect}; see {@link Kind#dialect()}.</li>
 * </ul>
 * Only one container runs at a time. It is stopped by a JVM shutdown hook (and by Ryuk if the JVM dies).
 */
public final class Db {

    public enum Kind {
        POSTGRES("POSTGRESQL"), ORACLE("ORACLE"), SQLSERVER("SQLSERVER"), MYSQL("MYSQL");

        private final String dialectName;

        Kind(String dialectName) {
            this.dialectName = dialectName;
        }

        /**
         * The product-layer dialect, looked up by name: product-layer {@link Dialect} has no MySQL variant yet, so on
         * MySQL this fails with a clear finding before any container starts (the product tables cannot be created).
         */
        public Dialect dialect() {
            try {
                return Dialect.valueOf(dialectName);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("product-layer Dialect has no " + dialectName + " variant: Schema, Outbox, "
                        + "Relay, Inbox, OperationLedger and AuthorizationPolicy cannot run on " + key()
                        + " (finding: VI.1.6 / A-15 dialect support incomplete for MySQL)", e);
            }
        }

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** One isolated database (PostgreSQL, SQL Server) or schema (Oracle) for one experiment class. */
    public record Target(Kind kind, String name, String url, String user, String password) {
        public Dialect dialect() {
            return kind.dialect();
        }
    }

    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static Databases.Running server;

    private Db() {
    }

    public static Kind kind() {
        String k = System.getProperty("spike.db", "postgres").trim().toLowerCase(Locale.ROOT);
        return switch (k) {
            case "postgres", "postgresql", "pg" -> Kind.POSTGRES;
            case "oracle" -> Kind.ORACLE;
            case "sqlserver", "mssql" -> Kind.SQLSERVER;
            case "mysql" -> Kind.MYSQL;
            default -> throw new IllegalStateException("unknown -Dspike.db '" + k + "' (postgres|oracle|sqlserver|mysql)");
        };
    }

    public static Dialect dialect() {
        return kind().dialect();
    }

    /** Empty for PostgreSQL (the Stage B result files), {@code -oracle} / {@code -sqlserver} otherwise. */
    public static String resultSuffix() {
        if (kind() == Kind.MYSQL && "mariadb".equalsIgnoreCase(System.getProperty(Databases.MYSQL_DRIVER_PROPERTY, "mysql"))) {
            return "-mysql-mariadb-driver";
        }
        return kind() == Kind.POSTGRES ? "" : "-" + kind().key();
    }

    public static String description() {
        return switch (kind()) {
            case POSTGRES -> "PostgreSQL 17 (Testcontainers " + Pg.IMAGE + "), one database per engine";
            case ORACLE -> "Oracle Free 23 (Testcontainers " + Databases.ORACLE_IMAGE + "), one user/schema per engine";
            case SQLSERVER -> "SQL Server 2022 (Testcontainers " + Databases.SQLSERVER_IMAGE
                    + "), one database per engine, READ_COMMITTED_SNAPSHOT ON";
            case MYSQL -> "MySQL 8.4 (Testcontainers " + Databases.MYSQL_IMAGE + "), one database per engine, driver "
                    + System.getProperty(Databases.MYSQL_DRIVER_PROPERTY, "mysql");
        };
    }

    public static synchronized Target create(String prefix) {
        return switch (kind()) {
            case POSTGRES -> {
                String url = Pg.createDatabase(prefix);
                yield new Target(Kind.POSTGRES, url.substring(url.lastIndexOf('/') + 1), url, Pg.user(), Pg.password());
            }
            case ORACLE -> oracleSchema(prefix);
            case SQLSERVER -> sqlServerDatabase(prefix);
            case MYSQL -> mysqlDatabase(prefix);
        };
    }

    private static String name(String prefix) {
        return (prefix + "_" + COUNTER.incrementAndGet()).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
    }

    private static Databases.Running server(String name) {
        if (server == null) {
            Databases.Running r = Databases.start(name);
            server = r;
            Runtime.getRuntime().addShutdownHook(new Thread(r::close, "stop-" + name));
        }
        return server;
    }

    private static Target oracleSchema(String prefix) {
        Databases.Running db = server("oracle");
        String n = name(prefix);
        String user = ("SPK_" + n).toUpperCase(Locale.ROOT);
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
        return new Target(Kind.ORACLE, user, db.jdbcUrl(), user, password);
    }

    private static Target mysqlDatabase(String prefix) {
        Databases.Running db = server("mysql");
        Databases.MySqlContainer c = (Databases.MySqlContainer) db.container();
        String n = name(prefix);
        try (Connection conn = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password());
             Statement s = conn.createStatement()) {
            s.execute("create database " + n + " character set utf8mb4 collate utf8mb4_bin");
        } catch (SQLException e) {
            throw new IllegalStateException("cannot create MySQL database " + n, e);
        }
        return new Target(Kind.MYSQL, n, c.jdbcUrl(n), db.username(), db.password());
    }

    private static Target sqlServerDatabase(String prefix) {
        Databases.Running db = server("sqlserver");
        String n = name(prefix);
        try (Connection c = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password());
             Statement s = c.createStatement()) {
            s.execute("create database " + n);
            s.execute("alter database " + n + " set read_committed_snapshot on with rollback immediate");
            s.execute("alter database " + n + " set allow_snapshot_isolation on");
        } catch (SQLException e) {
            throw new IllegalStateException("cannot create SQL Server database " + n, e);
        }
        return new Target(Kind.SQLSERVER, n, db.jdbcUrl() + ";databaseName=" + n, db.username(), db.password());
    }
}
