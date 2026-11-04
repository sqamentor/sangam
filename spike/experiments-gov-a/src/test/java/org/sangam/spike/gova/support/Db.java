package org.sangam.spike.gova.support;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Database per engine instance, selected with {@code -Dgov.db=h2|postgres}.
 * <ul>
 *   <li>{@code h2}: in-memory H2 (development pre-run; results stay under {@code target/}).</li>
 *   <li>{@code postgres}: one {@code postgres:17-alpine} container per JVM (= per engine execution) and a fresh
 *       database per engine instance, as in {@code experiments}. Measured run.</li>
 * </ul>
 */
public final class Db {

    public record Target(String url, String user, String password) {
    }

    public static final String PG_IMAGE = "postgres:17-alpine";
    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static PostgreSQLContainer<?> postgres;

    private Db() {
    }

    public static String kind() {
        String k = System.getProperty("gov.db", "h2").trim().toLowerCase(Locale.ROOT);
        return switch (k) {
            case "postgres", "postgresql", "pg" -> "postgres";
            case "h2" -> "h2";
            default -> throw new IllegalStateException("unknown -Dgov.db '" + k + "' (h2|postgres)");
        };
    }

    public static boolean isPostgres() {
        return "postgres".equals(kind());
    }

    public static String description() {
        return isPostgres() ? "PostgreSQL (Testcontainers " + PG_IMAGE + (postgres != null ? ", " + serverVersion() : "")
                + ", fresh database per engine instance)" : "H2 2.3.232 in-memory (pre-run)";
    }

    public static synchronized Target newDatabase(String prefix) {
        String name = (prefix + COUNTER.incrementAndGet()).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        if (!isPostgres()) {
            return new Target("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1", "sa", "");
        }
        if (postgres == null) {
            postgres = new PostgreSQLContainer<>(DockerImageName.parse(PG_IMAGE))
                    .withCommand("postgres", "-c", "max_connections=200");
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

    /** Row count per engine table (ACT_* and FLW_*), read with a plain JDBC connection (outside the engine). */
    public static Map<String, Long> engineTableRows(Target t) {
        Map<String, Long> out = new TreeMap<>();
        try (Connection c = DriverManager.getConnection(t.url(), t.user(), t.password())) {
            DatabaseMetaData md = c.getMetaData();
            List<String> tables = new ArrayList<>();
            String schema = isPostgres() ? "public" : "PUBLIC";
            try (ResultSet rs = md.getTables(null, schema, "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    String n = rs.getString("TABLE_NAME");
                    String u = n.toUpperCase(Locale.ROOT);
                    if (u.startsWith("ACT_") || u.startsWith("FLW_")) {
                        tables.add(n);
                    }
                }
            }
            try (Statement s = c.createStatement()) {
                for (String n : tables) {
                    try (ResultSet rs = s.executeQuery("select count(*) from " + n)) {
                        rs.next();
                        long v = rs.getLong(1);
                        if (v > 0) {
                            out.put(n.toUpperCase(Locale.ROOT), v);
                        }
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    private static String serverVersion() {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("show server_version")) {
            rs.next();
            return "server " + rs.getString(1);
        } catch (SQLException e) {
            return "server version unknown";
        }
    }
}
