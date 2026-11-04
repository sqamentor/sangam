package org.sangam.spike.it.t08;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.sangam.spike.it.support.ExperimentBase;
import org.sangam.spike.it.support.Scenario;
import org.sangam.spike.layer.AuditChain;
import org.sangam.spike.layer.Jdbc;
import org.sangam.spike.layer.Outbox;
import org.sangam.spike.layer.Publisher;
import org.sangam.spike.layer.Relay;

/**
 * T08 (Part III §16, VI.2.3 rule 5): a poison outbox event, i.e. a publisher that always fails for ONE event, against
 * the product-layer {@link Outbox} and {@link Relay} on the engine transaction ({@code Transactions.of(engine)}) and the
 * database selected with {@code -Dspike.db}.
 *
 * <ul>
 *   <li><b>Ordered destination:</b> the poison delivery is {@code PARKED} after {@code maxAttempts}; later events of the
 *       same root stay {@code PENDING} and are never published while it is parked; other roots continue. The repair API
 *       unblocks the line: {@link Relay#requeue} (fresh attempt budget, after the downstream is fixed) and
 *       {@link Relay#skip} (with a reason), each with an audit entry on the root's chain.</li>
 *   <li><b>Unordered destination:</b> the poison delivery becomes {@code SKIPPED} with a gap record
 *       ({@code last_error = "GAP: ..."} plus an {@code OUTBOX_DELIVERY_SKIPPED} audit entry) and blocks nothing.</li>
 * </ul>
 * The events are appended inside engine transactions without an engine command: the poison policy is a relay property,
 * and E5 already shows append atomic with engine commands.
 */
public abstract class AbstractT08PoisonOutboxTest extends ExperimentBase {

    static final String T = "tn-t08";
    static final String ORDERED = "history";
    static final String UNORDERED = "webhook";
    static final int MAX_ATTEMPTS = 3;

    /** Publisher that fails every attempt for the poisoned event ids; records every successful publish in order. */
    static final class PoisonPublisher implements Publisher {
        final Set<UUID> poison = ConcurrentHashMap.newKeySet();
        final Map<UUID, Integer> failures = new ConcurrentHashMap<>();
        final List<String> published = java.util.Collections.synchronizedList(new ArrayList<>());

        @Override
        public void publish(String destination, Outbox.OutboxEvent e) {
            if (poison.contains(e.eventId())) {
                failures.merge(e.eventId(), 1, Integer::sum);
                throw new IllegalStateException("downstream rejects event " + e.eventId() + " (poison)");
            }
            published.add(destination + "|" + e.rootId() + "|" + e.rootSeq());
        }

        /** root_seq values published for (destination, root), in publish order. */
        List<Long> seqs(String destination, String root) {
            List<Long> out = new ArrayList<>();
            synchronized (published) {
                for (String p : published) {
                    String[] f = p.split("\\|");
                    if (f[0].equals(destination) && f[1].equals(root)) {
                        out.add(Long.parseLong(f[2]));
                    }
                }
            }
            return out;
        }
    }

    PoisonPublisher publisher;
    Relay relay;

    @Override
    protected String experiment() {
        return "T08";
    }

    @Override
    protected void setUp() {
        publisher = new PoisonPublisher();
        // backoff 1 ms .. 5 ms on the DB clock, so the test needs only short sleeps between cycles
        relay = new Relay(env.dialect, env.tx, publisher,
                new Relay.Config("relay-t08", 100, Duration.ofSeconds(30), MAX_ATTEMPTS, Duration.ofMillis(1), Duration.ofMillis(5)),
                env.audit);
        notes.put("relay", "maxAttempts=" + MAX_ATTEMPTS + ", backoff 1..5 ms (DB clock), lease 30 s");
        notes.put("destinations", ORDERED + " (ordered), " + UNORDERED + " (unordered)");
        notes.put("database", env.dialect.name());
    }

    // ------------------------------------------------------------------ helpers

