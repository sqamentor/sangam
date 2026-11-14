// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.ReconciliationQueue;
import org.sangam.integration.connector.ConnectorInvoker;
import org.sangam.integration.connector.Json;
import org.sangam.integration.reconciliation.ReconciliationEngine;

/**
 * Owner decision 289: a pack operation's settlement is reported to the listener of its kind exactly once, in the
 * settling transaction, with status COMPLETED (and the connector output, whichever path settled) or FAILED (and the
 * failure code); a final row is never settled again.
 */
class PackSettlementH2Test {

    private final PackH2Fixture f;
    private final List<PackSettlementListener.Settlement> seen = new CopyOnWriteArrayList<>();

    PackSettlementH2Test() throws SQLException {
        f = new PackH2Fixture("settle");
        f.store.onSettled("data-object", (c, s) -> {
            assertFalse(c == null, "called with the settling connection");
            seen.add(s);
        });
    }

    @AfterEach
    void close() throws SQLException {
        f.close();
    }

    private boolean settle(PackOperationStore.Row r, PackOperationStore.Status status, String reason, String ref, Map<String, Object> data) {
        return f.tx().inTransaction(c -> f.store.settle(c, PackH2Fixture.TENANT, r.operationId(), status, reason, ref,
                data == null ? null : Json.write(data)));
    }

    @Test
    void aCompletedCreateIsReportedOnceWithItsRecord() {
        PackOperationStore.Row r = f.operation(null, "data-object:leave", PackOperationStore.Status.PENDING);
        assertTrue(settle(r, PackOperationStore.Status.COMPLETED, null, "ext-1", Map.of("record", Map.of("id", "rec-9"))));
        assertEquals(1, seen.size());
        PackSettlementListener.Settlement s = seen.get(0);
        assertEquals(PackOperationStore.Status.COMPLETED, s.status());
        assertEquals(r.operationId(), s.operationId());
        assertEquals("data-object:leave", s.businessKey());
        assertEquals("ext-1", s.externalRef());
        assertNull(s.reasonCode());
        assertEquals(Map.of("id", "rec-9"), s.output().get("record"));
        assertEquals(PackSettlementListener.SYSTEM_ACTOR, s.settledBy(), "the dispatch settles as the system");

        assertFalse(settle(r, PackOperationStore.Status.FAILED, "LATE", null, Map.of()), "a final row is never settled again");
        assertFalse(settle(r, PackOperationStore.Status.COMPLETED, null, null, Map.of()), "not even with the same state");
        assertEquals(1, seen.size(), "exactly once per operation");
        assertEquals(PackOperationStore.Status.COMPLETED, f.row(r.operationId()).status());
    }

    @Test
    void aFailedCreateIsReportedOnceWithItsCode() {
        PackOperationStore.Row r = f.operation(null, "data-object:leave", PackOperationStore.Status.PENDING);
        assertTrue(settle(r, PackOperationStore.Status.FAILED, "DOWNSTREAM_REJECTED", null, Map.of("message", "no")));
        assertEquals(1, seen.size());
        assertEquals(PackOperationStore.Status.FAILED, seen.get(0).status());
        assertEquals("DOWNSTREAM_REJECTED", seen.get(0).reasonCode());
        assertFalse(settle(r, PackOperationStore.Status.COMPLETED, null, null, Map.of()));
        assertEquals(1, seen.size());
    }

    @Test
    void stepsThatAreNotFinalAndOtherKindsAreNotReported() {
        PackOperationStore.Row r = f.operation(null, "data-object:leave", PackOperationStore.Status.PENDING);
        assertTrue(settle(r, PackOperationStore.Status.UNKNOWN, "OUTCOME_UNKNOWN", null, null));
        assertTrue(settle(r, PackOperationStore.Status.RETRY_REQUESTED, "NOT_FOUND", null, null));
        assertTrue(seen.isEmpty(), "UNKNOWN and RETRY_REQUESTED are not settlements");

        PackOperationStore.Row other = f.operation("pi-1", "invoice:42", PackOperationStore.Status.PENDING);
        assertTrue(settle(other, PackOperationStore.Status.COMPLETED, null, null, Map.of()));
        PackOperationStore.Row noKind = f.operation("pi-2", "nokind", PackOperationStore.Status.PENDING);
        assertTrue(settle(noKind, PackOperationStore.Status.COMPLETED, null, null, Map.of()));
        assertTrue(seen.isEmpty(), "only the registered kind is told");
    }

