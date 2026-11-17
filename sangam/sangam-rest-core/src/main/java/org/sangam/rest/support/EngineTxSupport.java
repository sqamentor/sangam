// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.support;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.sangam.core.api.EngineAdapter;
import org.sangam.core.layer.Dialect;
import org.sangam.engine.tx.CommandState;

/**
 * RM-30 (decision 386(2)): the {@link TxSupport} of a host without a transaction manager (the plain-Java host). The
 * engine's own command transaction is the transaction (P-1): {@link #inTxWithStatus} runs the work inside
 * {@link EngineAdapter#inEngineTransaction}, so engine calls made by the work join the same command (Operaton reuses
 * the active command context) and {@link #connection()} is the command's own connection. Engine state and product
 * rows commit once, or roll back together.
 *
 * <ul>
 *   <li>Joining: inside a transaction of this class, or inside an engine command (a delegate, a job), the work joins
 *       it.</li>
 *   <li>Rollback-only: the outermost unit of this class rolls the command back and still returns the work's value; a
 *       unit that joined an engine command it did not open fails that command (never a partial commit).</li>
 *   <li>{@link #inNewTx}: a new, independent JDBC transaction on the pool, for the short own-transaction claims
 *       (idempotency, the audit of a refused call). Engine calls do not belong in it.</li>
 *   <li>{@link #afterCommit}: after the engine command commits (and never on a rollback); after the commit of an
 *       {@link #inNewTx} unit inside one; at once outside every transaction.</li>
 * </ul>
 */
public final class EngineTxSupport extends TxSupport {

    private final EngineAdapter engine;
    private final ThreadLocal<Frame> current = new ThreadLocal<>();

    /** One unit of this class on this thread. */
    private static final class Frame implements TxStatus {
        final Connection connection;
        /** Null for an engine command; the own transaction of {@link #inNewTx} otherwise. */
        final List<Runnable> afterOwnCommit;
        boolean rollbackOnly;

        Frame(Connection connection, List<Runnable> afterOwnCommit) {
            this.connection = connection;
            this.afterOwnCommit = afterOwnCommit;
        }

        @Override
        public void setRollbackOnly() {
            rollbackOnly = true;
        }

        @Override
        public boolean isRollbackOnly() {
            return rollbackOnly;
        }
    }

    /** Carries the work's value out of a command rolled back because the work asked for it. */
    private static final class RollbackOnly extends RuntimeException {
        final transient Object value;

        RollbackOnly(Object value) {
            super("rollback-only", null, false, false);
            this.value = value;
        }
    }

    public EngineTxSupport(EngineAdapter engine, DataSource dataSource, Dialect dialect) {
        super(dataSource, dialect);
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    @Override
    public <T> T inTxWithStatus(Function<TxStatus, T> work) {
        Frame joined = current.get();
        if (joined != null) {
            return work.apply(joined);
        }
        try {
            return engine.inEngineTransaction(c -> {
                Frame f = new Frame(c, null);
                current.set(f);
                try {
                    T value = work.apply(f);
                    if (f.rollbackOnly) {
                        throw new RollbackOnly(value);
                    }
                    return value;
                } finally {
                    current.remove();
                }
            });
        } catch (RollbackOnly r) {
            @SuppressWarnings("unchecked")
            T value = (T) r.value;
            return value;
        }
    }

    @Override
    public <T> T inNewTx(Supplier<T> work) {
        Frame outer = current.get();
        List<Runnable> after = new ArrayList<>();
        T value;
        try (Connection c = dataSource().getConnection()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            Frame f = new Frame(c, after);
            current.set(f);
            try {
                value = work.get();
                if (f.rollbackOnly) {
                    c.rollback();
                    return value;
                }
                c.commit();
            } catch (RuntimeException | Error e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
                if (outer == null) {
                    current.remove();
                } else {
                    current.set(outer);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("own transaction failed: " + e.getMessage(), e);
        }
        after.forEach(Runnable::run);
        return value;
    }

    @Override
    public Connection connection() {
        Frame f = current.get();
        return f != null ? f.connection : CommandState.currentConnection();
    }

    @Override
    public void afterCommit(Runnable work) {
        Frame f = current.get();
        if (f != null && f.afterOwnCommit != null) {
            f.afterOwnCommit.add(work);
            return;
        }
        CommandState.afterCommit(work);
    }
}
