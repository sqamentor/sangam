// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.lock;

import java.sql.Connection;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;

import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.sangam.core.engine.EngineMetrics;
import org.sangam.core.layer.Jdbc;
import org.sangam.engine.db.EngineDialect;
import org.sangam.engine.tx.CommandState;

/**
 * <b>L2 root lock (VI.1, A-11, FLOWABLE-INTO-OPERATON D-24).</b> Operaton has only optimistic locking; on a hot root 30-75 % of commands
 * failed after 5 retries in E6. Every state-changing command therefore locks the row of its root instance in
 * {@code ROOT_SEQ} ({@code SELECT .. FOR UPDATE}, Oracle {@code FOR UPDATE WAIT n}, SQL Server
 * {@code WITH (UPDLOCK, ROWLOCK)}) <b>before</b> it reads or changes engine state, so commands on one root run one
 * after the other instead of colliding at flush. A child started by a call activity shares its parent's root id
 * (Operaton {@code rootProcessInstanceId}), so one lock covers the whole tree.
 *
 * <p>The same {@code ROOT_SEQ} row is the L2 surrogate of the product layer ({@code org.sangam.core.layer.RootSeq}:
 * outbox {@code root_seq} and audit {@code seq} are allocated under it), so engine and product share one lock per
 * root and the global lock order L1 → L2 → L4 holds (VI.1 rule 1).
 *
 * <p>Several roots are locked in ascending id order (VI.1 rule 1). A root a transaction already holds is skipped.
 * A root that sorts below one already held is still locked (the database detects any resulting deadlock, and the
 * command retrier repeats the command, which then resolves the full root set first: VI.1 rule 2).
 */
public final class RootLock {

    private static final String[] COLS = {"tenant_id", "root_id", "next_outbox_seq", "next_audit_seq"};

    private final EngineDialect dialect;
    private final Duration lockWait;
    private final EngineMetrics metrics;

    public RootLock(EngineDialect dialect, Duration lockWait, EngineMetrics metrics) {
        this.dialect = dialect;
        this.lockWait = lockWait;
        this.metrics = metrics == null ? EngineMetrics.NONE : metrics;
    }

    /**
     * Locks every root of {@code rootToTenant} not yet held by this command, ascending.
     *
     * @param metricKey process definition key for {@code root_lock_wait_seconds} (bounded cardinality)
     */
    public void lock(CommandContext cc, Map<String, String> rootToTenant, String metricKey) {
        if (rootToTenant.isEmpty()) {
            return;
        }
        CommandState state = CommandState.of(cc);
        Connection c = CommandState.connection(cc);
        for (Map.Entry<String, String> e : new TreeMap<>(rootToTenant).entrySet()) {
            String root = e.getKey();
            if (root == null || state.holdsRoot(root)) {
                continue;
            }
            long t0 = System.nanoTime();
            lockOne(c, e.getValue(), root);
            metrics.rootLockWait(metricKey == null ? "unknown" : metricKey, System.nanoTime() - t0);
            state.addHeldRoot(cc, root);
        }
    }

    /** Locks one root in the caller's transaction (creating its row on first use). */
    public void lockOne(Connection c, String tenant, String rootId) {
        String select = dialect.lockingSelect("root_id", "ROOT_SEQ", "tenant_id = ? AND root_id = ?", lockWait);
        if (Jdbc.one(c, select, rs -> rs.getString(1), tenant, rootId).isPresent()) {
            return;
        }
        dialect.insertIfAbsent(c, "ROOT_SEQ", COLS, "next_outbox_seq", tenant, rootId, 1L, 1L);
        Jdbc.one(c, select, rs -> rs.getString(1), tenant, rootId)
                .orElseThrow(() -> new IllegalStateException("L2: ROOT_SEQ row for " + rootId + " not created"));
    }
}
