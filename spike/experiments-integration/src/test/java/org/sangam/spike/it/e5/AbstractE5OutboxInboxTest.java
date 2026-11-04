package org.sangam.spike.it.e5;

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

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.sangam.spike.api.EngineAdapter.TaskView;
import org.sangam.spike.it.support.Env;
import org.sangam.spike.it.support.ExperimentBase;
import org.sangam.spike.it.support.Scenario;
import org.sangam.spike.it.support.Workflows;
import org.sangam.spike.layer.Inbox;
import org.sangam.spike.layer.Jdbc;
import org.sangam.spike.layer.Outbox;
import org.sangam.spike.layer.Publisher;
import org.sangam.spike.layer.Relay;

/**
 * E5 (SANGAM.md XI.4, VI.2): the outbox row is appended in the same engine transaction as the engine command; the relay
 * claims/publishes/marks with its own short engine transactions; the inbox buffers an early WF-3 clarification message
 * and correlates it in the same transaction that marks it PROCESSED.
 */
public abstract class AbstractE5OutboxInboxTest extends ExperimentBase {

    static final String T = "tn-e5";
    static final List<Outbox.Destination> DESTINATIONS =
            List.of(Outbox.Destination.ordered("history"), Outbox.Destination.unordered("webhook"));

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

    DedupConsumer consumer;
    Relay relay;
    Inbox inbox;

    @Override
    protected String experiment() {
        return "E5";
    }

    @Override
    protected void setUp() {
        env.deploy(T, Workflows.WF1_FILES);
        env.deploy(T, Workflows.WF3_FILES);
        consumer = new DedupConsumer();
        relay = new Relay(env.dialect, env.tx, consumer,
                new Relay.Config("relay-1", 100, Duration.ofMillis(500), 3, Duration.ofMillis(20), Duration.ofSeconds(1)), env.audit);
        inbox = new Inbox(env.dialect, env.tx, "inbox-1", Duration.ofSeconds(5), Duration.ofMillis(1), env.audit);
        notes.put("destinations", "history (ordered), webhook (unordered)");
        notes.put("relayLeaseTtl", "500 ms (DB clock)");
    }

    private String startWf1(String bk) {
        return env.engine.startProcess(T, "wf1-bill", bk, Workflows.wf1Vars(bk, 50_000, "seat-checker", "ACC-" + bk));
    }

    private long outboxRows(String rootId) {
        return env.count("SELECT count(*) FROM OUTBOX WHERE tenant_id = ? AND root_id = ?", T, rootId);
    }

    private String state(long outboxId, String destination) {
        return env.inTx(c -> Relay.state(c, outboxId, destination));
    }

    /** Completes the checker task and appends the event in one engine command. */
    private Outbox.Appended completeAndAppend(String pid, String taskId, Map<String, Object> vars, RuntimeException failAfter,
                                              boolean appendFirst) {
        return env.inTx(c -> {
            Outbox.Appended a = null;
            if (appendFirst) {
                a = env.outbox.append(c, T, pid, "wf1.checkerReview.completed", "{\"taskId\":\"" + taskId + "\"}", DESTINATIONS);
            }
            env.engine.completeTask(taskId, vars);
            if (!appendFirst) {
                a = env.outbox.append(c, T, pid, "wf1.checkerReview.completed", "{\"taskId\":\"" + taskId + "\"}", DESTINATIONS);
            }
            if (failAfter != null) {
                throw failAfter;
            }
            return a;
        });
    }

    @Test
    @Order(1)
    @Scenario(id = "E5-1", name = "Event appended in the same transaction as a task completion; the relay publishes it "
            + "to every destination", tests = {"T06"})
    void appendWithCompletionThenRelay() {
        String pid = startWf1("e5-1");
        TaskView task = Workflows.singleTask(env.engine, T, pid, "wf1_checkerReview");
        Outbox.Appended a = completeAndAppend(pid, task.id(), Map.of("checkerDecision", "forward"), null, false);
        assertEquals(1, outboxRows(pid));
        assertTrue(env.engine.getInstance(pid).activeActivityIds().contains("wf1_approve"), "engine command committed");
        Relay.CycleResult r = relay.runOnce();
        assertEquals(2, r.published());
        assertEquals("DONE", state(a.outboxId(), "history"));
        assertEquals("DONE", state(a.outboxId(), "webhook"));
        assertTrue(consumer.applied("history", a.eventId()));
        m.put("rootSeq", a.rootSeq());
        m.put("relayCycle", Map.of("claimed", r.claimed(), "published", r.published(), "fencedOut", r.fencedOut()));
    }

