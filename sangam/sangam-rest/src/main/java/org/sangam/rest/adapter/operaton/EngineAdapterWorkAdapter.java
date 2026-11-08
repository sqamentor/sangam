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
import org.sangam.core.engine.SangamEngineAdapter;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.OperationLedger;
import org.sangam.core.layer.ReconciliationQueue;
import org.sangam.core.layer.Tombstones;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.WorkPort;
import org.sangam.rest.port.WorkPort.WorkOperation;
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
    private final WorkLedger ledger;

    public EngineAdapterWorkAdapter(EngineAdapter engine, TxSupport tx, AuditPort audit, RestProperties props) {
        this.engine = engine;
        this.tx = tx;
        this.audit = audit;
        this.props = props;
        AuditChain chain = new AuditChain(tx.dialect());
        this.reconciliation = new ReconciliationQueue(tx.dialect(), chain);
        this.tombstones = new Tombstones(tx.dialect(), reconciliation, chain);
        this.ledger = new WorkLedger(tx.dialect(), reconciliation);
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
                int room = max - out.size();
                if (engine instanceof SangamEngineAdapter s) {
                    List<SangamEngineAdapter.LeasedWork> got = fenced("fetch", () ->
                            s.fetchAndLockWork(r.workerId(), t.name(), room, lockDuration(t.lockDurationMs())));
                    for (SangamEngineAdapter.LeasedWork lw : got) {
                        WorkOperation operation = recordIntent(r.workerId(), lw);
                        if (operation != null) {
                            out.add(map(ctx, lw.work(), lw.businessKey(), operation));
                        }
                    }
                } else {
                    List<EngineAdapter.ExternalWork> got = fenced("fetch", () ->
                            engine.fetchAndLock(r.workerId(), t.name(), room, lockDuration(t.lockDurationMs())));
                    for (EngineAdapter.ExternalWork w : got) {
                        out.add(map(ctx, w, null, null));
                    }
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

    /**
     * SL-16 / R37: the server records the intent of the leased operation before the worker sees the work (own engine
     * transaction right after the lease; a crash in between leaves a lease without an intent, which the expiry policy
     * re-offers: nothing was dispatched). Work whose operation awaits reconciliation, or that the ledger refuses, is
     * parked (retries 0, incident) instead of being handed out; returns null then.
     */
    private WorkOperation recordIntent(String workerId, SangamEngineAdapter.LeasedWork lw) {
        EngineAdapter.ExternalWork w = lw.work();
        WorkLedger.Lease lease = new WorkLedger.Lease(lw.operationId(), lw.tenantId(), lw.rootId(), w.activityInstanceId(),
                lw.capabilityClass(), w.id(), w.attemptId(), w.lockVersion());
        String refusal;
        try {
            WorkOperation op = engine.inEngineTransaction(c -> ledger.onLease(c, lease));
            if (!OperationLedger.State.UNKNOWN_OUTCOME.name().equals(op.state())) {
                return op;
            }
            refusal = "OPERATION_UNKNOWN_OUTCOME: operation " + lw.operationId() + " awaits reconciliation";
        } catch (OperationLedger.StaleLeaseException | IllegalStateException | IllegalArgumentException e) {
            refusal = "OPERATION_LEDGER_REFUSED: " + e.getMessage();
        } catch (RuntimeException e) {
            try {
                engine.unlockExternal(w.id(), workerId, w.lockVersion());
            } catch (RuntimeException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
        engine.failExternal(w.id(), workerId, w.lockVersion(), refusal, 0, Duration.ZERO);
        return null;
    }

    private ExternalWork map(RequestContext ctx, EngineAdapter.ExternalWork w, String leasedBusinessKey,
                             WorkOperation operation) {
        String businessKey = leasedBusinessKey;
        if (businessKey == null) {
            try {
                EngineAdapter.InstanceView view = engine.getInstance(w.processInstanceId());
                businessKey = view == null ? null : view.businessKey();
            } catch (RuntimeException ignored) {
                // the instance view is informational only
            }
        }
        // T35: the operation id is the one the engine adapter granted with the lease; never re-derived here.
        String op = w.operationId();
        if (op == null && engine instanceof SangamEngineAdapter s) {
            UUID id = s.operationIdOf(w.id());
            op = id == null ? null : id.toString();
        }
        return new ExternalWork(w.id(), w.topic(), ctx.tenantId(), w.processInstanceId(), null, w.activityId(),
                w.activityInstanceId(), businessKey, TypedVariables.toJson(w.variables()), w.lockVersion(),
                w.attemptId(), op, w.lockExpiresAt(), w.retries(), null, null, operation);
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
        UUID op = operationIdOf(workId);
        String outcome = WorkLedger.successJson(r.variables(), r.outcome());
        try {
            // SL-16: the ledger outcome commits with the engine completion (one engine transaction)
            engine.inEngineTransaction(c -> {
                engine.completeExternal(workId, r.workerId(), r.lockVersion(), vars);
                ledger.onComplete(c, op, r.lockVersion(), outcome);
                return null;
            });
        } catch (StaleLeaseException | OperationLedger.StaleLeaseException e) {
            if (engine.inEngineTransaction(c -> ledger.isRepeatedCompletion(c, workId, r.lockVersion(), outcome))) {
                return;   // the same completion again (a retried request): answered as the first one
            }
            throw fenceProblem(workId, e);
        }
        auditAfter(ctx, workId, "EXT_OP_ACKNOWLEDGED", r.workerId(), r.lockVersion(), r.attemptId(), Map.of());
    }

    @Override
    public void fail(RequestContext ctx, String workId, FailRequest r) {
        rejectIfTombstoned(ctx, workId, r.lockVersion(), Tombstones.ResultKind.FAIL, false, null);
        int retries = r.retries() == null ? 0 : Math.max(0, r.retries());
        Duration backoff = Duration.ofMillis(r.retryTimeoutMs() == null ? 0 : Math.max(0, r.retryTimeoutMs()));
        UUID op = operationIdOf(workId);
        fenced(workId, () -> engine.inEngineTransaction(c -> {
            engine.failExternal(workId, r.workerId(), r.lockVersion(), r.errorMessage(), retries, backoff);
            ledger.onFail(c, op, r.lockVersion(), retries, r.errorMessage());
            return null;
        }));
        auditAfter(ctx, workId, "EXT_OP_FAILED", r.workerId(), r.lockVersion(), r.attemptId(),
                Map.of("errorMessage", r.errorMessage() == null ? "" : r.errorMessage(), "retriesLeft", retries));
    }

    @Override
    public void bpmnError(RequestContext ctx, String workId, BpmnErrorRequest r) {
        if (r.errorCode() == null || r.errorCode().isBlank()) {
            throw ApiProblem.badRequest("ERROR_CODE_REQUIRED", "errorCode is required");
        }
        rejectIfTombstoned(ctx, workId, r.lockVersion(), Tombstones.ResultKind.COMPLETE, false, r.variables());
        UUID op = operationIdOf(workId);
        fenced(workId, () -> engine.inEngineTransaction(c -> {
            engine.bpmnErrorExternal(workId, r.workerId(), r.lockVersion(), r.errorCode(),
                    TypedVariables.fromJson(r.variables()));
            ledger.onBpmnError(c, op, r.lockVersion(), r.errorCode(), r.errorMessage(), r.variables());
            return null;
        }));
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
        UUID op = operationIdOf(workId);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("workerId", r.workerId());
        details.put("lockVersion", r.lockVersion());
        details.put("attemptId", r.attemptId());
        details.put("detail", r.detail());
        details.put("evidence", r.evidence() == null ? Map.of() : TypedVariables.toJson(r.evidence()));
        // Fencing first: extend the lease to the maximum so the work is held (never re-offered) while it is reconciled;
        // the ledger operation goes to UNKNOWN_OUTCOME with its reconciliation item in the same engine transaction.
        long item = fenced(workId, () -> engine.inEngineTransaction(c -> {
            engine.extendLock(workId, r.workerId(), r.lockVersion(), props.getMaxLockDuration());
            Long recorded = ledger.onUnknown(c, op, r.lockVersion(), workId, details);
            return recorded != null ? recorded : reconciliation.open(c, ctx.tenantId(),
                    ReconciliationQueue.Kind.UNKNOWN_OUTCOME, null, null, workId, PayloadHash.canonical(details));
        }));
        auditAfter(ctx, workId, "EXT_OP_UNKNOWN", r.workerId(), r.lockVersion(), r.attemptId(),
                Map.of("reconciliationItemId", String.valueOf(item)));
        return new OutcomeUnknownResult(workId, op == null ? null : op.toString(), String.valueOf(item), "UNKNOWN_OUTCOME");
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
        } catch (StaleLeaseException | OperationLedger.StaleLeaseException e) {
            throw fenceProblem(workId, e);
        }
    }

    private static ApiProblem fenceProblem(String workId, RuntimeException e) {
        return e instanceof StaleLeaseException s ? ApiProblem.staleLease(workId, s.reason().name(), s.getMessage())
                : ApiProblem.staleLease(workId, "STALE_VERSION", e.getMessage());
    }

    /** The engine operationId of open work (the one stored with its lease); null without a Sangam adapter. */
    private UUID operationIdOf(String workId) {
        return engine instanceof SangamEngineAdapter s ? s.operationIdOf(workId) : null;
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
