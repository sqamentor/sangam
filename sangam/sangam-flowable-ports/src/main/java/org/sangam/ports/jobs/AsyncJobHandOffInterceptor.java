// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.jobs;

import org.operaton.bpm.engine.impl.ProcessEngineImpl;
import org.operaton.bpm.engine.impl.cfg.TransactionState;
import org.operaton.bpm.engine.impl.context.Context;
import org.operaton.bpm.engine.impl.interceptor.Command;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.operaton.bpm.engine.impl.interceptor.CommandInterceptor;
import org.operaton.bpm.engine.impl.jobexecutor.JobExecutor;
import org.operaton.bpm.engine.impl.persistence.entity.MessageEntity;
import org.sangam.core.engine.SangamClockHolder;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.function.Supplier;

/**
 * Post-commit hand-off of new async jobs (FLOWABLE-INTO-OPERATON FC-24, Flowable's async executor hand-off): an async continuation created
 * by a command is pre-locked for this node in the same transaction and, once the transaction <b>committed</b>, handed
 * straight to the job executor's thread pool. The job never waits for the next acquisition cycle and is never
 * acquired by another node (it is locked). If the pool rejects it, the lock simply expires and normal acquisition
 * picks it up.
 *
 * <p>Only <b>non-exclusive</b> jobs are handed off by default: running an exclusive job outside acquisition batching
 * could run two exclusive jobs of one process instance in parallel. Due-dated and suspended jobs are never handed off.
 * Registered as a post command interceptor (runs inside the command context, before flush). Reads Operaton's entity
 * cache (impl class, PATCHES.md I-list).
 */
public class AsyncJobHandOffInterceptor extends CommandInterceptor {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AsyncJobHandOffInterceptor.class);

    private final Supplier<JobExecutor> jobExecutor;
    private final Supplier<ProcessEngineImpl> engine;
    private final boolean handOffExclusive;

    public AsyncJobHandOffInterceptor(Supplier<JobExecutor> jobExecutor, Supplier<ProcessEngineImpl> engine, boolean handOffExclusive) {
        this.jobExecutor = jobExecutor;
        this.engine = engine;
        this.handOffExclusive = handOffExclusive;
    }

    @Override
    public <T> T execute(Command<T> command) {
        T result = next.execute(command);
        CommandContext cc = Context.getCommandContext();
        JobExecutor je = jobExecutor.get();
        ProcessEngineImpl pe = engine.get();
        if (cc == null || je == null || pe == null || !je.isActive()
                // a deployment-aware node must not run jobs of deployments it has not registered (stock acquisition filters them)
                || pe.getProcessEngineConfiguration().isJobExecutorDeploymentAware()) {
            return result;
        }
        List<String> ids = new ArrayList<>();
        Date expiry = Date.from(SangamClockHolder.now().plusMillis(je.getLockTimeInMillis()));
        for (MessageEntity m : cc.getDbEntityManager().getDbEntityCache().getEntitiesByType(MessageEntity.class)) {
            if (!cc.getDbEntityManager().getDbEntityCache().isTransient(m) || m.getDuedate() != null || m.isSuspended()
                    || m.getLockOwner() != null || (m.isExclusive() && !handOffExclusive)) {
                continue;
            }
            m.setLockOwner(je.getLockOwner());
            m.setLockExpirationTime(expiry);
            ids.add(m.getId());
        }
        if (!ids.isEmpty()) {
            // FW-04/FW-37: the hand-off runs after COMMIT only (never before the job row is durable), and a failure of the
            // hand-off itself must not surface to the caller of an already committed command: the job keeps its lock
            // until expiry and normal acquisition picks it up.
            cc.getTransactionContext().addTransactionListener(TransactionState.COMMITTED, ctx -> {
                try {
                    je.executeJobs(ids, pe);
                } catch (RuntimeException e) {
                    LOG.warn("async hand-off of {} job(s) failed; they run after lock expiry: {}", ids.size(), e.toString());
                }
            });
        }
        return result;
    }
}
