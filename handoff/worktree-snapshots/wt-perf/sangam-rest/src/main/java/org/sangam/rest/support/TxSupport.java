// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.support;

import java.sql.Connection;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Transactions;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Spring-managed transactions shared by the engine and the product layer. The Operaton Spring Boot starter runs the
 * engine on the same {@link DataSource} and {@link PlatformTransactionManager}, so engine commands issued inside
 * {@link #inTx} join this transaction, and {@link #connection()} returns the very connection the engine uses: product
 * rows (audit chain, ledger, tombstones, idempotency) commit or roll back together with engine state (P-1, Q1).
 */
public final class TxSupport {

    private final DataSource dataSource;
    private final TransactionTemplate required;
    private final TransactionTemplate requiresNew;
    private final Dialect dialect;

    public TxSupport(DataSource dataSource, PlatformTransactionManager txManager, Dialect dialect) {
        this.dataSource = dataSource;
        this.required = new TransactionTemplate(txManager);
        this.required.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.requiresNew = new TransactionTemplate(txManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.requiresNew.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
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
        return required.execute(status -> work.get());
    }

    /** Joins/starts a transaction and gives the work its status (e.g. to mark it rollback-only). */
    public <T> T inTxWithStatus(Function<org.springframework.transaction.TransactionStatus, T> work) {
        return required.execute(work::apply);
    }

    public void runInTx(Runnable work) {
        required.executeWithoutResult(status -> work.run());
    }

    /** Joins/starts a transaction and hands the work the transaction-bound connection. */
    public <T> T withConnection(Function<Connection, T> work) {
        return required.execute(status -> work.apply(connection()));
    }

    /** Always a new, independent transaction (idempotency claim, FAILED_FINAL write, audit of a rejected call). */
    public <T> T inNewTx(Supplier<T> work) {
        return requiresNew.execute(status -> work.get());
    }

    /** The connection bound to the current Spring transaction (do not close it). */
    public Connection connection() {
        return DataSourceUtils.getConnection(dataSource);
    }

    /** core {@link Transactions} view that runs every unit in its own new Spring transaction. */
    public Transactions newTransactions() {
        return new Transactions() {
            @Override
            public <T> T inTransaction(Function<Connection, T> work) {
                return requiresNew.execute(status -> work.apply(connection()));
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
