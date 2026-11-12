// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;

/** SL-59: driver read-timeout mapping and the checkout network bound of {@link IsolationGuardDataSource}. */
class JdbcWaitBoundsTest {

    private static final Duration T = Duration.ofSeconds(300);

    @Test
    void readTimeoutPropertyPerDriverInItsOwnUnit() {
        assertEquals(Map.entry("socketTimeout", "300"),
                JdbcWaitBounds.readTimeoutProperty("jdbc:postgresql://db:5432/sangam", T).orElseThrow());
        assertEquals(Map.entry("socketTimeout", "300000"),
                JdbcWaitBounds.readTimeoutProperty("jdbc:sqlserver://db:1433;databaseName=sangam", T).orElseThrow());
        assertEquals(Map.entry("socketTimeout", "300000"),
                JdbcWaitBounds.readTimeoutProperty("jdbc:mariadb://db:3306/sangam", T).orElseThrow());
        assertEquals(Map.entry("socketTimeout", "300000"),
                JdbcWaitBounds.readTimeoutProperty("jdbc:mysql://db:3306/sangam", T).orElseThrow());
        assertEquals(Map.entry("oracle.jdbc.ReadTimeout", "300000"),
                JdbcWaitBounds.readTimeoutProperty("jdbc:oracle:thin:@//db:1521/FREEPDB1", T).orElseThrow());
    }

    @Test
    void postgresSecondsRoundUpAndNeverZero() {
        assertEquals("2", JdbcWaitBounds.readTimeoutProperty("jdbc:postgresql://db/s", Duration.ofMillis(1001))
                .orElseThrow().getValue());
        assertEquals("1", JdbcWaitBounds.readTimeoutProperty("jdbc:postgresql://db/s", Duration.ofMillis(1))
                .orElseThrow().getValue());
    }

    @Test
    void noPropertyForH2UnknownDriversOrADisabledBound() {
        assertTrue(JdbcWaitBounds.readTimeoutProperty("jdbc:h2:mem:x", T).isEmpty());
        assertTrue(JdbcWaitBounds.readTimeoutProperty("jdbc:db2://db:50000/x", T).isEmpty());
        assertTrue(JdbcWaitBounds.readTimeoutProperty(null, T).isEmpty());
        assertTrue(JdbcWaitBounds.readTimeoutProperty("jdbc:postgresql://db/s", Duration.ZERO).isEmpty());
        assertTrue(JdbcWaitBounds.readTimeoutProperty("jdbc:postgresql://db/s", Duration.ofSeconds(-1)).isEmpty());
        assertTrue(JdbcWaitBounds.readTimeoutProperty("jdbc:postgresql://db/s", null).isEmpty());
    }

    @Test
    void operatorValueInUrlOrPropertiesIsDetected() {
        assertTrue(JdbcWaitBounds.readTimeoutConfigured("jdbc:postgresql://db/s?ssl=false&socketTimeout=0", null));
        assertTrue(JdbcWaitBounds.readTimeoutConfigured("jdbc:postgresql://db/s?SOCKETTIMEOUT=10", null));
        assertTrue(JdbcWaitBounds.readTimeoutConfigured("jdbc:sqlserver://db;databaseName=s;socketTimeout=300000", null));
        assertTrue(JdbcWaitBounds.readTimeoutConfigured("jdbc:mariadb://db/s?socketTimeout=1000", null));
        Properties p = new Properties();
        p.setProperty("oracle.jdbc.ReadTimeout", "60000");
        assertTrue(JdbcWaitBounds.readTimeoutConfigured("jdbc:oracle:thin:@//db:1521/x", p));
        Properties lower = new Properties();
        lower.setProperty("sockettimeout", "5");
        assertTrue(JdbcWaitBounds.readTimeoutConfigured("jdbc:sqlserver://db", lower));
    }

    @Test
    void lookalikeKeysAreNotTakenForTheOperatorsValue() {
        assertFalse(JdbcWaitBounds.readTimeoutConfigured("jdbc:postgresql://db/s?mysocketTimeout=5", null));
        assertFalse(JdbcWaitBounds.readTimeoutConfigured("jdbc:postgresql://db/s?ApplicationName=x", new Properties()));
        assertFalse(JdbcWaitBounds.readTimeoutConfigured("jdbc:h2:mem:x;socketTimeout=1", null));
    }

