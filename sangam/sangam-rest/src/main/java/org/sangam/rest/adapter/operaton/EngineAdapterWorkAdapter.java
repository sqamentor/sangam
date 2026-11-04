// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.sangam.core.api.EngineAdapter;
import org.sangam.core.api.StaleLeaseException;
import org.sangam.core.layer.AuditChain;
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
 * Worker API delegating to the engine-neutral {@link EngineAdapter} (sangam-engine's hardened
 * {@code OperatonEngineAdapter}: lease fencing, ExpiryPolicy A-10, root lock A-11). Preferred over
 * {@link OperatonWorkAdapter} whenever an {@code EngineAdapter} bean exists. The core contract has no tenant filter on
 * fetch and no business key on the work item: the tenant check is made on {@code ExternalWork} results through the
 * instance, and {@code businessKey} is resolved from the instance view.
 */
public class EngineAdapterWorkAdapter implements WorkPort {

    private final EngineAdapter engine;
    private final TxSupport tx;
    private final AuditPort audit;
    private final RestProperties props;
    private final Tombstones tombstones;
    private final ReconciliationQueue reconciliation;

    public EngineAdapterWorkAdapter(EngineAdapter engine, TxSupport tx, AuditPort audit, RestProperties props) {
        this.engine = engine;
        this.tx = tx;
        this.audit = audit;
        this.props = props;
        AuditChain chain = new AuditChain(tx.dialect());
        this.reconciliation = new ReconciliationQueue(tx.dialect(), chain);
        this.tombstones = new Tombstones(tx.dialect(), reconciliation, chain);
    }

