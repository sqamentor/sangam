// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.cmmn;

import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.delegate.CaseExecutionListener;
import org.operaton.bpm.engine.delegate.DelegateCaseExecution;
import org.operaton.bpm.engine.impl.cmmn.execution.CmmnExecution;
import org.operaton.bpm.engine.impl.util.ClockUtil;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.TenantKey;
import org.sangam.ports.support.EngineTx;

/**
 * PATCHES.md P-5 / FLOWABLE-INTO-OPERATON FC-11 native: CMMN case external tasks.
 * <p>
 * Operaton's external-task entity belongs to a process execution only. A CMMN {@code task} that carries
 * {@code sangam:topic} is a blocking task (it stays ACTIVE after start); {@link PortsCmmnTransformListener} attaches
 * {@link #LIFECYCLE}, which writes one row of the Sangam table {@code PORT_CASE_EXT_TASK} when the task starts and
 * removes it when the case execution completes, terminates or exits. Every write runs in the engine transaction
 * ({@link EngineTx#connection()}), so the row and the case execution commit or roll back together.
 * <p>
 * Workers use the same contract as BPMN external tasks: fetch-and-lock per topic with a lock duration, complete with
 * variables (only by the lock owner), failure with retries and retry timeout, unlock, extend lock. Time is the engine
 * clock ({@link ClockUtil}). Lock acquisition is a conditional {@code UPDATE} guarded by {@code version_}, so two
 * workers never lock the same row.
 */
public class CaseExternalTaskService {

    public static final String TABLE = "PORT_CASE_EXT_TASK";

    /** Activity properties set by the transform listener. */
    public static final String PROPERTY_TOPIC = "sangam.caseExternalTopic";
    public static final String PROPERTY_PRIORITY = "sangam.caseExternalPriority";

    public record Topic(String name, long lockDurationMillis) {
    }

    public record CaseExternalTask(String id, String tenantId, String caseInstanceId, String caseExecutionId,
                                   String caseDefinitionId, String activityId, String topic, String workerId,
                                   Instant lockExpiration, Integer retries, String errorMessage, int priority,
                                   Instant createdAt, long version) {
    }

    public static final class CaseExternalTaskException extends RuntimeException {
        public CaseExternalTaskException(String message) {
            super(message);
        }
    }

    private static final String COLS = "id_, tenant_id, case_inst_id, case_exec_id, case_def_id, activity_id, topic,"
            + " worker_id, lock_exp_time, retries, error_msg, priority, created_at, version_";

    /** Row create/delete bound to the case execution lifecycle (runs inside the engine command). */
    public static final CaseExecutionListener LIFECYCLE = CaseExternalTaskService::onLifecycle;

    private final ProcessEngine engine;

    public CaseExternalTaskService(ProcessEngine engine) {
        this.engine = engine;
    }

    static void onLifecycle(DelegateCaseExecution ce) {
        Connection c = EngineTx.connection();
        String event = ce.getEventName();
        if (CaseExecutionListener.START.equals(event) || CaseExecutionListener.MANUAL_START.equals(event)) {
            var activity = ((CmmnExecution) ce).getActivity();
            Object topic = activity.getProperty(PROPERTY_TOPIC);
            Object prio = activity.getProperty(PROPERTY_PRIORITY);
            Jdbc.update(c, "INSERT INTO " + TABLE + " (" + COLS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(), TenantKey.of(ce.getTenantId()), ce.getCaseInstanceId(), ce.getId(),
                    ce.getCaseDefinitionId(), ce.getActivityId(), String.valueOf(topic), null, null, null, null,
                    prio instanceof Integer i ? i : 0, now(), 0L);
        } else {
            Jdbc.update(c, "DELETE FROM " + TABLE + " WHERE case_exec_id = ?", ce.getId());
        }
    }

    private static Instant now() {
        return ClockUtil.getCurrentTime().toInstant();
    }

    /**
     * Locks up to {@code maxTasks} unlocked (or lock-expired) tasks with retries left on the given topics, highest
     * priority first. {@code tenantId} null means every tenant (the caller applies the VI.4 tenant predicate).
     */
    public List<CaseExternalTask> fetchAndLock(String tenantId, String workerId, int maxTasks, List<Topic> topics) {
        if (workerId == null || workerId.isBlank()) {
            throw new CaseExternalTaskException("workerId is required");
        }
        if (maxTasks < 1 || topics == null || topics.isEmpty()) {
            return List.of();
        }
        return EngineTx.transactions(engine).inTransaction(c -> {
            Instant now = now();
            List<CaseExternalTask> out = new ArrayList<>();
            for (Topic topic : topics) {
                if (out.size() >= maxTasks) {
                    break;
                }
                StringBuilder where = new StringBuilder("topic = ? AND (retries IS NULL OR retries > 0)"
                        + " AND (lock_exp_time IS NULL OR lock_exp_time < ?)");
                List<Object> params = new ArrayList<>(List.of(topic.name(), now));
                if (tenantId != null) {
                    where.append(" AND tenant_id = ?");
                    params.add(TenantKey.of(tenantId));
                }
                for (CaseExternalTask cand : select(c, where + " ORDER BY priority DESC, created_at, id_", params.toArray())) {
                    if (out.size() >= maxTasks) {
                        break;
                    }
                    Instant until = now.plusMillis(topic.lockDurationMillis());
                    int n = Jdbc.update(c, "UPDATE " + TABLE + " SET worker_id = ?, lock_exp_time = ?, version_ = version_ + 1"
                            + " WHERE id_ = ? AND version_ = ?", workerId, until, cand.id(), cand.version());
                    if (n == 1) {
                        out.add(new CaseExternalTask(cand.id(), cand.tenantId(), cand.caseInstanceId(), cand.caseExecutionId(),
                                cand.caseDefinitionId(), cand.activityId(), cand.topic(), workerId, until, cand.retries(),
                                cand.errorMessage(), cand.priority(), cand.createdAt(), cand.version() + 1));
                    }
                }
            }
            return out;
        });
    }

