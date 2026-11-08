// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Outbox;
import org.sangam.core.layer.Publisher;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.relay.PermanentPublishException;
import org.sangam.integration.relay.PublisherRegistry;
import org.sangam.rest.support.TxSupport;
import org.sangam.server.acceptance.AbstractAcceptanceIT;
import org.sangam.server.acceptance.plugandplay.AccHttp;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * T08 (Part III §16, VI.2.3 rule 5) on the running server: the production outbox relay
 * ({@code sangamOutboxRelayService}, scheduled, multi-node safe) publishes through the server's
 * {@link PublisherRegistry}; this test registers a publisher for its own destination prefix and appends events to the
 * real OUTBOX tables. Repairs go through the operator API {@code POST /api/v1/ops/outbox/{id}/requeue|skip}.
 *
 * <ul>
 *   <li>Ordered destination: the poison delivery is PARKED; later events of the same root stay PENDING and are never
 *       attempted; other roots continue; requeue (after the downstream fix) drains the line in root_seq order; skip
 *       unblocks it; each repair audited on the root chain, the chain verifies.</li>
 *   <li>Unordered destination: the poison delivery is SKIPPED with a gap record and blocks nothing; SKIPPED is final.</li>
 *   <li>Transient failures (not a permanent poison): PARKED after exactly {@code maxAttempts} (product default 10).</li>
 * </ul>
 */
class T08PoisonOutboxIT extends AbstractAcceptanceIT {

    static final String TENANT = "acc";   // the dev token's tenant, so the operator API sees the deliveries
    static final int PRODUCT_MAX_ATTEMPTS = 10;
    /**
     * Product timing, not slack: relay backoff 200 ms doubling (cap 5 min) and the per-destination circuit breaker
     * (5 consecutive failures, then open 30 s and deferring without consuming attempts; each failed probe reopens it).
     * Parking after 10 attempts therefore spans several breaker periods, and after a requeue the breaker is already open.
     */
    static final Duration PARK_WAIT = Duration.ofMinutes(10);
    /** A deferred delivery resumes after the breaker's 30 s open period plus one backoff step. */
    static final Duration UNBLOCK_WAIT = Duration.ofMinutes(2);

    @LocalServerPort
    int port;

    @Autowired
    PublisherRegistry publishers;

    @Autowired
    TxSupport txSupport;

    @Autowired
    DataSource dataSource;

    @Autowired
    @Qualifier("sangamIntegrationAuditChain")
    AuditChain audit;

    final String run = UUID.randomUUID().toString().substring(0, 8);
    final String prefix = "acc-t08-" + run + ":";
    final String ordered = prefix + "history";
    final String unordered = prefix + "webhook";
    PoisonPublisher publisher;
    Transactions tx;
    Outbox outbox;
    AccHttp ops;

    /** Fails every attempt for poisoned event ids (permanently or transiently); records successful publishes. */
    static final class PoisonPublisher implements Publisher {
        final Set<UUID> permanent = ConcurrentHashMap.newKeySet();
        final Set<UUID> transientFail = ConcurrentHashMap.newKeySet();
        final Map<UUID, Integer> failures = new ConcurrentHashMap<>();
        final List<String> published = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void publish(String destination, Outbox.OutboxEvent e) throws Exception {
            if (permanent.contains(e.eventId())) {
                failures.merge(e.eventId(), 1, Integer::sum);
                throw new PermanentPublishException("downstream rejects event " + e.eventId() + " (poison)");
            }
            if (transientFail.contains(e.eventId())) {
                failures.merge(e.eventId(), 1, Integer::sum);
                throw new IllegalStateException("downstream unavailable for " + e.eventId());
            }
            published.add(destination + "|" + e.rootId() + "|" + e.rootSeq());
        }

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

    @BeforeEach
    void setUp() {
        publisher = new PoisonPublisher();
        publishers.register(prefix + "*", publisher);
        tx = Transactions.of(dataSource);
        outbox = new Outbox(txSupport.dialect());
        ops = AccHttp.local(port, "acceptance-token");
    }

    @AfterEach
    void tearDown() {
        publishers.unregister(prefix + "*");
    }

