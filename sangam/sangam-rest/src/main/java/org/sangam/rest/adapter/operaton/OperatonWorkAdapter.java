// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.operaton.bpm.engine.ExternalTaskService;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.externaltask.ExternalTask;
import org.operaton.bpm.engine.externaltask.ExternalTaskQueryBuilder;
import org.operaton.bpm.engine.externaltask.ExternalTaskQueryTopicBuilder;
import org.operaton.bpm.engine.externaltask.LockedExternalTask;
import org.sangam.core.api.LeaseTable;
import org.sangam.core.api.StaleLeaseException;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.OperationLedger;
import org.sangam.core.layer.ReconciliationQueue;
import org.sangam.core.layer.Tombstones;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.WorkPort;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;
import org.sangam.rest.support.TypedVariables;

/**
 * Worker API on Operaton external tasks with Sangam lease fencing (VI.3). Operaton's own lock is kept as the
 * dispatch mechanism; the {@link LeaseTable} row adds {@code lock_version} and {@code attempt_id}, bumped on every
 * grant, so a worker whose lease was re-offered gets {@code 409 STALE_LEASE} and changes nothing (VI.3 rule 2).
 * Engine command, lease row, tombstone check and audit event commit in one Spring transaction.
 */
public class OperatonWorkAdapter implements WorkPort {

    private final ExternalTaskService externalTasks;
    private final RuntimeService runtime;
    private final TxSupport tx;
    private final AuditPort audit;
    private final RestProperties props;
    private final Tombstones tombstones;
    private final ReconciliationQueue reconciliation;
    private final AtomicBoolean leaseTableReady = new AtomicBoolean();

    public OperatonWorkAdapter(ExternalTaskService externalTasks, RuntimeService runtime, TxSupport tx, AuditPort audit,
                               RestProperties props) {
        this.externalTasks = externalTasks;
        this.runtime = runtime;
        this.tx = tx;
        this.audit = audit;
        this.props = props;
        AuditChain chain = new AuditChain(tx.dialect());
        this.reconciliation = new ReconciliationQueue(tx.dialect(), chain);
        this.tombstones = new Tombstones(tx.dialect(), reconciliation, chain);
    }

    // ------------------------------------------------------------------ fetch

