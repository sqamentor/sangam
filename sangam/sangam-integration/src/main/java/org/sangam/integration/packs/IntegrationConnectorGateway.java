// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.packs;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.context.Context;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.LookupResult;
import org.sangam.core.integration.SangamConnector;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.core.layer.Hashes;
import org.sangam.core.layer.OperationLedger;
import org.sangam.core.layer.Outbox;
import org.sangam.core.layer.ReconciliationQueue;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.connector.ConnectorCallJournal;
import org.sangam.integration.connector.ConnectorRegistry;
import org.sangam.integration.connector.Json;
import org.sangam.integration.relay.BackpressureGate;
import org.sangam.integration.relay.OutboxWriter;
import org.sangam.packs.port.ConnectorGateway;
import org.sangam.packs.port.ConnectorResult;
import org.sangam.packs.port.OperationStatus;
import org.sangam.packs.port.ReconciliationRequest;

import java.sql.Connection;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Production {@link ConnectorGateway} for the packs (sangam-packs README §8), built on the integration connectors,
 * {@code ConnectorInvoker}, the operation ledger and the reconciliation queue (VI.1 rule 5, VI.2, VI.3, A-2, A-8, R36,
 * R37, R41, K13).
 *
 * <ul>
 *   <li>{@link #submit}: runs in the caller's engine transaction (the delegate's command connection). The first call per
 *       operationId inserts a {@code PACK_OPERATION} row and an outbox event to destination {@value #DISPATCH_DESTINATION},
 *       and returns PENDING; no remote call happens while engine locks are held. A repeat with the same payload hash
 *       returns the stored state; a different hash is {@code IDEMPOTENCY_PAYLOAD_MISMATCH}; after a
 *       {@code RETRY_SAME_OPERATION} resolution or a proven not-found lookup it dispatches again with the same
 *       operationId. The relay hands the event to {@link PackDispatchPublisher}, which calls the connector and delivers
 *       {@code sangam.operationResult} (or {@code sangam.signatureResult} for {@code sign}).</li>
 *   <li>{@link #query}: the pack step's query mode (an engine job inside a process): synchronous, no ledger.</li>
 *   <li>{@link #queryReadOnly}: the entry of an API read ({@link ApiReadGateway}, owner decisions 320 and 321): the same
 *       call, made only when the bound connector answers {@code readOnly} for the exact request; otherwise FAILED
 *       {@code CONNECTOR_OPERATION_NOT_READ_ONLY}, a warning in the log, and the connector is not invoked.</li>
 *   <li>{@link #lookup}: stored outcome, or the connector's outcome lookup (classes a/b); class (c) → UNKNOWN. A
 *       not-found for an UNKNOWN operation of a process instance marks it RETRY_REQUESTED for the pack's re-submit; one
 *       with no process instance stays UNKNOWN for its reconciliation item, which re-dispatches it (decision 290).</li>
 *   <li>{@link #cancel}: not yet handed to the connector → CANCELLED at once; in flight → cancel requested, the outcome
 *       is delivered as {@code sangam.cancelAck}; already completed → COMPLETED.</li>
 *   <li>{@link #openReconciliation}: opens (or reuses) the O04 item on the operation's ledger id.</li>
 *   <li>{@link #capabilityOf}: the bound connector's class, else the pack's declared class, else (c).</li>
 * </ul>
 * The ledger operation id is the pack's operationId itself (a UUID), so worker, ledger and downstream share one id; a
 * non-UUID id falls back to a derived one, with a warning. Pack connectors without a binding go to the
 * {@code fallback} gateway (the packs' simulator in development) or fail with {@code CONNECTOR_NOT_CONFIGURED}.
 */
public final class IntegrationConnectorGateway implements ConnectorGateway, ApiReadGateway {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(IntegrationConnectorGateway.class);

    public static final String DISPATCH_DESTINATION = "pack-dispatch";
    public static final String DISPATCH_EVENT = "sangam.pack.dispatch";

    private final Transactions tx;
    private final ProcessEngine engine;
    private final PackOperationStore store;
    private final PackConnectorBindings bindings;
    private final ConnectorRegistry registry;
    private final ConnectorCallJournal journal;
    private final ReconciliationQueue reconciliation;
    private final OutboxWriter outbox;
    private final BackpressureGate gate;
    private final ConnectorGateway fallback;

    public IntegrationConnectorGateway(Transactions tx, ProcessEngine engine, PackOperationStore store, PackConnectorBindings bindings,
                                       ConnectorRegistry registry, ConnectorCallJournal journal, ReconciliationQueue reconciliation,
                                       OutboxWriter outbox, BackpressureGate gate, ConnectorGateway fallbackOrNull) {
        this.tx = tx;
        this.engine = engine;
        this.store = store;
        this.bindings = bindings;
        this.registry = registry;
        this.journal = journal;
        this.reconciliation = reconciliation;
        this.outbox = outbox;
        this.gate = gate == null ? BackpressureGate.open() : gate;
        this.fallback = fallbackOrNull;
    }

    /** Ledger operation id of a pack operation. */
    public static UUID ledgerOperationId(String tenantId, String rootId, String packOperationId) {
        try {
            return UUID.fromString(packOperationId);   // the pack's own id is the one id, downstream too
        } catch (IllegalArgumentException e) {
            LOG.warn("pack operationId {} is not a UUID; deriving the ledger id", packOperationId);
            return OperationLedger.operationId(tenantId, rootId, "pack:" + packOperationId, 0);
        }
    }

    // ------------------------------------------------------------------ submit

    @Override
    public ConnectorResult submit(org.sangam.packs.port.ConnectorRequest req) {
        if (req.operationId() == null || req.operationId().isBlank()) {
            return ConnectorResult.failed("OPERATION_ID_REQUIRED", "effects need an operationId (VI.3)");
        }
        if (bindings.binding(req.tenantId(), req.connector()).isEmpty()) {
            return fallback != null ? fallback.submit(req)
                    : ConnectorResult.failed("CONNECTOR_NOT_CONFIGURED", "no integration connector bound to " + req.connector());
        }
        gate.check(BackpressureGate.Category.CONTINUATION);
        String tenant = tenantOf(req.tenantId());
        return inScope(c -> {
            Optional<PackOperationStore.Row> existing = store.lock(c, tenant, req.operationId());
            if (existing.isPresent()) {
                PackOperationStore.Row r = existing.get();
                if (r.payloadHash() != null && req.payloadHash() != null && !r.payloadHash().equals(req.payloadHash())) {
                    return ConnectorResult.failed("IDEMPOTENCY_PAYLOAD_MISMATCH",
                            "operationId " + req.operationId() + " was submitted with a different payload; a new action needs a new operationId (A-2)");
                }
                if (r.status() == PackOperationStore.Status.RETRY_REQUESTED) {
                    redispatch(c, r);
                    return ConnectorResult.pending(r.externalRef());
                }
                return toResult(r);
            }
            String root = rootOf(req.processInstanceId());
            store.insert(c, new PackOperationStore.Row(req.operationId(), tenant, req.connector(), req.operation(),
                    ledgerOperationId(tenant, root, req.operationId()), root, req.processInstanceId(), req.businessKey(),
                    req.payloadHash(), Json.write(req.payload()), PackOperationStore.Status.PENDING, null, null, null, false, 1, false));
            appendDispatch(c, tenant, root, req.operationId());
            return ConnectorResult.pending(null);
        });
    }

    /**
     * Dispatches the operation again with the same operationId (PENDING, next generation, a new dispatch event), on
     * {@code c} under the caller's row lock: after a {@code RETRY_SAME_OPERATION} the pack's re-submit does it, and for
     * an operation with no process instance the reconciliation hooks do (decision 290).
     */
    void redispatch(Connection c, PackOperationStore.Row r) {
        store.redispatch(c, r.tenantId(), r.operationId());
        appendDispatch(c, r.tenantId(), r.rootId(), r.operationId());
    }

    private void appendDispatch(Connection c, String tenant, String root, String operationId) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("tenantId", tenant);
        p.put("operationId", operationId);
        outbox.append(c, tenant, root, DISPATCH_EVENT, Json.write(p), List.of(Outbox.Destination.unordered(DISPATCH_DESTINATION)));
    }

    // ------------------------------------------------------------------ query

    @Override
    public ConnectorResult query(org.sangam.packs.port.ConnectorRequest req) {
        return query(req, false);
    }

    /** {@link ApiReadGateway}: {@link #query}, made only for a request the bound connector declares read-only. */
    @Override
    public ConnectorResult queryReadOnly(org.sangam.packs.port.ConnectorRequest req) {
        return query(req, true);
    }

    @Override
    public Optional<String> readOnlyRefusal(org.sangam.packs.port.ConnectorRequest req) {
        Optional<PackConnectorBindings.Binding> b = bindings.binding(req.tenantId(), req.connector());
        Optional<SangamConnector> conn = b.flatMap(x -> registry.find(req.tenantId(), x.connectorId()));
        if (conn.isEmpty()) {
            return Optional.empty();
        }
        ConnectorRequest cr = connectorRequest(req, b.get());
        return declaresReadOnly(conn.get(), cr) ? Optional.empty() : Optional.of(notReadOnly(req, b.get(), cr));
    }

    private ConnectorResult query(org.sangam.packs.port.ConnectorRequest req, boolean apiRead) {
        Optional<PackConnectorBindings.Binding> b = bindings.binding(req.tenantId(), req.connector());
        if (b.isEmpty()) {
            return fallback != null ? fallback.query(req)
                    : ConnectorResult.failed("CONNECTOR_NOT_CONFIGURED", "no integration connector bound to " + req.connector());
        }
        Optional<SangamConnector> conn = registry.find(req.tenantId(), b.get().connectorId());
        if (conn.isEmpty()) {
            return ConnectorResult.failed("CONNECTOR_NOT_CONFIGURED", "connector " + b.get().connectorId() + " is not registered");
        }
        ConnectorRequest cr = connectorRequest(req, b.get());
        if (apiRead && !declaresReadOnly(conn.get(), cr)) {
            String refusal = notReadOnly(req, b.get(), cr);
            LOG.warn("API read refused ({}): tenant {}: {}", SangamConnector.OPERATION_NOT_READ_ONLY, cr.tenantId(), refusal);
            return ConnectorResult.failed(SangamConnector.OPERATION_NOT_READ_ONLY, refusal);
        }
        return switch (conn.get().invoke(cr)) {
            case org.sangam.core.integration.ConnectorResult.Success s -> ConnectorResult.completed(s.providerRef(), s.output());
            case org.sangam.core.integration.ConnectorResult.BusinessFailure f ->
                    new ConnectorResult(OperationStatus.FAILED, f.code(), f.message(), null, f.output());
            case org.sangam.core.integration.ConnectorResult.TransientFailure t -> ConnectorResult.pending(null);
            case org.sangam.core.integration.ConnectorResult.OutcomeUnknown u -> ConnectorResult.pending(null);
        };
    }

    /** The connector request of a query: the binding's operation (else the caller's) and its mapped payload. */
    private ConnectorRequest connectorRequest(org.sangam.packs.port.ConnectorRequest req, PackConnectorBindings.Binding b) {
        UUID queryId = Hashes.uuidV5(OperationLedger.NAMESPACE, "query|" + req.connector() + "|" + req.payloadHash() + "|" + Instant.now());
        // rootId is the ROOT case (the connector contract; SL-119: a called process's config read finds the binding
        // its root case stored), as on the submit path
        String root = req.processInstanceId() == null ? null : rootOf(req.processInstanceId());
        return new ConnectorRequest(tenantOf(req.tenantId()), queryId, "query", 1, root, req.businessKey(),
                b.operation() == null ? req.operation() : b.operation(), b.payloadMapper().apply(req.payload()),
                Instant.now().plusSeconds(30));
    }

    /** Fail closed: a connector whose answer cannot be had (it throws) has not declared the request read-only. */
    private static boolean declaresReadOnly(SangamConnector connector, ConnectorRequest cr) {
        try {
            return connector.readOnly(cr);
        } catch (RuntimeException e) {
            LOG.warn("connector {} failed to answer readOnly for operation {}: {}", connector.descriptor().id(),
                    cr.operation(), e.toString());
            return false;
        }
    }

    private static String notReadOnly(org.sangam.packs.port.ConnectorRequest req, PackConnectorBindings.Binding b,
                                      ConnectorRequest cr) {
        return "pack connector " + req.connector() + " is bound to connector " + b.connectorId() + ", which does not"
                + " declare operation '" + cr.operation() + "' with this request read-only; an API read never calls it";
    }

    // ------------------------------------------------------------------ lookup

    /** {@link ApiReadGateway}: contract only in this commit (register P-3, step 1); it still answers as lookup. */
    @Override
    public ConnectorResult lookupReadOnly(String tenantId, String connector, String operationId) {
        return lookup(tenantId, connector, operationId);
    }

    @Override
    public ConnectorResult lookup(String tenantId, String connector, String operationId) {
        String tenant = tenantOf(tenantId);
        Optional<PackOperationStore.Row> row = tx.inTransaction(c -> store.find(c, tenant, operationId));
        if (row.isEmpty()) {
            return fallback != null && bindings.binding(tenantId, connector).isEmpty() ? fallback.lookup(tenantId, connector, operationId)
                    : ConnectorResult.failed("NOT_FOUND", "no operation " + operationId);
        }
        PackOperationStore.Row r = row.get();
        if (r.status() != PackOperationStore.Status.PENDING && r.status() != PackOperationStore.Status.UNKNOWN) {
            return toResult(r);
        }
        Optional<ConnectorCallJournal.Call> call = tx.inTransaction(c -> journal.find(c, r.ledgerOperationId()));
        if (call.isEmpty()) {
            return r.status() == PackOperationStore.Status.PENDING ? ConnectorResult.pending(r.externalRef())
                    : ConnectorResult.unknown("no connector call on record");
        }
        Optional<SangamConnector> conn = registry.find(tenant, call.get().connectorId());
        if (conn.isEmpty() || conn.get().capabilityClass() == CapabilityClass.C_NEITHER) {
            return r.status() == PackOperationStore.Status.PENDING ? ConnectorResult.pending(r.externalRef())
                    : ConnectorResult.unknown("class (c): no outcome lookup");
        }
        ConnectorRequest cr = new ConnectorRequest(tenant, r.ledgerOperationId(), "lookup", call.get().attempts(), r.rootId(),
                r.businessKey(), call.get().operation(), call.get().requestJson() == null ? Map.of() : Json.readMap(call.get().requestJson()),
                Instant.now().plusSeconds(30));
        LookupResult lr;
        try {
            lr = conn.get().lookup(cr);
        } catch (RuntimeException e) {
            lr = new LookupResult.Unavailable(e.getMessage());
        }
        return switch (lr) {
            case LookupResult.Found f when f.succeeded() -> ConnectorResult.completed(f.providerRef(), f.output());
            case LookupResult.Found f -> new ConnectorResult(OperationStatus.FAILED,
                    f.failureCode() == null ? "DOWNSTREAM_FAILED" : f.failureCode(), "confirmed by lookup", null, f.output());
            case LookupResult.NotFound nf -> {
                if (r.status() == PackOperationStore.Status.UNKNOWN) {
                    if (r.processInstanceId() != null) {
                        // nothing reached the external system: the pack may retry with the same operationId
                        tx.inTransaction(c -> {
                            store.settle(c, tenant, operationId, PackOperationStore.Status.RETRY_REQUESTED, "NOT_FOUND", null, r.resultJson());
                            return null;
                        });
                    }
                    // decision 290: no process instance re-submits, so the row stays UNKNOWN and its open reconciliation
                    // item re-dispatches it with the same operationId (PackReconciliationHooks), bounded
                    yield ConnectorResult.failed("NOT_FOUND", "the external system has no record of the operation");
                }
                yield ConnectorResult.pending(r.externalRef());
            }
            case LookupResult.Unavailable u -> ConnectorResult.pending(r.externalRef());
            case LookupResult.NotSupported ns -> ConnectorResult.unknown("connector has no outcome lookup");
        };
    }

    // ------------------------------------------------------------------ cancel

    @Override
    public ConnectorResult cancel(String tenantId, String connector, String operationId, String reason) {
        String tenant = tenantOf(tenantId);
        return inScope(c -> {
            Optional<PackOperationStore.Row> row = store.lock(c, tenant, operationId);
            if (row.isEmpty()) {
                return fallback != null && bindings.binding(tenantId, connector).isEmpty() ? fallback.cancel(tenantId, connector, operationId, reason)
                        : new ConnectorResult(OperationStatus.CANCELLED, null, "nothing was submitted", null, Map.of());
            }
            PackOperationStore.Row r = row.get();
            return switch (r.status()) {
                case COMPLETED -> toResult(r);
                case FAILED, CANCELLED -> new ConnectorResult(OperationStatus.CANCELLED, r.reasonCode(), "no effect", r.externalRef(), Map.of());
                case RETRY_REQUESTED -> {
                    store.settle(c, tenant, operationId, PackOperationStore.Status.CANCELLED, "CANCELLED", null, r.resultJson());
                    yield new ConnectorResult(OperationStatus.CANCELLED, null, "cancelled before the retry", null, Map.of());
                }
                case PENDING -> {
                    if (!r.dispatched()) {
                        store.settle(c, tenant, operationId, PackOperationStore.Status.CANCELLED, "CANCELLED", null, null);
                        yield new ConnectorResult(OperationStatus.CANCELLED, null, "cancelled before dispatch", null, Map.of());
                    }
                    store.requestCancel(c, tenant, operationId);
                    yield ConnectorResult.pending(r.externalRef());
                }
                case UNKNOWN -> {
                    store.requestCancel(c, tenant, operationId);
                    yield ConnectorResult.unknown("outcome unknown; reconciliation decides (VI.5 rule 4)");
                }
            };
        });
    }

    // ------------------------------------------------------------------ reconciliation

    @Override
    public String openReconciliation(ReconciliationRequest req) {
        String tenant = tenantOf(req.tenantId());
        return tx.inTransaction(c -> {
            Optional<PackOperationStore.Row> row = req.operationId() == null ? Optional.empty() : store.find(c, tenant, req.operationId());
            ReconciliationQueue.Kind kind = "LATE_RESULT_AFTER_DELETE".equals(req.kind()) ? ReconciliationQueue.Kind.LATE_RESULT_AFTER_DELETE
                    : ReconciliationQueue.Kind.UNKNOWN_OUTCOME;
            Map<String, Object> details = new LinkedHashMap<>(req.evidence());
            details.put("packKind", req.kind());
            details.put("packConnector", req.connector());
            details.put("packOperationId", req.operationId());
            details.put("businessKey", req.businessKey());
            UUID ledgerOp = row.map(PackOperationStore.Row::ledgerOperationId).orElse(null);
            String root = row.map(PackOperationStore.Row::rootId).orElse(req.processInstanceId());
            long id = reconciliation.open(c, tenant, kind, ledgerOp, root, ledgerOp == null ? req.operationId() : null, Json.write(details));
            return Long.toString(id);
        });
    }

    /** Bound connector's class first, then the pack declaration, then (c) (A-8). */
    @Override
    public CapabilityClass capabilityOf(String connector) {
        return bindings.binding(connector).flatMap(b -> registry.find(null, b.connectorId())).map(SangamConnector::capabilityClass)
                .orElseGet(() -> bindings.declaredClass(connector));
    }

    // ------------------------------------------------------------------ helpers

    static ConnectorResult toResult(PackOperationStore.Row r) {
        Map<String, Object> data = r.resultJson() == null ? Map.of() : Json.readMap(r.resultJson());
        return switch (r.status()) {
            case PENDING, RETRY_REQUESTED -> ConnectorResult.pending(r.externalRef());
            case COMPLETED -> ConnectorResult.completed(r.externalRef(), data);
            case FAILED -> new ConnectorResult(OperationStatus.FAILED, r.reasonCode(), null, r.externalRef(), data);
            case UNKNOWN -> ConnectorResult.unknown("outcome unknown; reconciliation item open");
            case CANCELLED -> new ConnectorResult(OperationStatus.CANCELLED, r.reasonCode(), null, r.externalRef(), data);
        };
    }

    private String rootOf(String processInstanceId) {
        if (processInstanceId == null) {
            return "pack-unknown-root";
        }
        try {
            ProcessInstance pi = engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(processInstanceId).singleResult();
            if (pi != null && pi.getRootProcessInstanceId() != null) {
                return pi.getRootProcessInstanceId();
            }
        } catch (RuntimeException ignored) {
            // fall back to the instance itself
        }
        return processInstanceId;
    }

    private static String tenantOf(String t) {
        return t == null || t.isBlank() ? "default" : t;
    }

    /** Runs in the current Operaton command's connection when there is one (delegate path), else in a new transaction. */
    private <T> T inScope(Function<Connection, T> work) {
        // Operaton 2.2: Context.getCommandContext() throws outside a command (ENGINE-09025), it never answers null
        CommandContext cc = Context.hasActiveCommandContext() ? Context.getCommandContext() : null;
        if (cc != null) {
            return work.apply(cc.getDbSqlSession().getSqlSession().getConnection());
        }
        return tx.inTransaction(work);
    }
}
