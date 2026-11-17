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
import org.sangam.engine.db.EngineDialect;
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
    /** VI.4 rule 7 / C3-05: the system identity a job of this command runs as (set by JobIdentitySupport). */
    private org.sangam.core.security.SecurityIdentity jobIdentity;

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

    /**
     * RM-30: runs {@code work} once the current engine command has committed, and not at all when it rolls back;
     * outside a command it runs at once. For a host whose transaction is the engine command (the plain-Java host's
     * {@code EngineTxSupport}).
     */
    public static void afterCommit(Runnable work) {
        CommandContext cc = Context.hasActiveCommandContext() ? Context.getCommandContext() : null;
        if (cc == null) {
            work.run();
            return;
        }
        cc.getTransactionContext().addTransactionListener(TransactionState.COMMITTED, x -> work.run());
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
    /** SL-99: the session lock wait before this command first set it (null: not session-scoped or not read). */
    private boolean lockTimeoutCaptured;
    private Integer previousLockTimeout;
    private boolean lockTimeoutRestored;

    /**
     * VI.1 rule 6 bounded waits for this command ({@link EngineDialect#applyLockTimeouts}), without leaking them
     * (SL-99). Where the database keeps the lock wait as a <b>session</b> setting (SQL Server {@code SET LOCK_TIMEOUT},
     * MySQL {@code innodb_lock_wait_timeout}, H2), a pooled connection would carry it into whatever uses the
     * connection next (Operaton's own commands, Spring transactions, the relay), so later lock waits there depended on
     * which code had last used the connection. The first call in a command therefore reads the session's value and
     * restores it once the transaction has ended ({@code COMMITTED} / {@code ROLLED_BACK}: after the flush and every
     * {@code COMMITTING} listener, so every statement of the command still runs with the bounded wait; the connection
     * is still bound to the transaction, under Operaton's Spring integration too, as for the rollback guard). Later
     * calls in the same command (nested adapter commands, the job handler) only set the values again.
     */
    public static void applyLockTimeouts(CommandContext cc, EngineDialect dialect, java.time.Duration lockTimeout,
                                         java.time.Duration statementTimeout) {
        Connection c = connection(cc);
        CommandState s = of(cc);
        boolean first;
        synchronized (s) {
            first = !s.lockTimeoutCaptured;
            s.lockTimeoutCaptured = true;
        }
        if (first) {
            Integer previous = dialect.sessionLockTimeout(c);
            if (previous != null) {
                synchronized (s) {
                    s.previousLockTimeout = previous;
                }
                cc.getTransactionContext().addTransactionListener(TransactionState.COMMITTED,
                        x -> restoreLockTimeout(cc, dialect));
                cc.getTransactionContext().addTransactionListener(TransactionState.ROLLED_BACK,
                        x -> restoreLockTimeout(cc, dialect));
            }
        }
        dialect.applyLockTimeouts(c, lockTimeout, statementTimeout);
    }

    private static void restoreLockTimeout(CommandContext cc, EngineDialect dialect) {
        CommandState s = of(cc);
        Connection kept;
        Integer previous;
        synchronized (s) {
            if (s.lockTimeoutRestored || s.previousLockTimeout == null) {
                return;
            }
            s.lockTimeoutRestored = true;
            kept = s.handedOut;
            previous = s.previousLockTimeout;
        }
        try {
            if (kept != null && !kept.isClosed()) {
                dialect.restoreSessionLockTimeout(kept, previous);
            }
        } catch (SQLException | RuntimeException e) {
            // never fail a finished transaction; a broken connection is discarded by the pool
            LOG.warn("SL-99: cannot restore the session lock timeout ({}) of the engine connection", previous, e);
        }
    }

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

    /** C3-05: the identity the job of this command runs as, or null outside a job. */
    public synchronized org.sangam.core.security.SecurityIdentity jobIdentity() {
        return jobIdentity;
    }

    /** C3-05: sets (or, with null, clears) the identity the job of this command runs as; returns the previous one. */
    public synchronized org.sangam.core.security.SecurityIdentity jobIdentity(
            org.sangam.core.security.SecurityIdentity identity) {
        org.sangam.core.security.SecurityIdentity previous = jobIdentity;
        jobIdentity = identity;
        return previous;
    }

    private Throwable jobFailure;

    /**
     * Decision 302: the original failure of the job whose failed-job command runs in this command (set by
     * {@code job/CountingFailedJobCommandFactory} before the engine's retry command), or null. The incident handler reads
     * it to derive {@code errorClass} for an incident opened by that command.
     */
    public synchronized Throwable jobFailure() {
        return jobFailure;
    }

    public synchronized void jobFailure(Throwable failure) {
        jobFailure = failure;
    }

    /**
     * Decision 332: a correlation key an adapter handed over, with the command depth it was handed over at.
     *
     * @param key   the key the adapter was called with
     * @param depth the number of engine commands open on this context when the adapter handed the key over
     */
    public record CorrelationKey(String key, int depth) {
    }

    private int commandDepth;
    private CorrelationKey correlationKey;

    /**
     * One more engine command runs on this context (the capture interceptor calls this around every command of an
     * engine with replay capture; nested commands reuse the context, so the count is the nesting depth).
     */
    public synchronized void enterCommand() {
        commandDepth++;
    }

    public synchronized void exitCommand() {
        commandDepth--;
    }

    /**
     * Decision 332: hands the correlation key an adapter was called with to the capture point of the message it is
     * about to correlate. Returns what was handed over before; the adapter gives it back to
     * {@link #restoreCorrelationKey} when its correlation has returned, so the key never outlives the call.
     */
    public synchronized CorrelationKey handOverCorrelationKey(String key) {
        CorrelationKey previous = correlationKey;
        correlationKey = key == null ? null : new CorrelationKey(key, commandDepth);
        return previous;
    }

    public synchronized void restoreCorrelationKey(CorrelationKey previous) {
        correlationKey = previous;
    }

    /**
     * The handed-over key for a message that is being delivered now, or null. It applies only to a delivery made by
     * the correlation command the adapter itself issued (exactly one command deeper than the hand-over). A correlation
     * issued further inside, by a delegate or a listener the delivery runs, is another path and gets null.
     */
    public synchronized String deliveredCorrelationKey() {
        return correlationKey != null && commandDepth == correlationKey.depth() + 1 ? correlationKey.key() : null;
    }
}
