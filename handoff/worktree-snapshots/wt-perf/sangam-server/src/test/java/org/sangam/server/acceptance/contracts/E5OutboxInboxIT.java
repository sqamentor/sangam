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
        long mineSwept = inTx(c -> Jdbc.one(c, "SELECT count(*) FROM OUTBOX_DELIVERY WHERE outbox_id = ? AND state = 'PENDING'",
                rs -> rs.getLong(1), a.outboxId()).orElse(0L));
        assertEquals(2, mineSwept, "exactly this event's two expired claims swept back to PENDING (sweep total " + reset + ")");
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
        relay.drain(5);
        assertTrue(a.outboxId() < b.outboxId(), "A got the lower id but committed later");
        assertTrue(bOnFirstPass, "B's two deliveries were published on the first pass");
        assertFalse(aOnFirstPass, "A was not visible on the first pass");
        assertEquals("DONE", state(a.outboxId(), HISTORY));
        assertEquals("DONE", state(a.outboxId(), WEBHOOK));
        assertTrue(consumer.applied(HISTORY, a.eventId()));
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

    @Test
    @Order(5)
    void e5_5_earlyMessageBufferedThenCorrelated() {
        String bk = key("lic-e5-5");
        String source = key("applicant-portal");
        String msgId = key("msg-e5-5");
        inboxService.registerSourceHandler(source, clarificationHandler());   // the server inbox uses the same handler
        String pid = engine.startProcess(T, licenceKey, bk, Map.of("applicationId", bk));
        Inbox.ReceiveResult first = inbox.receive(T, source, msgId, bk, "documents attached", Duration.ofHours(24));
        int processedEarly = inbox.processOnce(10, clarificationHandler());
        Inbox.State early = inTx(c -> Inbox.state(c, T, source, msgId));
        assertEquals(Inbox.ReceiveResult.ACCEPTED, first);
        assertEquals(0, processedEarly);
        assertEquals(Inbox.State.BUFFERED, early);

        engine.completeTask(singleTask(pid, "scrutiny").id(), Map.of("scrutinyDecision", "clarify"));
        assertTrue(engine.getInstance(pid).activeActivityIds().contains("awaitApplicantResponse"));

        int processed = inbox.processOnce(10, clarificationHandler());
        Inbox.State after = inTx(c -> Inbox.state(c, T, source, msgId));
        assertEquals(1, processed);
        assertEquals(Inbox.State.PROCESSED, after);
        assertEquals(List.of(pid), correlated);
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
