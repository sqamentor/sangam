package org.sangam.spike.govc.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * {@code -Dgov.db=postgres} (default; one postgres:17-alpine container per JVM, a fresh database per scenario, same
 * image and settings as module "experiments") or {@code h2} (in-memory, development runs).
 */
public final class Db {

    public record Target(String url, String user, String password) {
    }

    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static PostgreSQLContainer<?> postgres;

    private Db() {
    }

    public static String kind() {
        String k = System.getProperty("gov.db", "postgres").trim().toLowerCase(Locale.ROOT);
        return switch (k) {
            case "postgres", "postgresql", "pg" -> "postgres";
            case "h2" -> "h2";
            default -> throw new IllegalStateException("unknown -Dgov.db '" + k + "' (postgres|h2)");
        };
    }

    public static boolean isPostgres() {
        return "postgres".equals(kind());
    }

    public static String description() {
        if (!isPostgres()) {
            return "H2 2.3.232 in-memory (development run)";
        }
        return "PostgreSQL (Testcontainers postgres:17-alpine" + (postgres != null ? ", " + serverVersion() : "") + ")";
    }

    public static synchronized Target newDatabase(String prefix) {
        String name = (prefix + COUNTER.incrementAndGet()).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        if (!isPostgres()) {
            return new Target("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1" + System.getProperty("gov.h2extra", ""), "sa", "");
        }
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
