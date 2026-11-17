// SPDX-License-Identifier: Apache-2.0
package org.sangam.quarkus.host;

import org.sangam.core.api.ReadWork;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.sql.DataSource;

import jakarta.transaction.HeuristicMixedException;
import jakarta.transaction.HeuristicRollbackException;
import jakarta.transaction.InvalidTransactionException;
import jakarta.transaction.NotSupportedException;
import jakarta.transaction.RollbackException;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.SystemException;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;

import org.sangam.core.layer.Dialect;
import org.sangam.rest.support.TxSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Quarkus host's {@link TxSupport} (extraction phase 3, blocker 3): the contract the Spring host's
 * {@code SpringTxSupport} keeps, on the JTA {@link TransactionManager} and the JTA-enlisted data source the engine runs
 * on, so the idempotency store and the audit port can be assembled on this host.
 *
 * <ul>
 *   <li>{@link #inTxWithStatus} (and {@code inTx}, {@code runInTx}, {@code withConnection}) joins the running JTA
 *       transaction, or begins one and commits it after the work (Spring's REQUIRED). A failure rolls an owned
 *       transaction back and marks a joined one rollback-only; it is rethrown unchanged.</li>
 *   <li>{@link #inNewTx} always runs in a new, independent transaction (Spring's REQUIRES_NEW, which Spring's own JTA
 *       transaction manager runs the same way): the running transaction is suspended, a new one begun and ended, and
 *       the suspended one resumed whatever happened. What the new transaction commits stays committed when the outer
 *       one rolls back, and its rollback leaves the outer one untouched.</li>
 *   <li>{@link #connection()} is the one connection bound to the running transaction (the one the engine's commands
 *       in that transaction use as well); it is closed when the transaction completes, never by the caller. Outside a
 *       transaction it is refused: a connection that nobody closes is never handed out.</li>
 *   <li>{@link #afterCommit} runs its work once the running transaction has committed and not at all when it rolls
 *       back; outside a transaction at once.</li>
 *   <li>A transaction marked rollback-only by its own {@link TxStatus} ends in a rollback and the work's result is
 *       returned; one marked rollback-only by a joined participant ends in a rollback and is reported
 *       ({@code IllegalStateException}), as Spring reports an unexpected rollback.</li>
 * </ul>
 * READ COMMITTED is the isolation of the pool itself: the database checks refuse the engine's start on a pool with
 * another level (decision 287 c), so no level is set per transaction.
 */
public final class JtaTxSupport extends TxSupport {

    private static final Logger LOG = LoggerFactory.getLogger(JtaTxSupport.class);

    private final TransactionManager transactionManager;
    private final Map<Transaction, Connection> bound = new ConcurrentHashMap<>();

    public JtaTxSupport(TransactionManager transactionManager, DataSource dataSource, Dialect dialect) {
        super(Objects.requireNonNull(dataSource, "dataSource"), Objects.requireNonNull(dialect, "dialect"));
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager");
    }

    public TransactionManager transactionManager() {
        return transactionManager;
    }

    @Override
    public <T> T inTxWithStatus(Function<TxStatus, T> work) {
        Objects.requireNonNull(work, "work");
        boolean owner = begin();
        WorkStatus status = new WorkStatus();
        T result;
        try {
            result = work.apply(status);
        } catch (RuntimeException | Error e) {
            abandon(owner, e);
            throw e;
        }
        if (owner) {
            end(status.marked);
        }
        return result;
    }

    @Override
    public <T> T withReadConnection(ReadWork<T> work) {
        Objects.requireNonNull(work, "work");
        boolean owner = begin();
        T result;
        try {
            result = work.read(connection());
        } catch (RuntimeException | Error e) {
            abandon(owner, e);
            throw e;
        }
        if (owner) {
            end(false);
        }
        return result;
    }

    @Override
    public <T> T inNewReadTx(ReadWork<T> work) {
        Objects.requireNonNull(work, "work");
        Transaction suspended = suspend();
        try {
            beginNew();
            T result;
            try {
                result = work.read(connection());
            } catch (RuntimeException | Error e) {
                abandon(true, e);
                throw e;
            }
            end(false);
            return result;
        } finally {
            resume(suspended);
        }
    }

    @Override
    public <T> T inNewTx(Supplier<T> work) {
        Objects.requireNonNull(work, "work");
        Transaction suspended = suspend();
        try {
            beginNew();
            T result;
            try {
                result = work.get();
            } catch (RuntimeException | Error e) {
                abandon(true, e);
                throw e;
            }
            end(false);
            return result;
        } finally {
            resume(suspended);
        }
    }

    @Override
    public Connection connection() {
        Transaction tx = running();
        if (tx == null) {
            throw new IllegalStateException("no JTA transaction runs: the transaction-bound connection exists only "
                    + "inside inTx, withConnection or inNewTx");
        }
        Connection existing = bound.get(tx);
        if (existing != null) {
            return existing;
        }
        Connection c;
        try {
            c = dataSource().getConnection();
        } catch (SQLException e) {
            throw new IllegalStateException("the JTA-enlisted connection could not be obtained", e);
        }
        try {
            tx.registerSynchronization(new Release(tx, c));
        } catch (RollbackException | SystemException | RuntimeException e) {
            close(c, e);
            throw new IllegalStateException("the connection could not be bound to the JTA transaction", e);
        }
        bound.put(tx, c);
        return c;
    }

    @Override
    public void afterCommit(Runnable work) {
        Objects.requireNonNull(work, "work");
        Transaction tx = running();
        if (tx == null) {
            work.run();
            return;
        }
        try {
            tx.registerSynchronization(new Synchronization() {
                @Override
                public void beforeCompletion() {
                    // nothing before completion
                }

                @Override
                public void afterCompletion(int completion) {
                    if (completion == jakarta.transaction.Status.STATUS_COMMITTED) {
                        work.run();
                    }
                }
            });
        } catch (RollbackException e) {
            // marked rollback-only: it will never commit, so the work never runs
        } catch (SystemException e) {
            throw new IllegalStateException("after-commit work could not be registered", e);
        }
    }

    /** The number of connections bound to running transactions (tests: none after every transaction). */
    int boundConnections() {
        return bound.size();
    }

    // ------------------------------------------------------------------ JTA steps

    /** The running (active or rollback-only) transaction, or {@code null}. */
    private Transaction running() {
        try {
            int s = transactionManager.getStatus();
            return s == jakarta.transaction.Status.STATUS_ACTIVE || s == jakarta.transaction.Status.STATUS_MARKED_ROLLBACK
                    ? transactionManager.getTransaction() : null;
        } catch (SystemException e) {
            throw new IllegalStateException("JTA status could not be read", e);
        }
    }

    /** Begins a transaction when none runs; true when this call owns it. */
    private boolean begin() {
        try {
            int s = transactionManager.getStatus();
            if (s == jakarta.transaction.Status.STATUS_ACTIVE || s == jakarta.transaction.Status.STATUS_MARKED_ROLLBACK) {
                return false;
            }
            if (s != jakarta.transaction.Status.STATUS_NO_TRANSACTION) {
                throw new IllegalStateException("JTA transaction in state " + s + "; no work is joined to it");
            }
        } catch (SystemException e) {
            throw new IllegalStateException("JTA status could not be read", e);
        }
        beginNew();
        return true;
    }

    private void beginNew() {
        try {
            transactionManager.begin();
        } catch (NotSupportedException | SystemException e) {
            throw new IllegalStateException("JTA transaction could not be begun", e);
        }
    }

    /** Commits the owned transaction, or rolls it back when it is rollback-only. */
    private void end(boolean markedHere) {
        try {
            if (transactionManager.getStatus() == jakarta.transaction.Status.STATUS_MARKED_ROLLBACK) {
                transactionManager.rollback();
                if (!markedHere) {
                    throw new IllegalStateException("JTA transaction was marked rollback-only by a joined participant;"
                            + " it was rolled back");
                }
                return;
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

    private Transaction suspend() {
        try {
            return running() == null ? null : transactionManager.suspend();
        } catch (SystemException e) {
            throw new IllegalStateException("the running JTA transaction could not be suspended", e);
        }
    }

    private void resume(Transaction suspended) {
        if (suspended == null) {
            return;
        }
        try {
            transactionManager.resume(suspended);
        } catch (InvalidTransactionException | SystemException e) {
            throw new IllegalStateException("the suspended JTA transaction could not be resumed", e);
        }
    }

    private static void close(Connection c, Throwable cause) {
        try {
            c.close();
        } catch (SQLException e) {
            cause.addSuppressed(e);
        }
    }

    /** The status a work unit sees: marking it rollback-only marks the running transaction. */
    private final class WorkStatus implements TxStatus {

        private boolean marked;

        @Override
        public void setRollbackOnly() {
            try {
                transactionManager.setRollbackOnly();
                marked = true;
            } catch (SystemException e) {
                throw new IllegalStateException("JTA transaction could not be marked rollback-only", e);
            }
        }

        @Override
        public boolean isRollbackOnly() {
            try {
                return transactionManager.getStatus() == jakarta.transaction.Status.STATUS_MARKED_ROLLBACK;
            } catch (SystemException e) {
                throw new IllegalStateException("JTA status could not be read", e);
            }
        }
    }

    /** Closes the transaction's bound connection once the transaction has completed. */
    private final class Release implements Synchronization {

        private final Transaction tx;
        private final Connection connection;

        Release(Transaction tx, Connection connection) {
            this.tx = tx;
            this.connection = connection;
        }

        @Override
        public void beforeCompletion() {
            // the connection stays open until the transaction manager has ended the transaction
        }

        @Override
        public void afterCompletion(int completion) {
            bound.remove(tx, connection);
            try {
                connection.close();
            } catch (SQLException e) {
                LOG.warn("the connection bound to a completed JTA transaction did not close: {}", e.getMessage());
            }
        }
    }
}