    // ------------------------------------------------------------------ helpers

    private List<Outbox.Appended> append(String root, int n, Outbox.Destination d) {
        List<Outbox.Appended> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int k = i;
            out.add(tx.inTransaction(c -> outbox.append(c, TENANT, root, "acc.t08.event", "{\"n\":" + k + "}", List.of(d))));
        }
        return out;
    }

    private String state(Outbox.Appended a, String dest) {
        return tx.inTransaction(c -> Jdbc.one(c, "SELECT state FROM OUTBOX_DELIVERY WHERE outbox_id=? AND destination=?",
                rs -> rs.getString(1), a.outboxId(), dest).orElse(null));
    }

    private int attempts(Outbox.Appended a, String dest) {
        return tx.inTransaction(c -> Jdbc.one(c, "SELECT attempts FROM OUTBOX_DELIVERY WHERE outbox_id=? AND destination=?",
                rs -> rs.getInt(1), a.outboxId(), dest).orElse(-1));
    }

    private String lastError(Outbox.Appended a, String dest) {
        return tx.inTransaction(c -> Jdbc.one(c, "SELECT last_error FROM OUTBOX_DELIVERY WHERE outbox_id=? AND destination=?",
                rs -> rs.getString(1), a.outboxId(), dest).orElse(null));
    }

    private long audit(String root, String type) {
        return tx.inTransaction(c -> Jdbc.one(c, "SELECT COUNT(*) FROM AUDIT_EVENT WHERE tenant_id=? AND chain_id=? AND event_type=?",
                rs -> rs.getLong(1), TENANT, root, type).orElse(0L));
    }

    private long deadLetters(Outbox.Appended a, String dest, String st) {
        return tx.inTransaction(c -> Jdbc.one(c, "SELECT COUNT(*) FROM INTEGRATION_DEAD_LETTER WHERE kind='OUTBOX' AND ref_id=? AND state=?",
                rs -> rs.getLong(1), a.outboxId() + ":" + dest, st).orElse(0L));
    }

    private AuditChain.VerifyResult verify(String root) {
        return tx.inTransaction(c -> audit.verify(c, TENANT, root));
    }

