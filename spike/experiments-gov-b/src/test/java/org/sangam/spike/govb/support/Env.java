package org.sangam.spike.govb.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import org.sangam.spike.api.EngineAdapter;
import org.sangam.spike.flowable.FlowableEngineAdapter;
import org.sangam.spike.operaton.OperatonEngineAdapter;

/** Engine and database selection ({@code gov.engine}, {@code gov.db}) and adapter construction. */
public final class Env {

    /** Worker / client threads for every bulk phase, the same on both engines (fairness). */
    public static final int THREADS = 4;

    public static final String PG_IMAGE = "postgres:17-alpine";

    private static PostgreSQLContainer<?> pg;
    private static final AtomicInteger DB_SEQ = new AtomicInteger();

    private Env() {
    }

    public static String engine() {
        return System.getProperty("gov.engine", "operaton");
    }

    public static boolean isPostgres() {
        return "postgres".equalsIgnoreCase(System.getProperty("gov.db", "h2"));
    }

    public static String dbDescription() {
        return isPostgres() ? "PostgreSQL 17 (Testcontainers " + PG_IMAGE + ")" : "H2 2.3.232 in-memory";
    }

    /** Scale for bulk phases: the full number on PostgreSQL, a development number on H2. */
    public static int scale(int postgres, int h2) {
        return isPostgres() ? postgres : h2;
    }

    /** A fresh database and a pool on it; the adapter owns neither (the caller closes the pool). */
    public static HikariDataSource newDatabase(String name) {
        String url;
        String user;
        String password;
        if (isPostgres()) {
            PostgreSQLContainer<?> c = container();
            String db = (name + "_" + DB_SEQ.incrementAndGet()).toLowerCase().replaceAll("[^a-z0-9_]", "_");
            try (Connection conn = DriverManager.getConnection(c.getJdbcUrl(), c.getUsername(), c.getPassword());
                 Statement s = conn.createStatement()) {
                s.execute("create database " + db);
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            String base = c.getJdbcUrl();
            int q = base.indexOf('?');
            url = base.substring(0, base.lastIndexOf('/', q < 0 ? base.length() : q) + 1) + db;
            user = c.getUsername();
            password = c.getPassword();
        } else {
            url = "jdbc:h2:mem:" + name + "_" + UUID.randomUUID().toString().substring(0, 8) + ";DB_CLOSE_DELAY=-1";
            user = "sa";
            password = "";
        }
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(url);
        hc.setUsername(user);
        hc.setPassword(password);
        hc.setMaximumPoolSize(16);
        // Pool auto-commit as in each adapter's own factory (Flowable true, Operaton false).
        hc.setAutoCommit("flowable".equals(engine()));
        hc.setPoolName("gov-" + name);
        return new HikariDataSource(hc);
    }

    public static EngineAdapter newAdapter(HikariDataSource ds) {
        return switch (engine()) {
            case "operaton" -> new OperatonEngineAdapter(ds, ds.getJdbcUrl());
            case "flowable" -> new FlowableEngineAdapter(ds, ds.getJdbcUrl());
            default -> throw new IllegalArgumentException("gov.engine=" + engine());
        };
    }

    private static synchronized PostgreSQLContainer<?> container() {
        if (pg == null) {
            canonicaliseDefaultTimeZone();
            PostgreSQLContainer<?> c = new PostgreSQLContainer<>(DockerImageName.parse(PG_IMAGE))
                    .withCommand("postgres", "-c", "max_connections=200");
            c.start();
            pg = c;
            Runtime.getRuntime().addShutdownHook(new Thread(c::stop, "pg-stop"));
        }
        return pg;
    }

    /** Windows JVMs report India as Asia/Calcutta, which postgres:17 rejects at connect (simulators/README.md). */
    private static void canonicaliseDefaultTimeZone() {
        Map<String, String> legacy = Map.of("Asia/Calcutta", "Asia/Kolkata", "Asia/Katmandu", "Asia/Kathmandu");
        String canonical = legacy.get(TimeZone.getDefault().getID());
        if (canonical != null) {
            TimeZone.setDefault(TimeZone.getTimeZone(canonical));
        }
    }
}
