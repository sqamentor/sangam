// SPDX-License-Identifier: Apache-2.0
package org.sangam.quarkus.host;

import org.sangam.core.api.ReadWork;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.function.Function;

import javax.sql.DataSource;

import jakarta.transaction.HeuristicMixedException;
import jakarta.transaction.HeuristicRollbackException;
import jakarta.transaction.NotSupportedException;
import jakarta.transaction.RollbackException;
import jakarta.transaction.Status;
import jakarta.transaction.SystemException;
import jakarta.transaction.TransactionManager;

import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;

/**
 * Shared-transaction seam of the Quarkus host (P-1, design note sections 1.3 and 10.1): the core {@link Transactions}
 * over the JTA {@link TransactionManager} and the JTA-enlisted data source the engine uses as well.
 *
 * <ul>
 *   <li>Inside a running JTA transaction (an engine command, or a caller's transaction) the work joins it: the data
 *       source hands out the connection enlisted in that transaction, so product rows and engine rows commit or roll
 *       back together, never in two transactions. A failure marks the transaction rollback-only and is rethrown; the
 *       owner of the transaction ends it.</li>
 *   <li>Without one, a transaction is begun, committed after the work and rolled back when the work fails.</li>
 * </ul>
 * The connection's commit and auto-commit are never touched: the transaction manager owns them.
 */
public final class JtaTransactions implements Transactions {

    private final TransactionManager transactionManager;
    private final DataSource dataSource;

    public JtaTransactions(TransactionManager transactionManager, DataSource dataSource) {
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public TransactionManager transactionManager() {
        return transactionManager;
    }

    public DataSource dataSource() {
        return dataSource;
    }

    @Override
    public <T> T inTransaction(Function<Connection, T> work) {
        Objects.requireNonNull(work, "work");
        boolean owner = begin();
        T result;
        try (Connection c = dataSource.getConnection()) {
            result = work.apply(c);
        } catch (SQLException e) {
            Jdbc.LayerSqlException failure = new Jdbc.LayerSqlException("transaction", e);
            abandon(owner, failure);
            throw failure;
        } catch (RuntimeException | Error e) {
            abandon(owner, e);
            throw e;
        }
        if (owner) {
            commit();
        }
        return result;
    }

    @Override
    public <T> T read(ReadWork<T> work) {
        Objects.requireNonNull(work, "work");
        boolean owner = begin();
        T result;
        try (Connection c = dataSource.getConnection()) {
            result = work.read(c);
        } catch (SQLException e) {
            Jdbc.LayerSqlException failure = new Jdbc.LayerSqlException("transaction", e);
            abandon(owner, failure);
            throw failure;
        } catch (RuntimeException | Error e) {
            abandon(owner, e);
            throw e;
        }
        if (owner) {
            commit();
        }
        return result;
    }

    /** Begins a transaction when none runs; true when this call owns it. */
    private boolean begin() {
        try {
            int status = transactionManager.getStatus();
            if (status == Status.STATUS_ACTIVE || status == Status.STATUS_MARKED_ROLLBACK) {
                return false;
            }
            if (status != Status.STATUS_NO_TRANSACTION) {
                throw new IllegalStateException("JTA transaction in state " + status + "; no work is joined to it");
            }
            transactionManager.begin();
            return true;
        } catch (SystemException | NotSupportedException e) {
            throw new IllegalStateException("JTA transaction could not be begun", e);
        }
    }

    private void commit() {
        try {
            if (transactionManager.getStatus() == Status.STATUS_MARKED_ROLLBACK) {
                transactionManager.rollback();
                throw new IllegalStateException("JTA transaction was marked rollback-only; it was rolled back");
            }
            transactionManager.commit();
        } catch (RollbackException | HeuristicMixedException | HeuristicRollbackException | SystemException e) {
            throw new IllegalStateException("JTA transaction did not commit", e);
        }
    }

    /** Ends an owned transaction by rollback, or marks a joined one rollback-only; problems are added to {@code cause}. */
    private void abandon(boolean owner, Throwable cause) {
        try {
            if (owner) {
                transactionManager.rollback();
            } else {
                transactionManager.setRollbackOnly();
            }
        } catch (SystemException | RuntimeException e) {
            cause.addSuppressed(e);
        }
    }
}
