// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.contracts;

import org.sangam.core.layer.*;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** VI.2.6: dedup by (tenant, source, messageId), BUFFERED until a subscription appears, EXPIRED after the TTL. */
class InboxIT extends LayerSpecSupport {

    final AuditChain audit = new AuditChain(PG);
    final Inbox inbox = new Inbox(PG, TX, "n1", Duration.ofSeconds(30), Duration.ZERO, audit);

    @Test
    void duplicateDeliveryIsDroppedAndProcessedOnce() {
        assertEquals(Inbox.ReceiveResult.ACCEPTED, inbox.receive("t1", "kafka:payments", "m-1", "BILL-7", "{\"paid\":true}", Duration.ofHours(24)));
        assertEquals(Inbox.ReceiveResult.DUPLICATE, inbox.receive("t1", "kafka:payments", "m-1", "BILL-7", "{\"paid\":true}", Duration.ofHours(24)));
        // same message id from another source or tenant is a different message
        assertEquals(Inbox.ReceiveResult.ACCEPTED, inbox.receive("t1", "kafka:other", "m-1", "BILL-7", "{}", Duration.ofHours(24)));
        assertEquals(Inbox.ReceiveResult.ACCEPTED, inbox.receive("t2", "kafka:payments", "m-1", "BILL-7", "{}", Duration.ofHours(24)));

        AtomicInteger effects = new AtomicInteger();
        assertEquals(3, inbox.processOnce(10, (c, m) -> {
            effects.incrementAndGet();
            return true;
        }));
        // a redelivery after processing is still a duplicate, and nothing is re-processed
        assertEquals(Inbox.ReceiveResult.DUPLICATE, inbox.receive("t1", "kafka:payments", "m-1", "BILL-7", "{}", Duration.ofHours(24)));
        assertEquals(0, inbox.processOnce(10, (c, m) -> {
            effects.incrementAndGet();
            return true;
        }));
        assertEquals(3, effects.get());
        assertEquals(Inbox.State.PROCESSED, inTx(c -> Inbox.state(c, "t1", "kafka:payments", "m-1")));
    }

    @Test
    void concurrentDuplicateReceiveAcceptsExactlyOne() throws Exception {
        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CyclicBarrier barrier = new CyclicBarrier(n);
            List<Future<Inbox.ReceiveResult>> fs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                fs.add(pool.submit(() -> {
                    barrier.await();
                    return inbox.receive("t1", "mq", "dup-1", null, "{}", Duration.ofHours(1));
                }));
            }
            int accepted = 0;
            for (Future<Inbox.ReceiveResult> f : fs) {
                if (f.get() == Inbox.ReceiveResult.ACCEPTED) accepted++;
            }
            assertEquals(1, accepted);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void bufferedUntilASubscriptionAppearsOrExpiredAfterTtl() throws Exception {
        inbox.receive("t1", "mq", "early", "ORDER-1", "{}", Duration.ofHours(1));
        inbox.receive("t1", "mq", "orphan", "ORDER-2", "{}", Duration.ofMillis(300));
        AtomicBoolean subscribed = new AtomicBoolean(false);
        Inbox.Handler handler = (c, m) -> subscribed.get() && m.correlationKey().equals("ORDER-1");

        assertEquals(0, inbox.processOnce(10, handler));
        assertEquals(Inbox.State.BUFFERED, inTx(c -> Inbox.state(c, "t1", "mq", "early")));
        assertEquals(Inbox.State.BUFFERED, inTx(c -> Inbox.state(c, "t1", "mq", "orphan")));

        subscribed.set(true);                                   // the waiting instance's subscription now exists
        assertEquals(1, inbox.processOnce(10, handler));
        assertEquals(Inbox.State.PROCESSED, inTx(c -> Inbox.state(c, "t1", "mq", "early")));

        Thread.sleep(400);
        assertEquals(1, inbox.expire());
        assertEquals(Inbox.State.EXPIRED, inTx(c -> Inbox.state(c, "t1", "mq", "orphan")));
        assertEquals("INBOX_MESSAGE_EXPIRED", inTx(c -> Jdbc.one(c, "SELECT event_type FROM AUDIT_EVENT WHERE chain_id='sec:t1'",
                rs -> rs.getString(1)).orElseThrow()));
        assertEquals(0, inbox.processOnce(10, (c, m) -> true), "expired messages are never processed");
    }

    @Test
    void processingRollsBackWithTheHandlerAndAStaleClaimIsFenced() {
        inbox.receive("t1", "mq", "m-rb", "K", "{}", Duration.ofHours(1));
        assertThrows(IllegalStateException.class, () -> inbox.processOnce(10, (c, m) -> {
            throw new IllegalStateException("engine failure");
        }));
        // still CLAIMED (the claim committed separately); the sweeper releases it after the claim TTL
        assertEquals(Inbox.State.CLAIMED, inTx(c -> Inbox.state(c, "t1", "mq", "m-rb")));

        Inbox shortClaim = new Inbox(PG, TX, "n2", Duration.ofMillis(50), Duration.ZERO, null);
        run(c -> Jdbc.update(c, "UPDATE INBOX SET claim_expires_at = " + PG.nowPlusMillisParam(), -1000L));
        assertEquals(1, shortClaim.sweep());
        List<Inbox.Message> claimed = shortClaim.claim(10);
        assertEquals(1, claimed.size());
        Inbox.Message stale = new Inbox.Message("t1", "mq", "m-rb", "K", "{}", claimed.get(0).epoch() - 1);
        assertThrows(Inbox.ClaimLostException.class, () -> run(c -> shortClaim.markProcessed(c, stale)));
        run(c -> shortClaim.markProcessed(c, claimed.get(0)));
        assertEquals(Inbox.State.PROCESSED, inTx(c -> Inbox.state(c, "t1", "mq", "m-rb")));
    }
}
