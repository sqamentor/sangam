// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.sangam.engine.db.JdbcWaitBounds;
import org.springframework.mock.env.MockEnvironment;

/** SL-59: every Hikari pool gets a finite acquire and a socket read bound; a dead connection times out. */
class PoolWaitBoundsTest {

    private static final Duration T = Duration.ofSeconds(300);
    private BlackHole hole;

    @AfterEach
    void close() throws IOException {
        if (hole != null) {
            hole.close();
        }
    }

    private static HikariConfig pool(String url) {
        HikariConfig c = new HikariConfig();
        c.setJdbcUrl(url);
        return c;
    }

    @Test
    void addsTheDriversReadTimeoutToEachPool() {
        HikariConfig pg = pool("jdbc:postgresql://db:5432/sangam");
        PoolWaitBounds.apply(pg, T, "main");
        assertEquals("300", pg.getDataSourceProperties().getProperty("socketTimeout"));

        HikariConfig ms = pool("jdbc:sqlserver://db:1433;databaseName=sangam");
        PoolWaitBounds.apply(ms, T, "main");
        assertEquals("300000", ms.getDataSourceProperties().getProperty("socketTimeout"));

        HikariConfig maria = pool("jdbc:mariadb://db:3306/sangam?permitMysqlScheme");
        PoolWaitBounds.apply(maria, T, "main");
        assertEquals("300000", maria.getDataSourceProperties().getProperty("socketTimeout"));

        HikariConfig ora = pool("jdbc:oracle:thin:@//db:1521/FREEPDB1");
        PoolWaitBounds.apply(ora, T, "main");
        assertEquals("300000", ora.getDataSourceProperties().getProperty("oracle.jdbc.ReadTimeout"));
    }

    @Test
    void keepsTheOperatorsValue() {
        HikariConfig inUrl = pool("jdbc:postgresql://db/sangam?socketTimeout=60");
        PoolWaitBounds.apply(inUrl, T, "main");
        assertNull(inUrl.getDataSourceProperties().getProperty("socketTimeout"));

        HikariConfig inProps = pool("jdbc:sqlserver://db;databaseName=sangam");
        inProps.addDataSourceProperty("socketTimeout", "0");
        PoolWaitBounds.apply(inProps, T, "main");
        assertEquals("0", inProps.getDataSourceProperties().getProperty("socketTimeout"));
    }

    @Test
    void zeroDisablesTheReadBoundAndH2HasNone() {
        HikariConfig off = pool("jdbc:postgresql://db/sangam");
        PoolWaitBounds.apply(off, Duration.ZERO, "main");
        assertTrue(off.getDataSourceProperties().isEmpty());

        HikariConfig h2 = pool("jdbc:h2:mem:x;DB_CLOSE_DELAY=-1");
        PoolWaitBounds.apply(h2, T, "main");
        assertTrue(h2.getDataSourceProperties().isEmpty());
    }

    @Test
    void anUnboundedPoolAcquireFailsStartUp() {
        HikariConfig forever = pool("jdbc:postgresql://db/sangam");
        forever.setConnectionTimeout(0); // HikariCP: 0 = wait for ever
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> PoolWaitBounds.apply(forever, T, "main"));
        assertTrue(e.getMessage().contains("connectionTimeout"), e.getMessage());

        HikariConfig bounded = pool("jdbc:postgresql://db/sangam");
        bounded.setConnectionTimeout(5_000);
        PoolWaitBounds.apply(bounded, T, "main");
    }

    @Test
    void networkTimeoutPropertyDefaultsAndBinds() {
        assertEquals(JdbcWaitBounds.DEFAULT_NETWORK_TIMEOUT, PoolWaitBounds.networkTimeout(new MockEnvironment()));
        assertEquals(Duration.ofSeconds(45), PoolWaitBounds.networkTimeout(
                new MockEnvironment().withProperty("sangam.db.network-timeout", "45s")));
        assertEquals(Duration.ZERO, PoolWaitBounds.networkTimeout(
                new MockEnvironment().withProperty("sangam.db.network-timeout", "0")));
    }

    @Test
    void postProcessorBoundsHikariBeansBeforeTheyStart() throws Exception {
        HikariDataSource ds = new HikariDataSource();
        try {
            ds.setJdbcUrl("jdbc:postgresql://db/sangam");
            Object out = PoolWaitBounds.sangamPoolWaitBounds(
                            new MockEnvironment().withProperty("sangam.db.network-timeout", "20s"))
                    .postProcessBeforeInitialization(ds, "dataSource");
            assertSame(ds, out);
            assertEquals("20", ds.getDataSourceProperties().getProperty("socketTimeout"));
            assertFalse(ds.isRunning());
        } finally {
            ds.close();
        }
    }

    /**
     * The P-419 class, planted: a server that accepts the connection and never answers. With the pool's properties
     * the driver gives up within the bound; without them the same connect is still blocked after several bounds
     * (red without the fix) and only ends when the socket is closed under it.
     */
    @Test
    void aDeadConnectionTimesOutWithinTheBound() throws Exception {
        hole = new BlackHole();
        String url = "jdbc:postgresql://127.0.0.1:" + hole.port() + "/sangam?sslmode=disable&connectTimeout=5";
        HikariConfig cfg = pool(url);
        PoolWaitBounds.apply(cfg, Duration.ofSeconds(1), "main");
        Properties props = new Properties();
        props.putAll(cfg.getDataSourceProperties());
        props.setProperty("user", "sangam");
        props.setProperty("password", "sangam");

        long start = System.nanoTime();
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            assertThrows(SQLException.class, () -> DriverManager.getConnection(url, props));
        });
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(tookMs < 10_000, "bounded read ended after " + tookMs + " ms");

        Properties unbounded = new Properties();
        unbounded.setProperty("user", "sangam");
        unbounded.setProperty("password", "sangam");
        CompletableFuture<Object> blocked = CompletableFuture.supplyAsync(() -> {
            try {
                return DriverManager.getConnection(url, unbounded);
            } catch (SQLException e) {
                return e;
            }
        });
        Thread.sleep(3_000);
        assertFalse(blocked.isDone(), "without the read bound the connect must still be blocked (P-419 control)");
        hole.close(); // releases the blocked reader
        Object result = blocked.get(20, TimeUnit.SECONDS);
        assertTrue(result instanceof SQLException, String.valueOf(result));
    }

    /** Accepts connections, reads and discards what the client sends, never writes. */
    static final class BlackHole implements AutoCloseable {
        private final ServerSocket server;
        private final List<Socket> accepted = new CopyOnWriteArrayList<>();

        BlackHole() throws IOException {
            server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            Thread t = new Thread(() -> {
                while (!server.isClosed()) {
                    try {
                        Socket s = server.accept();
                        accepted.add(s);
                        Thread drain = new Thread(() -> {
                            try (InputStream in = s.getInputStream()) {
                                byte[] buf = new byte[1024];
                                while (in.read(buf) >= 0) {
                                    // discard
                                }
                            } catch (IOException ignored) {
                                // closed by the test
                            }
                        }, "black-hole-drain");
                        drain.setDaemon(true);
                        drain.start();
                    } catch (IOException e) {
                        return;
                    }
                }
            }, "black-hole-accept");
            t.setDaemon(true);
            t.start();
        }

        int port() {
            return server.getLocalPort();
        }

        @Override
        public void close() throws IOException {
            server.close();
            for (Socket s : accepted) {
                s.close();
            }
        }
    }
}
