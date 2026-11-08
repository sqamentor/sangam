// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.contracts;

import org.sangam.core.layer.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Outbox.Destination;
import org.sangam.core.layer.Outbox.OutboxEvent;

import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** VI.2: root_seq write path, state-based claim, epoch fencing, sweeper, poison / park, ordered vs unordered. */
class OutboxRelayIT extends LayerSpecSupport {

    static final List<Destination> HISTORY = List.of(Destination.ordered("history"));

    final Outbox outbox = new Outbox(PG);
    final List<String> published = Collections.synchronizedList(new ArrayList<>());
    final List<OutboxEvent> publishedEvents = Collections.synchronizedList(new ArrayList<>());

    /**
     * The class runs PER_CLASS (LayerSpecSupport), so the recording lists are shared by every test: each test starts
     * with empty lists, as the tables are truncated per test.
     */
    @BeforeEach
    void clearRecordings() {
        published.clear();
        publishedEvents.clear();
    }

    Relay relay(String node, Publisher p, Duration leaseTtl, int maxAttempts, AuditChain audit) {
        return new Relay(PG, TX, p, new Relay.Config(node, 50, leaseTtl, maxAttempts, Duration.ZERO, Duration.ZERO), audit);
    }

    Publisher recording() {
        return (dest, ev) -> {
            published.add(dest + ":" + ev.rootId() + "#" + ev.rootSeq());
            publishedEvents.add(ev);
        };
    }

    @Test
    void lateCommitWithALowerOutboxIdIsStillDelivered() throws Exception {
        Relay relay = relay("n1", recording(), Duration.ofSeconds(30), 10, null);
        Outbox.Appended a;
        try (Connection slow = openTx()) {
            a = outbox.append(slow, "t1", "rootA", "TASK_CREATED", "{}", HISTORY);       // lower id, commits later
            Outbox.Appended b = inTx(c -> outbox.append(c, "t1", "rootB", "TASK_CREATED", "{}", HISTORY));
            assertTrue(a.outboxId() < b.outboxId());
            relay.drain(10);
            assertEquals(List.of("history:rootB#1"), published);
            slow.commit();
        }
        relay.drain(10);
        assertEquals(List.of("history:rootB#1", "history:rootA#1"), published, "an id watermark would have skipped rootA#1");
        assertEquals("DONE", inTx(c -> Relay.state(c, a.outboxId(), "history")));
    }

