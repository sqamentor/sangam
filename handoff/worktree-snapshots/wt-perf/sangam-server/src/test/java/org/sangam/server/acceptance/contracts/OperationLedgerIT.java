// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.contracts;

import org.sangam.core.layer.*;

import org.junit.jupiter.api.Test;
import org.sangam.core.layer.OperationLedger.OperationRef;
import org.sangam.core.layer.ReconciliationQueue.Action;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** VI.3 rule 4–5, R36, A-2, A-8: UUIDv5 identity, lockVersion fencing, capability classes, reconciliation. */
class OperationLedgerIT extends LayerSpecSupport {

    final AuditChain audit = new AuditChain(PG);
    final ReconciliationQueue queue = new ReconciliationQueue(PG, audit);
    final OperationLedger ledger = new OperationLedger(PG, queue);
    final OperationRef pay = new OperationRef("t1", "root-1", "payTask:act-17", 0);

    // ------------------------------------------------------------------ identity (no database)

    @Test
    void uuidV5MatchesTheRfcAlgorithm() {
        // RFC 4122 / Python uuid.uuid5(NAMESPACE_DNS, "www.example.com")
        UUID dns = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");
        assertEquals(UUID.fromString("2ed6657d-e927-568b-95e1-2665a8aea6a2"), Hashes.uuidV5(dns, "www.example.com"));
    }

    @Test
    void operationIdIsStableVersion5AndSensitiveToEveryComponent() {
        UUID a = OperationLedger.operationId("t1", "root-1", "payTask:act-17", 0);
        assertEquals(a, OperationLedger.operationId("t1", "root-1", "payTask:act-17", 0));
        assertEquals(a, pay.operationId());
        assertEquals(5, a.version());
        assertEquals(2, a.variant());
        // golden value: pinned so a code change cannot silently re-key every ledger row (stability across restore/migration)
        assertEquals(UUID.fromString("c5a5fcab-f113-5e12-802b-bf60db8bce93"), a);
        assertEquals(a, OperationLedger.operationId("t1", "root-1", "payTask:act-17", 0, null));

        Set<UUID> ids = new HashSet<>();
        ids.add(a);
        ids.add(OperationLedger.operationId("t2", "root-1", "payTask:act-17", 0));
        ids.add(OperationLedger.operationId("t1", "root-2", "payTask:act-17", 0));
        ids.add(OperationLedger.operationId("t1", "root-1", "payTask:act-18", 0));
        ids.add(OperationLedger.operationId("t1", "root-1", "payTask:act-17", 1));
        ids.add(OperationLedger.operationId("t1", "root-1", "payTask:act-17", 0, "refund"));
        // length-prefixing: shifting a separator between components must not collide
        ids.add(OperationLedger.operationId("t1|", "root-1", "payTask:act-17", 0));
        ids.add(OperationLedger.operationId("t1", "|root-1", "payTask:act-17", 0));
        assertEquals(8, ids.size());
    }

    @Test
    void capabilityClassDefaultsToNeitherAndIrreversibleIsAnAlias() {
        assertEquals(CapabilityClass.C_NEITHER, CapabilityClass.fromDeclaration(null));
        assertEquals(CapabilityClass.C_NEITHER, CapabilityClass.fromDeclaration(" "));
        assertEquals(CapabilityClass.C_NEITHER, CapabilityClass.fromDeclaration("irreversible"));
        assertEquals(CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP, CapabilityClass.fromDeclaration("a"));
        assertEquals(CapabilityClass.B_LOOKUP_ONLY, CapabilityClass.fromDeclaration("B"));
        assertFalse(CapabilityClass.C_NEITHER.allowsAutomaticRetry());
        assertThrows(IllegalArgumentException.class, () -> CapabilityClass.fromDeclaration("maybe"));
    }

    // ------------------------------------------------------------------ ledger

