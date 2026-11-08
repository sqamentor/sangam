// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.contracts;

import org.sangam.core.layer.*;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** X.2.3.3–X.2.3.4: gapless per-root chain, verify, tamper detection, erasure keeps the chain valid. */
class AuditChainIT extends LayerSpecSupport {

    final AuditChain audit = new AuditChain(PG);

    private void appendFive(String root) {
        for (int i = 1; i <= 5; i++) {
            int n = i;
            run(c -> audit.append(c, "t1", root, "TASK_APPROVED", Map.of("step", n, "amount", "1250.50", "by", "seat-" + n)));
        }
    }

    @Test
    void chainVerifiesAndLinksFromGenesis() {
        appendFive("root-1");
        AuditChain.VerifyResult r = inTx(c -> audit.verify(c, "t1", "root-1"));
        assertTrue(r.ok(), String.valueOf(r));
        assertEquals(5, r.eventsChecked());
        byte[] firstPrev = inTx(c -> Jdbc.one(c, "SELECT prev_hash FROM AUDIT_EVENT WHERE chain_id='root-1' AND seq=1",
                rs -> rs.getBytes(1)).orElseThrow());
        assertArrayEquals(Hashes.sha256("sangam-audit-v1:t1:root-1".getBytes(StandardCharsets.UTF_8)), firstPrev);
    }

    /**
     * SG-15 T-SCALE: on PostgreSQL an audit append is two round trips (lock+read+clock, then event+payload+head) and
     * an outbox append three (allocate root_seq, outbox row, delivery batch), for a new and for an existing root;
     * before the fix they were six and five. The chain stays gapless and verifies.
     */
    @Test
    void appendRoundTripsOnPostgres() {
        Outbox outbox = new Outbox(PG);
        int[] counts = new int[4];
        run(c -> {
            org.junit.jupiter.api.Assumptions.assumeTrue(isPostgres(c), "PostgreSQL single-statement path");
            Connection cc = counting(c, counts, 0);
            audit.append(cc, "t1", "root-rt", "A", Map.of("n", 1));
        });
        run(c -> audit.append(counting(c, counts, 1), "t1", "root-rt", "A", Map.of("n", 2)));
        run(c -> outbox.append(counting(c, counts, 2), "t1", "root-rt2", "E", "{}", List.of(Outbox.Destination.ordered("d1"))));
        run(c -> outbox.append(counting(c, counts, 3), "t1", "root-rt2", "E", "{}",
                List.of(Outbox.Destination.ordered("d1"), Outbox.Destination.unordered("d2"))));
        assertEquals(2, counts[0], "audit append, new root");
        assertEquals(2, counts[1], "audit append, existing root");
        assertEquals(3, counts[2], "outbox append, new root, one destination");
        assertEquals(3, counts[3], "outbox append, existing root, two destinations");
        AuditChain.VerifyResult r = inTx(c -> audit.verify(c, "t1", "root-rt"));
        assertTrue(r.ok(), String.valueOf(r));
        assertEquals(2, r.eventsChecked());
        assertEquals(List.of(1L, 2L), inTx(c -> Jdbc.query(c, "SELECT root_seq FROM OUTBOX WHERE root_id='root-rt2' ORDER BY root_seq",
                rs -> rs.getLong(1))));
    }

