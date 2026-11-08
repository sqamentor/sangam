// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.packs;

import org.sangam.core.layer.CapabilityClass;
import org.sangam.core.layer.ReconciliationQueue;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.connector.Json;
import org.sangam.integration.reconciliation.ReconciliationEngine;

import java.util.Map;
import java.util.Optional;

/**
 * Connects reconciliation resolutions to the pack instances (sangam-packs README §4, OJ-02 / O04):
 * <ul>
 *   <li><b>Guard</b>: {@code RETRY_SAME_OPERATION} is refused for class (c) pack operations
 *       ({@code 422 RETRY_NOT_ALLOWED}, VI.3 rule 5); the operator confirms success or failure with evidence.</li>
 *   <li><b>Listener</b>: after a resolution of an operation the pack is waiting on (row UNKNOWN or PENDING), the row is
 *       settled and {@code sangam.reconciliationResolved(resolution, resolutionEvidence, resolvedBy)} is delivered with
 *       resolution {@code CONFIRMED_SUCCESS}, {@code CONFIRMED_FAILURE} or {@code RETRY_SAME_OPERATION}. A retry
 *       marks the row RETRY_REQUESTED so the pack's next {@code submit} dispatches again with the same operationId.</li>
 * </ul>
 */
public final class PackReconciliationHooks implements ReconciliationEngine.ResolutionGuard, ReconciliationEngine.ResolutionListener {

    private final Transactions tx;
    private final PackOperationStore store;
    private final IntegrationConnectorGateway gateway;
    private final PackMessages messages;

    public PackReconciliationHooks(Transactions tx, PackOperationStore store, IntegrationConnectorGateway gateway, PackMessages messages) {
        this.tx = tx;
        this.store = store;
        this.gateway = gateway;
        this.messages = messages;
    }

    private Optional<PackOperationStore.Row> rowOf(ReconciliationQueue.Item item) {
        return tx.inTransaction(c -> item.operationId() != null ? store.byLedgerOperation(c, item.operationId())
                : item.taskId() != null ? store.find(c, item.tenantId(), item.taskId()) : Optional.<PackOperationStore.Row>empty());
    }

    @Override
    public void check(ReconciliationQueue.Item item, ReconciliationQueue.Action action, String actor) {
        if (action != ReconciliationQueue.Action.RETRY_SAME_OPERATION) {
            return;
        }
        rowOf(item).ifPresent(r -> {
            if (gateway.capabilityOf(r.connector()) == CapabilityClass.C_NEITHER) {
                throw new IllegalArgumentException("422 RETRY_NOT_ALLOWED: class (c) operations are never repeated;"
                        + " confirm success or failure with evidence (VI.3 rule 5)");
            }
        });
    }

    @Override
    public void resolved(ReconciliationQueue.Item item, ReconciliationQueue.Action action, String actor, String resolutionJson) {
        Optional<PackOperationStore.Row> row = rowOf(item);
        if (row.isEmpty()) {
            return;
        }
        PackOperationStore.Row r = row.get();
        if (r.status() != PackOperationStore.Status.UNKNOWN && r.status() != PackOperationStore.Status.PENDING) {
            return;   // already settled (e.g. by a lookup the pack made itself)
        }
        Map<String, Object> res = Json.readMap(resolutionJson);
        String resolution;
        PackOperationStore.Status status;
        String reason = null;
        String ref = res.get("providerRef") == null ? null : String.valueOf(res.get("providerRef"));
        switch (action) {
            case CONFIRM_SUCCESS -> {
                resolution = "CONFIRMED_SUCCESS";
                status = PackOperationStore.Status.COMPLETED;
            }
            case MARK_FAILED -> {
                resolution = "CONFIRMED_FAILURE";
                status = PackOperationStore.Status.FAILED;
                reason = res.get("code") == null ? "CONFIRMED_FAILURE" : String.valueOf(res.get("code"));
            }
            default -> {
                resolution = "RETRY_SAME_OPERATION";
                status = PackOperationStore.Status.RETRY_REQUESTED;
            }
        }
        String finalReason = reason;
        tx.inTransaction(c -> {
            store.settle(c, r.tenantId(), r.operationId(), status, finalReason, ref, resolutionJson);
            return null;
        });
        messages.reconciliationResolved(r, resolution, resolutionJson, actor, reason);
    }
}
