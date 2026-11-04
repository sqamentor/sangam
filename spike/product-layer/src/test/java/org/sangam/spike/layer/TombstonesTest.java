package org.sangam.spike.layer;

import org.junit.jupiter.api.Test;
import org.sangam.spike.layer.Tombstones.Gone;
import org.sangam.spike.layer.Tombstones.ResultKind;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** VI.5 / T39-style: delete while a payment worker is mid-call; the late result is audited and reconciled. */
class TombstonesTest extends PgTestBase {

    final AuditChain audit = new AuditChain(PG);
    final ReconciliationQueue queue = new ReconciliationQueue(PG, audit);
    final OperationLedger ledger = new OperationLedger(PG, queue);
    final Tombstones tombstones = new Tombstones(PG, queue, audit);

    @Test
    void lateCompletionAfterDeleteIsGoneAuditedAndReconciled() {
        OperationLedger.OperationRef ref = new OperationLedger.OperationRef("t1", "root-del", "pay:1", 0);
        UUID op = inTx(c -> ledger.recordIntent(c, ref, null, null, "att-1", 3)).operationId();

        // technical delete: tombstone + INSTANCE_DELETED with the open operation ids, in one transaction
        run(c -> {
            tombstones.write(c, "t1", "task-9", op, "root-del", 3, Duration.ofMinutes(5));
            tombstones.recordDeletion(c, "t1", "root-del", "admin-1", "duplicate bill", List.of(op));
        });

        assertInstanceOf(Tombstones.NotTombstoned.class,
                inTx(c -> tombstones.onLateResult(c, "t1", "task-other", 1, ResultKind.COMPLETE, true, "{}")));

        Gone gone = (Gone) inTx(c -> tombstones.onLateResult(c, "t1", "task-9", 3, ResultKind.COMPLETE, true, "{\"bankRef\":\"UTR-9\"}"));
        assertEquals(410, gone.httpStatus());
        assertEquals("GONE_INSTANCE_DELETED", gone.code());
        assertNotNull(gone.reconciliationItemId());
        ReconciliationQueue.Item item = inTx(c -> queue.get(c, gone.reconciliationItemId())).orElseThrow();
        assertEquals(ReconciliationQueue.Kind.LATE_RESULT_AFTER_DELETE, item.kind());
        assertEquals(op, item.operationId());

        // retry is impossible for a deleted instance; confirm records the effect in the ledger
        assertThrows(IllegalArgumentException.class,
                () -> run(c -> queue.resolve(c, item.id(), ReconciliationQueue.Action.RETRY_SAME_OPERATION, "ops", null)));
        run(c -> queue.resolve(c, item.id(), ReconciliationQueue.Action.CONFIRM_SUCCESS, "ops", "{\"bankRef\":\"UTR-9\"}"));
        assertEquals(OperationLedger.State.SUCCEEDED, inTx(c -> ledger.find(c, op)).orElseThrow().state());

        List<String> types = inTx(c -> Jdbc.query(c, "SELECT event_type FROM AUDIT_EVENT WHERE chain_id='root-del' ORDER BY seq",
                rs -> rs.getString(1)));
        assertEquals(List.of("INSTANCE_DELETED", "LATE_RESULT_AFTER_DELETE", "RECONCILIATION_ACTION"), types);
        assertTrue(inTx(c -> audit.verify(c, "t1", "root-del")).ok());
    }

    @Test
    void lateFailureWithoutEffectIsAuditedButOpensNoItem() {
        run(c -> tombstones.write(c, "t1", "task-f", null, "root-f", 1, Duration.ofMinutes(5)));
        Gone gone = (Gone) inTx(c -> tombstones.onLateResult(c, "t1", "task-f", 1, ResultKind.FAIL, false, "{}"));
        assertNull(gone.reconciliationItemId());
        assertTrue(inTx(c -> queue.listOpen(c, "t1")).isEmpty());
        Gone unknown = (Gone) inTx(c -> tombstones.onLateResult(c, "t1", "task-f", 1, ResultKind.OUTCOME_UNKNOWN, false, "{}"));
        assertNotNull(unknown.reconciliationItemId(), "an unknown outcome may hide an effect");
    }

    @Test
    void tombstonedLeaseExpiringWithoutCancelAckBecomesUnknownOutcome() throws Exception {
        OperationLedger.OperationRef ref = new OperationLedger.OperationRef("t1", "root-x", "pay:2", 0);
        UUID op = inTx(c -> ledger.recordIntent(c, ref, CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP, null, "att-1", 1)).operationId();
        run(c -> {
            tombstones.write(c, "t1", "task-x", op, "root-x", 1, Duration.ofMillis(100));
            tombstones.write(c, "t1", "task-acked", null, "root-x", 1, Duration.ofMillis(100));
        });
        assertTrue(txBool(c -> tombstones.ackCancel(c, "t1", "task-acked", 1)));
        Thread.sleep(250);
        assertEquals(1, (int) inTx(tombstones::sweepExpiredLeases));
        assertEquals(Tombstones.State.UNKNOWN_OUTCOME, inTx(c -> tombstones.find(c, "t1", "task-x")).orElseThrow().state());
        assertEquals(Tombstones.State.CANCELLED, inTx(c -> tombstones.find(c, "t1", "task-acked")).orElseThrow().state());
        assertEquals(OperationLedger.State.UNKNOWN_OUTCOME, inTx(c -> ledger.find(c, op)).orElseThrow().state());
        assertEquals(1, inTx(c -> queue.listOpen(c, "t1")).size());
    }
}