    @Override
    public List<ExternalWork> fetchAndLock(RequestContext ctx, FetchAndLockRequest r) {
        if (r == null || r.workerId() == null || r.workerId().isBlank()) {
            throw ApiProblem.badRequest("WORKER_ID_REQUIRED", "workerId is required");
        }
        if (r.topics() == null || r.topics().isEmpty()) {
            throw ApiProblem.badRequest("TOPICS_REQUIRED", "at least one topic is required");
        }
        int max = r.maxTasks() == null ? 1 : Math.max(1, Math.min(100, r.maxTasks()));
        long wait = Math.min(r.asyncResponseTimeoutMs() == null ? 0 : Math.max(0, r.asyncResponseTimeoutMs()),
                props.getMaxAsyncResponseTimeout().toMillis());
        long deadline = System.currentTimeMillis() + wait;
        long sleep = 100;
        while (true) {
            List<ExternalWork> out = new ArrayList<>();
            for (TopicRequest t : r.topics()) {
                if (out.size() >= max) {
                    break;
                }
                List<EngineAdapter.ExternalWork> got = fenced("fetch", () ->
                        engine.fetchAndLock(r.workerId(), t.name(), max - out.size(), lockDuration(t.lockDurationMs())));
                for (EngineAdapter.ExternalWork w : got) {
                    out.add(map(ctx, w));
                }
            }
            if (!out.isEmpty() || System.currentTimeMillis() >= deadline) {
                return out;
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

    private ExternalWork map(RequestContext ctx, EngineAdapter.ExternalWork w) {
        String businessKey = null;
        try {
            EngineAdapter.InstanceView view = engine.getInstance(w.processInstanceId());
            businessKey = view == null ? null : view.businessKey();
        } catch (RuntimeException ignored) {
            // the instance view is informational only
        }
        UUID op = OperatonWorkAdapter.operationId(ctx.tenantId(), w.processInstanceId(), w.activityInstanceId());
        return new ExternalWork(w.id(), w.topic(), ctx.tenantId(), w.processInstanceId(), null, w.activityId(),
                w.activityInstanceId(), businessKey, TypedVariables.toJson(w.variables()), w.lockVersion(),
                w.attemptId(), op.toString(), w.lockExpiresAt(), w.retries(), null, null);
    }

    @Override
    public LockState extendLock(RequestContext ctx, String workId, ExtendLockRequest r) {
        boolean cancelRequested = engine.inEngineTransaction(c -> tombstones.find(c, ctx.tenantId(), workId)
                .map(t -> t.state() == Tombstones.State.CANCEL_REQUESTED).orElse(false));
        Duration d = lockDuration(r.newDurationMs());
        fenced(workId, () -> {
            engine.extendLock(workId, r.workerId(), r.lockVersion(), d);
            return null;
        });
        return new LockState(workId, r.lockVersion(), Instant.now().plus(d), cancelRequested);
    }

    @Override
    public void complete(RequestContext ctx, String workId, CompleteRequest r) {
        rejectIfTombstoned(ctx, workId, r.lockVersion(), Tombstones.ResultKind.COMPLETE, true, r.variables());
        Map<String, Object> vars = TypedVariables.fromJson(r.variables());
        if (r.localVariables() != null) {
            vars.putAll(TypedVariables.fromJson(r.localVariables()));
        }
        fenced(workId, () -> {
            engine.completeExternal(workId, r.workerId(), r.lockVersion(), vars);
            return null;
        });
        auditAfter(ctx, workId, "EXT_OP_ACKNOWLEDGED", r.workerId(), r.lockVersion(), r.attemptId(), Map.of());
    }

    @Override
    public void fail(RequestContext ctx, String workId, FailRequest r) {
        rejectIfTombstoned(ctx, workId, r.lockVersion(), Tombstones.ResultKind.FAIL, false, null);
        int retries = r.retries() == null ? 0 : Math.max(0, r.retries());
        Duration backoff = Duration.ofMillis(r.retryTimeoutMs() == null ? 0 : Math.max(0, r.retryTimeoutMs()));
        fenced(workId, () -> {
            engine.failExternal(workId, r.workerId(), r.lockVersion(), r.errorMessage(), retries, backoff);
            return null;
        });
        auditAfter(ctx, workId, "EXT_OP_FAILED", r.workerId(), r.lockVersion(), r.attemptId(),
                Map.of("errorMessage", r.errorMessage() == null ? "" : r.errorMessage(), "retriesLeft", retries));
    }

    @Override
    public void bpmnError(RequestContext ctx, String workId, BpmnErrorRequest r) {
        if (r.errorCode() == null || r.errorCode().isBlank()) {
            throw ApiProblem.badRequest("ERROR_CODE_REQUIRED", "errorCode is required");
        }
        rejectIfTombstoned(ctx, workId, r.lockVersion(), Tombstones.ResultKind.COMPLETE, false, r.variables());
        fenced(workId, () -> {
            engine.bpmnErrorExternal(workId, r.workerId(), r.lockVersion(), r.errorCode(),
                    TypedVariables.fromJson(r.variables()));
            return null;
        });
        auditAfter(ctx, workId, "EXT_OP_BPMN_ERROR", r.workerId(), r.lockVersion(), r.attemptId(),
                Map.of("errorCode", r.errorCode()));
    }

    @Override
    public void unlock(RequestContext ctx, String workId, UnlockRequest r) {
        fenced(workId, () -> {
            engine.unlockExternal(workId, r.workerId(), r.lockVersion());
            return null;
        });
        auditAfter(ctx, workId, "EXT_OP_RELEASED", r.workerId(), r.lockVersion(), r.attemptId(), Map.of());
    }

    @Override
    public OutcomeUnknownResult outcomeUnknown(RequestContext ctx, String workId, OutcomeUnknownRequest r) {
        rejectIfTombstoned(ctx, workId, r.lockVersion(), Tombstones.ResultKind.OUTCOME_UNKNOWN, true, r.evidence());
        // Fencing first: extend the lease to the maximum so the work is held (never re-offered) while it is reconciled.
        fenced(workId, () -> {
            engine.extendLock(workId, r.workerId(), r.lockVersion(), props.getMaxLockDuration());
            return null;
        });
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("workerId", r.workerId());
        details.put("lockVersion", r.lockVersion());
        details.put("attemptId", r.attemptId());
        details.put("detail", r.detail());
        details.put("evidence", r.evidence() == null ? Map.of() : TypedVariables.toJson(r.evidence()));
        long item = engine.inEngineTransaction(c -> reconciliation.open(c, ctx.tenantId(),
                ReconciliationQueue.Kind.UNKNOWN_OUTCOME, null, null, workId, PayloadHash.canonical(details)));
        auditAfter(ctx, workId, "EXT_OP_UNKNOWN", r.workerId(), r.lockVersion(), r.attemptId(),
                Map.of("reconciliationItemId", String.valueOf(item)));
        return new OutcomeUnknownResult(workId, null, String.valueOf(item), "UNKNOWN_OUTCOME");
    }

    @Override
    public void cancelAck(RequestContext ctx, String workId, CancelAckRequest r) {
        Optional<Tombstones.Tombstone> t = engine.inEngineTransaction(c -> tombstones.find(c, ctx.tenantId(), workId));
        if (t.isEmpty()) {
            throw ApiProblem.notFound("cancel request for work", workId);
        }
        boolean acked = engine.inEngineTransaction(c -> tombstones.ackCancel(c, ctx.tenantId(), workId, r.lockVersion()));
        if (!acked) {
            throw ApiProblem.staleLease(workId, "STALE_VERSION",
                    "no CANCEL_REQUESTED tombstone with lockVersion " + r.lockVersion());
        }
        try {
            engine.unlockExternal(workId, r.workerId(), r.lockVersion());
        } catch (RuntimeException ignored) {
            // the instance was deleted: the work no longer exists in the engine
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("workId", workId);
        p.put("workerId", r.workerId());
        p.put("lockVersion", r.lockVersion());
        tx.runInTx(() -> audit.record(ctx, t.get().rootId(), "CANCEL_ACKED", p));
    }

    // ------------------------------------------------------------------ helpers

    private Duration lockDuration(Long ms) {
        Duration d = ms == null || ms <= 0 ? props.getDefaultLockDuration() : Duration.ofMillis(ms);
        return d.compareTo(props.getMaxLockDuration()) > 0 ? props.getMaxLockDuration() : d;
    }

    private <T> T fenced(String workId, Supplier<T> call) {
        try {
            return call.get();
        } catch (StaleLeaseException e) {
            throw ApiProblem.staleLease(workId, e.reason().name(), e.getMessage());
        } catch (org.sangam.core.layer.OperationLedger.StaleLeaseException e) {
            throw ApiProblem.staleLease(workId, "STALE_VERSION", e.getMessage());
        }
    }

    private void rejectIfTombstoned(RequestContext ctx, String workId, long lockVersion, Tombstones.ResultKind kind,
                                    boolean effectHappened, Map<String, Object> result) {
        String json = PayloadHash.canonical(result == null ? Map.of() : result);
        Tombstones.LateResultDecision d = engine.inEngineTransaction(c ->
                tombstones.onLateResult(c, ctx.tenantId(), workId, lockVersion, kind, effectHappened, json));
        if (d instanceof Tombstones.Gone g) {
            throw ApiProblem.gone(g.code(), "The instance of this work was deleted; the result was recorded")
                    .with("workId", workId)
                    .with("reconciliationItemId", g.reconciliationItemId() == null ? null
                            : String.valueOf(g.reconciliationItemId()));
        }
    }

    private void auditAfter(RequestContext ctx, String workId, String type, String workerId, long lockVersion,
                            String attemptId, Map<String, ?> extra) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("work_id", workId);
        p.put("worker_id", workerId);
        p.put("lock_version", lockVersion);
        if (attemptId != null) {
            p.put("attempt_id", attemptId);
        }
        p.putAll(extra);
        // The core contract does not expose the root of a work item after completion, so worker-call events go to
        // the per-work chain "work:<workId>".
        tx.runInTx(() -> audit.record(ctx, "work:" + workId, type, p));
    }
}
