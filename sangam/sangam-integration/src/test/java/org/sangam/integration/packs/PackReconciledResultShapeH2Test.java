// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.sql.SQLException;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.ReconciliationQueue;
import org.sangam.integration.connector.ConnectorInvoker;
import org.sangam.integration.connector.Json;
import org.sangam.integration.reconciliation.ReconciliationEngine;
import org.sangam.packs.port.ConnectorResult;
import org.sangam.packs.port.OperationStatus;

/**
 * Root cause of the missing record after a reconciliation confirm: a reconciliation settlement stores
 * {@code result_json} in the same shape as a dispatch settlement (the connector output), not the resolution wrapper
 * {@code {output, providerRef, duplicate}}, so the operation GET (the gateway lookup that RestBackedDataObjects reads)
 * answers with the record whichever path settled it.
 */
class PackReconciledResultShapeH2Test {

    private final PackH2Fixture f;

    PackReconciledResultShapeH2Test() throws SQLException {
        f = new PackH2Fixture("shape");
    }

    @AfterEach
    void close() throws SQLException {
        f.close();
    }

    private PackReconciliationHooks hooks() {
        return new PackReconciliationHooks(f.tx(), f.store, f.gateway, f.messages);
    }

    @Test
    void theOperationGetReturnsTheRecordAfterAReconciliationConfirm() {
        PackOperationStore.Row r = f.operation(null, "data-object:leave", PackOperationStore.Status.UNKNOWN);
        ReconciliationQueue.Item item = f.unknownItem(r);
        Map<String, Object> output = Map.of("record", Map.of("id", "rec-7", "name", "a"));
        hooks().resolved(item, ReconciliationQueue.Action.CONFIRM_SUCCESS, ReconciliationEngine.SYSTEM_ACTOR,
                ConnectorInvoker.successJson(output, "ext-7", true));

        ConnectorResult got = f.gateway.lookup(PackH2Fixture.TENANT, "records", r.operationId());
        assertEquals(OperationStatus.COMPLETED, got.status());
        assertEquals("ext-7", got.externalRef());
        assertEquals(Map.of("id", "rec-7", "name", "a"), got.data().get("record"), "the record, as after a dispatch");
        assertNull(got.data().get("output"), "not the resolution wrapper");
        assertFalse(got.data().containsKey("duplicate"));
    }

    @Test
    void bothPathsStoreTheSameShape() {
        Map<String, Object> output = Map.of("record", Map.of("id", "rec-1"));
        PackOperationStore.Row dispatched = f.operation(null, "data-object:leave", PackOperationStore.Status.PENDING);
        f.tx().inTransaction(c -> f.store.settle(c, PackH2Fixture.TENANT, dispatched.operationId(),
                PackOperationStore.Status.COMPLETED, null, "ext-1", Json.write(output)));

        PackOperationStore.Row reconciled = f.operation(null, "data-object:leave", PackOperationStore.Status.UNKNOWN);
        hooks().resolved(f.unknownItem(reconciled), ReconciliationQueue.Action.CONFIRM_SUCCESS, "user:officer",
                ConnectorInvoker.successJson(output, "ext-1", true));

        assertEquals(Json.readMap(f.row(dispatched.operationId()).resultJson()),
                Json.readMap(f.row(reconciled.operationId()).resultJson()));
    }

    @Test
    void aReconciledFailureStoresTheMessageAsADispatchFailureDoes() {
        PackOperationStore.Row r = f.operation(null, "data-object:leave", PackOperationStore.Status.UNKNOWN);
        hooks().resolved(f.unknownItem(r), ReconciliationQueue.Action.MARK_FAILED, "user:officer",
                ConnectorInvoker.failureJson("DUPLICATE_REJECTED", "confirmed with the department", Map.of()));
        PackOperationStore.Row after = f.row(r.operationId());
        assertEquals(PackOperationStore.Status.FAILED, after.status());
        assertEquals(Map.of("message", "confirmed with the department"), Json.readMap(after.resultJson()));
        ConnectorResult got = f.gateway.lookup(PackH2Fixture.TENANT, "records", r.operationId());
        assertEquals(OperationStatus.FAILED, got.status());
        assertEquals("DUPLICATE_REJECTED", got.reasonCode());
    }

    @Test
    void aRetryOfAProcessInstanceOperationKeepsWhatTheRowHad() {
        PackOperationStore.Row r = f.operation("pi-1", "invoice:42", PackOperationStore.Status.UNKNOWN);
        String before = f.row(r.operationId()).resultJson();
        hooks().resolved(f.unknownItem(r), ReconciliationQueue.Action.RETRY_SAME_OPERATION, ReconciliationEngine.SYSTEM_ACTOR,
                "{\"reason\":\"not found downstream\"}");
        PackOperationStore.Row after = f.row(r.operationId());
        assertEquals(PackOperationStore.Status.RETRY_REQUESTED, after.status());
        assertEquals(before, after.resultJson(), "the resolution reason is not stored as the result");
    }
}