    @Test
    @Order(2)
    @Scenario(id = "E5-2", name = "Relay crash between publish and mark: re-published after the claim lease, the "
            + "consumer dedups on event_id, every destination ends DONE", tests = {"T07"})
    void crashBetweenPublishAndMark() throws Exception {
        String pid = startWf1("e5-2");
        TaskView task = Workflows.singleTask(env.engine, T, pid, "wf1_checkerReview");
        Outbox.Appended a = completeAndAppend(pid, task.id(), Map.of("checkerDecision", "forward"), null, false);
        List<Relay.Claimed> claimed = relay.claim();
        assertEquals(2, claimed.size());
        for (Relay.Claimed cl : claimed) {
            consumer.publish(cl.destination(), cl.event());   // published ... then the relay node dies before mark
        }
        assertEquals("CLAIMED", state(a.outboxId(), "history"));
        Thread.sleep(800);   // claim lease 500 ms
        int reset = relay.sweep();
        int cycles = relay.drain(5);
        assertEquals(2, reset);
        for (String d : List.of("history", "webhook")) {
            assertEquals("DONE", state(a.outboxId(), d));
            assertEquals(2, consumer.deliveries(d, a.eventId()), "published twice (at-least-once)");
            assertTrue(consumer.applied(d, a.eventId()));
        }
        m.put("sweptBackToPending", reset);
        m.put("deliveriesPerDestination", 2);
        m.put("appliedPerDestination", 1);
        m.put("drainCycles", cycles);
    }

    @Test
    @Order(3)
    @Scenario(id = "E5-3", name = "Rollback of the engine command also removes the outbox row (exception after the "
            + "callback, and an engine failure inside the command)", tests = {"T06"})
    void rollbackRemovesOutboxRow() {
        String pid = startWf1("e5-3");
        TaskView task = Workflows.singleTask(env.engine, T, pid, "wf1_checkerReview");
        long seqBefore = env.count("SELECT coalesce(max(next_outbox_seq),0) FROM ROOT_SEQ WHERE tenant_id=? AND root_id=?", T, pid);

        assertThrows(IllegalStateException.class, () -> completeAndAppend(pid, task.id(), Map.of("checkerDecision", "forward"),
                new IllegalStateException("crash after completeTask + append"), false));
        long afterCrash = outboxRows(pid);
        boolean taskOpenAfterCrash = !env.engine.queryTasks(new org.sangam.spike.api.EngineAdapter.TaskQuery(T, null, null, pid,
                "wf1_checkerReview", 0, 10)).isEmpty();

        // engine failure: the gateway after the checker task needs checkerDecision; the append ran before the failure
        RuntimeException engineFailure = assertThrows(RuntimeException.class,
                () -> completeAndAppend(pid, task.id(), Map.of(), null, true));
        long afterEngineFailure = outboxRows(pid);
        boolean taskOpenAfterFailure = !env.engine.queryTasks(new org.sangam.spike.api.EngineAdapter.TaskQuery(T, null, null, pid,
                "wf1_checkerReview", 0, 10)).isEmpty();
        long seqAfter = env.count("SELECT coalesce(max(next_outbox_seq),0) FROM ROOT_SEQ WHERE tenant_id=? AND root_id=?", T, pid);

        assertEquals(0, afterCrash);
        assertTrue(taskOpenAfterCrash);
        assertEquals(0, afterEngineFailure);
        assertTrue(taskOpenAfterFailure);
        assertEquals(seqBefore, seqAfter, "root_seq allocation rolled back as well");
        assertEquals(0, env.count("SELECT count(*) FROM OUTBOX_DELIVERY WHERE root_id = ?", pid));
        m.put("outboxRowsAfterCallbackException", afterCrash);
        m.put("outboxRowsAfterEngineFailure", afterEngineFailure);
        m.put("engineFailure", engineFailure.getClass().getSimpleName() + ": " + engineFailure.getMessage());
        m.put("taskStillOpen", true);
    }

