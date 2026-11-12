// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.layer;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SL-133 (M-003): pending audit rows are recorded without the root's {@code ROOT_SEQ} lock and drained into the
 * root's chain, in order, once the lock holder's transaction ends.
 */
class PendingAuditH2Test {

    static final Dialect D = Dialect.POSTGRESQL;

    private static JdbcDataSource h2() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;LOCK_TIMEOUT=10000");
        try (Connection c = ds.getConnection()) {
            Schema.create(c, D);
            PendingAudit.create(c, D);
        }
        return ds;
    }

    private static List<String> types(Transactions tx, String root) {
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT event_type FROM AUDIT_EVENT WHERE tenant_id='t' AND chain_id=? ORDER BY seq",
                rs -> rs.getString(1), root));
    }

    @Test
    void recordedRowsAreAppendedInOrderAndDeleted() throws Exception {
        JdbcDataSource ds = h2();
        Transactions tx = Transactions.of(ds);
        AuditChain chain = new AuditChain(D);
        PendingAudit pending = new PendingAudit(tx, D, chain);
        tx.inTransaction(c -> {
            pending.record(c, "t", "r1", "OPERATION_DISPATCHED", Map.of("operationId", "op-1"));
            return null;
        });
        tx.inTransaction(c -> {
            pending.record(c, "t", "r1", "OPERATION_OUTCOME", Map.of("operationId", "op-1", "state", "SUCCEEDED"));
            return null;
        });
        assertEquals(0, tx.inTransaction(c -> Jdbc.query(c, "SELECT 1 FROM ROOT_SEQ", rs -> 1)).size(),
                "recording never touches ROOT_SEQ");
        assertEquals(2, pending.count("t", "r1"));

        assertEquals(2, pending.drain("t", "r1"));
        assertEquals(0, pending.count("t", "r1"));
        assertEquals(List.of("OPERATION_DISPATCHED", "OPERATION_OUTCOME"), types(tx, "r1"));
        assertTrue(tx.inTransaction(c -> chain.verify(c, "t", "r1")).ok());
        String payload = tx.inTransaction(c -> Jdbc.one(c, "SELECT p.payload FROM AUDIT_PAYLOAD p JOIN AUDIT_EVENT e"
                + " ON e.event_id = p.event_id WHERE e.chain_id='r1' AND e.seq=1", rs -> rs.getString(1)).orElseThrow());
        assertTrue(payload.contains("\"operationId\":\"op-1\"") && payload.contains("\"recordedAt\":"), payload);
        assertEquals(0, pending.drain("t", "r1"), "a second drain finds nothing");
    }

    @Test
    void recordingWhileAnotherConnectionHoldsTheRootLockDoesNotWaitAndTheDrainWaitsForTheHolder() throws Exception {
        JdbcDataSource ds = h2();
        Transactions tx = Transactions.of(ds);
        AuditChain chain = new AuditChain(D);
        PendingAudit pending = new PendingAudit(tx, D, chain);
        try (Connection c1 = ds.getConnection()) {
            c1.setAutoCommit(false);
            chain.append(c1, "t", "r2", "JOB_STEP", Map.of("n", 1));   // C1 now holds ROOT_SEQ(t, r2)

            // C2 on another connection: completes at once (before SL-133 the append here waited for C1)
            CompletableFuture.runAsync(() -> tx.inTransaction(c -> {
                pending.record(c, "t", "r2", "OPERATION_DISPATCHED", Map.of("operationId", "op-2"));
                return null;
            })).get(5, TimeUnit.SECONDS);

            CompletableFuture<Integer> drain = CompletableFuture.supplyAsync(() -> pending.drain("t", "r2"));
            assertThrows(TimeoutException.class, () -> drain.get(500, TimeUnit.MILLISECONDS), "the drain waits for C1");
            c1.commit();
            assertEquals(1, drain.get(9, TimeUnit.SECONDS));
        }
        assertEquals(List.of("JOB_STEP", "OPERATION_DISPATCHED"), types(tx, "r2"), "pending events follow the holder's");
        assertTrue(tx.inTransaction(c -> chain.verify(c, "t", "r2")).ok());
    }

    @Test
    void theDrainerDrainsOnSignalAndTheSweepCatchesLostSignals() throws Exception {
        JdbcDataSource ds = h2();
        Transactions tx = Transactions.of(ds);
        AuditChain chain = new AuditChain(D);
        PendingAudit pending = new PendingAudit(tx, D, chain);
        try (PendingAuditDrainer drainer = new PendingAuditDrainer(pending,
                new PendingAuditDrainer.Settings(2, Duration.ZERO, Duration.ZERO, 10))) {
            tx.inTransaction(c -> {
                pending.record(c, "t", "r3", "OPERATION_DISPATCHED", Map.of());
                return null;
            });
            drainer.signal("t", "r3");
            drainer.signal("t", "r3");   // coalesces
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(9);
            while (pending.count("t", "r3") > 0 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertEquals(0, pending.count("t", "r3"));
            assertEquals(List.of("OPERATION_DISPATCHED"), types(tx, "r3"));

            // a row without a signal (crash between commit and signal): only the sweep sees it
            tx.inTransaction(c -> {
                pending.record(c, "t", "r4", "OPERATION_UNKNOWN", Map.of());
                pending.record(c, "u", "r5", "OPERATION_OUTCOME", Map.of());
                return null;
            });
            assertEquals(List.of(new PendingAudit.ChainRef("t", "r4"), new PendingAudit.ChainRef("u", "r5")),
                    pending.due(Duration.ZERO, 10));
            assertEquals(List.of(), pending.due(Duration.ofHours(1), 10), "younger than the minimum age");
            assertEquals(2, drainer.sweep(Duration.ZERO));
            assertEquals(List.of(), pending.due(Duration.ZERO, 10));
            assertTrue(tx.inTransaction(c -> chain.verify(c, "u", "r5")).ok());
        }
    }

    @Test
    void aFailedDrainKeepsTheRowsAndCountsTheAttempt() throws Exception {
        JdbcDataSource ds = h2();
        Transactions tx = Transactions.of(ds);
        PendingAudit pending = new PendingAudit(tx, D, new AuditChain(D));
        tx.inTransaction(c -> {
            pending.record(c, "t", "r6", "OPERATION_OUTCOME", Map.of());
            Jdbc.update(c, "UPDATE AUDIT_PENDING SET payload='not json' WHERE chain_id='r6'");
            return null;
        });
        assertThrows(IllegalStateException.class, () -> pending.drain("t", "r6"));
        assertEquals(1, pending.count("t", "r6"), "rolled back: the row stays");
        assertEquals(0, tx.inTransaction(c -> Jdbc.query(c, "SELECT 1 FROM AUDIT_EVENT", rs -> 1)).size());
        pending.recordFailure("t", "r6", "x".repeat(400));
        int attempts = tx.inTransaction(c -> Jdbc.one(c, "SELECT attempts FROM AUDIT_PENDING WHERE chain_id='r6'",
                rs -> rs.getInt(1)).orElseThrow());
        assertEquals(1, attempts);
        assertFalse(tx.inTransaction(c -> Jdbc.one(c, "SELECT last_error FROM AUDIT_PENDING WHERE chain_id='r6'",
                rs -> rs.getString(1)).orElseThrow()).length() > 255);
    }
}
