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
