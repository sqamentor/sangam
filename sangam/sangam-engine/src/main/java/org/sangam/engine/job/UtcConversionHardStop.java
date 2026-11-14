// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.job;

import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;

import org.operaton.bpm.engine.impl.cmd.AcquireJobsCmd;
import org.operaton.bpm.engine.impl.cmd.FetchExternalTasksCmd;
import org.operaton.bpm.engine.impl.context.Context;
import org.operaton.bpm.engine.impl.interceptor.Command;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.operaton.bpm.engine.impl.interceptor.CommandInterceptor;
import org.operaton.bpm.engine.impl.jobexecutor.AcquiredJobs;
import org.sangam.core.layer.Jdbc;
import org.sangam.engine.db.EngineDialect;
import org.sangam.engine.tx.CommandState;

/**
 * Decision 150 follow-up, coordinator rulings 3 and 4 (2026-11-13): the engine side of the UTC conversion gate. The
 * gate is one row of {@code OPS_UTC_GATE} (W4-G table, read only here, {@code gate_id} {@value #GATE_ID}). The UTC tool
 * closes it in its own committed transaction before a run moves to {@code APPLYING} or {@code ROLLING_BACK}, and
 * reopens it only when the run is {@code APPLIED} or {@code ROLLED_BACK}.
 *
 * <p>Every job acquisition ({@link AcquireJobsCmd}, stock and partitioned) and every external-task fetch
 * ({@link FetchExternalTasksCmd}, the adapter and {@code /engine-rest}) reads the gate row first, with a lock held to
 * the end of its own transaction: the transaction that then writes the job or task locks. Hence:
 * <ul>
 *   <li>a gate closed before the read: the read waits for the close to commit and sees {@code CLOSED}; the acquisition
 *       returns an idle result (nothing of the executor's full request acquired, so the executor backs off; with zero
 *       attempted Operaton would count it busy and poll at once) and the fetch an empty list, and no lock is written;</li>
 *   <li>an acquisition in flight when the tool closes: the tool's {@code UPDATE} needs the exclusive row lock, so it
 *       waits until the acquisition commits; the locks it wrote are committed before the gate is closed, and the tool's
 *       lock check ({@code EngineLocks}) sees them.</li>
 * </ul>
 * So a closed gate means no job or task lock can commit. The lock per dialect ({@link #gateSql}): a shared row lock on
 * PostgreSQL ({@code FOR SHARE}), MySQL and MariaDB ({@code LOCK IN SHARE MODE}) and SQL Server
 * ({@code REPEATABLEREAD} hint: a shared lock to the end of the transaction, also under RCSI); Oracle and H2 have no
 * shared row lock, so they take the row exclusively ({@code FOR UPDATE}, Oracle with the engine lock timeout) and
 * acquisitions there serialise on the gate row. A missing gate row fails the command (fail closed). Manual job
 * execution and the completion of work already locked are not gated: the tool waits for those locks.
 *
 * <p>Registered by {@code SangamProcessEnginePlugin} as a post command interceptor, one object per chain.
 */
public final class UtcConversionHardStop extends CommandInterceptor {

    /** The gate row's key. */
    public static final String GATE_ID = "ENGINE_WORK";
    /** The gate state that stops acquisition and fetch. */
    public static final String CLOSED = "CLOSED";
    /** The gate state that lets them run. */
    public static final String OPEN = "OPEN";

    private final String sql;

    public UtcConversionHardStop(EngineDialect dialect, Duration lockWait) {
        this.sql = gateSql(dialect, lockWait);
    }

    /** The locking gate read of {@code dialect} (package-private for tests). */
    static String gateSql(EngineDialect dialect, Duration lockWait) {
        String select = "SELECT state, run_id FROM OPS_UTC_GATE";
        String where = " WHERE gate_id = ?";
        return switch (dialect) {
            case POSTGRESQL -> select + where + " FOR SHARE";
            case MYSQL -> select + where + " LOCK IN SHARE MODE";
            case SQLSERVER -> select + " WITH (REPEATABLEREAD, ROWLOCK)" + where;
            case ORACLE -> select + where + " FOR UPDATE WAIT " + Math.max(1, lockWait.toSeconds());
            case H2 -> select + where + " FOR UPDATE";
        };
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T execute(Command<T> command) {
        boolean acquire = command instanceof AcquireJobsCmd;
        if (!acquire && !(command instanceof FetchExternalTasksCmd) || !Context.hasActiveCommandContext()) {
            return next.execute(command);
        }
        CommandContext cc = Context.getCommandContext();
        if (closed(CommandState.connection(cc))) {
            return (T) (acquire ? idle(cc) : new ArrayList<>());
        }
        return next.execute(command); // the gate's lock is held until this command's transaction ends
    }

    /** Reads the gate with its lock; true when it is closed. */
    boolean closed(Connection c) {
        String state = Jdbc.one(c, sql, rs -> rs.getString(1), GATE_ID).orElseThrow(() -> new IllegalStateException(
                "decision 150: the UTC conversion gate row OPS_UTC_GATE." + GATE_ID + " is missing (fail closed)"));
        if (OPEN.equals(state)) {
            return false;
        }
        if (CLOSED.equals(state)) {
            return true;
        }
        throw new IllegalStateException("decision 150: unknown UTC conversion gate state " + state + " (fail closed)");
    }

    private static AcquiredJobs idle(CommandContext cc) {
        int attempted = cc.getProcessEngineConfiguration().getJobExecutor() == null ? 1
                : Math.max(1, cc.getProcessEngineConfiguration().getJobExecutor().getMaxJobsPerAcquisition());
        return new AcquiredJobs(attempted);
    }
}
