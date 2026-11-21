// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.sangam.core.api.EngineAdapter;
import org.sangam.core.api.EngineAdapter.TaskView;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Inbox;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Outbox;
import org.sangam.core.layer.Publisher;
import org.sangam.core.layer.Relay;
import org.sangam.core.layer.Transactions;

/**
 * E5 (SANGAM.md XI.4, VI.2) on Sangam: an outbox row appended in the same engine command as a task completion of the
 * server's engine adapter; sangam-core's relay claims/publishes/marks in short engine transactions; the inbox buffers an
 * early clarification message and correlates it (through the adapter) in the transaction that marks it PROCESSED.
 *
 * <p>Runs on the server's real Liquibase tables with its background relay, inbox and maintenance running. The test's
 * destinations are run-unique and registered on the server's {@code PublisherRegistry}, so whichever relay (the
 * server's or this test's) delivers an event, the dedup consumer sees it; the test relay publishes through the same
 * registry, so rows of other destinations it claims go to their real publishers. The server's history handler appends
 * its own events for the same roots; counts are taken exactly over this test's event type / event ids.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class E5OutboxInboxIT extends ContractSupport {

    static final String EVENT = "acc.checkerReview.completed";
    /** Run-unique destinations, registered on the server's PublisherRegistry so the server relay delivers them too. */
    static final String HISTORY = key("history");
    static final String WEBHOOK = key("webhook");
    static final List<Outbox.Destination> DESTINATIONS =
            List.of(Outbox.Destination.ordered(HISTORY), Outbox.Destination.unordered(WEBHOOK));

    final String T = key("tn-e5");
    final String checkerKey = key("e5-checker");
    final String licenceKey = key("e5-licence");
    final String message = key("applicantResponse");

    /** Consumer that dedups on event_id (VI.2.3 rule 4), counting raw deliveries and applied effects. */
    static final class DedupConsumer implements Publisher {
        final Map<String, Integer> deliveries = new ConcurrentHashMap<>();
        final Set<String> applied = ConcurrentHashMap.newKeySet();

        @Override
        public void publish(String destination, Outbox.OutboxEvent event) {
            String k = destination + "/" + event.eventId();
            deliveries.merge(k, 1, Integer::sum);
            applied.add(k);   // idempotent apply (upsert by event_id)
        }

        int deliveries(String destination, UUID eventId) {
            return deliveries.getOrDefault(destination + "/" + eventId, 0);
        }

        boolean applied(String destination, UUID eventId) {
            return applied.contains(destination + "/" + eventId);
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    org.sangam.integration.relay.PublisherRegistry publishers;

    @org.springframework.beans.factory.annotation.Autowired
    org.sangam.integration.inbox.InboxService inboxService;

    Transactions etx;
    Outbox outbox;
    DedupConsumer consumer;
    Relay relay;
    Inbox inbox;

    @BeforeAll
    void setUp() {
        etx = Transactions.of(engine);
        deploy(T, "e5-checker.bpmn", Models.checkerFlow(checkerKey));
        deploy(T, "e5-licence.bpmn", Models.clarification(licenceKey, message));
        AuditChain audit = new AuditChain(dialect());
        outbox = new Outbox(dialect());
        consumer = new DedupConsumer();
        publishers.register(HISTORY, consumer);
        publishers.register(WEBHOOK, consumer);
        relay = new Relay(dialect(), etx, publishers,
                new Relay.Config("relay-1", 100, Duration.ofMillis(500), 3, Duration.ofMillis(20), Duration.ofSeconds(1)), audit);
        inbox = new Inbox(dialect(), etx, "inbox-1", Duration.ofSeconds(5), Duration.ofMillis(1), audit);
    }

    private String startChecker(String bk) {
        return engine.startProcess(T, checkerKey, bk, Map.of("billId", bk));
    }

    private TaskView singleTask(String pid, String taskKey) {
        List<TaskView> tasks = engine.queryTasks(new EngineAdapter.TaskQuery(T, null, null, pid, taskKey, 0, 10));
        if (tasks.size() != 1) {
            throw new IllegalStateException("expected one task " + taskKey + " in " + pid + ", found " + tasks.size());
        }
        return tasks.get(0);
    }

    private long myOutboxRows(String rootId) {
        return inTx(c -> Jdbc.one(c, "SELECT count(*) FROM OUTBOX WHERE tenant_id = ? AND root_id = ? AND event_type = ?",
                rs -> rs.getLong(1), T, rootId, EVENT).orElse(0L));
    }

    private String state(long outboxId, String destination) {
        return inTx(c -> Relay.state(c, outboxId, destination));
    }

    private Outbox.Appended completeAndAppend(String pid, String taskId, Map<String, Object> vars, RuntimeException failAfter,
                                              boolean appendFirst) {
        return inEngineTx(c -> {
            Outbox.Appended a = null;
            if (appendFirst) {
                a = outbox.append(c, T, pid, EVENT, "{\"taskId\":\"" + taskId + "\"}", DESTINATIONS);
            }
            engine.completeTask(taskId, vars);
            if (!appendFirst) {
                a = outbox.append(c, T, pid, EVENT, "{\"taskId\":\"" + taskId + "\"}", DESTINATIONS);
            }
            if (failAfter != null) {
                throw failAfter;
            }
            return a;
        });
    }

    @Test
    @Order(1)
    void e5_1_appendWithCompletionThenRelay() {
        String pid = startChecker(key("e5-1"));
        TaskView task = singleTask(pid, "checkerReview");
        Outbox.Appended a = completeAndAppend(pid, task.id(), Map.of("checkerDecision", "forward"), null, false);
        assertEquals(1, myOutboxRows(pid));
        assertTrue(engine.getInstance(pid).activeActivityIds().contains("approve"), "engine command committed");
        relay.runOnce();
        assertEquals("DONE", state(a.outboxId(), HISTORY));
        assertEquals("DONE", state(a.outboxId(), WEBHOOK));
        assertEquals(1, consumer.deliveries(HISTORY, a.eventId()));
        assertEquals(1, consumer.deliveries(WEBHOOK, a.eventId()));
        assertTrue(consumer.applied(HISTORY, a.eventId()));
    }

    @Test
    @Order(2)
    void e5_2_crashBetweenPublishAndMark() throws Exception {
        String pid = startChecker(key("e5-2"));
        TaskView task = singleTask(pid, "checkerReview");
        Outbox.Appended a = completeAndAppend(pid, task.id(), Map.of("checkerDecision", "forward"), null, false);
        List<Relay.Claimed> claimed = relay.claim().stream().filter(cl -> cl.event().eventId().equals(a.eventId())).toList();
        assertEquals(2, claimed.size());
        for (Relay.Claimed cl : claimed) {
            consumer.publish(cl.destination(), cl.event());   // published ... then the relay node dies before mark
        }
        assertEquals("CLAIMED", state(a.outboxId(), HISTORY));
        Thread.sleep(800);   // claim lease 500 ms
        int reset = relay.sweep();
        // The server's background relay sweeps and claims on the same table (class javadoc), so after the expiry this
        // event's rows may already be PENDING, re-claimed under a newer epoch, or DONE, whichever sweeper got there
        // first (CI 44022815109: on SQL Server the server sweeper reset them before ours). The contract is that the dead
        // claim no longer holds them: no row is still CLAIMED under the epoch the dead node was given.
        long released = 0;
        for (Relay.Claimed cl : claimed) {
            released += inTx(c -> Jdbc.one(c, "SELECT count(*) FROM OUTBOX_DELIVERY WHERE outbox_id = ? AND destination = ?"
                            + " AND NOT (state = 'CLAIMED' AND claim_epoch = ?)",
                    rs -> rs.getLong(1), a.outboxId(), cl.destination(), cl.epoch()).orElse(0L));
        }
        assertEquals(2, released, "both expired claims of this event released from the dead node (our sweep reset " + reset + ")");
        relay.drain(5);
        for (String d : List.of(HISTORY, WEBHOOK)) {
            assertEquals("DONE", state(a.outboxId(), d));
            assertEquals(2, consumer.deliveries(d, a.eventId()), "published twice (at-least-once)");
            assertTrue(consumer.applied(d, a.eventId()));
        }
    }

    @Test
    @Order(3)
    void e5_3_rollbackRemovesOutboxRow() {
        String pid = startChecker(key("e5-3"));
        TaskView task = singleTask(pid, "checkerReview");
        long seqBefore = inTx(c -> Jdbc.one(c, "SELECT coalesce(max(next_outbox_seq),0) FROM ROOT_SEQ WHERE tenant_id=? AND root_id=?",
                rs -> rs.getLong(1), T, pid).orElse(0L));

        assertThrows(IllegalStateException.class, () -> completeAndAppend(pid, task.id(), Map.of("checkerDecision", "forward"),
                new IllegalStateException("crash after completeTask + append"), false));
        long afterCrash = myOutboxRows(pid);
        boolean taskOpenAfterCrash = !engine.queryTasks(new EngineAdapter.TaskQuery(T, null, null, pid, "checkerReview", 0, 10)).isEmpty();

        // engine failure: the gateway after the checker task needs checkerDecision; the append ran before the failure
        assertThrows(RuntimeException.class, () -> completeAndAppend(pid, task.id(), Map.of(), null, true));
        long afterEngineFailure = myOutboxRows(pid);
        boolean taskOpenAfterFailure = !engine.queryTasks(new EngineAdapter.TaskQuery(T, null, null, pid, "checkerReview", 0, 10)).isEmpty();
        long seqAfter = inTx(c -> Jdbc.one(c, "SELECT coalesce(max(next_outbox_seq),0) FROM ROOT_SEQ WHERE tenant_id=? AND root_id=?",
                rs -> rs.getLong(1), T, pid).orElse(0L));

        assertEquals(0, afterCrash);
        assertTrue(taskOpenAfterCrash);
        assertEquals(0, afterEngineFailure);
        assertTrue(taskOpenAfterFailure);
        assertEquals(seqBefore, seqAfter, "root_seq allocation rolled back as well");
        // only the deliveries of this test's event type: the server's own history events of the root are not ours
        assertEquals(0L, (long) inTx(c -> Jdbc.one(c, "SELECT count(*) FROM OUTBOX_DELIVERY d JOIN OUTBOX o"
                        + " ON o.id = d.outbox_id WHERE d.root_id = ? AND o.event_type = ?",
                rs -> rs.getLong(1), pid, EVENT).orElse(0L)));
    }

    @Test
    @Order(4)
    void e5_4_lateLowerIdCommitDelivered() throws Exception {
        String pidA = startChecker(key("e5-4a"));
        String pidB = startChecker(key("e5-4b"));
        String taskA = singleTask(pidA, "checkerReview").id();
        String taskB = singleTask(pidB, "checkerReview").id();
        CountDownLatch appended = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService ex = Executors.newSingleThreadExecutor();
        Future<Outbox.Appended> slow = ex.submit(() -> inEngineTx(c -> {
            engine.completeTask(taskA, Map.of("checkerDecision", "forward"));
            Outbox.Appended a = outbox.append(c, T, pidA, EVENT, "{}", DESTINATIONS);
            appended.countDown();
            await(release);
            return a;
        }));
        assertTrue(appended.await(30, TimeUnit.SECONDS));
        Outbox.Appended b = completeAndAppend(pidB, taskB, Map.of("checkerDecision", "forward"), null, false);
        relay.runOnce();
        boolean bOnFirstPass = consumer.applied(HISTORY, b.eventId()) && consumer.applied(WEBHOOK, b.eventId());
        release.countDown();
        Outbox.Appended a = slow.get(30, TimeUnit.SECONDS);
        ex.shutdown();
        boolean aOnFirstPass = consumer.applied(HISTORY, a.eventId()) || consumer.applied(WEBHOOK, a.eventId());
        assertTrue(a.outboxId() < b.outboxId(), "A got the lower id but committed later");
        assertTrue(bOnFirstPass, "B's two deliveries were published on the first pass");
        assertFalse(aOnFirstPass, "A was not visible on the first pass");
        // Run 44462054979: the server's relay held the claim on A's delivery while this test drained, so drain skipped
        // it and the committed state was still PENDING (in flight). Reproduced by e5_4r. The contract is the settled
        // state, whichever relay delivers A: both deliveries DONE.
        awaitDelivered(a);
        assertEquals("DONE", state(a.outboxId(), HISTORY));
        assertEquals("DONE", state(a.outboxId(), WEBHOOK));
        assertTrue(consumer.applied(HISTORY, a.eventId()));
    }

    /**
     * Run 44462054979 (sqlserver2025, e5_4): right after A committed, the server's background relay held the claim on
     * A's delivery while this test drained. The test relay skipped the locked row (SKIP LOCKED / READPAST), claimed
     * nothing, so drain stopped at once, and the committed state was still PENDING. Here a second connection plays that
     * in-flight claim, so the interleaving happens on every run.
     */
    @Test
    @Order(4)
    void e5_4r_committedEventWhileAnotherRelayHoldsTheClaim() throws Exception {
        String pid = startChecker(key("e5-4r"));
        Outbox.Appended a = completeAndAppend(pid, singleTask(pid, "checkerReview").id(),
                Map.of("checkerDecision", "forward"), null, false);
        Connection competitor = holdCompetingClaim(c -> Jdbc.query(c, dialect().skipLockedSelect("d.outbox_id",
                        "OUTBOX_DELIVERY", "d", "d.outbox_id = ? AND d.destination = ? AND d.state = 'PENDING'", "d.outbox_id", 1),
                rs -> rs.getLong(1), a.outboxId(), HISTORY), List.of(a.outboxId()),
                () -> "PENDING".equals(state(a.outboxId(), HISTORY)));
        if (competitor != null) {   // null: the server's relay delivered A first; the interleaving is gone this run
            try {
                relay.drain(5);
                assertEquals("PENDING", state(a.outboxId(), HISTORY),
                        "the competing claim is not committed: the delivery is in flight, skipped, not published twice");
                assertFalse(consumer.applied(HISTORY, a.eventId()), "a claimed delivery is not published by this relay");
            } finally {
                competitor.rollback();   // the competing relay crashed mid-claim; its claim never committed
                competitor.close();
            }
        }
        awaitDelivered(a);
        assertEquals("DONE", state(a.outboxId(), HISTORY));
        assertEquals("DONE", state(a.outboxId(), WEBHOOK));
        assertTrue(consumer.applied(HISTORY, a.eventId()));
    }

    /**
     * The settled state of a committed event is DONE on each destination, whichever relay (this test's or the server's
     * background relay) delivers it; PENDING and CLAIMED are in-flight states of a competing claim and the delivery must
     * never be PARKED or SKIPPED here. Drives this test's relay until both deliveries settle (at most 30 s).
     */
    private void awaitDelivered(Outbox.Appended a) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            String h = state(a.outboxId(), HISTORY);
            String w = state(a.outboxId(), WEBHOOK);
            if ("DONE".equals(h) && "DONE".equals(w)) {
                return;
            }
            for (String s : List.of(h, w)) {
                assertTrue("DONE".equals(s) || "PENDING".equals(s) || "CLAIMED".equals(s), "delivery of A reached " + s);
            }
            relay.drain(5);
            Thread.sleep(200);
        }
    }

    private static void await(CountDownLatch l) {
        try {
            if (!l.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ inbox (VI.2.6)

    private final List<String> correlated = Collections.synchronizedList(new ArrayList<>());

    /** Correlates only when the instance waits at the message catch; otherwise the message stays BUFFERED. */
    private Inbox.Handler clarificationHandler() {
        return (Connection c, Inbox.Message msg) -> {
            long waiting = Jdbc.one(c, "SELECT count(*) FROM ACT_RU_EVENT_SUBSCR s JOIN ACT_RU_EXECUTION p ON p.ID_ = s.PROC_INST_ID_"
                            + " WHERE s.EVENT_TYPE_ = 'message' AND s.EVENT_NAME_ = ?"
                            + " AND p.BUSINESS_KEY_ = ? AND p.TENANT_ID_ = ?",
                    rs -> rs.getLong(1), message, msg.correlationKey(), msg.tenantId()).orElse(0L);
            if (waiting == 0) {
                return false;
            }
            String pid = engine.correlateMessage(msg.tenantId(), message, msg.correlationKey(),
                    Map.of("applicantResponseNote", msg.payload()));
            correlated.add(pid);
            return true;
        };
    }

    /**
     * Deterministic reproduction of run 44233540674 (postgres: expected BUFFERED, was RECEIVED). The server's own inbox
     * loop claims every due row; while its claim transaction holds the row (skip-locked select, not yet committed), this
     * test's {@code processOnce} skips the row and returns 0, and the committed state is still RECEIVED. Here a second
     * connection plays that in-flight claim, so the interleaving happens on every run.
     */
    @Test
    @Order(6)
    void e5_5r_earlyMessageWhileAnotherInboxHoldsTheClaim() throws Exception {
        String bk = key("lic-e5-5r");
        String source = key("applicant-portal");
        String msgId = key("msg-e5-5r");
        inboxService.registerSourceHandler(source, clarificationHandler());
        engine.startProcess(T, licenceKey, bk, Map.of("applicationId", bk));
        assertEquals(Inbox.ReceiveResult.ACCEPTED, inbox.receive(T, source, msgId, bk, "documents attached", Duration.ofHours(24)));
        try (Connection competitor = holdCompetingClaim(c -> claimInboxRow(c, source, msgId), List.of(msgId), () -> true)) {
            try {
                // the server's loop may have buffered the row before the competitor got it; either way it is held now
                Inbox.State held = inTx(c -> Inbox.state(c, T, source, msgId));
                assertTrue(held == Inbox.State.RECEIVED || held == Inbox.State.BUFFERED, "held row is " + held);
                int before = correlated.size();
                assertEquals(0, inbox.processOnce(10, clarificationHandler()), "a claimed row is skipped, never processed twice");
                assertEquals(held, inTx(c -> Inbox.state(c, T, source, msgId)),
                        "the competing claim is not committed: the row is in flight, its state unchanged");
                assertEquals(before, correlated.size());
            } finally {
                competitor.rollback();   // the competing inbox crashed mid-claim; its claim never committed
            }
        }
        int before = correlated.size();
        assertEquals(Inbox.State.BUFFERED, awaitEarlyBuffered(source, msgId));
        assertEquals(before, correlated.size(), "no subscription yet: nothing correlated");
    }

    /**
     * Opens a transaction that holds a competing skip-locked claim, as another node's inbox or relay would. The server's
     * own loop may hold or take the row first (it claims every due row), so a one-shot claim can find nothing; retry in a
     * fresh transaction until the claim returns {@code expected} (at most 30 s, 200 ms apart). Returns null once
     * {@code stillClaimable} says the row has settled elsewhere. The caller rolls back.
     */
    private <T> Connection holdCompetingClaim(java.util.function.Function<Connection, List<T>> claim, List<T> expected,
            java.util.function.BooleanSupplier stillClaimable) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (true) {
            if (!stillClaimable.getAsBoolean()) {
                return null;
            }
            Connection c = dataSource.getConnection();
            c.setAutoCommit(false);
            if (expected.equals(claim.apply(c))) {
                return c;
            }
            c.rollback();
            c.close();
            assertTrue(System.nanoTime() < deadline, "precondition: the competing claim never held the row");
            Thread.sleep(200);
        }
    }

    /** The skip-locked claim e5_5r's competing inbox takes on one message (the same predicate as core Inbox.claim). */
    private List<String> claimInboxRow(Connection c, String source, String msgId) {
        return Jdbc.query(c, dialect().skipLockedSelect("i.message_id", "INBOX", "i",
                        "i.tenant_id = ? AND i.source = ? AND i.message_id = ? AND i.state IN ('RECEIVED','BUFFERED')",
                        "i.received_at", 1),
                rs -> rs.getString(1), T, source, msgId);
    }

    /**
     * Run 44498412338 (oracle, e5_5r): the precondition "the competing claim holds the row" failed. The server's own
     * inbox loop claims every due row, whatever its source, so its claim on the new message could be in flight when
     * e5_5r's competitor asked for the row; SKIP LOCKED then skipped it and the competitor held nothing. Here a third
     * connection plays the server's in-flight claim and releases it a second later, so the interleaving happens on every
     * run.
     */
    @Test
    @Order(6)
    void e5_5rs_competingClaimSetupWhileTheServerInboxHoldsTheRow() throws Exception {
        String source = key("applicant-portal");
        String msgId = key("msg-e5-5rs");
        assertEquals(Inbox.ReceiveResult.ACCEPTED, inbox.receive(T, source, msgId, key("lic-e5-5rs"), "documents attached",
                Duration.ofHours(24)));
        Connection server = dataSource.getConnection();
        server.setAutoCommit(false);
        assertEquals(List.of(msgId), claimInboxRow(server, source, msgId), "precondition: the server-side claim holds the row");
        ExecutorService ex = Executors.newSingleThreadExecutor();
        Future<?> release = ex.submit(() -> {
            Thread.sleep(1000);
            server.rollback();
            server.close();
            return null;
        });
        try (Connection competitor = holdCompetingClaim(c -> claimInboxRow(c, source, msgId), List.of(msgId), () -> true)) {
            competitor.rollback();
        } finally {
            release.get(30, TimeUnit.SECONDS);
            ex.shutdown();
        }
    }

    /**
     * Before the instance waits at the message catch, the only settled state is BUFFERED, whichever inbox (this test's
     * or the server's own loop) claims the row. RECEIVED and CLAIMED are in-flight states of a competing claim; the row
     * must never be PROCESSED or EXPIRED here. Drives this test's inbox until the row settles.
     */
    private Inbox.State awaitEarlyBuffered(String source, String msgId) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        Inbox.State s = inTx(c -> Inbox.state(c, T, source, msgId));
        while (s != Inbox.State.BUFFERED && System.nanoTime() < deadline) {
            assertTrue(s == Inbox.State.RECEIVED || s == Inbox.State.CLAIMED, "early message reached " + s);
            assertEquals(0, inbox.processOnce(10, clarificationHandler()), "no subscription yet: nothing processed");
            s = inTx(c -> Inbox.state(c, T, source, msgId));
            if (s != Inbox.State.BUFFERED) {
                Thread.sleep(200);
            }
        }
        return s;
    }

    @Test
    @Order(5)
    void e5_5_earlyMessageBufferedThenCorrelated() throws InterruptedException {
        String bk = key("lic-e5-5");
        String source = key("applicant-portal");
        String msgId = key("msg-e5-5");
        inboxService.registerSourceHandler(source, clarificationHandler());   // the server inbox uses the same handler
        String pid = engine.startProcess(T, licenceKey, bk, Map.of("applicationId", bk));
        Inbox.ReceiveResult first = inbox.receive(T, source, msgId, bk, "documents attached", Duration.ofHours(24));
        int processedEarly = inbox.processOnce(10, clarificationHandler());
        assertEquals(Inbox.ReceiveResult.ACCEPTED, first);
        assertEquals(0, processedEarly);
        // Run 44233540674: the server's inbox loop (registered above) held the claim while this processOnce ran, so it
        // skipped the row and the committed state was still RECEIVED (in flight). Reproduced by e5_5r. The contract is
        // the settled state, whichever inbox claims it: BUFFERED, nothing correlated.
        assertEquals(Inbox.State.BUFFERED, awaitEarlyBuffered(source, msgId));
        assertTrue(correlated.isEmpty(), "no subscription yet: nothing correlated");

        engine.completeTask(singleTask(pid, "scrutiny").id(), Map.of("scrutinyDecision", "clarify"));
        assertTrue(engine.getInstance(pid).activeActivityIds().contains("awaitApplicantResponse"));

        // The server's own inbox loop processes this source too (same handler, registered above), with its own buffer
        // retry (InboxService.Settings: 5 s). When its poll finds the BUFFERED row before this test's next call, it
        // buffers it again for 5 s and this test's processOnce sees nothing due; either inbox may then correlate it.
        // Skip-locked claims with epochs make that exactly once whichever inbox wins: one correlation into this
        // instance and the row PROCESSED, as for the relay above ("whichever relay delivers").
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        Inbox.State after = inTx(c -> Inbox.state(c, T, source, msgId));
        while (after != Inbox.State.PROCESSED && System.nanoTime() < deadline) {
            inbox.processOnce(10, clarificationHandler());
            after = inTx(c -> Inbox.state(c, T, source, msgId));
            if (after != Inbox.State.PROCESSED) {
                Thread.sleep(200);
            }
        }
        assertEquals(Inbox.State.PROCESSED, after);
        assertEquals(List.of(pid), correlated, "correlated exactly once, into this instance");
        var view = engine.getInstance(pid);
        assertTrue(view.activeActivityIds().contains("scrutiny"), "back at scrutiny");
        assertEquals("documents attached", view.variables().get("applicantResponseNote"));

        Inbox.ReceiveResult again = inbox.receive(T, source, msgId, bk, "documents attached", Duration.ofHours(24));
        int processedAgain = inbox.processOnce(10, clarificationHandler());
        assertEquals(Inbox.ReceiveResult.DUPLICATE, again);
        assertEquals(0, processedAgain);
        assertEquals(1, correlated.size());
        assertFalse(view.activeActivityIds().contains("awaitApplicantResponse"));
    }
}
