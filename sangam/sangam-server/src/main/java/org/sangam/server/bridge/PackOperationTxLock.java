// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import org.sangam.core.layer.DefinitionVersions;
import org.sangam.engine.db.EngineDialect;
import org.sangam.packs.spi.PackDeployer.PackLifecycleException;
import org.sangam.packs.spi.PackOperationLock;
import org.sangam.rest.support.TxSupport;

/**
 * SL-116: the server's {@link PackOperationLock}. The work runs in a Spring transaction ({@link TxSupport#inTx}, joined
 * when the caller already has one) that first row-locks the pack's mark in {@code EVT_DEFINITION_SEQ}
 * ({@link DefinitionVersions#lockPack}, kind {@code pack}, key = (tenant scope, pack id)) on the transaction's own
 * connection. Operaton's commands join that transaction on the same connection ({@code TransactionAwareDataSourceProxy},
 * see {@link EngineConfigurationCustomizer}), so the lock and the deployment commit together and a second operation on
 * the same pack and scope computes from the first one's committed result. Another pack or tenant is another row.
 *
 * <p><b>Bounded wait.</b> The lock statement waits at most {@code lockWait}: PostgreSQL {@code SET LOCAL lock_timeout}
 * (as {@code set_config('lock_timeout', ?, true)}, see {@link #setLocalLockTimeout}; to the end of the transaction,
 * so the deployment's own lock waits are bounded too); SQL Server, MySQL and H2 the
 * session lock wait of {@link EngineDialect#applyLockTimeouts}, set back right after the lock statement (SL-99: a
 * pooled connection never keeps it); Oracle {@code FOR UPDATE WAIT 2} of the product-layer row lock. A lock-wait
 * timeout anywhere in the section ({@link #isLockTimeout}) is reported as {@link PackLifecycleException}
 * {@link PackOperationLock#PACK_OPERATION_IN_PROGRESS} (REST 409), after the transaction rolled back.
 */
public final class PackOperationTxLock implements PackOperationLock {

    private final TxSupport tx;
    private final EngineDialect engineDialect;
    private final Duration lockWait;

    public PackOperationTxLock(TxSupport tx, EngineDialect engineDialect, Duration lockWait) {
        this.tx = tx;
        this.engineDialect = engineDialect;
        this.lockWait = lockWait;
    }

    /** Detects the engine dialect from the transaction's pool. */
    public static PackOperationTxLock of(TxSupport tx, Duration lockWait) {
        try (Connection c = tx.dataSource().getConnection()) {
            return new PackOperationTxLock(tx, EngineDialect.detect(c), lockWait);
        } catch (SQLException e) {
            throw new IllegalStateException("cannot detect the database for the pack operation lock", e);
        }
    }

    @Override
    public <T> T exclusive(String tenantId, String packId, Supplier<T> work) {
        try {
            return tx.inTx(() -> {
                lock(tx.connection(), tenantId, packId);
                return work.get();
            });
        } catch (RuntimeException e) {
            if (isLockTimeout(engineDialect, e)) {
                throw new PackLifecycleException(PACK_OPERATION_IN_PROGRESS, "another operation on pack " + packId
                        + " in " + (tenantId == null ? "the shared scope" : "tenant " + tenantId)
                        + " holds its lock; retry when it has finished", List.of(packId));
            }
            throw e;
        }
    }

    private void lock(Connection c, String tenantId, String packId) {
        Integer previous = engineDialect.sessionLockTimeout(c);
        if (engineDialect == EngineDialect.POSTGRESQL) {
            setLocalLockTimeout(c, lockWait);
        } else {
            engineDialect.applyLockTimeouts(c, lockWait, lockWait); // statement timeout: PostgreSQL only, unused here
        }
        try {
            DefinitionVersions.lockPack(c, tx.dialect(), tenantId, packId);
        } finally {
            if (previous != null) {
                engineDialect.restoreSessionLockTimeout(c, previous);
            }
        }
    }

    /**
     * PostgreSQL {@code SET LOCAL lock_timeout} as one constant statement: {@code SET} takes no bind parameter, its
     * function form {@code set_config(name, value, is_local)} does, and {@code is_local = true} is {@code SET LOCAL}
     * (the setting ends with the transaction). The wait is bound as text ({@code "<millis>ms"}).
     */
    static final String PG_SET_LOCAL_LOCK_TIMEOUT = "SELECT set_config('lock_timeout', ?, true)";

    static void setLocalLockTimeout(Connection c, Duration wait) {
        try (PreparedStatement ps = c.prepareStatement(PG_SET_LOCAL_LOCK_TIMEOUT)) {
            ps.setString(1, wait.toMillis() + "ms");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next(); // one row: the value now in force
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot set the lock timeout on PostgreSQL", e);
        }
    }

    /**
     * Whether {@code t}, or anything it wraps (causes and chained {@link SQLException}s, cycle-safe), is this database's
     * lock-wait timeout: PostgreSQL SQLState 55P03, Oracle ORA-30006 (and ORA-00054 for a NOWAIT lock), SQL Server 1222,
     * MySQL 1205, H2 50200 / SQLState HYT00.
     */
    public static boolean isLockTimeout(EngineDialect dialect, Throwable t) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        java.util.ArrayDeque<Throwable> todo = new java.util.ArrayDeque<>();
        if (t != null) {
            todo.add(t);
        }
        while (!todo.isEmpty()) {
            Throwable x = todo.poll();
            if (!seen.add(x)) {
                continue;
            }
            if (x instanceof SQLException s) {
                if (isLockTimeoutCode(dialect, s.getSQLState(), s.getErrorCode())) {
                    return true;
                }
                if (s.getNextException() != null) {
                    todo.add(s.getNextException());
                }
            }
            if (x.getCause() != null) {
                todo.add(x.getCause());
            }
        }
        return false;
    }

    static boolean isLockTimeoutCode(EngineDialect dialect, String sqlState, int code) {
        return switch (dialect) {
            case POSTGRESQL -> "55P03".equals(sqlState);
            case ORACLE -> code == 30006 || code == 54;
            case SQLSERVER -> code == 1222;
            case MYSQL -> code == 1205;
            case H2 -> code == 50200 || "HYT00".equals(sqlState);
        };
    }
}