    private static void await(String what, Duration max, BooleanSupplier cond) throws InterruptedException {
        long end = System.nanoTime() + max.toNanos();
        while (System.nanoTime() < end) {
            if (cond.getAsBoolean()) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("timed out after " + max + " waiting for: " + what);
    }

    private Map<String, Object> repair(Outbox.Appended a, String dest, String reason) {
        return Map.of("destination", dest, "reason", reason);
    }

    // ------------------------------------------------------------------ T08-1 ordered: park, block, requeue

    @Test
    void orderedParkBlockAndRequeue() throws Exception {
        String rootA = "acc-t08-" + run + "-a";
        String rootB = "acc-t08-" + run + "-b";
        // poison registered before append: the relay may claim the moment the row commits
        List<Outbox.Appended> a = new ArrayList<>();
        Outbox.Appended a1 = append(rootA, 1, Outbox.Destination.ordered(ordered)).get(0);
        a.add(a1);
        await("a1 delivered", UNBLOCK_WAIT, () -> "DONE".equals(state(a1, ordered)));
        // a2..a4 in one transaction, a2 poisoned before the commit (the running relay claims as soon as rows commit)
        List<Outbox.Appended> rest = tx.inTransaction(c -> {
            List<Outbox.Appended> l = new ArrayList<>();
            for (int i = 1; i < 4; i++) {
                l.add(outbox.append(c, TENANT, rootA, "acc.t08.event", "{\"n\":" + i + "}",
                        List.of(Outbox.Destination.ordered(ordered))));
            }
            publisher.transientFail.add(l.get(0).eventId());
            return l;
        });
        a.addAll(rest);
        Outbox.Appended poison = a.get(1);
        List<Outbox.Appended> b = append(rootB, 3, Outbox.Destination.ordered(ordered));

        await("poison PARKED and root B drained", PARK_WAIT,
                () -> "PARKED".equals(state(poison, ordered)) && b.stream().allMatch(e -> "DONE".equals(state(e, ordered))));
        Thread.sleep(3000);   // several more relay cycles: nothing may leak past the parked head

        assertThat(state(a.get(2), ordered)).as("a3 blocked behind the parked event").isEqualTo("PENDING");
        assertThat(state(a.get(3), ordered)).as("a4 blocked behind the parked event").isEqualTo("PENDING");
        assertThat(attempts(a.get(2), ordered)).as("a3 never attempted").isZero();
        assertThat(publisher.seqs(ordered, rootA)).as("only a1 published for root A").containsExactly(1L);
        assertThat(publisher.seqs(ordered, rootB)).as("root B in root_seq order").containsExactly(1L, 2L, 3L);
        assertThat(attempts(poison, ordered)).as("parked after exactly maxAttempts").isEqualTo(PRODUCT_MAX_ATTEMPTS);
        assertThat(publisher.failures.get(poison.eventId())).isEqualTo(PRODUCT_MAX_ATTEMPTS);
        assertThat(audit(rootA, "OUTBOX_DELIVERY_PARKED")).as("park audited on the root chain").isEqualTo(1);
        assertThat(deadLetters(poison, ordered, "OPEN")).as("park dead-lettered").isEqualTo(1);

        // requeue while the downstream still rejects: parked again (repair is not a bypass)
        ops.call("POST", "/api/v1/ops/outbox/" + poison.outboxId() + "/requeue",
                repair(poison, ordered, "retry after broker restart"));
        await("parked again", PARK_WAIT, () -> "PARKED".equals(state(poison, ordered))
                && publisher.failures.getOrDefault(poison.eventId(), 0) == 2 * PRODUCT_MAX_ATTEMPTS);
        assertThat(audit(rootA, "OUTBOX_DELIVERY_PARKED")).isEqualTo(2);
        assertThat(state(a.get(2), ordered)).isEqualTo("PENDING");

        // downstream fixed, then requeue: the line drains in order
        publisher.transientFail.remove(poison.eventId());
        ops.call("POST", "/api/v1/ops/outbox/" + poison.outboxId() + "/requeue",
                repair(poison, ordered, "downstream schema fixed"));
        await("root A drained", PARK_WAIT, () -> a.stream().allMatch(e -> "DONE".equals(state(e, ordered))));
        assertThat(publisher.seqs(ordered, rootA)).as("root A delivered in root_seq order, no gap")
                .containsExactly(1L, 2L, 3L, 4L);
        assertThat(audit(rootA, "OUTBOX_REQUEUE")).as("each requeue audited").isEqualTo(2);
        // requeue only applies to PARKED
        ops.call("POST", "/api/v1/ops/outbox/" + poison.outboxId() + "/requeue", repair(poison, ordered, "again"), 409);
        AuditChain.VerifyResult chain = verify(rootA);
        assertThat(chain.ok()).as("audit chain of root A verifies: " + chain.reason()).isTrue();
    }

    // ------------------------------------------------------------------ T08-2 ordered: skip

    @Test
    void orderedSkip() throws Exception {
        String root = "acc-t08-" + run + "-c";
        List<Outbox.Appended> c = tx.inTransaction(conn -> {
            List<Outbox.Appended> l = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                l.add(outbox.append(conn, TENANT, root, "acc.t08.event", "{\"n\":" + i + "}",
                        List.of(Outbox.Destination.ordered(ordered))));
            }
            publisher.transientFail.add(l.get(1).eventId());
            return l;
        });
        Outbox.Appended poison = c.get(1);
        await("poison PARKED", PARK_WAIT, () -> "PARKED".equals(state(poison, ordered)));
        assertThat(state(c.get(2), ordered)).isEqualTo("PENDING");

        ops.call("POST", "/api/v1/ops/outbox/" + poison.outboxId() + "/skip",
                repair(poison, ordered, "payload references a withdrawn schema"));
        await("line unblocked after skip", UNBLOCK_WAIT, () -> "DONE".equals(state(c.get(2), ordered)));

        assertThat(state(poison, ordered)).isEqualTo("SKIPPED");
        assertThat(publisher.seqs(ordered, root)).as("seq 2 skipped, never published; order kept").containsExactly(1L, 3L);
        String err = lastError(poison, ordered);
        assertThat(err).as("repair recorded on the delivery").contains("OUTBOX_SKIP", "acc-admin", "withdrawn schema");
        assertThat(audit(root, "OUTBOX_SKIP")).as("skip audited").isEqualTo(1);
        String envelope = tx.inTransaction(conn -> Jdbc.one(conn, "SELECT p.payload FROM AUDIT_EVENT e JOIN AUDIT_PAYLOAD p"
                + " ON p.event_id = e.event_id WHERE e.tenant_id=? AND e.chain_id=? AND e.event_type='OUTBOX_SKIP'",
                rs -> rs.getString(1), TENANT, root).orElse(""));
        assertThat(envelope).as("audit payload carries actor and event id")
                .contains("acc-admin", poison.eventId().toString());
        assertThat(deadLetters(poison, ordered, "DISCARDED")).as("dead letter closed as discarded").isEqualTo(1);
        assertThat(verify(root).ok()).isTrue();
    }

