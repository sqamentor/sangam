package org.sangam.spike.sim.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Testcontainers database harness for the experiments (SANGAM.md XI.5). Starts ONE database at a
 * time, by name, and hands back a HikariCP pool plus the JDBC URL and a dialect name.
 *
 * <pre>
 * try (Databases.Running db = Databases.start("postgres")) {
 *     DataSource ds = db.dataSource();
 * }
 * </pre>
 *
 * Names: {@code postgres} ({@value #POSTGRES_IMAGE}), {@code oracle} ({@value #ORACLE_IMAGE}),
 * {@code sqlserver} ({@value #SQLSERVER_IMAGE}, the Microsoft EULA is accepted for dev/test use),
 * {@code mysql} ({@value #MYSQL_IMAGE}, Tier 1 since 2026-11; utf8mb4 / utf8mb4_bin, READ-COMMITTED).
 *
 * <p>MySQL driver: {@code -Dspike.mysql.driver=mysql} (default, MySQL Connector/J, GPL + FOSS exception, test scope
 * only) or {@code mariadb} (MariaDB Connector/J, LGPL, the driver the product plans to ship for MySQL, LIC-4). The
 * consumer puts the chosen driver on its test class path.
 *
 * <p>The Oracle and SQL Server JDBC drivers are not bundled (LIC-2): this module declares them
 * {@code provided}, so a consumer must put them on its own test classpath. {@link #start(String)}
 * fails fast with a clear message when the driver class is missing.
 */
public final class Databases {

    public static final String POSTGRES_IMAGE = "postgres:17";
    public static final String ORACLE_IMAGE = "gvenzl/oracle-free:23-slim-faststart";
    public static final String SQLSERVER_IMAGE = "mcr.microsoft.com/mssql/server:2022-latest";
    public static final String MYSQL_IMAGE = "mysql:8.4";
    /** {@code mysql} (Connector/J) or {@code mariadb} (MariaDB Connector/J against the MySQL server). */
    public static final String MYSQL_DRIVER_PROPERTY = "spike.mysql.driver";
    /** Set to {@code true} to run the Oracle and SQL Server tests (they are off by default). */
    public static final String ENV_ALL = "SPIKE_DB_ALL";

    private static final AtomicReference<Running> CURRENT = new AtomicReference<>();

    private Databases() { }

    /** A started database. Closing it closes the pool and stops the container. */
    public static final class Running implements AutoCloseable {
        private final String name;
        private final String dialect;
        private final JdbcDatabaseContainer<?> container;
        private final HikariDataSource dataSource;

        private Running(String name, String dialect, JdbcDatabaseContainer<?> container, HikariDataSource ds) {
            this.name = name;
            this.dialect = dialect;
            this.container = container;
            this.dataSource = ds;
        }

        public String name() {
            return name;
        }

        /** {@code postgresql}, {@code oracle}, {@code sqlserver} or {@code mysql}. */
        public String dialect() {
            return dialect;
        }

        public String jdbcUrl() {
            return container.getJdbcUrl();
        }

        public String username() {
            return container.getUsername();
        }

        public String password() {
            return container.getPassword();
        }

        public DataSource dataSource() {
            return dataSource;
        }

        public JdbcDatabaseContainer<?> container() {
            return container;
        }

        /** The dialect's "is alive" query ({@code select 1}, or {@code select 1 from dual} on Oracle). */
        public String selectOne() {
            return "oracle".equals(dialect) ? "select 1 from dual" : "select 1";
        }

        @Override
        public void close() {
            try {
                dataSource.close();
            } finally {
                try {
                    container.stop();
                } finally {
                    CURRENT.compareAndSet(this, null);
                }
            }
        }
    }

    /** The currently running database, if any. */
    public static Running current() {
        return CURRENT.get();
    }

    /**
     * Start a database by name ({@code postgres}, {@code oracle}, {@code sqlserver}, {@code mysql}).
     *
     * @throws IllegalStateException if another database started here is still running, or the JDBC
     *                               driver for the requested database is not on the classpath
     */
    public static synchronized Running start(String name) {
        Running running = CURRENT.get();
        if (running != null) {
            throw new IllegalStateException("database '" + running.name() + "' is still running; close it first "
                    + "(one container at a time)");
        }
        String key = name.toLowerCase(Locale.ROOT).trim();
        JdbcDatabaseContainer<?> c;
        String dialect;
        switch (key) {
            case "postgres", "postgresql" -> {
                c = new PostgreSQLContainer<>(DockerImageName.parse(POSTGRES_IMAGE));
                dialect = "postgresql";
            }
            case "oracle" -> {
                c = new OracleContainer(DockerImageName.parse(ORACLE_IMAGE))
                        .withStartupTimeout(Duration.ofMinutes(5));
                dialect = "oracle";
            }
            case "sqlserver", "mssql" -> {
                c = new MSSQLServerContainer<>(DockerImageName.parse(SQLSERVER_IMAGE)).acceptLicense()
                        .withStartupTimeout(Duration.ofMinutes(5));
                dialect = "sqlserver";
            }
            case "mysql" -> {
                c = new MySqlContainer(DockerImageName.parse(MYSQL_IMAGE),
                        System.getProperty(MYSQL_DRIVER_PROPERTY, "mysql"));
                dialect = "mysql";
            }
            default -> throw new IllegalArgumentException("unknown database '" + name
                    + "'; expected postgres, oracle, sqlserver or mysql");
        }
        requireDriver(c.getDriverClassName(), dialect);
        if ("postgresql".equals(dialect)) {
            canonicaliseDefaultTimeZone();
        }
        c.start();
        try {
            HikariConfig cfg = new HikariConfig();
            cfg.setPoolName("spike-" + dialect);
            cfg.setJdbcUrl(c.getJdbcUrl());
            cfg.setUsername(c.getUsername());
            cfg.setPassword(c.getPassword());
            cfg.setDriverClassName(c.getDriverClassName());
            cfg.setMaximumPoolSize(10);
            cfg.setConnectionTimeout(30_000);
            Running r = new Running(key, dialect, c, new HikariDataSource(cfg));
            CURRENT.set(r);
            return r;
        } catch (RuntimeException e) {
            c.stop();
            throw e;
        }
    }

    /**
     * MySQL 8.4 as a {@link JdbcDatabaseContainer}, defined here so the simulators module needs no extra Testcontainers
     * artifact. Server: utf8mb4 with the case-sensitive {@code utf8mb4_bin} collation (both engines compare ids and
     * keys case-sensitively), {@code READ-COMMITTED} (both engines' MySQL guidance), 300 connections. The JDBC URL
     * carries {@code useSSL=false&allowPublicKeyRetrieval=true} (caching_sha2_password without TLS in a test container),
     * {@code nullCatalogMeansCurrent=true} (Flowable's schema check) and UTF-8. The user is {@code root}, so a consumer
     * can create one database per engine.
     */
    public static final class MySqlContainer extends JdbcDatabaseContainer<MySqlContainer> {
        private static final int PORT = 3306;
        private static final String DB = "test";
        private static final String PASSWORD = "spike";
        private final boolean mariadbDriver;

        public MySqlContainer(DockerImageName image, String driver) {
            super(image);
            this.mariadbDriver = "mariadb".equalsIgnoreCase(driver == null ? "" : driver.trim());
            addExposedPort(PORT);
            addEnv("MYSQL_ROOT_PASSWORD", PASSWORD);
            addEnv("MYSQL_DATABASE", DB);
            setCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_bin",
                    "--transaction-isolation=READ-COMMITTED", "--max-connections=300");
            withStartupTimeoutSeconds(300);
        }

        /** True when the MariaDB Connector/J is used against the MySQL server. */
        public boolean mariadbDriver() {
            return mariadbDriver;
        }

        @Override
        public String getDriverClassName() {
            return mariadbDriver ? "org.mariadb.jdbc.Driver" : "com.mysql.cj.jdbc.Driver";
        }

        /** URL of database {@code db} on this server, with the engine-relevant parameters. */
        public String jdbcUrl(String db) {
            String common = "useSSL=false&allowPublicKeyRetrieval=true";
            String params = mariadbDriver ? common
                    : common + "&nullCatalogMeansCurrent=true&characterEncoding=UTF-8&connectionTimeZone=UTC";
            return (mariadbDriver ? "jdbc:mariadb://" : "jdbc:mysql://") + getHost() + ":" + getMappedPort(PORT) + "/" + db
                    + "?" + params;
        }

        @Override
        public String getJdbcUrl() {
            return jdbcUrl(DB);
        }

        @Override
        public String getUsername() {
            return "root";
        }

        @Override
        public String getPassword() {
            return PASSWORD;
        }

        @Override
        protected String getTestQueryString() {
            return "SELECT 1";
        }
    }

    /** Legacy tz aliases that Windows JVMs report and that recent PostgreSQL images reject. */
    private static final java.util.Map<String, String> LEGACY_ZONES = java.util.Map.of(
            "Asia/Calcutta", "Asia/Kolkata",
            "Asia/Katmandu", "Asia/Kathmandu",
            "Asia/Saigon", "Asia/Ho_Chi_Minh",
            "Asia/Rangoon", "Asia/Yangon",
            "Europe/Kiev", "Europe/Kyiv");

    /**
     * pgjdbc sends the JVM default zone as the {@code TimeZone} startup parameter. On Windows the
     * JVM reports India as the legacy alias {@code Asia/Calcutta}, which the postgres:17 image
     * rejects ({@code FATAL: invalid value for parameter "TimeZone"}). Same zone, canonical name.
     */
    static void canonicaliseDefaultTimeZone() {
        String id = java.util.TimeZone.getDefault().getID();
        String canonical = LEGACY_ZONES.get(id);
        if (canonical != null) {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(canonical));
        }
    }

    private static void requireDriver(String driverClass, String dialect) {
        try {
            Class.forName(driverClass, false, Databases.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("JDBC driver " + driverClass + " for " + dialect + " is not on the classpath. "
                    + "It is not bundled (SANGAM.md X.3a LIC-2): add it to your module with test or provided scope, "
                    + "see spike/simulators/README.md", e);
        }
    }
}
