// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.Executor;

/**
 * SL-59 (S-R-5 "never hang", P-419): the network read bound of every pooled JDBC connection.
 *
 * <p>The statement timeouts Sangam already sets (Operaton {@code jdbcStatementTimeout}, PostgreSQL
 * {@code SET LOCAL statement_timeout}, the per-dialect lock waits of {@link EngineDialect#applyLockTimeouts}) are
 * enforced by the database server or by a cancel request on a second channel. When the connection itself is dead (TCP
 * silently dropped, the server gone, a half-open socket) neither fires: the driver logs "Command could not be timed
 * out" and the calling thread stays in a socket read for ever (P-419, mssql-jdbc {@code NioSocketImpl.timedRead}).
 * Only a client-side socket read timeout ends that wait. It is set once per pool, generically, for every statement
 * on every connection (engine, product layer, quota pool, replica), instead of per call site:
 * <ul>
 *   <li>pools Sangam configures (the server's Hikari pools): the driver's own connection property
 *       ({@link #readTimeoutProperty}), unless the operator already set one in the URL or the pool properties;</li>
 *   <li>connections of a host-owned pool (embedded engine): {@link Connection#setNetworkTimeout} on checkout when the
 *       connection has none ({@link #boundNetwork}), never overriding the host's own value.</li>
 * </ul>
 * The bound must exceed the longest legitimate statement; it is a backstop behind the statement timeouts, which fire
 * first on a live connection.
 */
public final class JdbcWaitBounds {

    /**
     * Default network read bound: 300 s. Ten times the engine's {@code jdbcStatementTimeout} (30 s), so a live
     * statement is always ended by its statement timeout first; the value IFMS hardened its SQL Server and Oracle
     * pools with (P-419, R-93: hardened Oracle fails at 303 s, unhardened blocked past 360 s).
     */
    public static final Duration DEFAULT_NETWORK_TIMEOUT = Duration.ofSeconds(300);

    private static final Executor DIRECT = Runnable::run;

    private JdbcWaitBounds() {
    }

    /** The JDBC drivers Sangam ships or supports, by URL prefix. */
    public enum Driver {
        /** pgjdbc: {@code socketTimeout} in seconds. */
        POSTGRESQL("socketTimeout"),
        /** mssql-jdbc: {@code socketTimeout} in milliseconds. */
        SQLSERVER("socketTimeout"),
        /** MariaDB Connector/J (also for {@code jdbc:mysql:} URLs): {@code socketTimeout} in milliseconds. */
        MARIADB("socketTimeout"),
        /** Oracle thin: {@code oracle.jdbc.ReadTimeout} in milliseconds. */
        ORACLE("oracle.jdbc.ReadTimeout"),
        /** H2: embedded/in-memory in Sangam (development only), no socket. */
        H2(null),
        OTHER(null);

        private final String readTimeoutKey;

        Driver(String readTimeoutKey) {
            this.readTimeoutKey = readTimeoutKey;
        }

        /** The driver's connection property for the socket read timeout; null when the driver has none. */
        public String readTimeoutKey() {
            return readTimeoutKey;
        }

        public static Driver of(String jdbcUrl) {
            String u = jdbcUrl == null ? "" : jdbcUrl.toLowerCase(Locale.ROOT);
            if (u.startsWith("jdbc:postgresql:")) {
                return POSTGRESQL;
            }
            if (u.startsWith("jdbc:sqlserver:")) {
                return SQLSERVER;
            }
            if (u.startsWith("jdbc:mariadb:") || u.startsWith("jdbc:mysql:")) {
                return MARIADB;
            }
            if (u.startsWith("jdbc:oracle:")) {
                return ORACLE;
            }
            if (u.startsWith("jdbc:h2:")) {
                return H2;
            }
            return OTHER;
        }
    }

    /**
     * The driver connection property that bounds every socket read on a connection to {@code jdbcUrl} by
     * {@code timeout}, in that driver's unit (seconds for pgjdbc, rounded up; milliseconds otherwise). Empty when the
     * timeout is zero or negative (disabled) or the driver has no such property.
     */
    public static Optional<Map.Entry<String, String>> readTimeoutProperty(String jdbcUrl, Duration timeout) {
        Driver d = Driver.of(jdbcUrl);
        if (d.readTimeoutKey() == null || timeout == null || timeout.isZero() || timeout.isNegative()) {
            return Optional.empty();
        }
        long ms = timeout.toMillis();
        String value = d == Driver.POSTGRESQL
                ? Long.toString(Math.max(1, (ms + 999) / 1000))
                : Long.toString(Math.max(1, ms));
        return Optional.of(Map.entry(d.readTimeoutKey(), value));
    }

    /**
     * Whether the operator already chose a read timeout for {@code jdbcUrl}: the driver's key in the URL (any case)
     * or in {@code properties}. The operator's value always wins, including {@code 0}.
     */
    public static boolean readTimeoutConfigured(String jdbcUrl, Properties properties) {
        String key = Driver.of(jdbcUrl).readTimeoutKey();
        if (key == null) {
            return false;
        }
        String k = key.toLowerCase(Locale.ROOT);
        String u = jdbcUrl.toLowerCase(Locale.ROOT);
        int at = u.indexOf(k + "=");
        while (at >= 0) {
            char before = at == 0 ? '?' : u.charAt(at - 1);
            if (before == '?' || before == '&' || before == ';' || before == ':' || Character.isWhitespace(before)) {
                return true;
            }
            at = u.indexOf(k + "=", at + 1);
        }
        if (properties != null) {
            for (String name : properties.stringPropertyNames()) {
                if (name.equalsIgnoreCase(key)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Bounds every socket read on {@code c} by {@code timeout} when the connection has no network timeout of its own
     * ({@link Connection#getNetworkTimeout()} is 0). A host-set value is never changed. A pool that tracks the value
     * (HikariCP) restores its own default when the connection is returned, so this runs on every checkout. Drivers
     * without network timeouts (H2 in memory) are left as they are.
     *
     * @return whether the bound was set by this call
     */
    public static boolean boundNetwork(Connection c, Duration timeout) throws SQLException {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            return false;
        }
        try {
            if (c.getNetworkTimeout() != 0) {
                return false;
            }
            c.setNetworkTimeout(DIRECT, (int) Math.min(Integer.MAX_VALUE, Math.max(1, timeout.toMillis())));
            return true;
        } catch (SQLFeatureNotSupportedException | AbstractMethodError | UnsupportedOperationException e) {
            return false;
        }
    }
}