    @Test
    void boundNetworkSetsTheBoundOnlyWhenTheConnectionHasNone() throws SQLException {
        FakeConnection unbounded = new FakeConnection(0, false);
        assertTrue(JdbcWaitBounds.boundNetwork(unbounded.proxy(), T));
        assertEquals(List.of(300_000), unbounded.set);

        FakeConnection hostSet = new FakeConnection(60_000, false);
        assertFalse(JdbcWaitBounds.boundNetwork(hostSet.proxy(), T));
        assertTrue(hostSet.set.isEmpty(), "a host-set network timeout is never changed");

        FakeConnection off = new FakeConnection(0, false);
        assertFalse(JdbcWaitBounds.boundNetwork(off.proxy(), Duration.ZERO));
        assertFalse(JdbcWaitBounds.boundNetwork(off.proxy(), null));
        assertTrue(off.set.isEmpty());
    }

    @Test
    void driversWithoutNetworkTimeoutsAreLeftAlone() throws SQLException {
        FakeConnection unsupported = new FakeConnection(0, true);
        assertFalse(JdbcWaitBounds.boundNetwork(unsupported.proxy(), T));
    }

    @Test
    void isolationGuardBoundsEveryCheckoutWithTheConfiguredTimeout() throws SQLException {
        FakeConnection c = new FakeConnection(0, false);
        IsolationGuardDataSource ds = new IsolationGuardDataSource(new FakeDataSource(c), Duration.ofSeconds(7));
        assertSame(c.proxy(), ds.getConnection());
        assertEquals(List.of(7_000), c.set);
        assertEquals(Connection.TRANSACTION_READ_COMMITTED, c.isolation);
        assertEquals(Duration.ofSeconds(7), ds.networkTimeout());
    }

    @Test
    void isolationGuardDefaultsToTheDefaultBoundAndZeroDisablesIt() throws SQLException {
        assertEquals(JdbcWaitBounds.DEFAULT_NETWORK_TIMEOUT, new IsolationGuardDataSource(new FakeDataSource(null))
                .networkTimeout());
        FakeConnection c = new FakeConnection(0, false);
        new IsolationGuardDataSource(new FakeDataSource(c), Duration.ZERO).getConnection();
        assertTrue(c.set.isEmpty());
    }

    @Test
    void isolationGuardClosesTheConnectionWhenTheGuardFails() {
        FakeConnection c = new FakeConnection(0, false);
        c.failSetNetworkTimeout = true;
        IsolationGuardDataSource ds = new IsolationGuardDataSource(new FakeDataSource(c), T);
        assertThrows(SQLException.class, ds::getConnection);
        assertTrue(c.closed, "a connection whose guard failed is returned to the pool, not leaked");
    }

    /** A {@link Connection} stub recording network-timeout calls. */
    static final class FakeConnection {
        final List<Integer> set = new ArrayList<>();
        int networkTimeout;
        int isolation = Connection.TRANSACTION_REPEATABLE_READ;
        boolean closed;
        boolean failSetNetworkTimeout;
        private final boolean unsupported;
        private final Connection proxy;

        FakeConnection(int networkTimeout, boolean unsupported) {
            this.networkTimeout = networkTimeout;
            this.unsupported = unsupported;
            this.proxy = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (p, m, a) -> switch (m.getName()) {
                        case "getNetworkTimeout" -> {
                            if (this.unsupported) {
                                throw new SQLFeatureNotSupportedException("getNetworkTimeout");
                            }
                            yield this.networkTimeout;
                        }
                        case "setNetworkTimeout" -> {
                            if (failSetNetworkTimeout) {
                                throw new SQLException("socket closed");
                            }
                            set.add((Integer) a[1]);
                            this.networkTimeout = (Integer) a[1];
                            yield null;
                        }
                        case "getTransactionIsolation" -> isolation;
                        case "setTransactionIsolation" -> {
                            isolation = (Integer) a[0];
                            yield null;
                        }
                        case "close" -> {
                            closed = true;
                            yield null;
                        }
                        case "hashCode" -> System.identityHashCode(p);
                        case "equals" -> p == a[0];
                        case "toString" -> "FakeConnection";
                        default -> throw new UnsupportedOperationException(m.getName());
                    });
        }

        Connection proxy() {
            return proxy;
        }
    }

    /** A {@link DataSource} handing out one connection. */
    record FakeDataSource(FakeConnection c) implements DataSource {
        @Override
        public Connection getConnection() {
            return c.proxy();
        }

        @Override
        public Connection getConnection(String username, String password) {
            return c.proxy();
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("not a wrapper");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