    @Override
    public List<ExternalWork> fetchAndLock(RequestContext ctx, FetchAndLockRequest r) {
        if (r == null || r.workerId() == null || r.workerId().isBlank()) {
            throw ApiProblem.badRequest("WORKER_ID_REQUIRED", "workerId is required");
        }
        if (r.topics() == null || r.topics().isEmpty()) {
            throw ApiProblem.badRequest("TOPICS_REQUIRED", "at least one topic is required");
        }
        ensureLeaseTable();
        int max = r.maxTasks() == null ? 1 : Math.max(1, Math.min(100, r.maxTasks()));
        long wait = Math.min(r.asyncResponseTimeoutMs() == null ? 0 : Math.max(0, r.asyncResponseTimeoutMs()),
                props.getMaxAsyncResponseTimeout().toMillis());
        long deadline = System.currentTimeMillis() + wait;
        long sleep = 100;
        while (true) {
            List<ExternalWork> locked = tx.inTx(() -> fetchOnce(ctx, r, max));
            if (!locked.isEmpty() || System.currentTimeMillis() >= deadline) {
                return locked;
            }
            try {
                Thread.sleep(Math.min(sleep, Math.max(1, deadline - System.currentTimeMillis())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return List.of();
            }
            sleep = Math.min(1000, sleep * 2);
        }
    }

    private List<ExternalWork> fetchOnce(RequestContext ctx, FetchAndLockRequest r, int max) {
        Connection c = tx.connection();
        // Fixed lock order over topics, so two concurrent fetches cannot deadlock (VI.1 rule 1).
        TreeMap<String, TopicRequest> topics = new TreeMap<>();
        for (TopicRequest t : r.topics()) {
            if (t == null || t.name() == null || t.name().isBlank()) {
                throw ApiProblem.badRequest("TOPIC_NAME_REQUIRED", "every topic needs a name");
            }
            topics.put(t.name(), t);
        }
        for (String name : topics.keySet()) {
            LeaseTable.lockFetch(c, name);
        }
        ExternalTaskQueryBuilder builder = OperatonSupport.call(() ->
                externalTasks.fetchAndLock(max, r.workerId(), Boolean.TRUE.equals(r.usePriority())));
        for (TopicRequest t : topics.values()) {
            ExternalTaskQueryTopicBuilder tb = builder.topic(t.name(), lockDuration(t.lockDurationMs()).toMillis());
            if (ctx.tenantId() != null) {
                tb = tb.tenantIdIn(ctx.tenantId());
            }
            if (t.variables() != null && !t.variables().isEmpty()) {
                tb = tb.variables(t.variables());
            }
            if (Boolean.TRUE.equals(t.localVariables())) {
                tb = tb.localVariables();
            }
            if (t.processDefinitionKey() != null) {
                tb = tb.processDefinitionKey(t.processDefinitionKey());
            }
            if (t.businessKey() != null) {
                tb = tb.businessKey(t.businessKey());
            }
            builder = tb;
        }
        ExternalTaskQueryBuilder finalBuilder = builder;
        List<LockedExternalTask> tasks = OperatonSupport.call(finalBuilder::execute);
        List<ExternalWork> out = new ArrayList<>(tasks.size());
        for (LockedExternalTask t : tasks) {
            Instant expires = OperatonSupport.instant(t.getLockExpirationTime());
            LeaseTable.Lease lease = LeaseTable.grant(c, t.getId(), r.workerId(), expires);
            out.add(new ExternalWork(t.getId(), t.getTopicName(), t.getTenantId(), t.getProcessInstanceId(),
                    t.getProcessDefinitionKey(), t.getActivityId(), t.getActivityInstanceId(), t.getBusinessKey(),
                    TypedVariables.toJson(t.getVariables()), lease.lockVersion(), lease.attemptId(),
                    operationId(t.getTenantId(), t.getProcessInstanceId(), t.getActivityInstanceId()).toString(),
                    expires, t.getRetries(), t.getPriority(), t.getErrorMessage()));
        }
        return out;
    }

    // ------------------------------------------------------------------ lease calls

    @Override
    public LockState extendLock(RequestContext ctx, String workId, ExtendLockRequest r) {
        ensureLeaseTable();
        return tx.inTx(() -> {
            Connection c = tx.connection();
            boolean cancelRequested = tombstones.find(c, ctx.tenantId(), workId)
                    .map(t -> t.state() == Tombstones.State.CANCEL_REQUESTED).orElse(false);
            ExternalTask task = task(workId);
            if (task == null) {
                if (cancelRequested) {
                    return new LockState(workId, r.lockVersion(), null, true);
                }
                throw ApiProblem.notFound("external work", workId);
            }
            checkTenant(ctx, task, workId);
            fence(c, workId, r.workerId(), r.lockVersion(), r.attemptId());
            Duration d = lockDuration(r.newDurationMs());
            OperatonSupport.run(() -> externalTasks.extendLock(workId, r.workerId(), d.toMillis()));
            Instant expires = Instant.now().plus(d);
            LeaseTable.extend(c, workId, expires);
            return new LockState(workId, r.lockVersion(), expires, cancelRequested);
        });
    }

    @Override
    public void complete(RequestContext ctx, String workId, CompleteRequest r) {
        ensureLeaseTable();
        tx.runInTx(() -> {
            Connection c = tx.connection();
            rejectIfTombstoned(c, ctx, workId, r.lockVersion(), Tombstones.ResultKind.COMPLETE, true, r.variables());
            ExternalTask task = requireTask(ctx, workId);
            fence(c, workId, r.workerId(), r.lockVersion(), r.attemptId());
            OperatonSupport.run(() -> externalTasks.complete(workId, r.workerId(),
                    TypedVariables.fromJson(r.variables()), TypedVariables.fromJson(r.localVariables())));
            LeaseTable.remove(c, workId);
            audit.record(ctx, task.getProcessInstanceId(), "EXT_OP_ACKNOWLEDGED", payload(task, r.workerId(),
                    r.lockVersion(), r.attemptId(), Map.of("variables", names(r.variables()))));
        });
    }

    @Override
    public void fail(RequestContext ctx, String workId, FailRequest r) {
        ensureLeaseTable();
        ExternalTask probe = task(workId);
        if (probe != null && props.getIrreversibleTopics().contains(probe.getTopicName())
                && (r.retries() == null || r.retries() > 0)) {
            // VI.3 rule 5: no blind automatic retry of an irreversible effect.
            outcomeUnknown(ctx, workId, new OutcomeUnknownRequest(r.workerId(), r.lockVersion(), r.attemptId(),
                    "failure on irreversible topic: " + r.errorMessage(), Map.of("errorDetails",
                    r.errorDetails() == null ? "" : r.errorDetails())));
            return;
        }
        tx.runInTx(() -> {
            Connection c = tx.connection();
            rejectIfTombstoned(c, ctx, workId, r.lockVersion(), Tombstones.ResultKind.FAIL, false, null);
            ExternalTask task = requireTask(ctx, workId);
            fence(c, workId, r.workerId(), r.lockVersion(), r.attemptId());
            int retries = r.retries() == null ? Math.max(0, (task.getRetries() == null ? 1 : task.getRetries()) - 1)
                    : Math.max(0, r.retries());
            long timeout = r.retryTimeoutMs() == null ? 0 : Math.max(0, r.retryTimeoutMs());
            OperatonSupport.run(() -> externalTasks.handleFailure(workId, r.workerId(), r.errorMessage(),
                    r.errorDetails(), retries, timeout));
            LeaseTable.end(c, workId);
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("errorMessage", r.errorMessage());
            extra.put("retriesLeft", retries);
            audit.record(ctx, task.getProcessInstanceId(), "EXT_OP_FAILED",
                    payload(task, r.workerId(), r.lockVersion(), r.attemptId(), extra));
        });
    }

    @Override
    public void bpmnError(RequestContext ctx, String workId, BpmnErrorRequest r) {
        ensureLeaseTable();
        if (r.errorCode() == null || r.errorCode().isBlank()) {
            throw ApiProblem.badRequest("ERROR_CODE_REQUIRED", "errorCode is required");
        }
        tx.runInTx(() -> {
            Connection c = tx.connection();
            rejectIfTombstoned(c, ctx, workId, r.lockVersion(), Tombstones.ResultKind.COMPLETE, false, r.variables());
            ExternalTask task = requireTask(ctx, workId);
            fence(c, workId, r.workerId(), r.lockVersion(), r.attemptId());
            OperatonSupport.run(() -> externalTasks.handleBpmnError(workId, r.workerId(), r.errorCode(),
                    r.errorMessage(), TypedVariables.fromJson(r.variables())));
            LeaseTable.remove(c, workId);
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("errorCode", r.errorCode());
            extra.put("errorMessage", r.errorMessage());
            audit.record(ctx, task.getProcessInstanceId(), "EXT_OP_BPMN_ERROR",
                    payload(task, r.workerId(), r.lockVersion(), r.attemptId(), extra));
        });
    }

    @Override
    public void unlock(RequestContext ctx, String workId, UnlockRequest r) {
        ensureLeaseTable();
        tx.runInTx(() -> {
            Connection c = tx.connection();
            ExternalTask task = requireTask(ctx, workId);
            fence(c, workId, r.workerId(), r.lockVersion(), r.attemptId());
            OperatonSupport.run(() -> externalTasks.unlock(workId));
            LeaseTable.end(c, workId);
            audit.record(ctx, task.getProcessInstanceId(), "EXT_OP_RELEASED",
                    payload(task, r.workerId(), r.lockVersion(), r.attemptId(), Map.of()));
        });
    }

    @Override
    public OutcomeUnknownResult outcomeUnknown(RequestContext ctx, String workId, OutcomeUnknownRequest r) {
        ensureLeaseTable();
        return tx.inTx(() -> {
            Connection c = tx.connection();
            rejectIfTombstoned(c, ctx, workId, r.lockVersion(), Tombstones.ResultKind.OUTCOME_UNKNOWN, true,
                    r.evidence());
            ExternalTask task = requireTask(ctx, workId);
            fence(c, workId, r.workerId(), r.lockVersion(), r.attemptId());
            UUID op = operationId(task.getTenantId(), task.getProcessInstanceId(), task.getActivityInstanceId());
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("workerId", r.workerId());
            details.put("lockVersion", r.lockVersion());
            details.put("attemptId", r.attemptId());
            details.put("topic", task.getTopicName());
            details.put("detail", r.detail());
            details.put("evidence", r.evidence() == null ? Map.of() : TypedVariables.toJson(r.evidence()));
            long item = reconciliation.open(c, ctx.tenantId(), ReconciliationQueue.Kind.UNKNOWN_OUTCOME, op,
                    task.getProcessInstanceId(), workId, PayloadHash.canonical(details));
            // Keep the Operaton task locked so it is never re-offered; reconciliation decides (CX-5).
            OperatonSupport.run(() -> externalTasks.extendLock(workId, r.workerId(),
                    props.getMaxLockDuration().toMillis()));
            LeaseTable.end(c, workId);
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("reconciliationItemId", String.valueOf(item));
            extra.put("detail", r.detail());
            audit.record(ctx, task.getProcessInstanceId(), "EXT_OP_UNKNOWN",
                    payload(task, r.workerId(), r.lockVersion(), r.attemptId(), extra));
            return new OutcomeUnknownResult(workId, op.toString(), String.valueOf(item), "UNKNOWN_OUTCOME");
        });
    }

    @Override
    public void cancelAck(RequestContext ctx, String workId, CancelAckRequest r) {
        ensureLeaseTable();
        tx.runInTx(() -> {
            Connection c = tx.connection();
            Optional<Tombstones.Tombstone> t = tombstones.find(c, ctx.tenantId(), workId);
            if (t.isEmpty()) {
                throw ApiProblem.notFound("cancel request for work", workId);
            }
            if (!tombstones.ackCancel(c, ctx.tenantId(), workId, r.lockVersion())) {
                throw ApiProblem.staleLease(workId, "STALE_VERSION",
                        "no CANCEL_REQUESTED tombstone with lockVersion " + r.lockVersion());
            }
            ExternalTask task = task(workId);
            if (task != null) {
                OperatonSupport.run(() -> externalTasks.unlock(workId));
            }
            LeaseTable.remove(c, workId);
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("workId", workId);
            p.put("workerId", r.workerId());
            p.put("lockVersion", r.lockVersion());
            p.put("operation_id", t.get().operationId() == null ? null : t.get().operationId().toString());
            audit.record(ctx, t.get().rootId(), "CANCEL_ACKED", p);
        });
    }

    // ------------------------------------------------------------------ helpers

    static UUID operationId(String tenant, String rootId, String activityInstanceId) {
        return OperationLedger.operationId(tenant == null ? "" : tenant, rootId == null ? "" : rootId,
                activityInstanceId == null ? "" : activityInstanceId, 0);
    }

    private void ensureLeaseTable() {
        if (leaseTableReady.get()) {
            return;
        }
        tx.inNewTx(() -> {
            LeaseTable.createIfMissing(tx.connection());
            return null;
        });
        leaseTableReady.set(true);
    }

    private Duration lockDuration(Long ms) {
        Duration d = ms == null || ms <= 0 ? props.getDefaultLockDuration() : Duration.ofMillis(ms);
        return d.compareTo(props.getMaxLockDuration()) > 0 ? props.getMaxLockDuration() : d;
    }

    private ExternalTask task(String workId) {
        return OperatonSupport.call(() -> externalTasks.createExternalTaskQuery().externalTaskId(workId).singleResult());
    }

    private ExternalTask requireTask(RequestContext ctx, String workId) {
        ExternalTask t = task(workId);
        if (t == null) {
            throw ApiProblem.notFound("external work", workId);
        }
        checkTenant(ctx, t, workId);
        return t;
    }

    private static void checkTenant(RequestContext ctx, ExternalTask t, String workId) {
        // K14: the worker principal is bound to its tenant.
        if (ctx.tenantId() != null && t.getTenantId() != null && !ctx.tenantId().equals(t.getTenantId())) {
            throw ApiProblem.notFound("external work", workId);
        }
    }

    private void fence(Connection c, String workId, String workerId, long lockVersion, String attemptId) {
        if (workerId == null || workerId.isBlank()) {
            throw ApiProblem.badRequest("WORKER_ID_REQUIRED", "workerId is required");
        }
        try {
            LeaseTable.check(c, workId, workerId, lockVersion, Instant.now());
        } catch (StaleLeaseException e) {
            throw ApiProblem.staleLease(workId, e.reason().name(), e.getMessage());
        }
        if (attemptId != null) {
            LeaseTable.Row row = LeaseTable.lock(c, workId);
            if (row != null && row.attemptId() != null && !attemptId.equals(row.attemptId())) {
                throw ApiProblem.staleLease(workId, "ATTEMPT_MISMATCH",
                        "attemptId " + attemptId + " is not the current attempt");
            }
        }
    }

    private void rejectIfTombstoned(Connection c, RequestContext ctx, String workId, long lockVersion,
                                    Tombstones.ResultKind kind, boolean effectHappened, Map<String, Object> result) {
        if (tombstones.find(c, ctx.tenantId(), workId).isEmpty()) {
            return;
        }
        // The late result is audited and (when an effect happened) a reconciliation item is opened. That must
        // survive the 410 rejection, so it commits in its own transaction; the outer one holds no locks yet.
        String resultJson = PayloadHash.canonical(result == null ? Map.of() : result);
        Tombstones.LateResultDecision d = tx.inNewTx(() -> tombstones.onLateResult(tx.connection(), ctx.tenantId(),
                workId, lockVersion, kind, effectHappened, resultJson));
        if (d instanceof Tombstones.Gone g) {
            throw ApiProblem.gone(g.code(), "The instance of this work was deleted; the result was recorded")
                    .with("workId", workId)
                    .with("reconciliationItemId", g.reconciliationItemId() == null ? null
                            : String.valueOf(g.reconciliationItemId()));
        }
    }

    private static Map<String, Object> payload(ExternalTask t, String workerId, long lockVersion, String attemptId,
                                               Map<String, ?> extra) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("work_id", t.getId());
        p.put("topic", t.getTopicName());
        p.put("activity_id", t.getActivityId());
        p.put("activity_instance_id", t.getActivityInstanceId());
        p.put("operation_id", operationId(t.getTenantId(), t.getProcessInstanceId(), t.getActivityInstanceId())
                .toString());
        p.put("worker_id", workerId);
        p.put("lock_version", lockVersion);
        p.put("attempt_id", attemptId);
        if (extra != null) {
            extra.forEach((k, v) -> {
                if (v != null) {
                    p.put(k, v);
                }
            });
        }
        p.values().removeIf(java.util.Objects::isNull);
        return p;
    }

    private static List<String> names(Map<String, ?> vars) {
        return vars == null ? List.of() : List.copyOf(new TreeMap<>(vars).keySet());
    }

    /** Exposed for the process adapter: the Operaton runtime service this adapter was built with. */
    RuntimeService runtime() {
        return runtime;
    }
}
