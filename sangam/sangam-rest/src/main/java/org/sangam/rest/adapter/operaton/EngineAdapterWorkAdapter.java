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
    private final AuditChain chain;

    public EngineAdapterWorkAdapter(EngineAdapter engine, TxSupport tx, AuditPort audit, RestProperties props) {
        this.engine = engine;
        this.tx = tx;
        this.audit = audit;
        this.props = props;
        this.chain = new AuditChain(tx.dialect());
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
        for (TopicRequest t : r.topics()) {
            guardTopic(ctx, t.name());   // R26 (SL-158): every requested topic, before anything is leased
        }
        int max = r.maxTasks() == null ? 1 : Math.max(1, Math.min(100, r.maxTasks()));
        long wait = Math.min(r.asyncResponseTimeoutMs() == null ? 0 : Math.max(0, r.asyncResponseTimeoutMs()),
                props.getMaxAsyncResponseTimeout().toMillis());
        long deadline = System.currentTimeMillis() + wait;
        org.sangam.rest.support.WorkLongPoll.Settings backoff = longPollSettings;
        long sleep = backoff.firstInterval().toMillis();
        while (true) {
            List<ExternalWork> out = new ArrayList<>();
            for (TopicRequest t : r.topics()) {
                if (out.size() >= max) {
                    break;
                }
                int room = max - out.size();
                if (engine instanceof SangamEngineAdapter s) {
                    List<SangamEngineAdapter.LeasedWork> got = fenced("fetch", () ->
                            fetchOwnTenant(ctx, s, r.workerId(), t.name(), room, lockDuration(t.lockDurationMs())));
                    for (SangamEngineAdapter.LeasedWork lw : got) {
                        if (!ownTenant(ctx, lw.tenantId())) {
                            // SL-157: never hand out (or record an intent for) another tenant's work; give it back
                            engine.unlockExternal(lw.work().id(), r.workerId(), lw.work().lockVersion());
                            continue;
                        }
                        Intent intent = recordIntent(r.workerId(), lw);
                        if (intent != null) {
                            out.add(map(ctx, lw.work(), lw.businessKey(), intent.operation())
                                    .withTraceContext(intent.traceContext()));
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
            sleep = backoff.nextIntervalMillis(sleep);   // X1-07: the one back-off rule
        }
    }

    /**
     * SL-16 / R37: the server records the intent of the leased operation before the worker sees the work (own engine
     * transaction right after the lease; a crash in between leaves a lease without an intent, which the expiry policy
     * re-offers: nothing was dispatched). Work whose operation awaits reconciliation, or that the ledger refuses, is
     * parked (retries 0, incident) instead of being handed out; returns null then.
     */
    private Intent recordIntent(String workerId, SangamEngineAdapter.LeasedWork lw) {
        EngineAdapter.ExternalWork w = lw.work();
        WorkLedger.Lease lease = new WorkLedger.Lease(lw.operationId(), lw.tenantId(), lw.rootId(), w.activityInstanceId(),
                lw.capabilityClass(), w.id(), w.attemptId(), w.lockVersion());
        String refusal;
        try {
            // M-062: the creating trace context is read in the same engine transaction (no extra commit)
            Intent intent = engine.inEngineTransaction(c -> new Intent(ledger.onLease(c, lease), traceContext(c, w.id())));
            if (!OperationLedger.State.UNKNOWN_OUTCOME.name().equals(intent.operation().state())) {
                return intent;
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

    /** The ledger state of a leased item and its stored creating trace context (null: none, or trace links off). */
    private record Intent(WorkOperation operation, WorkPort.TraceContext traceContext) {
    }

    private volatile boolean traceLinks;

    /**
     * M-062: when on (the engine's {@code trace-links} setting, off by default), every fetched item carries the trace
     * context stored when the engine created it ({@code JobTraceContexts}, W2-D), so the worker's span links back to
     * that command. Off: no read, and fetch is unchanged.
     */
    public void setTraceLinks(boolean traceLinks) {
        this.traceLinks = traceLinks;
    }

    /** The stored creating context of work {@code workId}, on the caller's connection; null when off or none stored. */
    private WorkPort.TraceContext traceContext(java.sql.Connection c, String workId) {
        if (!traceLinks || !(engine instanceof org.sangam.engine.OperatonEngineAdapter)) {
            return null;
        }
        return org.sangam.engine.trace.JobTraceContexts.read(c, workId)
                .map(t -> new WorkPort.TraceContext(t.traceparent(),
                        t.tracestate() == null || t.tracestate().isBlank() ? null : t.tracestate()))
                .orElse(null);
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
        guardWork(ctx, workId);   // SL-157 tenant, SL-158 topic binding
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
        guardWork(ctx, workId);   // SL-157 tenant, SL-158 topic binding
        rejectIfTombstoned(ctx, workId, r.lockVersion(), Tombstones.ResultKind.COMPLETE, true, r.variables());
        Map<String, Object> vars = TypedVariables.fromJson(r.variables());
        if (r.localVariables() != null) {
            vars.putAll(TypedVariables.fromJson(r.localVariables()));
        }
        String outcome = WorkLedger.successJson(r.variables(), r.outcome());
        Map<String, Object> acknowledged = workPayload(workId, r.workerId(), r.lockVersion(), r.attemptId(), Map.of());
        try {
            // SG-15: the operation id stored with the lease is read in the completion transaction by one plain SQL read
            // (no engine query, so no engine entity is cached before completeExternal takes the root lock, SG-11). Only
            // a lease without a stored id needs the derived one: that attempt is rolled back before any effect and the
            // id is derived in its own transaction first, exactly as before.
            if (!complete(ctx, workId, r, vars, outcome, acknowledged, null)) {
                complete(ctx, workId, r, vars, outcome, acknowledged, Optional.ofNullable(operationIdOf(workId)));
            }
        } catch (StaleLeaseException | OperationLedger.StaleLeaseException e) {
            if (engine.inEngineTransaction(c -> ledger.isRepeatedCompletion(c, workId, r.lockVersion(), outcome))) {
                return;   // the same completion again (a retried request): answered as the first one
            }
            throw fenceProblem(workId, e);
        }
    }

    /** Signals that the lease has no stored operation id; thrown before any effect, so the attempt rolls back clean. */
    private static final class NoStoredOperationId extends RuntimeException {
        NoStoredOperationId() {
            super(null, null, false, false);
        }
    }

    /**
     * One completion transaction: the engine completion, the SL-16 ledger outcome and the EXT_OP_ACKNOWLEDGED audit
     * commit together. {@code knownOp} null: the operation id is the one stored with the lease, read here on the
     * transaction's connection; returns false (nothing done) when the lease stores none. Otherwise {@code knownOp} is
     * the derived id (empty: no Sangam operation id, as for a non-Sangam engine).
     */
    private boolean complete(RequestContext ctx, String workId, CompleteRequest r, Map<String, Object> vars,
                             String outcome, Map<String, Object> acknowledged, Optional<UUID> knownOp) {
        try {
            engine.inEngineTransaction(c -> {
                UUID op = knownOp != null ? knownOp.orElse(null) : storedOperationId(c, workId);
                engine.completeExternal(workId, r.workerId(), r.lockVersion(), vars);
                ledger.onComplete(c, op, r.lockVersion(), outcome);
                chain.append(c, ctx.tenantId(), workChain(workId), "EXT_OP_ACKNOWLEDGED",
                        org.sangam.rest.adapter.jdbc.JdbcAuditAdapter.eventPayload(ctx, acknowledged));
                return null;
            });
            return true;
        } catch (NoStoredOperationId e) {
            return false;
        }
    }

    /**
     * The operation id stored with the lease ({@code SangamLeaseTable.storedOperationIdOf}, W2-D); null without a Sangam
     * engine adapter (as {@link #operationIdOf}); throws {@link NoStoredOperationId} when the lease stores none.
     */
    private UUID storedOperationId(java.sql.Connection c, String workId) {
        if (!(engine instanceof SangamEngineAdapter)) {
            return null;
        }
        if (!(engine instanceof org.sangam.engine.OperatonEngineAdapter)) {
            throw new NoStoredOperationId();   // another Sangam adapter: derive it as before
        }
        return org.sangam.engine.lease.SangamLeaseTable.storedOperationIdOf(c, workId)
                .orElseThrow(NoStoredOperationId::new);
    }

    @Override
    public void fail(RequestContext ctx, String workId, FailRequest r) {
        guardWork(ctx, workId);   // SL-157 tenant, SL-158 topic binding
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
        guardWork(ctx, workId);   // SL-157 tenant, SL-158 topic binding
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
        guardWork(ctx, workId);   // SL-157 tenant, SL-158 topic binding
        fenced(workId, () -> {
            engine.unlockExternal(workId, r.workerId(), r.lockVersion());
            return null;
        });
        auditAfter(ctx, workId, "EXT_OP_RELEASED", r.workerId(), r.lockVersion(), r.attemptId(), Map.of());
    }

    @Override
    public OutcomeUnknownResult outcomeUnknown(RequestContext ctx, String workId, OutcomeUnknownRequest r) {
        guardWork(ctx, workId);   // SL-157 tenant, SL-158 topic binding
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
        guardWork(ctx, workId);   // SL-157 tenant, SL-158 topic binding
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

    /**
     * FWT-D12: the attempt is resolved on the SL-16 work model, the operation ledger row that the lease wrote (attempt id
     * and lock version of the current lease). The worker id is not checked here: the fenced call that follows is.
     */
    @Override
    public long lockVersionOfAttempt(RequestContext ctx, String workId, String workerId, String attemptId) {
        UUID op = operationIdOf(workId);
        if (op == null) {
            throw ApiProblem.staleLease(workId, "NOT_OWNER", "work " + workId + " has no current lease");
        }
        Optional<Long> lv = engine.inEngineTransaction(c -> ledger.lockVersionOfAttempt(c, op, ctx.tenantId(), attemptId));
        return lv.orElseThrow(() -> ApiProblem.staleLease(workId, "STALE_VERSION",
                "sangamAttemptId does not match the current attempt of " + workId));
    }

    private volatile org.sangam.rest.support.WorkLongPoll.Settings longPollSettings =
            org.sangam.rest.support.WorkLongPoll.Settings.defaults();

    /**
     * X1-07: the in-thread fallback wait (no long-poll bean, or above its waiter bound) uses the long-poll's schedule,
     * so the back-off is defined once ({@code WorkLongPoll.Settings}); default {@code Settings.defaults()}.
     */
    public void setLongPollSettings(org.sangam.rest.support.WorkLongPoll.Settings settings) {
        this.longPollSettings = java.util.Objects.requireNonNull(settings, "settings");
    }

    // ------------------------------------------------------------------ tenant and topic (SL-157, SL-158)

    private volatile WorkerTopicGuard topicGuard;

    /** R26 (SL-158): binds the topic-binding check; without it every caller past the endpoint gate may use any topic. */
    public void setTopicGuard(WorkerTopicGuard topicGuard) {
        this.topicGuard = topicGuard;
    }

    private void guardTopic(RequestContext ctx, String topic) {
        WorkerTopicGuard g = topicGuard;
        if (g != null) {
            g.requireTopic(ctx, topic);
        }
    }

    /**
     * Before a worker call on one item: the item's lease must belong to the caller's tenant (SL-157; otherwise it is
     * reported as not found, so no other tenant's work id is confirmed), and the caller must be bound to its topic
     * (SL-158). An item without a lease row is left to the fenced call, which refuses it ({@code NO_LEASE}).
     */
    private void guardWork(RequestContext ctx, String workId) {
        if (!(engine instanceof org.sangam.engine.OperatonEngineAdapter o)) {
            return;   // the lease row (tenant, topic) is known only to the Sangam engine adapter
        }
        Optional<org.sangam.engine.lease.SangamLeaseTable.Row> lease = o.leaseOf(workId);
        if (lease.isEmpty()) {
            return;
        }
        if (!ownTenant(ctx, lease.get().tenantId())) {
            throw ApiProblem.notFound("work", workId);
        }
        guardTopic(ctx, lease.get().topic());
    }

    /** The caller's tenant as it appears on leased work (a null tenant is the engine's default tenant). */
    private boolean ownTenant(RequestContext ctx, String workTenant) {
        String mine = engine instanceof org.sangam.engine.OperatonEngineAdapter o
                ? o.leaseTenant(ctx.tenantId()) : ctx.tenantId();
        return java.util.Objects.equals(mine, workTenant);
    }

    /** SL-157: the fetch itself limited to the caller's tenant where the engine adapter supports it. */
    private static List<SangamEngineAdapter.LeasedWork> fetchOwnTenant(RequestContext ctx, SangamEngineAdapter s,
            String workerId, String topic, int room, Duration lockDuration) {
        if (s instanceof org.sangam.engine.OperatonEngineAdapter o) {
            List<String> tenants = new ArrayList<>();
            tenants.add(ctx.tenantId());   // null: the default tenant
            return o.fetchAndLockWork(workerId, topic, room, lockDuration, tenants);
        }
        return s.fetchAndLockWork(workerId, topic, room, lockDuration);
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
        Map<String, Object> p = workPayload(workId, workerId, lockVersion, attemptId, extra);
        tx.runInTx(() -> audit.record(ctx, workChain(workId), type, p));
    }

    private static Map<String, Object> workPayload(String workId, String workerId, long lockVersion, String attemptId,
                                                   Map<String, ?> extra) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("work_id", workId);
        p.put("worker_id", workerId);
        p.put("lock_version", lockVersion);
        if (attemptId != null) {
            p.put("attempt_id", attemptId);
        }
        p.putAll(extra);
        return p;
    }

    /**
     * The core contract does not expose the root of a work item after completion, so worker-call events go to the
     * per-work chain "work:<workId>".
     */
    private static String workChain(String workId) {
        return "work:" + workId;
    }
}