    /** Completes the task and its case execution in one engine command; only the current lock owner may complete. */
    public void complete(String id, String workerId, Map<String, Object> variables) {
        EngineTx.execute(engine, ctx -> {
            CaseExternalTask t = lockedBy(EngineTx.connection(), id, workerId);
            var cmd = engine.getCaseService().withCaseExecution(t.caseExecutionId());
            if (variables != null && !variables.isEmpty()) {
                cmd.setVariables(variables);
            }
            cmd.complete();
            return null;
        });
    }

    /** Records a failure; {@code retries} 0 leaves the task unfetchable until {@link #setRetries}. */
    public void handleFailure(String id, String workerId, String errorMessage, int retries, long retryTimeoutMillis) {
        EngineTx.transactions(engine).inTransaction(c -> {
            lockedBy(c, id, workerId);
            Instant next = retries > 0 ? now().plusMillis(Math.max(0, retryTimeoutMillis)) : null;
            Jdbc.update(c, "UPDATE " + TABLE + " SET worker_id = NULL, lock_exp_time = ?, retries = ?, error_msg = ?,"
                    + " version_ = version_ + 1 WHERE id_ = ?", next, Math.max(0, retries), truncate(errorMessage), id);
            return null;
        });
    }

    public void setRetries(String id, int retries) {
        EngineTx.transactions(engine).inTransaction(c -> {
            byId(c, id);
            Jdbc.update(c, "UPDATE " + TABLE + " SET retries = ?, version_ = version_ + 1 WHERE id_ = ?", Math.max(0, retries), id);
            return null;
        });
    }

    public void unlock(String id) {
        EngineTx.transactions(engine).inTransaction(c -> {
            byId(c, id);
            Jdbc.update(c, "UPDATE " + TABLE + " SET worker_id = NULL, lock_exp_time = NULL, version_ = version_ + 1 WHERE id_ = ?", id);
            return null;
        });
    }

    public void extendLock(String id, String workerId, long newDurationMillis) {
        EngineTx.transactions(engine).inTransaction(c -> {
            lockedBy(c, id, workerId);
            Jdbc.update(c, "UPDATE " + TABLE + " SET lock_exp_time = ?, version_ = version_ + 1 WHERE id_ = ?",
                    now().plusMillis(newDurationMillis), id);
            return null;
        });
    }

    /** Tasks of one case instance ({@code caseInstanceId} null: all), raw rows for the caller's tenant predicate. */
    public List<CaseExternalTask> list(String tenantId, String caseInstanceId) {
        return EngineTx.transactions(engine).inTransaction(c -> {
            StringBuilder where = new StringBuilder("1 = 1");
            List<Object> params = new ArrayList<>();
            if (tenantId != null) {
                where.append(" AND tenant_id = ?");
                params.add(TenantKey.of(tenantId));
            }
            if (caseInstanceId != null) {
                where.append(" AND case_inst_id = ?");
                params.add(caseInstanceId);
            }
            return select(c, where + " ORDER BY created_at, id_", params.toArray());
        });
    }

    public CaseExternalTask get(String id) {
        return EngineTx.transactions(engine).inTransaction(c -> byId(c, id));
    }

    private CaseExternalTask lockedBy(Connection c, String id, String workerId) {
        CaseExternalTask t = byId(c, id);
        if (workerId == null || !workerId.equals(t.workerId()) || t.lockExpiration() == null
                || !t.lockExpiration().isAfter(now())) {
            throw new CaseExternalTaskException("case external task " + id + " is not locked by worker " + workerId);
        }
        return t;
    }

    private CaseExternalTask byId(Connection c, String id) {
        List<CaseExternalTask> l = select(c, "id_ = ?", id);
        if (l.isEmpty()) {
            throw new CaseExternalTaskException("case external task " + id + " not found");
        }
        return l.get(0);
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 255 ? s : s.substring(0, 255);
    }

    private static List<CaseExternalTask> select(Connection c, String where, Object... params) {
        return Jdbc.query(c, "SELECT " + COLS + " FROM " + TABLE + " WHERE " + where, rs -> {
            int r = rs.getInt("retries");
            Integer retries = rs.wasNull() ? null : r;
            return new CaseExternalTask(rs.getString("id_"), rs.getString("tenant_id"), rs.getString("case_inst_id"),
                    rs.getString("case_exec_id"), rs.getString("case_def_id"), rs.getString("activity_id"),
                    rs.getString("topic"), rs.getString("worker_id"), Jdbc.instant(rs, "lock_exp_time"), retries,
                    rs.getString("error_msg"), rs.getInt("priority"), Jdbc.instant(rs, "created_at"), rs.getLong("version_"));
        }, params);
    }
}
