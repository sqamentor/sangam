// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.packs;

import org.sangam.core.layer.Outbox;
import org.sangam.core.layer.Publisher;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.connector.ConnectorCallJournal;
import org.sangam.integration.connector.ConnectorInvoker;
import org.sangam.integration.connector.Json;
import org.sangam.integration.ledger.OperationLedgerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Relay publisher for destination {@value IntegrationConnectorGateway#DISPATCH_DESTINATION}: performs the external call
 * of a pack operation outside any engine transaction (VI.1 rule 5, VI.2.3) through {@link ConnectorInvoker} (ledger
 * intent, capability-class rules, outcome, reconciliation item), then delivers the result message to the instance.
 *
 * <ul>
 *   <li>Row not PENDING (completed, cancelled, already settled by a redelivered event) → nothing to do.</li>
 *   <li>Cancel requested before the connector was called, or after a proven no-effect attempt → CANCELLED and
 *       {@code sangam.cancelAck(cancelResult=CANCELLED)}.</li>
 *   <li>Completed / failed / unknown → row settled, {@code sangam.operationResult} (or {@code sangam.signatureResult}
 *       for {@code sign}); with a pending cancel also {@code sangam.cancelAck} carrying the final status.</li>
 *   <li>Retryable failure → exception, the relay retries with back-off; after {@code maxAttempts} the operation becomes
 *       UNKNOWN_OUTCOME with a reconciliation item (no endless blind retry, R28).</li>
 * </ul>
 */
public final class PackDispatchPublisher implements Publisher {

    private static final Logger LOG = LoggerFactory.getLogger(PackDispatchPublisher.class);

    private final Transactions tx;
    private final PackOperationStore store;
    private final PackConnectorBindings bindings;
    private final ConnectorInvoker invoker;
    private final ConnectorCallJournal journal;
    private final OperationLedgerService ledger;
    private final PackMessages messages;
    private final int maxAttempts;
    private final Duration callTimeout;

    public PackDispatchPublisher(Transactions tx, PackOperationStore store, PackConnectorBindings bindings, ConnectorInvoker invoker,
                                 ConnectorCallJournal journal, OperationLedgerService ledger, PackMessages messages,
                                 int maxAttempts, Duration callTimeout) {
        this.tx = tx;
        this.store = store;
        this.bindings = bindings;
        this.invoker = invoker;
        this.journal = journal;
        this.ledger = ledger;
        this.messages = messages;
        this.maxAttempts = maxAttempts <= 0 ? 5 : maxAttempts;
        this.callTimeout = callTimeout == null ? Duration.ofMinutes(2) : callTimeout;
    }

    private record Claim(PackOperationStore.Row row, boolean cancelled) {
    }

    @Override
    public void publish(String destination, Outbox.OutboxEvent event) {
        Map<String, Object> p = Json.readMap(event.payload());
        String tenant = String.valueOf(p.get("tenantId"));
        String opId = String.valueOf(p.get("operationId"));
        Optional<Claim> claim = tx.inTransaction(c -> {
            Optional<PackOperationStore.Row> row = store.lock(c, tenant, opId);
            if (row.isEmpty() || row.get().status() != PackOperationStore.Status.PENDING) {
                return Optional.<Claim>empty();
            }
            PackOperationStore.Row r = row.get();
            if (r.cancelRequested()) {
                boolean noEffect = !r.dispatched() || journal.find(c, r.ledgerOperationId())
                        .map(j -> j.lastResult() == ConnectorCallJournal.LastResult.NO_EFFECT).orElse(true);
                if (noEffect) {
                    store.settle(c, tenant, opId, PackOperationStore.Status.CANCELLED, "CANCELLED", null, null);
                    return Optional.of(new Claim(r, true));
                }
            }
            store.markDispatched(c, tenant, opId);
            return Optional.of(new Claim(r, false));
        });
        if (claim.isEmpty()) {
            return;
        }
        PackOperationStore.Row r = claim.get().row();
        if (claim.get().cancelled()) {
            messages.cancelAck(r, "CANCELLED");
            return;
        }
        Optional<PackConnectorBindings.Binding> b = bindings.binding(r.tenantId(), r.connector());
        if (b.isEmpty()) {
            settle(r, PackOperationStore.Status.FAILED, "CONNECTOR_NOT_CONFIGURED", null, null);
            return;
        }
        Map<String, Object> input = b.get().payloadMapper().apply(Json.readMap(r.payloadJson()));
        ConnectorInvoker.Invocation inv = new ConnectorInvoker.Invocation(r.tenantId(), r.rootId(), "pack:" + r.operationId(), 0,
                null, r.businessKey(), b.get().connectorId(), b.get().operation() == null ? r.operation() : b.get().operation(), input,
                UUID.randomUUID().toString(), 0L, Instant.now().plus(callTimeout), r.ledgerOperationId());
        ConnectorInvoker.Outcome o = invoker.invoke(inv);
        switch (o) {
            case ConnectorInvoker.Completed done -> settle(r, PackOperationStore.Status.COMPLETED, null, done.providerRef(), done.output());
            case ConnectorInvoker.Failed f -> settle(r, PackOperationStore.Status.FAILED, f.code(), null, Map.of("message", String.valueOf(f.message())));
            case ConnectorInvoker.Unknown u -> settle(r, PackOperationStore.Status.UNKNOWN, "OUTCOME_UNKNOWN", null, Map.of("reason", String.valueOf(u.reason())));
            case ConnectorInvoker.Stale s -> LOG.info("stale pack dispatch {}: {}", r.operationId(), s.message());
            case ConnectorInvoker.Retry retry -> {
                int attempts = tx.inTransaction(c -> journal.find(c, r.ledgerOperationId()).map(ConnectorCallJournal.Call::attempts).orElse(1));
                if (attempts < maxAttempts) {
                    throw new IllegalStateException("pack operation " + r.operationId() + " retry " + attempts + ": " + retry.message());
                }
                try {
                    ledger.reportUnknown(r.ledgerOperationId(), 0L, "no definite answer after " + attempts + " attempts: " + retry.message());
                } catch (RuntimeException e) {
                    LOG.warn("cannot mark pack operation {} unknown", r.operationId(), e);
                }
                settle(r, PackOperationStore.Status.UNKNOWN, "OUTCOME_UNKNOWN", null, Map.of("reason", String.valueOf(retry.message())));
            }
        }
    }

    private void settle(PackOperationStore.Row r, PackOperationStore.Status status, String reason, String ref, Map<String, Object> data) {
        boolean cancelRequested = tx.inTransaction(c -> {
            store.settle(c, r.tenantId(), r.operationId(), status, reason, ref, data == null ? null : Json.write(data));
            return store.find(c, r.tenantId(), r.operationId()).map(PackOperationStore.Row::cancelRequested).orElse(false);
        });
        if ("sign".equals(r.operation())) {
            messages.signatureResult(r, status.name(), data, reason);
        } else {
            messages.operationResult(r, status.name(), reason, ref);
        }
        if (cancelRequested) {
            messages.cancelAck(r, status == PackOperationStore.Status.FAILED ? "CANCELLED" : status.name());
        }
    }
}
