// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.sangam.server.bridge.MediaQueueFixture.await;
import static org.sangam.server.bridge.MediaQueueFixture.media;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.channels.messenger.InboundMediaQueue;
import org.sangam.channels.messenger.MediaDownloads;

/**
 * Inbound media is parked, never dropped (SL-142, VI.2): a worker that stops in the middle of its queue leaves every
 * pending row in the database, a fresh worker finishes them, and the stopped worker's late completion changes nothing.
 * H2; written, not run (Wave 3).
 */
class ChannelMediaWorkerRestartH2Test {

    static final Duration LEASE = Duration.ofMinutes(10);

    MediaQueueFixture f;

    @BeforeEach
    void db() throws Exception {
        f = new MediaQueueFixture();
    }

    /** A provider call that hangs and ignores interrupts, like a stuck socket. */
    static void hang(CountDownLatch release) {
        while (true) {
            try {
                if (release.await(30, TimeUnit.SECONDS)) {
                    return;
                }
            } catch (InterruptedException e) {
                // keep hanging: the stop must not depend on this thread
            }
        }
    }

    @Test
    void aWorkerStoppedMidQueueLosesNothingAndTheRestartedNodeFinishesExactlyOnce() throws Exception {
        f.inbox("Ev1", "F1");
        f.inbox("Ev2", "F2");
        f.inbox("Ev3", "F3");
        CountDownLatch inF2 = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ChannelMediaWorker first = f.worker("node-1", LEASE, (t, e, ref) -> {
            if (ref.equals("F2")) {
                inF2.countDown();
                hang(release);
            }
            return media(ref);
        }, true, 1).start();
        assertTrue(inF2.await(10, TimeUnit.SECONDS));
        assertEquals(List.of("F1.pdf"), f.delivered());

        first.close();   // the node stops with Ev2 in work and Ev3 still queued
        assertEquals("DONE", f.state("Ev1"));
        assertEquals("CLAIMED", f.state("Ev2"), "still on the database, claimed by the stopped node");
        assertEquals("PENDING", f.state("Ev3"));
        assertEquals("F3", f.column("media_ref", "Ev3"));

        AtomicInteger secondCalls = new AtomicInteger();
        try (ChannelMediaWorker second = f.worker("node-1", LEASE, (t, e, ref) -> {
            secondCalls.incrementAndGet();
            return media(ref);
        }, true, 1).start()) {
            await(() -> f.delivered().size() == 3);
            assertEquals(List.of("F1.pdf", "F2.pdf", "F3.pdf"), f.delivered(),
                    "the restarted node takes back its own claim at once, without waiting for the lease");
            assertEquals(2, secondCalls.get());
            assertEquals("DONE", f.state("Ev2"));
            assertEquals("DONE", f.state("Ev3"));
        }

        release.countDown();   // the stopped worker's stuck download returns now and tries to finish Ev2
        assertTrue(first.awaitStopped(Duration.ofSeconds(10)));
        assertEquals(List.of("F1.pdf", "F2.pdf", "F3.pdf"), f.delivered(), "its late completion is fenced off by the claim epoch");
        assertEquals("DONE", f.state("Ev2"));
        assertTrue(f.failures.isEmpty());
    }

    @Test
    void anotherNodeTakesTheRowsOfANodeThatNeverCameBackAfterTheLease() {
        f.inbox("Ev4", "F4");
        f.inbox("Ev5", "F5");
        InboundMediaQueue dead = f.queue("node-dead", Duration.ofMillis(200));
        List<InboundMediaQueue.Pending> taken = dead.claim(10);
        assertEquals(2, taken.size(), "the node claims both rows and then disappears");
        assertEquals("CLAIMED", f.state("Ev4"));

        try (ChannelMediaWorker other = f.worker("node-2", LEASE, (t, e, ref) -> media(ref), true, 2).start()) {
            await(() -> f.delivered().size() == 2);
            assertEquals(List.of("F4.pdf", "F5.pdf"), f.delivered());
            assertEquals("2", f.column("media_epoch", "Ev4"), "taken with the next epoch");
        }
        for (InboundMediaQueue.Pending p : taken) {
            boolean completed = f.tx.inTransaction(c -> dead.complete(c, p));
            assertFalse(completed, "the vanished node's claim no longer counts");
            assertFalse(dead.retry(p, Duration.ZERO, "late", null));
        }
        assertEquals("DONE", f.state("Ev4"));
        assertEquals("DONE", f.state("Ev5"));
    }

    @Test
    void aRetryScheduledBeforeTheStopIsKeptAcrossTheRestart() {
        f.inbox("Ev6", "F6");
        AtomicInteger firstCalls = new AtomicInteger();
        try (ChannelMediaWorker first = new ChannelMediaWorker(f.queue("node-1", LEASE), f.tx, t -> MediaQueueFixture.ENTRIES, (t, e, ref) -> {
            firstCalls.incrementAndGet();
            throw new MediaDownloads.MediaUnavailableException("HTTP 503", true);
        }, f.store, (n, b) -> true, f.bot, 1, Duration.ofMillis(50), List.of(Duration.ofMinutes(5), Duration.ofMinutes(5)),
                (c, m, reason) -> f.failures.add(m.messageId() + ":" + reason)).start()) {
            await(() -> "1".equals(f.column("media_attempt", "Ev6")));
            assertEquals(1, firstCalls.get());
            assertEquals("PENDING", f.state("Ev6"), "waiting for its retry on the database");
            assertEquals("HTTP 503", f.column("media_error", "Ev6"));
        }
        // the retry falls due (the 5 minutes elapse) while the node is down
        f.tx.inTransaction(c -> org.sangam.core.layer.Jdbc.update(c, "UPDATE CH_INBOUND_SEEN SET media_next_at=CURRENT_TIMESTAMP WHERE message_id='Ev6'"));
        try (ChannelMediaWorker second = f.worker("node-1", LEASE, (t, e, ref) -> media(ref), true, 1).start()) {
            await(() -> "DONE".equals(f.state("Ev6")));
            assertEquals(List.of("F6.pdf"), f.delivered());
            assertEquals("1", f.column("media_attempt", "Ev6"), "the attempt count survived the restart");
        }
        assertTrue(f.failures.isEmpty());
    }
}