    @Test
    void sameRootWritersAreSerializedSoRootSeqOrderIsCommitOrder() throws Exception {
        Relay relay = relay("n1", recording(), Duration.ofSeconds(30), 10, null);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection first = openTx()) {
            Outbox.Appended s1 = outbox.append(first, "t1", "rootR", "E1", "{}", HISTORY);
            assertEquals(1, s1.rootSeq());
            Future<Outbox.Appended> second = pool.submit(() -> inTx(c -> outbox.append(c, "t1", "rootR", "E2", "{}", HISTORY)));
            assertThrows(TimeoutException.class, () -> second.get(400, TimeUnit.MILLISECONDS), "second writer must wait on the root row");
            relay.drain(10);
            assertTrue(published.isEmpty(), "nothing of rootR is visible yet");
            Thread.sleep(100);
            first.commit();                                    // seq 1 commits; only now can seq 2 be allocated
            assertEquals(2, second.get(5, TimeUnit.SECONDS).rootSeq());
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(30, TimeUnit.SECONDS);   // the second writer has committed or given up
        }
        relay.drain(10);
        assertEquals(List.of("history:rootR#1", "history:rootR#2"), published);
    }

    @Test
    void relayCrashAfterPublishDuplicatesAndConsumerDedupsOnEventId() throws Exception {
        Outbox.Appended ev = inTx(c -> outbox.append(c, "t1", "rootC", "TASK_COMPLETED", "{\"n\":1}", HISTORY));
        Relay crashing = relay("n1", recording(), Duration.ofMillis(100), 10, null);
        Relay healthy = relay("n2", recording(), Duration.ofSeconds(30), 10, null);

        List<Relay.Claimed> claimed = crashing.claim();
        assertEquals(1, claimed.size());
        Relay.Claimed mine = claimed.get(0);
        recording().publish(mine.destination(), mine.event());     // published ... then the node dies before mark
        assertEquals(0, healthy.claim().size(), "a live claim is not stolen");

        Thread.sleep(300);
        assertEquals(1, healthy.sweep());                         // lease expired -> PENDING
        Relay.CycleResult r = healthy.runOnce();
        assertEquals(1, r.published());
        assertFalse(crashing.markDone(mine), "the old epoch is fenced out");
        assertEquals("DONE", inTx(c -> Relay.state(c, ev.outboxId(), "history")));

        assertEquals(2, publishedEvents.size());
        assertEquals(publishedEvents.get(0).eventId(), publishedEvents.get(1).eventId());
        // the consumer dedups on event_id through its inbox
        Inbox consumerInbox = new Inbox(PG, TX, "consumer", Duration.ofSeconds(30), Duration.ofSeconds(1), null);
        List<Inbox.ReceiveResult> results = new ArrayList<>();
        for (OutboxEvent e : publishedEvents) {
            results.add(consumerInbox.receive("t1", "sangam.history", e.eventId().toString(), e.rootId(), e.payload(), Duration.ofHours(24)));
        }
        assertEquals(List.of(Inbox.ReceiveResult.ACCEPTED, Inbox.ReceiveResult.DUPLICATE), results);
    }

    @Test
    void poisonParksAndBlocksOrderedDeliveryForThatRootOnly() {
        AuditChain audit = new AuditChain(PG);
        Publisher poisonAware = (dest, ev) -> {
            if (ev.payload().contains("poison")) throw new IllegalStateException("broker rejects payload");
            recording().publish(dest, ev);
        };
        Relay relay = relay("n1", poisonAware, Duration.ofSeconds(30), 3, audit);
        List<Destination> both = List.of(Destination.ordered("history"), Destination.unordered("webhook"));
        Outbox.Appended a1 = inTx(c -> outbox.append(c, "t1", "rootA", "E", "{\"poison\":true}", both));
        Outbox.Appended a2 = inTx(c -> outbox.append(c, "t1", "rootA", "E", "{\"ok\":2}", both));
        Outbox.Appended b1 = inTx(c -> outbox.append(c, "t1", "rootB", "E", "{\"ok\":1}", HISTORY));

        relay.drain(50);
        Map<String, String> states = inTx(c -> {
            Map<String, String> m = new HashMap<>();
            m.put("a1.history", Relay.state(c, a1.outboxId(), "history"));
            m.put("a2.history", Relay.state(c, a2.outboxId(), "history"));
            m.put("a1.webhook", Relay.state(c, a1.outboxId(), "webhook"));
            m.put("a2.webhook", Relay.state(c, a2.outboxId(), "webhook"));
            m.put("b1.history", Relay.state(c, b1.outboxId(), "history"));
            return m;
        });
        assertEquals("PARKED", states.get("a1.history"));
        assertEquals("PENDING", states.get("a2.history"), "blocked behind the parked head of its line");
        assertEquals("SKIPPED", states.get("a1.webhook"), "unordered: skipped with a gap record");
        assertEquals("DONE", states.get("a2.webhook"));
        assertEquals("DONE", states.get("b1.history"), "other roots are not blocked");
        assertFalse(published.contains("history:rootA#2"));
        assertTrue(inTx(c -> Jdbc.one(c, "SELECT last_error FROM OUTBOX_DELIVERY WHERE outbox_id=? AND destination='webhook'",
                rs -> rs.getString(1), a1.outboxId()).orElseThrow()).startsWith("GAP:"));
        assertEquals(3, txInt(c -> Jdbc.one(c, "SELECT attempts FROM OUTBOX_DELIVERY WHERE outbox_id=? AND destination='history'",
                rs -> rs.getInt(1), a1.outboxId()).orElseThrow()));

        // audited repair: skip the parked head, the line continues
        assertTrue(relay.skip(a1.outboxId(), "history", "ops-1", "payload rejected by broker; re-sent manually"));
        relay.drain(10);
        assertEquals("DONE", inTx(c -> Relay.state(c, a2.outboxId(), "history")));
        assertTrue(published.contains("history:rootA#2"));
        List<String> auditTypes = inTx(c -> Jdbc.query(c, "SELECT event_type FROM AUDIT_EVENT WHERE chain_id='rootA' ORDER BY seq",
                rs -> rs.getString(1)));
        assertEquals(3, auditTypes.size());
        assertTrue(auditTypes.containsAll(List.of("OUTBOX_DELIVERY_SKIPPED", "OUTBOX_DELIVERY_PARKED")));
        assertEquals("OUTBOX_SKIP", auditTypes.get(2));
        assertTrue(inTx(c -> audit.verify(c, "t1", "rootA")).ok());
    }

    @Test
    void requeueGivesAParkedDeliveryAFreshBudget() {
        boolean[] healed = {false};
        Publisher flaky = (dest, ev) -> {
            if (!healed[0]) throw new IllegalStateException("down");
            recording().publish(dest, ev);
        };
        Relay relay = relay("n1", flaky, Duration.ofSeconds(30), 2, new AuditChain(PG));
        Outbox.Appended e1 = inTx(c -> outbox.append(c, "t1", "rootQ", "E", "{}", HISTORY));
        relay.drain(10);
        assertEquals("PARKED", inTx(c -> Relay.state(c, e1.outboxId(), "history")));
        healed[0] = true;
        assertTrue(relay.requeue(e1.outboxId(), "history", "ops-2", "broker recovered"));
        relay.drain(10);
        assertEquals("DONE", inTx(c -> Relay.state(c, e1.outboxId(), "history")));
    }

    @Test
    void concurrentRelaysPreservePerRootOrderForOrderedDestinations() throws Exception {
        int roots = 5, perRoot = 30;
        for (int i = 0; i < perRoot; i++) {
            for (int r = 0; r < roots; r++) {
                String root = "root" + r;
                run(c -> outbox.append(c, "t1", root, "E", "{}", HISTORY));
            }
        }
        Publisher p = recording();
        List<CompletableFuture<Void>> workers = new ArrayList<>();
        java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            for (int n = 0; n < 3; n++) {
                Relay relay = relay("n" + n, p, Duration.ofSeconds(30), 10, null);
                workers.add(CompletableFuture.runAsync(() -> {
                    int idle = 0;
                    while (idle < 3 && !stop.get()) {
                        if (relay.runOnce().claimed() == 0) idle++;
                        else idle = 0;
                    }
                }, pool));
            }
            CompletableFuture.allOf(workers.toArray(CompletableFuture[]::new)).get(60, TimeUnit.SECONDS);
        } finally {
            // no relay of this test may still hold row locks when the next test truncates the tables
            stop.set(true);
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "relay workers stopped");
        }
        assertEquals(roots * perRoot, published.size());
        Map<String, Integer> last = new HashMap<>();
        for (String s : published) {
            String root = s.substring(s.indexOf(':') + 1, s.indexOf('#'));
            int seq = Integer.parseInt(s.substring(s.indexOf('#') + 1));
            assertEquals(last.getOrDefault(root, 0) + 1, seq, "per-root order broken at " + s);
            last.put(root, seq);
        }
    }
}
