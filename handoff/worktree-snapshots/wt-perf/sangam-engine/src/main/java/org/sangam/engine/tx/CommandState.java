// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.tx;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.WeakHashMap;

import org.operaton.bpm.engine.impl.cfg.TransactionState;
import org.operaton.bpm.engine.impl.context.Context;
import org.operaton.bpm.engine.impl.db.sql.DbSqlSession;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.operaton.bpm.engine.impl.interceptor.Session;
import org.sangam.core.engine.LockScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sangam's per-command state, attached to one Operaton {@link CommandContext} (weakly, so it goes away with it):
 * the Q1 rollback guard, the L2 roots this transaction holds, the {@link LockScope} mark and the CMMN tasks completed
 * in this command (for the audit projection).
 *
 * <p><b>Q1 / FLOWABLE-INTO-OPERATON D-22 / W-2.</b> MyBatis {@code DefaultSqlSession.rollback()} does nothing while no MyBatis write ran
 * ({@code dirty == false}); Operaton flushes its own writes only at command close; {@code JdbcTransaction.close()}
 * then calls {@code setAutoCommit(true)}, which under JDBC rules <b>commits</b> product rows written through the
 * engine connection. {@link #connection(CommandContext)} therefore registers a {@code ROLLINGBACK} listener that rolls
 * the connection back explicitly. Every Sangam component that writes through an engine connection gets it from here.
 */
public final class CommandState {

    private static final Logger LOG = LoggerFactory.getLogger(CommandState.class);
    private static final Map<CommandContext, CommandState> STATES = Collections.synchronizedMap(new WeakHashMap<>());

    private boolean rollbackGuard;
    /**
     * The engine connection handed out by {@link #connection}, kept for the rollback guard: under Operaton's Spring
     * integration ROLLINGBACK runs from Spring's beforeCompletion, after the command context closed its MyBatis
     * session ("Executor was closed"), while the JDBC connection is still bound to the transaction.
     */
    private Connection handedOut;
    private boolean lockScopeEntered;
    private final TreeSet<String> heldRoots = new TreeSet<>();
    private final Map<String, String> caseTaskOutcomes = new java.util.HashMap<>();

    private CommandState() {
    }

    /** The state of {@code cc}, created on first use. */
    public static CommandState of(CommandContext cc) {
        return STATES.computeIfAbsent(cc, k -> new CommandState());
    }

    /** The state of the current command, or null outside a command. */
    public static CommandState current() {
        CommandContext cc = Context.hasActiveCommandContext() ? Context.getCommandContext() : null;
        return cc == null ? null : of(cc);
    }

    /**
     * The JDBC connection of the command's {@link DbSqlSession}, with the Q1 rollback guard registered once per
     * command. Must only be called inside an engine command.
     */
    public static Connection connection(CommandContext cc) {
        Connection c = cc.getDbSqlSession().getSqlSession().getConnection();
        CommandState s = of(cc);
        synchronized (s) {
            s.handedOut = c;
        }
        installRollbackGuard(cc);
        return c;
    }

    /** {@link #connection(CommandContext)} for the current command. */
    public static Connection currentConnection() {
        CommandContext cc = Context.hasActiveCommandContext() ? Context.getCommandContext() : null;
        if (cc == null) {
            throw new IllegalStateException("no active engine command");
        }
        return connection(cc);
    }

    /**
     * Registers the Q1 rollback listener once for {@code cc}. It does not open a database session: on rollback it
     * rolls back only a {@link DbSqlSession} that the command actually opened.
     */
    public static void installRollbackGuard(CommandContext cc) {
        CommandState s = of(cc);
        synchronized (s) {
            if (s.rollbackGuard) {
                return;
            }
            s.rollbackGuard = true;
        }
        cc.getTransactionContext().addTransactionListener(TransactionState.ROLLINGBACK, CommandState::rollbackOpenSession);
    }

    private static void rollbackOpenSession(CommandContext cc) {
        Connection kept;
        CommandState s = of(cc);
        synchronized (s) {
            kept = s.handedOut;
        }
        boolean done = false;
        for (Session session : cc.getSessions().values()) {
            if (session instanceof DbSqlSession db) {
                Connection c;
                try {
                    c = db.getSqlSession().getConnection();
                } catch (RuntimeException closed) {
                    // session already closed by the command context (Spring integration): use the kept connection
                    c = kept;
                }
                done |= rollback(c);
            }
        }
        if (!done && kept != null) {
            rollback(kept);
        }
    }

    private static boolean rollback(Connection c) {
        if (c == null) {
            return false;
        }
        try {
            if (!c.isClosed() && !c.getAutoCommit()) {
                c.rollback();
            }
            return true;
        } catch (SQLException | RuntimeException e) {
            // The connection is broken; it is discarded by the pool and nothing can commit through it.
            LOG.warn("Q1 rollback guard: explicit rollback of the engine connection failed", e);
            return true;
        }
    }

    private boolean closeListenerRegistered;
    private boolean acquisition;

    /** R2 / VI.1 rule 4: this command is an acquisition (job or external-task fetch, lease sweep); it never takes L2. */
    public synchronized void markAcquisition() {
        acquisition = true;
    }

    public synchronized boolean isAcquisition() {
        return acquisition;
    }

    /** True the first time it is called for this command (the close listener is then registered once). */
    public synchronized boolean markCloseListenerRegistered() {
        if (closeListenerRegistered) {
            return false;
        }
        closeListenerRegistered = true;
        return true;
    }

    /** Roots already locked (L2) by this transaction, ascending. */
    public synchronized Set<String> heldRoots() {
        return Collections.unmodifiableSet(new TreeSet<>(heldRoots));
    }

    public synchronized boolean holdsRoot(String rootId) {
        return heldRoots.contains(rootId);
    }

    /** Marks {@code rootId} held; enters {@link LockScope} on the first root and leaves it when the command ends. */
    public synchronized void addHeldRoot(CommandContext cc, String rootId) {
        heldRoots.add(rootId);
        if (!lockScopeEntered) {
            lockScopeEntered = true;
            LockScope.enter();
            cc.getTransactionContext().addTransactionListener(TransactionState.COMMITTED, x -> LockScope.exit());
            cc.getTransactionContext().addTransactionListener(TransactionState.ROLLED_BACK, x -> LockScope.exit());
        }
    }

    /**
     * CMMN audit projection (FLOWABLE-INTO-OPERATON D-25): the transition a case task (case execution id) took in this command,
     * {@code COMPLETED} or {@code EXITED}; a completion is never overwritten by a later exit of the same task.
     */
    public synchronized void caseTaskOutcome(String caseExecutionId, String outcome) {
        caseTaskOutcomes.merge(caseExecutionId, outcome, (old, n) -> "COMPLETED".equals(old) ? old : n);
    }

    /** {@link #caseTaskOutcome} recorded in this command, or null. */
    public synchronized String caseTaskOutcome(String caseExecutionId) {
        return caseTaskOutcomes.get(caseExecutionId);
    }
}
