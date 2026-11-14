// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.job;

import java.sql.Connection;
import java.util.Map;

import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.operaton.bpm.engine.impl.jobexecutor.JobHandler;
import org.operaton.bpm.engine.impl.jobexecutor.JobHandlerConfiguration;
import org.operaton.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.operaton.bpm.engine.impl.persistence.entity.JobEntity;
import org.operaton.bpm.engine.impl.persistence.entity.TimerEntity;
import org.sangam.core.engine.EngineMetrics;
import org.sangam.core.engine.SangamClock;
import org.sangam.engine.db.EngineDialect;
import org.sangam.engine.lock.RootLock;
import org.sangam.engine.timer.TimerGuard;
import org.sangam.engine.timer.TimerPolicies;
import org.sangam.engine.tx.CommandState;

/**
 * Wraps every Operaton {@link JobHandler} (timers, async continuations, batch, history cleanup, ...) so that job
 * execution follows VI.1 like adapter commands do, <b>as the system identity of the job's resolved tenant</b> (VI.4
 * rule 7, decision 144, {@link JobIdentitySupport}):
 * <ol>
 *   <li>bounded lock waits (VI.1 rule 6);</li>
 *   <li>the <b>L2 root lock first</b> (A-11, VI.1 rule 4: acquisition never takes L2, execution takes it before it
 *       changes state);</li>
 *   <li>for cycle timers, the <b>DST timer guard</b> (A-12): a firing whose interval already fired, or a missed one
 *       under {@code SKIP_MISSED}, is suppressed. The engine has already scheduled the follow-up timer in
 *       {@code TimerEntity.preExecute}, so the series continues; only the effect is dropped.</li>
 * </ol>
 * Installed by the plugin in {@code postInit}, replacing each entry of the engine's job-handler map.
 */
public final class GuardedJobHandler<T extends JobHandlerConfiguration> implements JobHandler<T> {

    private final JobHandler<T> delegate;
    private final EngineDialect dialect;
    private final RootLock rootLock;
    private final TimerGuard timerGuard;
    private final TimerPolicies policies;
    private final SangamClock clock;
    private final EngineMetrics metrics;
    private final java.time.Duration lockTimeout;
    private final java.time.Duration statementTimeout;
    /** The L2 root-lock key of an untenanted root, as the adapter and RootLockFlushListener use it (a lock namespace). */
    private final String lockTenant;
    private final JobIdentitySupport identity;
    private final org.sangam.engine.replay.CapturePoints capture;

    public GuardedJobHandler(JobHandler<T> delegate, EngineDialect dialect, RootLock rootLock, TimerGuard timerGuard,
                             TimerPolicies policies, SangamClock clock, EngineMetrics metrics,
                             java.time.Duration lockTimeout, java.time.Duration statementTimeout, String lockTenant,
                             JobIdentitySupport identity) {
        this(delegate, dialect, rootLock, timerGuard, policies, clock, metrics, lockTimeout, statementTimeout,
                lockTenant, identity, org.sangam.engine.replay.CapturePoints.NONE);
    }

    /** @param capture decision 151: a timer that takes effect is reported as {@code TIMER_FIRED} */
    public GuardedJobHandler(JobHandler<T> delegate, EngineDialect dialect, RootLock rootLock, TimerGuard timerGuard,
                             TimerPolicies policies, SangamClock clock, EngineMetrics metrics,
                             java.time.Duration lockTimeout, java.time.Duration statementTimeout, String lockTenant,
                             JobIdentitySupport identity, org.sangam.engine.replay.CapturePoints capture) {
        this.lockTenant = lockTenant;
        this.capture = capture == null ? org.sangam.engine.replay.CapturePoints.NONE : capture;
        this.delegate = delegate;
        this.identity = identity;
        this.dialect = dialect;
        this.rootLock = rootLock;
        this.timerGuard = timerGuard;
        this.policies = policies;
        this.clock = clock;
        this.metrics = metrics;
        this.lockTimeout = lockTimeout;
        this.statementTimeout = statementTimeout;
    }

    public JobHandler<T> delegate() {
        return delegate;
    }

    @Override
    public String getType() {
        return delegate.getType();
    }

    @Override
    public void execute(T configuration, ExecutionEntity execution, CommandContext cc, String tenantId) {
        // decision 144: resolved before anything runs; an unresolved tenant throws here and the job is not executed
        org.sangam.core.security.SecurityIdentity id = identity.identityFor(cc, cc.getCurrentJob(), execution, tenantId);
        identity.runAs(cc, id, () -> {
            executeGuarded(configuration, execution, cc, tenantId);
            return null;
        });
    }

