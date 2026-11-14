// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.support;

import java.sql.Connection;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Transactions;

/**
 * The transactions shared by the engine and the product layer (P-1, Q1), as plain Java (M-141c). The host supplies the
 * implementation that binds them to its transaction manager; the Spring host's is
 * {@code org.sangam.rest.config.SpringTxSupport}. The engine runs on the same {@link DataSource} and transaction
 * manager, so engine commands issued inside {@link #inTx} join this transaction, and {@link #connection()} returns the
 * very connection the engine uses: product rows (audit chain, ledger, tombstones, idempotency) commit or roll back
 * together with engine state.
 *
 * <p>Contract every implementation keeps: {@link #inTx}, {@link #inTxWithStatus}, {@link #runInTx} and
 * {@link #withConnection} join the current transaction or start one at READ COMMITTED; {@link #inNewTx} and
 * {@link #newTransactions()} always start a new, independent one at READ COMMITTED; an exception leaving the work rolls
 * the transaction back and is rethrown unchanged.
 */
public abstract class TxSupport {

    /** The status of the running transaction, handed to {@link #inTxWithStatus}. */
    public interface TxStatus {

        /** The transaction ends in a rollback, although the work returns normally. */
        void setRollbackOnly();

        boolean isRollbackOnly();
    }

    private final DataSource dataSource;
    private final Dialect dialect;

    protected TxSupport(DataSource dataSource, Dialect dialect) {
        this.dataSource = dataSource;
        this.dialect = dialect;
    }

    public Dialect dialect() {
        return dialect;
    }

    public DataSource dataSource() {
        return dataSource;
    }

    /** Joins the current transaction or starts one. */
    public <T> T inTx(Supplier<T> work) {
        return inTxWithStatus(status -> work.get());
    }

    /** Joins/starts a transaction and gives the work its status (e.g. to mark it rollback-only). */
    public abstract <T> T inTxWithStatus(Function<TxStatus, T> work);

    public void runInTx(Runnable work) {
        inTxWithStatus(status -> {
            work.run();
            return null;
        });
    }

    /** Joins/starts a transaction and hands the work the transaction-bound connection. */
    public <T> T withConnection(Function<Connection, T> work) {
        return inTxWithStatus(status -> work.apply(connection()));
    }

    /** Always a new, independent transaction (idempotency claim, FAILED_FINAL write, audit of a rejected call). */
    public abstract <T> T inNewTx(Supplier<T> work);

    /** The connection bound to the current transaction (do not close it). */
    public abstract Connection connection();

    /**
     * Runs {@code work} once the current transaction has committed, and not at all when it rolls back; outside a
     * transaction it runs at once (work handed to a background executor must not see uncommitted rows).
     */
    public abstract void afterCommit(Runnable work);

    /** core {@link Transactions} view that runs every unit in its own new transaction. */
    public Transactions newTransactions() {
        return new Transactions() {
            @Override
            public <T> T inTransaction(Function<Connection, T> work) {
                return inNewTx(() -> work.apply(connection()));
            }
        };
    }

    /** Detects the product-layer dialect from the pool (H2 is treated as PostgreSQL-compatible for development). */
    public static Dialect detectDialect(DataSource ds) {
        try (Connection c = ds.getConnection()) {
            String p = c.getMetaData().getDatabaseProductName().toLowerCase(java.util.Locale.ROOT);
            if (p.contains("oracle")) {
                return Dialect.ORACLE;
            }
            if (p.contains("sql server")) {
                return Dialect.SQLSERVER;
            }
            if (p.contains("mysql") || p.contains("mariadb")) {
                return Dialect.MYSQL;
            }
            return Dialect.POSTGRESQL;
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("cannot detect the database dialect", e);
        }
    }
}
