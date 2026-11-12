// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.db;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import java.util.logging.Logger;

import javax.sql.DataSource;

/**
 * READ COMMITTED on every connection the engine takes (FLOWABLE-INTO-OPERATON D-56, R84, ARCHITECTURE §1.6 "REPEATABLE READ rejected").
 * MySQL's default is REPEATABLE READ, which Operaton refuses at build ({@code ENGINE-12019}); a pool that hands out a
 * session left in another level by other code is also corrected here, per checkout. SL-59: each checked-out connection
 * without a network timeout of its own also gets the engine's socket read bound ({@link JdbcWaitBounds}).
 */
public final class IsolationGuardDataSource implements DataSource {

    private final DataSource delegate;
    private final Duration networkTimeout;

    /** With the default network read bound ({@link JdbcWaitBounds#DEFAULT_NETWORK_TIMEOUT}). */
    public IsolationGuardDataSource(DataSource delegate) {
        this(delegate, JdbcWaitBounds.DEFAULT_NETWORK_TIMEOUT);
    }

    /**
     * @param networkTimeout SL-59: socket read bound set on every checked-out connection that has none of its own
     *                       ({@link JdbcWaitBounds#boundNetwork}); zero or null disables it
     */
    public IsolationGuardDataSource(DataSource delegate, Duration networkTimeout) {
        this.delegate = delegate;
        this.networkTimeout = networkTimeout == null ? Duration.ZERO : networkTimeout;
    }

    public DataSource delegate() {
        return delegate;
    }

    private Connection guard(Connection c) throws SQLException {
        try {
            if (c.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
                c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            }
            JdbcWaitBounds.boundNetwork(c, networkTimeout); // SL-59: no read on this connection waits for ever
            return c;
        } catch (SQLException | RuntimeException e) {
            try {
                c.close(); // never leak the checked-out connection when the guard fails
            } catch (SQLException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }

    /** The network read bound applied on checkout (SL-59); {@link Duration#ZERO} when disabled. */
    public Duration networkTimeout() {
        return networkTimeout;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return guard(delegate.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return guard(delegate.getConnection(username, password));
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        delegate.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return delegate.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return iface.isInstance(this) ? iface.cast(this) : delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this) || delegate.isWrapperFor(iface);
    }
}
