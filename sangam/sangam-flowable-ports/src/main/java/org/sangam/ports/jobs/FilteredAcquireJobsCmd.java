// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.jobs;

import org.operaton.bpm.engine.impl.cmd.AcquireJobsCmd;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.operaton.bpm.engine.impl.jobexecutor.AcquiredJobs;
import org.operaton.bpm.engine.impl.jobexecutor.JobExecutor;
import org.operaton.bpm.engine.impl.persistence.entity.AcquirableJobEntity;
import org.sangam.core.engine.SangamClockHolder;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;

import java.sql.Connection;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Job acquisition restricted to one job class (FLOWABLE-INTO-OPERATON FC-24, Flowable {@code AcquireTimerJobsRunnable} /
 * {@code AcquireAsyncJobsDueRunnable}), so timers and async jobs are acquired by <b>separate</b> job executors and a
 * burst of async continuations cannot starve due timers (and vice versa). Optional <b>global acquire lock</b>
 * (Flowable {@code AcquireJobsWithGlobalAcquireLockCmd}): one row of {@code JOB_ACQUIRE_LOCK} is locked
 * {@code FOR UPDATE} for the acquisition, which serialises acquisition across nodes on databases or settings without
 * SKIP LOCKED and removes acquisition OLE storms.
 *
 * <p>Locking reuses Operaton's own {@code AcquireJobsCmd.lockJob} and its optimistic-locking listener (a job taken by
 * another node is silently dropped from this batch). Exclusive jobs of one process instance are batched together, as
 * Operaton does. Not supported here (they stay on the stock acquisition): deployment-aware filtering and priority
 * ranges; the stock factory is kept when those settings are used.
 */
public class FilteredAcquireJobsCmd extends AcquireJobsCmd {

    public enum JobClass { TIMERS, NON_TIMERS }

    private final JobExecutor jobExecutor;
    private final JobClass jobClass;
    private final Dialect dialect;
    private final String globalLockName;

    public FilteredAcquireJobsCmd(JobExecutor jobExecutor, int numJobs, JobClass jobClass, Dialect dialect, String globalLockNameOrNull) {
        super(jobExecutor, numJobs);
        this.jobExecutor = jobExecutor;
        this.jobClass = jobClass;
        this.dialect = dialect;
        this.globalLockName = globalLockNameOrNull;
    }

    @Override
    public AcquiredJobs execute(CommandContext cc) {
        acquiredJobs = new AcquiredJobs(numJobsToAcquire);
        Connection c = cc.getDbSqlSession().getSqlSession().getConnection();
        if (globalLockName != null) {
            Jdbc.insertIfAbsent(c, dialect, "INSERT INTO JOB_ACQUIRE_LOCK(lock_name, owner_id, acquired_at) VALUES (?,NULL,NULL)", globalLockName);
            Jdbc.one(c, dialect.lockingSelect("lock_name", "JOB_ACQUIRE_LOCK", "lock_name = ?"), rs -> rs.getString(1), globalLockName);
            Jdbc.update(c, "UPDATE JOB_ACQUIRE_LOCK SET owner_id = ?, acquired_at = " + dialect.now() + " WHERE lock_name = ?",
                    jobExecutor.getLockOwner(), globalLockName);
        }
        Timestamp now = Timestamp.from(SangamClockHolder.now());
        String typeClause = jobClass == JobClass.TIMERS ? "j.TYPE_ = 'timer'" : "j.TYPE_ <> 'timer'";
        // FW-36: keep Operaton's exclusive-job rule that the stock acquisition query enforces: an exclusive job is not
        // acquirable while another exclusive job of the same process instance (or root, when configured) holds a live
        // lock, on any node and in either executor (timers vs async). Without it the split executors could run two
        // exclusive jobs of one instance in parallel (Flowable's async-executor exclusivity gap; OLE storms).
        String scopeCol = cc.getProcessEngineConfiguration().isJobExecutorAcquireExclusiveOverProcessHierarchies()
                ? "ROOT_PROC_INST_ID_" : "PROCESS_INSTANCE_ID_";
        String exclusiveClause = " AND (j.EXCLUSIVE_ = " + falseLiteral() + " OR j." + scopeCol + " IS NULL OR NOT EXISTS (SELECT 1 FROM ACT_RU_JOB j2"
                + " WHERE j2." + scopeCol + " = j." + scopeCol + " AND j2.EXCLUSIVE_ = " + trueLiteral()
                + " AND j2.ID_ <> j.ID_ AND j2.LOCK_OWNER_ IS NOT NULL AND j2.LOCK_EXP_TIME_ >= ?))";
        String where = "j.RETRIES_ > 0 AND (j.DUEDATE_ IS NULL OR j.DUEDATE_ <= ?) AND (j.LOCK_OWNER_ IS NULL OR j.LOCK_EXP_TIME_ < ?)"
                + " AND j.SUSPENSION_STATE_ = 1 AND " + typeClause + exclusiveClause;
        String sql = globalLockName != null
                ? "SELECT j.ID_ FROM ACT_RU_JOB j WHERE " + where + " ORDER BY j.PRIORITY_ DESC, j.DUEDATE_"
                + org.sangam.ports.support.Sql.limit(dialect, numJobsToAcquire)
                : dialect.skipLockedSelect("j.ID_", "ACT_RU_JOB", "j", where, "j.PRIORITY_ DESC, j.DUEDATE_", numJobsToAcquire);
        List<String> ids = Jdbc.query(c, sql, rs -> rs.getString(1), now, now, now);
        Map<String, List<String>> exclusive = new LinkedHashMap<>();
        for (String id : ids) {
            // Operaton maps no "selectAcquirableJob" statement; JobEntity ("selectJob") is an AcquirableJobEntity
            AcquirableJobEntity job = cc.getDbEntityManager().selectById(org.operaton.bpm.engine.impl.persistence.entity.JobEntity.class, id);
            if (job == null) {
                continue;
            }
            lockJob(job);
            if (job.isExclusive()) {
                String key = job.getRootProcessInstanceId() != null ? job.getRootProcessInstanceId() : job.getProcessInstanceId();
                exclusive.computeIfAbsent(key == null ? id : key, k -> new ArrayList<>()).add(id);
            } else {
                acquiredJobs.addJobIdBatch(id);
            }
        }
        exclusive.values().forEach(acquiredJobs::addJobIdBatch);
        cc.getDbEntityManager().registerOptimisticLockingListener(this);
        return acquiredJobs;
    }

    /** Boolean column literals: {@code EXCLUSIVE_} is BOOLEAN on PostgreSQL, a number/bit elsewhere. */
    private String trueLiteral() {
        return dialect == Dialect.POSTGRESQL ? "TRUE" : "1";
    }

    private String falseLiteral() {
        return dialect == Dialect.POSTGRESQL ? "FALSE" : "0";
    }
}