    @Test
    @Order(4)
    @Scenario(id = "E5-4", name = "A late commit with a lower outbox id is still delivered (state scan, no id watermark)",
            tests = {"T06"})
    void lateLowerIdCommitDelivered() throws Exception {
        String pidA = startWf1("e5-4a");
        String pidB = startWf1("e5-4b");
        String taskA = Workflows.singleTask(env.engine, T, pidA, "wf1_checkerReview").id();
        String taskB = Workflows.singleTask(env.engine, T, pidB, "wf1_checkerReview").id();
        CountDownLatch appended = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService ex = Executors.newSingleThreadExecutor();
        Future<Outbox.Appended> slow = ex.submit(() -> env.inTx(c -> {
            env.engine.completeTask(taskA, Map.of("checkerDecision", "forward"));
            Outbox.Appended a = env.outbox.append(c, T, pidA, "wf1.checkerReview.completed", "{}", DESTINATIONS);
            appended.countDown();
            await(release);
            return a;
        }));
        assertTrue(appended.await(30, TimeUnit.SECONDS));
        Outbox.Appended b = completeAndAppend(pidB, taskB, Map.of("checkerDecision", "forward"), null, false);
        int firstPass = relay.runOnce().published();
        release.countDown();
        Outbox.Appended a = slow.get(30, TimeUnit.SECONDS);
        ex.shutdown();
        relay.drain(5);
        assertTrue(a.outboxId() < b.outboxId(), "A got the lower id but committed later");
        assertEquals(2, firstPass, "only B's two deliveries were visible");
        assertEquals("DONE", state(a.outboxId(), "history"));
        assertEquals("DONE", state(a.outboxId(), "webhook"));
        assertTrue(consumer.applied("history", a.eventId()));
        m.put("lateCommitOutboxId", a.outboxId());
        m.put("earlyCommitOutboxId", b.outboxId());
        m.put("deliveredOnFirstPass", firstPass);
        m.put("lateEventDelivered", true);
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

    /** Correlates only when the instance waits at the message catch; otherwise the message stays BUFFERED. */
    private final List<String> correlated = Collections.synchronizedList(new ArrayList<>());

    private Inbox.Handler clarificationHandler() {
        return clarificationHandler(true);
    }

    /**
     * @param byBusinessKey true: correlate through {@code EngineAdapter.correlateMessage(tenant, name, businessKey)};
     *                      false: pass a null business key (the SQL check above has already established that exactly
     *                      one instance with this business key waits, and the scenario makes it the only waiting one)
     */
    private Inbox.Handler clarificationHandler(boolean byBusinessKey) {
        return (Connection c, Inbox.Message msg) -> {
            long waiting = Jdbc.one(c, "SELECT count(*) FROM ACT_RU_EVENT_SUBSCR s JOIN ACT_RU_EXECUTION p ON p.ID_ = s.PROC_INST_ID_"
                            + " WHERE s.EVENT_TYPE_ = 'message' AND s.EVENT_NAME_ = 'applicantResponse'"
                            + " AND p.BUSINESS_KEY_ = ? AND p.TENANT_ID_ = ?",
                    rs -> rs.getLong(1), msg.correlationKey(), msg.tenantId()).orElse(0L);
            if (waiting == 0) {
                return false;
            }
            String pid = env.engine.correlateMessage(msg.tenantId(), "applicantResponse",
                    byBusinessKey ? msg.correlationKey() : null, Map.of("applicantResponseNote", msg.payload()));
            correlated.add(pid);
            return true;
        };
    }

    @Test
    @Order(5)
    @Scenario(id = "E5-5", name = "Early inbound message (WF-3 clarification) is buffered, then correlated when the "
            + "instance reaches the catch event; a redelivery is dropped", tests = {"T09"})
    void earlyMessageBufferedThenCorrelated() {
        String bk = "lic-e5-5";
        String pid = env.engine.startProcess(T, "wf3-licence", bk,
                Map.of("applicationId", bk, "applicantId", "APP-1", "licenceType", "trade"));
        Inbox.ReceiveResult first = inbox.receive(T, "applicant-portal", "msg-e5-5", bk, "documents attached", Duration.ofHours(24));
        int processedEarly = inbox.processOnce(10, clarificationHandler());
        Inbox.State early = env.inTx(c -> Inbox.state(c, T, "applicant-portal", "msg-e5-5"));
        assertEquals(Inbox.ReceiveResult.ACCEPTED, first);
        assertEquals(0, processedEarly);
        assertEquals(Inbox.State.BUFFERED, early);

        String scrutiny = Workflows.singleTask(env.engine, T, pid, "wf3_scrutiny").id();
        env.engine.completeTask(scrutiny, Map.of("scrutinyDecision", "clarify"));
        assertTrue(env.engine.getInstance(pid).activeActivityIds().contains("wf3_awaitApplicantResponse"));

        int processed = inbox.processOnce(10, clarificationHandler());
        Inbox.State after = env.inTx(c -> Inbox.state(c, T, "applicant-portal", "msg-e5-5"));
        assertEquals(1, processed);
        assertEquals(Inbox.State.PROCESSED, after);
        assertEquals(List.of(pid), correlated);
        var view = env.engine.getInstance(pid);
        assertTrue(view.activeActivityIds().contains("wf3_scrutiny"), "back at scrutiny");
        assertEquals("documents attached", view.variables().get("applicantResponseNote"));

        Inbox.ReceiveResult again = inbox.receive(T, "applicant-portal", "msg-e5-5", bk, "documents attached", Duration.ofHours(24));
        int processedAgain = inbox.processOnce(10, clarificationHandler());
        assertEquals(Inbox.ReceiveResult.DUPLICATE, again);
        assertEquals(0, processedAgain);
        assertEquals(1, correlated.size());
        assertFalse(view.activeActivityIds().contains("wf3_awaitApplicantResponse"));
        m.put("earlyState", early.name());
        m.put("afterCatchEvent", after.name());
        m.put("correlations", correlated.size());
        m.put("redelivery", again.name());
    }

    @Test
    @Order(6)
    @Scenario(id = "E5-5w", name = "Same as E5-5 with the adapter's business-key correlation bypassed (null business "
            + "key, single waiting instance): isolates the inbox mechanism from the Flowable adapter defect", counted = false)
    void earlyMessageWithoutAdapterBusinessKey() {
        // leave exactly one waiting WF-3 instance in the tenant
        for (String old : env.strings("SELECT ID_ FROM ACT_RU_EXECUTION WHERE PARENT_ID_ IS NULL AND TENANT_ID_ = ? AND BUSINESS_KEY_ LIKE 'lic-%'", T)) {
            env.engine.cancelInstance(old, "cleanup before E5-5w");
        }
        correlated.clear();
        String bk = "lic-e5-5w";
        String pid = env.engine.startProcess(T, "wf3-licence", bk,
                Map.of("applicationId", bk, "applicantId", "APP-2", "licenceType", "trade"));
        inbox.receive(T, "applicant-portal-w", "msg-e5-5w", bk, "documents attached", Duration.ofHours(24));
        inbox.processOnce(10, clarificationHandler(false));
        Inbox.State early = env.inTx(c -> Inbox.state(c, T, "applicant-portal-w", "msg-e5-5w"));
        env.engine.completeTask(Workflows.singleTask(env.engine, T, pid, "wf3_scrutiny").id(), Map.of("scrutinyDecision", "clarify"));
        inbox.processOnce(10, clarificationHandler(false));
        Inbox.State after = env.inTx(c -> Inbox.state(c, T, "applicant-portal-w", "msg-e5-5w"));
        assertEquals(Inbox.State.BUFFERED, early);
        assertEquals(Inbox.State.PROCESSED, after);
        assertEquals(List.of(pid), correlated);
        assertTrue(env.engine.getInstance(pid).activeActivityIds().contains("wf3_scrutiny"));
        m.put("earlyState", early.name());
        m.put("afterCatchEvent", after.name());
        m.put("correlations", correlated.size());
    }
}
