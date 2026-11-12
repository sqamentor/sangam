// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.job;

import java.sql.Connection;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.cmd.AcquireJobsCmd;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.operaton.bpm.engine.impl.jobexecutor.AcquiredJobs;
import org.operaton.bpm.engine.impl.jobexecutor.JobExecutor;
import org.operaton.bpm.engine.impl.persistence.entity.AcquirableJobEntity;
import org.operaton.bpm.engine.impl.persistence.entity.JobEntity;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.engine.tx.CommandState;

/**
 * M-056 (R39, SANGAM.md X.2.1.5): job acquisition of a node with partitioned executors on, installed through
 * {@code JobExecutor.setAcquireJobsCmdFactory} (no engine patch: M-106 evidence for FLOWABLE-INTO-OPERATON FC-24). The candidate query keeps the
 * stock acquisition rules of Operaton 2.2.0 {@code Job.xml selectNextJobsToExecute} (retries, due date, expired or no
 * lock, active, the exclusive rule per process instance or per root, history cleanup only when enabled, ordering by
 * priority, timers first and due date, SKIP LOCKED) and adds the partition filter: a job is acquirable here when its
 * slot is owned by this node's incarnation (STABLE slot, ACTIVE partition, unexpired lease on the database clock), or
 * when no live partition owns it (no {@code EXEC_JOB_SLOT} row, or an unowned or expired partition), so no job is ever
 * stranded. A job acquired through an owned slot gets {@code owner_partition}/{@code owner_epoch} by a fenced update
 * (X.2.1.5 F-0) before it is locked; a job whose fence fails is left alone.
 *
 * <p>Locking and batching are Operaton's ({@code lockJob}, {@code selectProcessInstanceId}, the optimistic-locking
 * listener), so correctness still rests on the job's REV_ compare-and-set and the L2 root lock (X.2.1.8 rule 1).
 * Configurations the filtered query does not reproduce (deployment-aware acquisition, a job priority range, acquisition
 * without SKIP LOCKED) are refused at start-up by {@link #requireSupported}.
 */
public class PartitionedAcquireJobsCmd extends AcquireJobsCmd {

    /** The {@code EXEC_JOB_SLOT} / {@code EXEC_SLOT} / {@code EXEC_PARTITION} filter (contract §1, §3). */
    static final String OWNED = "EXISTS (SELECT 1 FROM EXEC_JOB_SLOT s, EXEC_SLOT es, EXEC_PARTITION p"
            + " WHERE s.job_id = j.ID_ AND es.slot = s.slot AND es.state = 'STABLE' AND p.partition_id = es.partition_id"
            + " AND p.state = 'ACTIVE' AND p.owner_incarnation = ? AND p.lease_expires_at > %1$s)";
    static final String NOT_LIVE_OWNED = "NOT EXISTS (SELECT 1 FROM EXEC_JOB_SLOT s, EXEC_SLOT es, EXEC_PARTITION p"
            + " WHERE s.job_id = j.ID_ AND es.slot = s.slot AND p.partition_id = es.partition_id"
            + " AND p.state IN ('ACTIVE', 'DRAINING') AND p.lease_expires_at > %1$s)";

    private final String incarnation;
    private final Dialect dialect;
    private final java.util.function.Supplier<java.time.Instant> clock;

    public PartitionedAcquireJobsCmd(JobExecutor executor, int numJobs, String incarnation, Dialect dialect,
                                     java.util.function.Supplier<java.time.Instant> engineClock) {
        super(executor, numJobs);
        this.incarnation = incarnation;
        this.dialect = dialect;
        this.clock = engineClock;
    }

    /**
     * @throws IllegalStateException when the configuration uses an acquisition option this command does not reproduce
     */
    public static void requireSupported(ProcessEngineConfigurationImpl cfg) {
        List<String> unsupported = new ArrayList<>();
        if (cfg.isJobExecutorDeploymentAware()) {
            unsupported.add("jobExecutorDeploymentAware");
        }
        if (cfg.getJobExecutorPriorityRangeMin() != Long.MIN_VALUE || cfg.getJobExecutorPriorityRangeMax() != Long.MAX_VALUE) {
            unsupported.add("jobExecutorPriorityRange");
        }
        if (!cfg.isJobExecutorAcquireWithSkipLocked()) {
            unsupported.add("jobExecutorAcquireWithSkipLocked=false");
        }
        if (!unsupported.isEmpty()) {
            throw new IllegalStateException("M-056: sangam.engine.executor-partitioning cannot be combined with "
                    + unsupported + " (the partitioned acquisition does not reproduce them)");
        }
    }

