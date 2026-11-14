// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.job;

import java.sql.SQLException;
import java.util.function.Consumer;

import org.operaton.bpm.engine.OptimisticLockingException;
import org.operaton.bpm.engine.impl.interceptor.Command;
import org.operaton.bpm.engine.impl.jobexecutor.FailedJobCommandFactory;
import org.sangam.core.engine.EngineMetrics;
import org.sangam.engine.db.Conflicts;

/**
 * {@code sangam_jobs_failed_total{cause,conflict}} (FLOWABLE-INTO-OPERATON D-39, R13 metric): Operaton runs the failed-job
 * command for every job execution that failed (in the handler, at flush or at commit), from the job executor and from
 * {@code ManagementService.executeJob} alike. This factory counts the failure and delegates to the engine's own
 * factory.
 *
 * <p><b>Transient conflicts keep the job's retries (SL-89).</b> Operaton's {@code JobRetryCmd.decrementRetries}
 * spares a retry only for an {@link OptimisticLockingException}. The L2 root lock ({@code RootLock}) exists to turn
 * those flush-time collisions into bounded lock waits, so on a contended root a job now fails with a lock timeout or
 * deadlock (SQL Server 1222/1205, PostgreSQL 55P03/40P01, ...) instead, and every such wait cost a retry until the
 * job became a {@code failedJob} incident. A failure that {@link Conflicts#isRetryable} classifies as a transient
 * conflict (the transaction only changed database state and rolled back, VI.1 rule 7) is therefore handed to the
 * engine as an {@code OptimisticLockingException} carrying the original as its cause: the job is unlocked and
 * re-acquired without losing a retry, exactly as Operaton treats its own conflicts. Every other failure is passed
 * through unchanged, so retries, retry cycles and incidents are as before.
 *
 * <p>The command handed back runs the engine's command after recording the original failure on the command state
 * (decision 302, {@code errorClass} of {@code INCIDENT_OPENED}).
 *
 * <p>Such re-tries are unbounded, as for Operaton's own conflicts, so a conflict that never clears must not stay
 * silent: every conversion logs a WARN with the job id and the conflict, and is counted with {@code conflict=true}.
 */
public final class CountingFailedJobCommandFactory implements FailedJobCommandFactory {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(CountingFailedJobCommandFactory.class);

    private final FailedJobCommandFactory delegate;
    private final EngineMetrics metrics;
    private final Consumer<String> warn;

    public CountingFailedJobCommandFactory(FailedJobCommandFactory delegate, EngineMetrics metrics) {
        this(delegate, metrics, LOG::warn);
    }

    /** {@code warn} receives the conversion message (the WARN log in production). */
    CountingFailedJobCommandFactory(FailedJobCommandFactory delegate, EngineMetrics metrics, Consumer<String> warn) {
        this.delegate = delegate;
        this.metrics = metrics;
        this.warn = warn;
    }

    @Override
    public Command<Object> getCommand(String jobId, Throwable exception) {
        Throwable forEngine = asEngineConflict(exception);
        boolean converted = forEngine != exception;
        metrics.jobFailed(exception == null ? "unknown" : exception.getClass().getSimpleName(), converted);
        if (converted) {
            warn.accept("SL-89: job " + jobId + " failed on a transient database conflict (" + conflictOf(exception)
                    + "); its retry is kept and it will be re-acquired. A job that keeps repeating this is in a"
                    + " livelock: check sangam_jobs_failed_total{conflict=\"true\"}");
        }
        return failureRecording(delegate.getCommand(jobId, forEngine), exception);
    }

    /**
     * Decision 302: the engine's failed-job command, run after the original failure is recorded on its command's
     * {@code CommandState}, so an incident it opens (retries used up) can derive {@code errorClass} from the original
     * failure, not from the converted one. Operaton's {@code FailedJobListenerCmd} only calls {@code execute} on it
     * (javap 2.2.0).
     */
    static Command<Object> failureRecording(Command<Object> engineCommand, Throwable original) {
        return cc -> {
            if (cc != null) {
                org.sangam.engine.tx.CommandState.of(cc).jobFailure(original);
            }
            return engineCommand.execute(cc);
        };
    }

    /**
     * The failure as the engine's retry logic should see it: a transient database conflict becomes an
     * {@link OptimisticLockingException} (original kept as the cause, so the job's stored stack trace still shows it);
     * anything else, including null and an existing {@code OptimisticLockingException}, is returned as is.
     */
    static Throwable asEngineConflict(Throwable exception) {
        if (exception == null || exception instanceof OptimisticLockingException
                || !Conflicts.isRetryable(exception, false)) {
            return exception;
        }
        return new OptimisticLockingException("transient database conflict (SL-89): " + exception.getMessage(), exception);
    }

    /** The conflict for the log: failure class plus SQLState and vendor code of the retryable SQL error. */
    static String conflictOf(Throwable exception) {
        for (Throwable t = exception; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof SQLException s) {
                for (SQLException x = s; x != null; x = x.getNextException()) {
                    if (Conflicts.isRetryable(x, false)) {
                        return exception.getClass().getSimpleName() + " SQLState=" + x.getSQLState()
                                + " code=" + x.getErrorCode();
                    }
                }
            }
        }
        return exception.getClass().getSimpleName();
    }
}