    // ------------------------------------------------------------------ T08-3 unordered: gap record

    @Test
    void unorderedSkippedWithGap() throws Exception {
        String root = "acc-t08-" + run + "-d";
        List<Outbox.Appended> d = tx.inTransaction(conn -> {
            List<Outbox.Appended> l = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                l.add(outbox.append(conn, TENANT, root, "acc.t08.event", "{\"n\":" + i + "}",
                        List.of(Outbox.Destination.unordered(unordered))));
            }
            publisher.transientFail.add(l.get(1).eventId());
            return l;
        });
        Outbox.Appended poison = d.get(1);
        await("all settled", PARK_WAIT, () -> "DONE".equals(state(d.get(0), unordered))
                && "DONE".equals(state(d.get(2), unordered)) && "SKIPPED".equals(state(poison, unordered)));

        String gap = lastError(poison, unordered);
        assertThat(gap).as("gap record").startsWith("GAP: skipped after " + PRODUCT_MAX_ATTEMPTS + " attempts");
        assertThat(attempts(poison, unordered)).isEqualTo(PRODUCT_MAX_ATTEMPTS);
        assertThat(audit(root, "OUTBOX_DELIVERY_SKIPPED")).as("gap audited on the root chain").isEqualTo(1);
        assertThat(audit(root, "OUTBOX_DELIVERY_PARKED")).isZero();
        assertThat(publisher.seqs(unordered, root)).contains(1L, 3L).doesNotContain(2L);
        ops.call("POST", "/api/v1/ops/outbox/" + poison.outboxId() + "/requeue", repair(poison, unordered, "x"), 409);
        assertThat(verify(root).ok()).isTrue();
    }

    // ------------------------------------------------------------------ T08-4 transient failures: maxAttempts

    @Test
    void transientFailuresParkAfterMaxAttempts() throws Exception {
        String root = "acc-t08-" + run + "-e";
        List<Outbox.Appended> e = tx.inTransaction(conn -> {
            List<Outbox.Appended> l = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                l.add(outbox.append(conn, TENANT, root, "acc.t08.event", "{\"n\":" + i + "}",
                        List.of(Outbox.Destination.ordered(ordered))));
            }
            publisher.transientFail.add(l.get(0).eventId());
            return l;
        });
        Outbox.Appended poison = e.get(0);
        // backoff 200 ms doubling, so 10 attempts need about 100 s of relay time
        await("PARKED after maxAttempts", PARK_WAIT, () -> "PARKED".equals(state(poison, ordered)));
        assertThat(attempts(poison, ordered)).isEqualTo(PRODUCT_MAX_ATTEMPTS);
        assertThat(publisher.failures.get(poison.eventId())).isEqualTo(PRODUCT_MAX_ATTEMPTS);
        assertThat(state(e.get(1), ordered)).isEqualTo("PENDING");
        assertThat(audit(root, "OUTBOX_DELIVERY_PARKED")).isEqualTo(1);
        assertThat(deadLetters(poison, ordered, "OPEN")).isEqualTo(1);
    }
}
