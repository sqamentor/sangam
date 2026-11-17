// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.layer;

import org.sangam.core.api.EngineAdapter;
import org.sangam.core.api.ReadWork;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Function;

/**
 * Runs a unit of work in one database transaction. Product components that need their own short
 * transaction (idempotency claim, relay claim/mark, inbox claim, sweepers) take one of these, so they can run
 * either through {@link EngineAdapter#inEngineTransaction(Function)} or on a plain {@link DataSource}.
 *
 * <p>{@link #read(ReadWork)} is the same transaction for work that only reads (round 9): same connection, isolation,
 * commit and rollback as {@link #inTransaction(Function)}, but a {@link ReadWork} callback, so a read path never calls
 * the shared write callback. An implementation of {@code read} must not delegate to {@code inTransaction} (that would
 * call the write callback again); it repeats the transaction handling with {@code work.read(c)}.
 */
public interface Transactions {

    <T> T inTransaction(Function<Connection, T> work);

    /** {@link #inTransaction(Function)} for work that only reads. */
    <T> T read(ReadWork<T> work);

    /** Uses the engine's own transaction (product rows commit with engine state). */
    static Transactions of(EngineAdapter engine) {
        return new Transactions() {
            @Override
            public <T> T inTransaction(Function<Connection, T> work) {
                return engine.inEngineTransaction(work);
            }

            @Override
            public <T> T read(ReadWork<T> work) {
                return engine.inEngineReadTransaction(work);
            }
        };
    }

    /** Plain JDBC transaction on a pool (READ COMMITTED, the engines' default). */
    static Transactions of(DataSource ds) {
        return new Transactions() {
            @Override
            public <T> T inTransaction(Function<Connection, T> work) {
                try (Connection c = ds.getConnection()) {
                    boolean auto = c.getAutoCommit();
                    c.setAutoCommit(false);
                    try {
                        T result = work.apply(c);
                        c.commit();
                        return result;
                    } catch (RuntimeException | Error e) {
                        c.rollback();
                        throw e;
                    } finally {
                        c.setAutoCommit(auto);
                    }
                } catch (SQLException e) {
                    throw new Jdbc.LayerSqlException("transaction", e);
                }
            }

            @Override
            public <T> T read(ReadWork<T> work) {
                try (Connection c = ds.getConnection()) {
                    boolean auto = c.getAutoCommit();
                    c.setAutoCommit(false);
                    try {
                        T result = work.read(c);
                        c.commit();
                        return result;
                    } catch (RuntimeException | Error e) {
                        c.rollback();
                        throw e;
                    } finally {
                        c.setAutoCommit(auto);
                    }
                } catch (SQLException e) {
                    throw new Jdbc.LayerSqlException("transaction", e);
                }
            }
        };
    }
}
