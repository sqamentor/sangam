package org.sangam.spike.layer;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.function.Function;

/**
 * One database container for the whole test run (singleton pattern), a Hikari pool, the product schema.
 * Engine transactions are simulated with plain JDBC transactions ({@link #inTx}), mirroring
 * {@code EngineAdapter.inEngineTransaction(Function<Connection,T>)}.
 *
 * <p>The database is chosen with {@code -Dlayer.db=postgres|mysql} (default postgres; the POM runs both):
 * <ul>
 *   <li>{@code postgres}: {@code postgres:17-alpine}.</li>
 *   <li>{@code mysql}: {@code mysql:8.4}, utf8mb4 / utf8mb4_bin, READ-COMMITTED (server default and per session),
 *       MySQL Connector/J (test scope) with {@code connectionTimeZone=UTC} so DATETIME values are UTC (see
 *       {@link Dialect#MYSQL}).</li>
 * </ul>
 * {@link #PG} is the dialect under test (the name is kept from the PostgreSQL-only suite).
 */
abstract class PgTestBase {

    static final Dialect PG;
    static final JdbcDatabaseContainer<?> DB;
    static final HikariDataSource DS;
    static final Transactions TX;

    static {
        String db = System.getProperty("layer.db", "postgres").trim().toLowerCase(Locale.ROOT);
        HikariConfig cfg = new HikariConfig();
        switch (db) {
            case "postgres", "postgresql" -> {
                PG = Dialect.POSTGRESQL;
                DB = new PostgreSQLContainer<>("postgres:17-alpine");
                DB.start();
                cfg.setJdbcUrl(DB.getJdbcUrl());
            }
            case "mysql" -> {
                PG = Dialect.MYSQL;
                MySQLContainer<?> my = new MySQLContainer<>("mysql:8.4");
                my.withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_bin",
                        "--transaction-isolation=READ-COMMITTED", "--max-connections=300");
                my.withUrlParam("useSSL", "false").withUrlParam("allowPublicKeyRetrieval", "true")
                        .withUrlParam("connectionTimeZone", "UTC")
                        .withUrlParam("sessionVariables", "transaction_isolation='READ-COMMITTED'");
                DB = my;
                DB.start();
                cfg.setJdbcUrl(DB.getJdbcUrl());
            }
            default -> throw new IllegalStateException("unknown -Dlayer.db=" + db + " (postgres|mysql)");
        }
        // stop the container when the JVM ends (not only via Ryuk), so the next surefire run starts on a free machine
        Runtime.getRuntime().addShutdownHook(new Thread(DB::stop, "stop-" + db));
        cfg.setUsername(DB.getUsername());
        cfg.setPassword(DB.getPassword());
        cfg.setMaximumPoolSize(24);
        cfg.setAutoCommit(true);
        DS = new HikariDataSource(cfg);
        TX = Transactions.of(DS);
        TX.inTransaction(c -> {
            Schema.create(c, PG);
            return null;
        });
    }

    @BeforeEach
    void truncateProductTables() throws SQLException {
        try (Connection c = DS.getConnection(); Statement st = c.createStatement()) {
            if (PG == Dialect.POSTGRESQL) {
                st.execute("TRUNCATE " + String.join(", ", Schema.tables()) + " RESTART IDENTITY");
            } else {
                // MySQL: TRUNCATE is DDL (drop + re-create, slow for 12 tables per test), so DELETE and reset counters
                for (String t : Schema.tables()) {
                    st.execute("DELETE FROM " + t);
                }
                st.execute("ALTER TABLE OUTBOX AUTO_INCREMENT = 1");
                st.execute("ALTER TABLE RECONCILIATION_ITEM AUTO_INCREMENT = 1");
            }
        }
    }

    static <T> T inTx(Function<Connection, T> work) {
        return TX.inTransaction(work);
    }

    static boolean txBool(Function<Connection, Boolean> work) {
        return TX.inTransaction(work);
    }

    static long txLong(Function<Connection, Long> work) {
        return TX.inTransaction(work);
    }

    static int txInt(Function<Connection, Integer> work) {
        return TX.inTransaction(work);
    }

    static void run(java.util.function.Consumer<Connection> work) {
        TX.inTransaction(c -> {
            work.accept(c);
            return null;
        });
    }

    /** A connection with autocommit off that the test commits or rolls back explicitly (for interleavings). */
    static Connection openTx() throws SQLException {
        Connection c = DS.getConnection();
        c.setAutoCommit(false);
        return c;
    }
}
