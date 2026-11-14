// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.ReconciliationQueue;
import org.sangam.integration.reconciliation.ReconciliationEngine;

/**
 * Owner decision 290: a not-found reconciliation of an operation with no process instance re-dispatches it at once with
 * the same operationId (no new operation, no duplicate), automatic re-dispatches are bounded, past the bound a human
 * item is opened, and operations of a process instance keep the pack's own re-submit.
 */
class PackRedispatchH2Test {

    private static final String RETRY_JSON = "{\"reason\":\"not found downstream; idempotent retry with the same operationId\"}";

    private final PackH2Fixture f;
    private final List<String> humans = new CopyOnWriteArrayList<>();
    private final PackReconciliationHooks hooks;

    PackRedispatchH2Test() throws SQLException {
        f = new PackH2Fixture("redispatch");
        hooks = new PackReconciliationHooks(f.tx(), f.store, f.gateway, f.messages, 2).humans(
                new PackReconciliationHooks.HumanReconciliation() {
                    @Override
                    public long open(Connection c, UUID ledgerOperationId, String reason) {
                        humans.add("open " + ledgerOperationId);
                        return 42L;
                    }

                    @Override
                    public void escalate(long itemId, String reason) {
                        humans.add("escalate " + itemId);
                    }
                });
    }

    @AfterEach
    void close() throws SQLException {
        f.close();
    }

    private void notFound(PackOperationStore.Row r, String actor) {
        ReconciliationQueue.Item item = f.unknownItem(r);
        hooks.resolved(item, ReconciliationQueue.Action.RETRY_SAME_OPERATION, actor, RETRY_JSON);
    }

    /** What a dispatch that again ended without a definite answer leaves behind. */
    private void unknownAgain(PackOperationStore.Row r) {
        f.tx().inTransaction(c -> f.store.settle(c, PackH2Fixture.TENANT, r.operationId(), PackOperationStore.Status.UNKNOWN,
                "OUTCOME_UNKNOWN", null, null));
    }

    @Test
    void aNotFoundCreateIsReDispatchedWithTheSameOperationId() {
        PackOperationStore.Row r = f.operation(null, "data-object:leave", PackOperationStore.Status.UNKNOWN);
        assertEquals(0, f.dispatches(r.operationId()));
        notFound(r, ReconciliationEngine.SYSTEM_ACTOR);
        PackOperationStore.Row after = f.row(r.operationId());
        assertEquals(PackOperationStore.Status.PENDING, after.status(), "dispatched again, not left RETRY_REQUESTED");
        assertEquals(r.operationId(), after.operationId());
        assertEquals(r.ledgerOperationId(), after.ledgerOperationId(), "the same ledger operation downstream");
        assertEquals(r.payloadHash(), after.payloadHash());
        assertEquals(2, after.generation());
        assertFalse(after.dispatched(), "the dispatcher marks it when it calls the connector");
        assertEquals(1, f.dispatches(r.operationId()), "one dispatch event carrying the same operationId");
        assertEquals(1, f.operationRows(), "no second operation");
        assertTrue(humans.isEmpty());
    }

    @Test
    void theBoundOpensAHumanItemInsteadOfAnotherDispatch() {
        PackOperationStore.Row r = f.operation(null, "data-object:leave", PackOperationStore.Status.UNKNOWN);
        notFound(r, ReconciliationEngine.SYSTEM_ACTOR);              // re-dispatch 1 of 2
        unknownAgain(r);
        notFound(f.row(r.operationId()), ReconciliationEngine.SYSTEM_ACTOR);   // re-dispatch 2 of 2
        unknownAgain(r);
        assertEquals(2, f.dispatches(r.operationId()));
        assertEquals(3, f.row(r.operationId()).generation());

        notFound(f.row(r.operationId()), ReconciliationEngine.SYSTEM_ACTOR);
        PackOperationStore.Row after = f.row(r.operationId());
        assertEquals(PackOperationStore.Status.UNKNOWN, after.status(), "a human decides now");
        assertEquals(PackReconciliationHooks.REDISPATCH_LIMIT_REACHED, after.reasonCode());
        assertEquals(3, after.generation());
        assertEquals(2, f.dispatches(r.operationId()), "no third re-dispatch");
        assertEquals(List.of("open " + r.ledgerOperationId(), "escalate 42"), humans);
    }

    @Test
    void aHumansRetryPastTheBoundStillReDispatches() {
        PackOperationStore.Row r = f.operation(null, "data-object:leave", PackOperationStore.Status.UNKNOWN);
        notFound(r, ReconciliationEngine.SYSTEM_ACTOR);
        unknownAgain(r);
        notFound(f.row(r.operationId()), ReconciliationEngine.SYSTEM_ACTOR);
        unknownAgain(r);
        notFound(f.row(r.operationId()), "user:officer");
        assertEquals(PackOperationStore.Status.PENDING, f.row(r.operationId()).status());
        assertEquals(3, f.dispatches(r.operationId()));
        assertTrue(humans.isEmpty(), "the human chose to retry");
    }

    @Test
    void anOperationOfAProcessInstanceKeepsThePacksReSubmit() {
        PackOperationStore.Row r = f.operation("pi-1", "invoice:42", PackOperationStore.Status.UNKNOWN);
        notFound(r, ReconciliationEngine.SYSTEM_ACTOR);
        PackOperationStore.Row after = f.row(r.operationId());
        assertEquals(PackOperationStore.Status.RETRY_REQUESTED, after.status(), "the pack's next submit dispatches it");
        assertEquals(1, after.generation());
        assertEquals(0, f.dispatches(r.operationId()), "nothing re-dispatched by reconciliation");
        assertTrue(humans.isEmpty());
    }

    @Test
    void anOperationOfAProcessInstanceIsNeverBounded() {
        PackOperationStore.Row r = f.operation("pi-1", "invoice:42", PackOperationStore.Status.UNKNOWN);
        f.tx().inTransaction(c -> {
            f.store.settle(c, PackH2Fixture.TENANT, r.operationId(), PackOperationStore.Status.RETRY_REQUESTED, "NOT_FOUND", null, null);
            f.gateway.redispatch(c, f.store.lock(c, PackH2Fixture.TENANT, r.operationId()).orElseThrow());
            f.gateway.redispatch(c, f.store.lock(c, PackH2Fixture.TENANT, r.operationId()).orElseThrow());
            return null;
        });
        unknownAgain(r);
        notFound(f.row(r.operationId()), ReconciliationEngine.SYSTEM_ACTOR);
        assertEquals(PackOperationStore.Status.RETRY_REQUESTED, f.row(r.operationId()).status());
        assertTrue(humans.isEmpty());
    }

    @Test
    void theBoundIsAtLeastOne() {
        assertThrows(IllegalArgumentException.class,
                () -> new PackReconciliationHooks(f.tx(), f.store, f.gateway, f.messages, 0));
        assertEquals(PackReconciliationHooks.DEFAULT_MAX_REDISPATCHES,
                new PackReconciliationHooks(f.tx(), f.store, f.gateway, f.messages).maxRedispatches());
    }
}