    /**
     * SG-15: a root written by the multi-statement path (every row before the upgrade) and then by the single-statement
     * path verifies as one gapless chain, and so do outbox root_seq values across the switch.
     */
    @Test
    void mixedOldAndNewPathHistoryVerifies() {
        Outbox outbox = new Outbox(PG);
        for (int i = 1; i <= 3; i++) {
            int n = i;
            run(c -> {
                org.junit.jupiter.api.Assumptions.assumeTrue(isPostgres(c), "PostgreSQL single-statement path");
                Connection old = asNonPostgres(c);
                audit.append(old, "t1", "root-mix", "OLD", Map.of("n", n));
                outbox.append(old, "t1", "root-mix", "E", "{}", List.of(Outbox.Destination.ordered("d1")));
            });
        }
        for (int i = 4; i <= 6; i++) {
            int n = i;
            run(c -> {
                audit.append(c, "t1", "root-mix", "NEW", Map.of("n", n));
                outbox.append(c, "t1", "root-mix", "E", "{}", List.of(Outbox.Destination.ordered("d1")));
            });
        }
        run(c -> audit.append(asNonPostgres(c), "t1", "root-mix", "OLD", Map.of("n", 7)));
        AuditChain.VerifyResult r = inTx(c -> audit.verify(c, "t1", "root-mix"));
        assertTrue(r.ok(), String.valueOf(r));
        assertEquals(7, r.eventsChecked());
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L), inTx(c -> Jdbc.query(c,
                "SELECT root_seq FROM OUTBOX WHERE root_id='root-mix' ORDER BY root_seq", rs -> rs.getLong(1))));
    }

    /** The same PostgreSQL connection, reporting another product name: forces the multi-statement (pre-SG-15) path. */
    private static Connection asNonPostgres(Connection c) {
        return (Connection) java.lang.reflect.Proxy.newProxyInstance(AuditChainIT.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (p, m, a) -> {
                    if (m.getName().equals("getMetaData")) {
                        java.sql.DatabaseMetaData md = c.getMetaData();
                        return java.lang.reflect.Proxy.newProxyInstance(AuditChainIT.class.getClassLoader(),
                                new Class<?>[] {java.sql.DatabaseMetaData.class}, (p2, m2, a2) ->
                                        m2.getName().equals("getDatabaseProductName") ? "PostgreSQL-legacy-path" : m2.invoke(md, a2));
                    }
                    try {
                        return m.invoke(c, a);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private static boolean isPostgres(Connection c) {
        try {
            return "PostgreSQL".equals(c.getMetaData().getDatabaseProductName());
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Counts prepared statements created on {@code c} (each one is executed once by Jdbc: one round trip). */
    private static Connection counting(Connection c, int[] counts, int slot) {
        return (Connection) java.lang.reflect.Proxy.newProxyInstance(AuditChainIT.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (p, m, a) -> {
                    if (m.getName().equals("prepareStatement")) {
                        counts[slot]++;
                    }
                    try {
                        return m.invoke(c, a);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    @Test
    void payloadTamperIsDetected() {
        appendFive("root-2");
        run(c -> Jdbc.update(c, "UPDATE AUDIT_PAYLOAD SET payload = REPLACE(payload, '1250.50', '9250.50')"
                + " WHERE event_id = (SELECT event_id FROM AUDIT_EVENT WHERE chain_id='root-2' AND seq=3)"));
        AuditChain.VerifyResult r = inTx(c -> audit.verify(c, "t1", "root-2"));
        assertFalse(r.ok());
        assertEquals(3L, r.firstBadSeq());
    }

    @Test
    void envelopeOrColumnTamperIsDetected() {
        appendFive("root-3");
        run(c -> Jdbc.update(c, "UPDATE AUDIT_EVENT SET event_type='TASK_REJECTED' WHERE chain_id='root-3' AND seq=2"));
        AuditChain.VerifyResult r = inTx(c -> audit.verify(c, "t1", "root-3"));
        assertFalse(r.ok());
        assertEquals(2L, r.firstBadSeq());

        appendFive("root-3b");
        run(c -> Jdbc.update(c, "UPDATE AUDIT_EVENT SET envelope = REPLACE(envelope, 'TASK_APPROVED', 'TASK_REJECTED'),"
                + " event_type='TASK_REJECTED' WHERE chain_id='root-3b' AND seq=4"));
        AuditChain.VerifyResult r2 = inTx(c -> audit.verify(c, "t1", "root-3b"));
        assertFalse(r2.ok());
        assertEquals(4L, r2.firstBadSeq(), r2.reason());
    }

    @Test
    void deletedMiddleEventAndTruncatedTailAreDetected() {
        appendFive("root-4");
        run(c -> Jdbc.update(c, "DELETE FROM AUDIT_EVENT WHERE chain_id='root-4' AND seq=3"));
        AuditChain.VerifyResult gap = inTx(c -> audit.verify(c, "t1", "root-4"));
        assertFalse(gap.ok());
        assertEquals(3L, gap.firstBadSeq());

        appendFive("root-5");
        run(c -> Jdbc.update(c, "DELETE FROM AUDIT_EVENT WHERE chain_id='root-5' AND seq=5"));
        AuditChain.VerifyResult tail = inTx(c -> audit.verify(c, "t1", "root-5"));
        assertFalse(tail.ok(), "the ROOT_SEQ head detects a truncated tail");
    }

    @Test
    void erasureKeepsTheChainVerifiable() {
        appendFive("root-6");
        String eventId = inTx(c -> Jdbc.one(c, "SELECT event_id FROM AUDIT_EVENT WHERE chain_id='root-6' AND seq=2",
                rs -> rs.getString(1)).orElseThrow());
        run(c -> audit.erasePayload(c, eventId, "DPDP erasure request"));
        assertTrue(inTx(c -> audit.verify(c, "t1", "root-6")).ok());
    }

    @Test
    void concurrentAppendersOnOneRootProduceAGaplessValidChain() throws Exception {
        List<CompletableFuture<Void>> fs = new ArrayList<>();
        for (int t = 0; t < 6; t++) {
            int id = t;
            fs.add(CompletableFuture.runAsync(() -> {
                for (int i = 0; i < 10; i++) {
                    int n = i;
                    run(c -> audit.append(c, "t1", "root-hot", "TASK_CLAIMED", Map.of("worker", id, "i", n)));
                }
            }));
        }
        CompletableFuture.allOf(fs.toArray(CompletableFuture[]::new)).get(60, TimeUnit.SECONDS);
        AuditChain.VerifyResult r = inTx((Connection c) -> audit.verify(c, "t1", "root-hot"));
        assertTrue(r.ok(), String.valueOf(r));
        assertEquals(60, r.eventsChecked());
    }
}