    @Test
    void attemptsReuseTheOperationIdAndStaleLeasesAreFenced() {
        byte[] fp = Hashes.sha256("{\"amount\":\"100.00\"}");
        OperationLedger.Intent i1 = inTx(c -> ledger.recordIntent(c, pay, CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP, fp, "att-1", 1));
        assertTrue(i1.firstAttempt());
        assertEquals(CapabilityClass.LeaseExpiryAction.RETRY_SAME_OPERATION,
                inTx(c -> ledger.onLeaseExpired(c, i1.operationId(), 2)));
        OperationLedger.Intent i2 = inTx(c -> ledger.recordIntent(c, pay, CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP, fp, "att-2", 2));
        assertEquals(i1.operationId(), i2.operationId());
        assertEquals(2, i2.attempts());

        // the first worker (lockVersion 1) comes back late: 409 STALE_LEASE, nothing changes
        assertThrows(OperationLedger.StaleLeaseException.class,
                () -> run(c -> ledger.recordOutcome(c, i1.operationId(), 1, true, "{\"ref\":\"bank-1\"}")));
        run(c -> ledger.recordOutcome(c, i2.operationId(), 2, true, "{\"ref\":\"bank-2\"}"));
        run(c -> ledger.recordOutcome(c, i2.operationId(), 2, true, "{\"ref\":\"bank-2\"}"));   // identical repeat: no-op
        OperationLedger.Entry e = inTx(c -> ledger.find(c, i2.operationId())).orElseThrow();
        assertEquals(OperationLedger.State.SUCCEEDED, e.state());
        assertEquals("{\"ref\":\"bank-2\"}", e.outcome());

        // a settled operation is never re-dispatched
        OperationLedger.Intent again = inTx(c -> ledger.recordIntent(c, pay, CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP, fp, "att-3", 3));
        assertFalse(again.mayDispatch());
        // a different request under the same identity is a new business action, not a retry
        assertThrows(OperationLedger.FingerprintMismatchException.class,
                () -> run(c -> ledger.recordIntent(c, pay, CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP, Hashes.sha256("other"), "att-4", 4)));
    }

    @Test
    void undeclaredClassGoesToUnknownOutcomeOnLeaseExpiryAndIsReconciled() {
        OperationLedger.Intent i = inTx(c -> ledger.recordIntent(c, pay, null, null, "att-1", 1));      // A-8 default (c)
        assertEquals(CapabilityClass.LeaseExpiryAction.UNKNOWN_OUTCOME, inTx(c -> ledger.onLeaseExpired(c, i.operationId(), 2)));
        assertEquals(OperationLedger.State.UNKNOWN_OUTCOME, inTx(c -> ledger.find(c, i.operationId())).orElseThrow().state());
        var items = inTx(c -> queue.listOpen(c, "t1"));
        assertEquals(1, items.size());
        assertEquals(ReconciliationQueue.Kind.UNKNOWN_OUTCOME, items.get(0).kind());
        assertEquals(i.operationId(), items.get(0).operationId());

        // operator: retry with the same operationId
        run(c -> queue.resolve(c, items.get(0).id(), Action.RETRY_SAME_OPERATION, "ops-1", "{\"note\":\"bank confirms no debit\"}"));
        OperationLedger.Intent retry = inTx(c -> ledger.recordIntent(c, pay, null, null, "att-2", 2));
        assertEquals(i.operationId(), retry.operationId());
        assertTrue(retry.mayDispatch());
        assertThrows(ReconciliationQueue.AlreadyResolvedException.class,
                () -> run(c -> queue.resolve(c, items.get(0).id(), Action.MARK_FAILED, "ops-2", null)));
        assertTrue(inTx(c -> audit.verify(c, "t1", "root-1")).ok());
    }

    @Test
    void workerReportedUnknownCanBeConfirmedOrFailed() {
        OperationRef op2 = new OperationRef("t1", "root-1", "payTask:act-17", 1);
        OperationLedger.Intent a = inTx(c -> ledger.recordIntent(c, pay, CapabilityClass.B_LOOKUP_ONLY, null, "att-1", 1));
        OperationLedger.Intent b = inTx(c -> ledger.recordIntent(c, op2, CapabilityClass.B_LOOKUP_ONLY, null, "att-1", 1));
        assertNotEquals(a.operationId(), b.operationId());
        long itemA = inTx(c -> ledger.markUnknown(c, a.operationId(), 1, "timeout reading bank response"));
        long itemB = inTx(c -> ledger.markUnknown(c, b.operationId(), 1, "connection reset"));
        assertEquals(itemA, txLong(c -> ledger.markUnknown(c, a.operationId(), 1, "repeat")), "an open item is reused");
        run(c -> queue.resolve(c, itemA, Action.CONFIRM_SUCCESS, "ops-1", "{\"bankRef\":\"UTR-1\"}"));
        run(c -> queue.resolve(c, itemB, Action.MARK_FAILED, "ops-1", "{\"reason\":\"bank: no such txn\"}"));
        assertEquals(OperationLedger.State.SUCCEEDED, inTx(c -> ledger.find(c, a.operationId())).orElseThrow().state());
        assertEquals(OperationLedger.State.FAILED, inTx(c -> ledger.find(c, b.operationId())).orElseThrow().state());
        assertEquals(CapabilityClass.LeaseExpiryAction.LOOKUP_THEN_RETRY, CapabilityClass.B_LOOKUP_ONLY.onLeaseExpired());
    }
}