    @Test
    void aReconciliationConfirmationReportsTheConnectorOutputOnce() {
        PackOperationStore.Row r = f.operation(null, "data-object:leave", PackOperationStore.Status.UNKNOWN);
        ReconciliationQueue.Item item = f.unknownItem(r);
        PackReconciliationHooks hooks = new PackReconciliationHooks(f.tx(), f.store, f.gateway, f.messages);
        String resolution = ConnectorInvoker.successJson(Map.of("record", Map.of("id", "rec-7")), "ext-7", true);
        hooks.resolved(item, ReconciliationQueue.Action.CONFIRM_SUCCESS, ReconciliationEngine.SYSTEM_ACTOR, resolution);
        assertEquals(1, seen.size());
        assertEquals(PackOperationStore.Status.COMPLETED, seen.get(0).status());
        assertEquals(Map.of("id", "rec-7"), seen.get(0).output().get("record"),
                "the same shape as a dispatch settlement, not the resolution wrapper");
        assertEquals("ext-7", seen.get(0).externalRef());
        assertEquals(ReconciliationEngine.SYSTEM_ACTOR, seen.get(0).settledBy());

        hooks.resolved(item, ReconciliationQueue.Action.MARK_FAILED, "user:officer",
                ConnectorInvoker.failureJson("LATE", "a second resolution", Map.of()));
        assertEquals(1, seen.size(), "a settled operation is not settled again");
        assertEquals(PackOperationStore.Status.COMPLETED, f.row(r.operationId()).status());
    }

    @Test
    void aReconciliationFailureReportsItsCode() {
        PackOperationStore.Row r = f.operation(null, "data-object:leave", PackOperationStore.Status.UNKNOWN);
        ReconciliationQueue.Item item = f.unknownItem(r);
        new PackReconciliationHooks(f.tx(), f.store, f.gateway, f.messages).resolved(item, ReconciliationQueue.Action.MARK_FAILED,
                "user:officer", ConnectorInvoker.failureJson("DUPLICATE_REJECTED", "confirmed", Map.of()));
        assertEquals(1, seen.size());
        assertEquals(PackOperationStore.Status.FAILED, seen.get(0).status());
        assertEquals("DUPLICATE_REJECTED", seen.get(0).reasonCode());
        assertEquals("user:officer", seen.get(0).settledBy(), "a human resolution names the officer");
    }

    @Test
    void aListenerFailureRollsTheSettlementBack() throws SQLException {
        try (PackH2Fixture g = new PackH2Fixture("settlefail")) {
            g.store.onSettled("data-object", (c, s) -> {
                throw new IllegalStateException("audit unavailable");
            });
            PackOperationStore.Row r = g.operation(null, "data-object:leave", PackOperationStore.Status.PENDING);
            assertThrows(IllegalStateException.class, () -> g.tx().inTransaction(c -> g.store.settle(c, PackH2Fixture.TENANT,
                    r.operationId(), PackOperationStore.Status.COMPLETED, null, null, "{}")));
            assertEquals(PackOperationStore.Status.PENDING, g.row(r.operationId()).status(), "settlement and its event are one");
        }
    }

    @Test
    void oneListenerPerKind() {
        assertThrows(IllegalStateException.class, () -> f.store.onSettled("data-object", (c, s) -> { }));
        assertEquals("data-object", PackSettlementListener.kindOf("data-object:leave"));
        assertNull(PackSettlementListener.kindOf("nokind"));
        assertNull(PackSettlementListener.kindOf(":x"));
        assertNull(PackSettlementListener.kindOf(null));
    }
}
