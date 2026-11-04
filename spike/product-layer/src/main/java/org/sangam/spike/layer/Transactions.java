package org.sangam.spike.layer;

import org.sangam.spike.api.EngineAdapter;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Function;

/**
 * Runs a unit of work in one database transaction. Product components that need their own short
 * transaction (idempotency claim, relay claim/mark, inbox claim, sweepers) take one of these, so they can run
 * either through {@link EngineAdapter#inEngineTransaction(Function)} or on a plain {@link DataSource}.
 */
@FunctionalInterface
public interface Transactions {

    <T> T inTransaction(Function<Connection, T> work);

    /** Uses the engine's own transaction (product rows commit with engine state). */
    static Transactions of(EngineAdapter engine) {
        return engine::inEngineTransaction;
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
        };
    }
}