    private void executeGuarded(T configuration, ExecutionEntity execution, CommandContext cc, String tenantId) {
        Connection c = CommandState.connection(cc);
        String type = delegate.getType();
        boolean longRunning = type != null && (type.startsWith("history-cleanup") || type.contains("batch"));
        // SL-99: session-scoped lock waits are restored when the job's transaction ends
        CommandState.applyLockTimeouts(cc, dialect, lockTimeout, longRunning ? java.time.Duration.ZERO : statementTimeout);
        if (execution != null && execution.getRootProcessInstanceId() != null) {
            // the lock key, not an attribution: the same key every other L2 site takes for this root
            String key = tenantId != null ? tenantId : lockTenant;
            rootLock.lock(cc, Map.of(execution.getRootProcessInstanceId(), key),
                    keyOf(execution.getProcessDefinitionId()));
        }
        JobEntity job = cc.getCurrentJob();
        if (job instanceof TimerEntity timer && timer.getRepeat() != null) {
            scheduleFollowUp(timer, configuration);
            TimerGuard.Verdict v = timerGuard.decide(c, timer,
                    policies.policyFor(timer.getProcessDefinitionKey(), timer.getActivityId()), clock.now().toEpochMilli());
            if (v != TimerGuard.Verdict.FIRE) {
                metrics.timerSuppressed(timer.getProcessDefinitionKey(), v.name());
                return;
            }
        }
        if (job instanceof TimerEntity timer) {
            capture.timerFired(timer); // decision 151: after the guard said FIRE, before the timer's effect
        }
        delegate.execute(configuration, execution, cc, tenantId);
    }

    /**
     * Creates the next job of a repeating timer. Operaton does this in {@code TimerEntity.preExecute} only when
     * {@code getJobHandler() instanceof TimerEventJobHandler}, which is false for this wrapper, so without this every
     * cycle timer fired once and stopped. Runs before the guard decision, as Operaton's pre-execute does, so the cycle
     * calendar sees the fire count of the firings before this one (Rn bound). Not done: Operaton's re-evaluation of a
     * changed cycle expression when due, and its rollback listener for a failed repeating job.
     */
    private void scheduleFollowUp(TimerEntity timer, T configuration) {
        if (delegate instanceof org.operaton.bpm.engine.impl.jobexecutor.TimerEventJobHandler
                && configuration instanceof org.operaton.bpm.engine.impl.jobexecutor.TimerEventJobHandler.TimerJobConfiguration tc
                && !tc.isFollowUpJobCreated()) {
            java.util.Date next = timer.calculateNewDueDate();
            if (next != null) {
                timer.createNewTimerJob(next);
            }
        }
    }

    @Override
    public T newConfiguration(String canonicalString) {
        return delegate.newConfiguration(canonicalString);
    }

    @Override
    public void onDelete(T configuration, JobEntity jobEntity) {
        delegate.onDelete(configuration, jobEntity);
    }

    private static String keyOf(String processDefinitionId) {
        if (processDefinitionId == null) {
            return null;
        }
        int i = processDefinitionId.indexOf(':');
        return i < 0 ? processDefinitionId : processDefinitionId.substring(0, i);
    }

    /** Wraps a raw handler from the engine's map (the map's value type is a wildcard). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static JobHandler<?> wrap(JobHandler<?> handler, EngineDialect dialect, RootLock rootLock, TimerGuard guard,
                                     TimerPolicies policies, SangamClock clock, EngineMetrics metrics,
                                     java.time.Duration lockTimeout, java.time.Duration statementTimeout,
                                     String lockTenant, JobIdentitySupport identity) {
        return wrap(handler, dialect, rootLock, guard, policies, clock, metrics, lockTimeout, statementTimeout,
                lockTenant, identity, org.sangam.engine.replay.CapturePoints.NONE);
    }

    /** As above, with the decision 151 capture points. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static JobHandler<?> wrap(JobHandler<?> handler, EngineDialect dialect, RootLock rootLock, TimerGuard guard,
                                     TimerPolicies policies, SangamClock clock, EngineMetrics metrics,
                                     java.time.Duration lockTimeout, java.time.Duration statementTimeout,
                                     String lockTenant, JobIdentitySupport identity,
                                     org.sangam.engine.replay.CapturePoints capture) {
        if (handler instanceof GuardedJobHandler || handler instanceof GuardedBatchJobHandler) {
            return handler;
        }
        GuardedJobHandler guarded = new GuardedJobHandler((JobHandler) handler, dialect, rootLock, guard, policies, clock,
                metrics, lockTimeout, statementTimeout, lockTenant, identity, capture);
        // Operaton's BatchBuilder casts the job-handler map entry to BatchJobHandler: a batch handler must stay one.
        if (handler instanceof org.operaton.bpm.engine.impl.batch.BatchJobHandler<?> batch) {
            return new GuardedBatchJobHandler(batch, guarded);
        }
        return guarded;
    }
}
