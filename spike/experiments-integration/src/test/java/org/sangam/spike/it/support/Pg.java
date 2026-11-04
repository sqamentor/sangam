package org.sangam.spike.it.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One PostgreSQL 17 container per test JVM (lazy singleton). Every experiment class gets its own database in it, so the
 * two engines never share {@code ACT_*} tables. The container is stopped by a JVM shutdown hook (and by Testcontainers'
 * Ryuk reaper if the JVM dies).
 */
public final class Pg {

    public static final String IMAGE = "postgres:17-alpine";

    private static PostgreSQLContainer<?> container;
    private static final AtomicInteger DB_COUNTER = new AtomicInteger();

    private Pg() {
    }

    public static synchronized PostgreSQLContainer<?> container() {
        if (container == null) {
            canonicaliseDefaultTimeZone();
            PostgreSQLContainer<?> c = new PostgreSQLContainer<>(DockerImageName.parse(IMAGE))
                    .withCommand("postgres", "-c", "max_connections=200");
            c.start();
            container = c;
            Runtime.getRuntime().addShutdownHook(new Thread(c::stop, "pg-stop"));
        }
        return container;
    }

    /** Creates a fresh database and returns its JDBC URL. */
    public static String createDatabase(String prefix) {
        PostgreSQLContainer<?> c = container();
        String db = (prefix + "_" + DB_COUNTER.incrementAndGet()).toLowerCase().replaceAll("[^a-z0-9_]", "_");
        try (Connection conn = DriverManager.getConnection(c.getJdbcUrl(), c.getUsername(), c.getPassword());
             Statement s = conn.createStatement()) {
            s.execute("create database " + db);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        String base = c.getJdbcUrl();
        int q = base.indexOf('?');
        return base.substring(0, base.lastIndexOf('/', q < 0 ? base.length() : q) + 1) + db;
    }

    public static String user() {
        return container().getUsername();
    }

    public static String password() {
        return container().getPassword();
    }

    /** Windows JVMs report India as Asia/Calcutta, which postgres:17 rejects at connect (see simulators/README.md). */
    private static void canonicaliseDefaultTimeZone() {
        Map<String, String> legacy = Map.of("Asia/Calcutta", "Asia/Kolkata", "Asia/Katmandu", "Asia/Kathmandu",
                "Asia/Saigon", "Asia/Ho_Chi_Minh", "Asia/Rangoon", "Asia/Yangon", "Europe/Kiev", "Europe/Kyiv");
        String canonical = legacy.get(TimeZone.getDefault().getID());
        if (canonical != null) {
            TimeZone.setDefault(TimeZone.getTimeZone(canonical));
        }
    }
}