    private List<Outbox.Appended> append(String root, int n, Outbox.Destination destination) {
        List<Outbox.Appended> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int k = i;
            out.add(env.inTx(c -> env.outbox.append(c, T, root, "t08.event", "{\"n\":" + k + "}", List.of(destination))));
        }
        return out;
    }

    private String state(Outbox.Appended a, String destination) {
        return env.inTx(c -> Relay.state(c, a.outboxId(), destination));
    }

    private int attempts(Outbox.Appended a, String destination) {
        return env.inTx(c -> Jdbc.one(c, "SELECT attempts FROM OUTBOX_DELIVERY WHERE outbox_id=? AND destination=?",
                rs -> rs.getInt(1), a.outboxId(), destination).orElse(-1));
    }

    private String lastError(Outbox.Appended a, String destination) {
        return env.inTx(c -> Jdbc.one(c, "SELECT last_error FROM OUTBOX_DELIVERY WHERE outbox_id=? AND destination=?",
                rs -> rs.getString(1), a.outboxId(), destination).orElse(null));
    }

    private long audit(String root, String type) {
        return env.count("SELECT count(*) FROM AUDIT_EVENT WHERE tenant_id = ? AND chain_id = ? AND event_type = ?", T, root, type);
    }

    private AuditChain.VerifyResult verifyChain(String root) {
        return env.inTx(c -> env.audit.verify(c, T, root));
    }

    /**
     * Runs relay cycles until nothing is claimable for several consecutive cycles (each ordered line advances one event
     * per cycle; failed attempts back off by a few ms). Bounded.
     */
    private int settle() throws InterruptedException {
        int cycles = 0;
        int idle = 0;
        while (cycles < 400 && idle < 4) {
            Relay.CycleResult r = relay.runOnce();
            cycles++;
            idle = r.claimed() == 0 ? idle + 1 : 0;
            Thread.sleep(8);
        }
        return cycles;
    }

    private static Outbox.Appended at(List<Outbox.Appended> l, int seq1) {
        return l.get(seq1 - 1);
    }

    // ------------------------------------------------------------------ ordered: park, block, other roots continue, requeue

    @Test
    @Order(1)
    @Scenario(id = "T08-1", name = "Ordered destination: the poison event is PARKED after maxAttempts; later events of "
            + "the same root are blocked; other roots continue; requeue (after the downstream fix) unblocks the line, audited",
            tests = {"T08"})
    void orderedParkBlockAndRequeue() throws Exception {
        String rootA = "t08-ord-a";
        String rootB = "t08-ord-b";
        List<Outbox.Appended> a = append(rootA, 4, Outbox.Destination.ordered(ORDERED));
        List<Outbox.Appended> b = append(rootB, 3, Outbox.Destination.ordered(ORDERED));
        Outbox.Appended poison = at(a, 2);
        publisher.poison.add(poison.eventId());

        int cycles = settle();

        assertEquals("DONE", state(at(a, 1), ORDERED), "a1 before the poison is delivered");
        assertEquals("PARKED", state(poison, ORDERED), "poison PARKED");
        assertEquals(MAX_ATTEMPTS, attempts(poison, ORDERED), "parked after exactly maxAttempts");
        assertEquals(MAX_ATTEMPTS, publisher.failures.get(poison.eventId()));
        assertEquals("PENDING", state(at(a, 3), ORDERED), "a3 blocked behind the parked event");
        assertEquals("PENDING", state(at(a, 4), ORDERED), "a4 blocked behind the parked event");
        assertEquals(0, attempts(at(a, 3), ORDERED), "a3 never attempted");
        assertEquals(List.of(1L), publisher.seqs(ORDERED, rootA), "only a1 published for root A");
        for (Outbox.Appended e : b) {
            assertEquals("DONE", state(e, ORDERED), "other root continues");
        }
        assertEquals(List.of(1L, 2L, 3L), publisher.seqs(ORDERED, rootB), "root B in root_seq order");
        assertEquals(1, audit(rootA, "OUTBOX_DELIVERY_PARKED"), "park audited on the root chain");

        // still blocked on further cycles (no leak past the parked head)
        settle();
        assertEquals("PENDING", state(at(a, 3), ORDERED));

        // requeue while the downstream still rejects: fresh budget, parked again (repair is not a bypass)
        boolean requeued = relay.requeue(poison.outboxId(), ORDERED, "ops-officer-1", "retry after broker restart");
        assertTrue(requeued);
        assertEquals(0, attempts(poison, ORDERED), "requeue resets the attempt budget");
        settle();
        assertEquals("PARKED", state(poison, ORDERED), "still poison: parked again");
        assertEquals(2 * MAX_ATTEMPTS, publisher.failures.get(poison.eventId()));
        assertEquals(2, audit(rootA, "OUTBOX_DELIVERY_PARKED"));

        // downstream fixed, then requeue: the line drains in order
        publisher.poison.remove(poison.eventId());
        assertTrue(relay.requeue(poison.outboxId(), ORDERED, "ops-officer-1", "downstream schema fixed"));
        settle();
        for (Outbox.Appended e : a) {
            assertEquals("DONE", state(e, ORDERED));
        }
        assertEquals(List.of(1L, 2L, 3L, 4L), publisher.seqs(ORDERED, rootA), "root A delivered in root_seq order, no gap");
        assertEquals(2, audit(rootA, "OUTBOX_REQUEUE"), "each requeue audited");
        assertFalse(relay.requeue(poison.outboxId(), ORDERED, "ops-officer-1", "again"), "requeue only applies to PARKED");
        AuditChain.VerifyResult chain = verifyChain(rootA);
        assertTrue(chain.ok(), "audit chain of root A verifies: " + chain.reason());

        m.put("cyclesToPark", cycles);
        m.put("poisonAttemptsTotal", publisher.failures.get(poison.eventId()));
        m.put("rootASeqsPublished", publisher.seqs(ORDERED, rootA));
        m.put("rootBSeqsPublished", publisher.seqs(ORDERED, rootB));
        m.put("auditRootA", Map.of("OUTBOX_DELIVERY_PARKED", audit(rootA, "OUTBOX_DELIVERY_PARKED"),
                "OUTBOX_REQUEUE", audit(rootA, "OUTBOX_REQUEUE"), "chainEventsChecked", chain.eventsChecked()));
    }

    // ------------------------------------------------------------------ ordered: skip

    @Test
    @Order(2)
    @Scenario(id = "T08-2", name = "Ordered destination: skip of a PARKED poison event (reason + actor audited) unblocks "
            + "the later events of the root; the skipped event is never published", tests = {"T08"})
    void orderedSkip() throws Exception {
        String root = "t08-ord-c";
        List<Outbox.Appended> c = append(root, 3, Outbox.Destination.ordered(ORDERED));
        Outbox.Appended poison = at(c, 2);
        publisher.poison.add(poison.eventId());
        settle();
        assertEquals("PARKED", state(poison, ORDERED));
        assertEquals("PENDING", state(at(c, 3), ORDERED));

        assertTrue(relay.skip(poison.outboxId(), ORDERED, "ops-officer-2", "payload references a withdrawn schema"));
        settle();

        assertEquals("SKIPPED", state(poison, ORDERED));
        assertEquals("DONE", state(at(c, 3), ORDERED), "line unblocked after skip");
        assertEquals(List.of(1L, 3L), publisher.seqs(ORDERED, root), "seq 2 skipped, never published; order kept");
        String err = lastError(poison, ORDERED);
        assertNotNull(err);
        assertTrue(err.contains("OUTBOX_SKIP") && err.contains("ops-officer-2") && err.contains("withdrawn schema"),
                "repair recorded on the delivery: " + err);
        assertEquals(1, audit(root, "OUTBOX_SKIP"), "skip audited");
        String envelope = env.strings("SELECT p.payload FROM AUDIT_EVENT e JOIN AUDIT_PAYLOAD p ON p.event_id = e.event_id"
                + " WHERE e.tenant_id = ? AND e.chain_id = ? AND e.event_type = 'OUTBOX_SKIP'", T, root).get(0);
        assertTrue(envelope.contains("ops-officer-2") && envelope.contains(poison.eventId().toString()),
                "audit payload carries actor and event id: " + envelope);
        assertTrue(verifyChain(root).ok());

        m.put("seqsPublished", publisher.seqs(ORDERED, root));
        m.put("deliveryLastError", err);
        m.put("auditPayload", envelope);
    }

    // ------------------------------------------------------------------ unordered: skipped with a gap record

    @Test
    @Order(3)
    @Scenario(id = "T08-3", name = "Unordered destination: the poison event becomes SKIPPED with a gap record after "
            + "maxAttempts; the other events of the root are not blocked", tests = {"T08"})
    void unorderedSkippedWithGap() throws Exception {
        String root = "t08-unord-d";
        List<Outbox.Appended> d = append(root, 3, Outbox.Destination.unordered(UNORDERED));
        Outbox.Appended poison = at(d, 2);
        publisher.poison.add(poison.eventId());

        settle();

        assertEquals("DONE", state(at(d, 1), UNORDERED));
        assertEquals("DONE", state(at(d, 3), UNORDERED), "not blocked by the poison event");
        assertEquals("SKIPPED", state(poison, UNORDERED), "unordered poison is SKIPPED, never PARKED");
        assertEquals(MAX_ATTEMPTS, attempts(poison, UNORDERED));
        String gap = lastError(poison, UNORDERED);
        assertNotNull(gap);
        assertTrue(gap.startsWith("GAP: skipped after " + MAX_ATTEMPTS + " attempts"), "gap record: " + gap);
        assertEquals(1, audit(root, "OUTBOX_DELIVERY_SKIPPED"), "gap audited on the root chain");
        assertEquals(0, audit(root, "OUTBOX_DELIVERY_PARKED"));
        List<Long> seqs = publisher.seqs(UNORDERED, root);
        assertTrue(seqs.containsAll(List.of(1L, 3L)) && !seqs.contains(2L), "published " + seqs);
        // a consumer sees the gap: root_seq 2 never arrives; the gap row is queryable for reconciliation
        long gaps = env.count("SELECT count(*) FROM OUTBOX_DELIVERY WHERE tenant_id = ? AND root_id = ? AND destination = ?"
                + " AND state = 'SKIPPED'", T, root, UNORDERED);
        assertEquals(1, gaps);
        assertFalse(relay.requeue(poison.outboxId(), UNORDERED, "ops", "x"), "SKIPPED is final for the repair API");
        assertTrue(verifyChain(root).ok());

        Map<String, Object> mm = new LinkedHashMap<>();
        mm.put("gapRecord", gap);
        mm.put("seqsPublished", seqs);
        mm.put("skippedRows", gaps);
        m.putAll(mm);
    }
}
