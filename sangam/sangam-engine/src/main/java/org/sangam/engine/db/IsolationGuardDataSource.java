// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.db;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

import javax.sql.DataSource;

/**
 * READ COMMITTED on every connection the engine takes (FLOWABLE-INTO-OPERATON D-56, R84, ARCHITECTURE §1.6 "REPEATABLE READ rejected").
 * MySQL's default is REPEATABLE READ, which Operaton refuses at build ({@code ENGINE-12019}); a pool that hands out a
 * session left in another level by other code is also corrected here, per checkout.
 */
public final class IsolationGuardDataSource implements DataSource {

    private final DataSource delegate;

    public IsolationGuardDataSource(DataSource delegate) {
        this.delegate = delegate;
    }

    public DataSource delegate() {
        return delegate;
    }

    private static Connection guard(Connection c) throws SQLException {
        if (c.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        }
        return c;
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