    /** The candidate query (package-private for tests). */
    static String candidateSql(Dialect dialect, ProcessEngineConfigurationImpl cfg, int limit) {
        String scopeCol = cfg.isJobExecutorAcquireExclusiveOverProcessHierarchies() ? "ROOT_PROC_INST_ID_"
                : "PROCESS_INSTANCE_ID_";
        String t = dialect == Dialect.POSTGRESQL ? "TRUE" : "1";
        String f = dialect == Dialect.POSTGRESQL ? "FALSE" : "0";
        String now = dialect.now();
        StringBuilder where = new StringBuilder("j.RETRIES_ > 0");
        where.append(cfg.isEnsureJobDueDateNotNull() ? " AND j.DUEDATE_ <= ?" : " AND (j.DUEDATE_ IS NULL OR j.DUEDATE_ <= ?)");
        where.append(" AND (j.LOCK_OWNER_ IS NULL OR j.LOCK_EXP_TIME_ < ?) AND j.SUSPENSION_STATE_ = 1");
        // Operaton's exclusive rule (Job.xml AtomicExclusiveJobs): no other exclusive job of the scope holds a live lock
        where.append(" AND (j.EXCLUSIVE_ = ").append(f).append(" OR j.").append(scopeCol)
                .append(" IS NULL OR NOT EXISTS (SELECT 1 FROM ACT_RU_JOB j2 WHERE j2.").append(scopeCol).append(" = j.")
                .append(scopeCol).append(" AND j2.EXCLUSIVE_ = ").append(t)
                .append(" AND j2.ID_ <> j.ID_ AND j2.LOCK_OWNER_ IS NOT NULL AND j2.LOCK_EXP_TIME_ >= ?))");
        if (!cfg.isHistoryCleanupEnabled()) {
            where.append(" AND j.HANDLER_TYPE_ <> 'history-cleanup'");
        }
        where.append(" AND (").append(String.format(OWNED, now)).append(" OR ").append(String.format(NOT_LIVE_OWNED, now))
                .append(")");
        List<String> order = new ArrayList<>();
        if (cfg.isJobExecutorAcquireByPriority()) {
            order.add("j.PRIORITY_ DESC");
        }
        if (cfg.isJobExecutorPreferTimerJobs()) {
            order.add("j.TYPE_ DESC"); // 'timer' sorts after 'message' / 'ever-living': Operaton's TYPE DESC
        }
        if (cfg.isJobExecutorAcquireByDueDate()) {
            order.add("j.DUEDATE_");
        }
        order.add("j.ID_");
        return dialect.skipLockedSelect("j.ID_", "ACT_RU_JOB", "j", where.toString(), String.join(", ", order), limit);
    }

    @Override
    public AcquiredJobs execute(CommandContext cc) {
        acquiredJobs = new AcquiredJobs(numJobsToAcquire);
        Connection c = CommandState.connection(cc);
        Timestamp now = Timestamp.from(clock.get());
        List<String> ids = Jdbc.query(c, candidateSql(dialect, cc.getProcessEngineConfiguration(), numJobsToAcquire),
                rs -> rs.getString(1), now, now, now, incarnation);
        Map<String, long[]> owned = ownedSlots(c, ids);
        boolean byRoot = isAcquireExclusiveOverProcessHierarchies(cc);
        Map<String, List<String>> exclusive = new LinkedHashMap<>();
        for (String id : ids) {
            long[] partitionEpoch = owned.get(id);
            if (partitionEpoch != null && !fence(c, id, partitionEpoch)) {
                continue; // the partition changed owner since the query: not ours to run (X.2.1.5)
            }
            AcquirableJobEntity job = cc.getDbEntityManager().selectById(JobEntity.class, id);
            if (job == null) {
                continue;
            }
            lockJob(job);
            if (job.isExclusive()) {
                exclusive.computeIfAbsent(selectProcessInstanceId(job, byRoot), k -> new ArrayList<>()).add(id);
            } else {
                acquiredJobs.addJobIdBatch(id);
            }
        }
        exclusive.values().forEach(acquiredJobs::addJobIdBatch);
        cc.getDbEntityManager().registerOptimisticLockingListener(this);
        return acquiredJobs;
    }

    /** The candidates acquired through a slot owned here: job id -> {partition id, owner epoch}. */
    private Map<String, long[]> ownedSlots(Connection c, List<String> ids) {
        Map<String, long[]> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        String in = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        List<Object> params = new ArrayList<>(ids);
        params.add(incarnation);
        List<Object[]> rows = Jdbc.query(c, "SELECT s.job_id, p.partition_id, p.owner_epoch"
                        + " FROM EXEC_JOB_SLOT s, EXEC_SLOT es, EXEC_PARTITION p"
                        + " WHERE s.job_id IN (" + in + ") AND es.slot = s.slot AND es.state = 'STABLE'"
                        + " AND p.partition_id = es.partition_id AND p.state = 'ACTIVE' AND p.owner_incarnation = ?"
                        + " AND p.lease_expires_at > " + dialect.now(),
                rs -> new Object[]{rs.getString(1), rs.getLong(2), rs.getLong(3)}, params.toArray());
        for (Object[] r : rows) {
            out.put((String) r[0], new long[]{(Long) r[1], (Long) r[2]});
        }
        return out;
    }

    /** X.2.1.5 F-0: stamps the owner on the job's slot row only while this incarnation still holds that epoch. */
    private boolean fence(Connection c, String jobId, long[] partitionEpoch) {
        return Jdbc.update(c, "UPDATE EXEC_JOB_SLOT SET owner_partition = ?, owner_epoch = ? WHERE job_id = ?"
                        + " AND EXISTS (SELECT 1 FROM EXEC_PARTITION p WHERE p.partition_id = ? AND p.owner_incarnation = ?"
                        + " AND p.owner_epoch = ? AND p.state = 'ACTIVE' AND p.lease_expires_at > " + dialect.now() + ")",
                partitionEpoch[0], partitionEpoch[1], jobId, partitionEpoch[0], incarnation, partitionEpoch[1]) == 1;
    }

    public String incarnation() {
        return incarnation;
    }
}
