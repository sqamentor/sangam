// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Outbox;
import org.sangam.core.layer.Relay;
import org.sangam.integration.connector.EgressPolicy;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.RetryPolicy;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.integration.publish.HttpWebhookPublisher;
import org.sangam.integration.publish.WebhookHealth;
import org.sangam.integration.publish.WebhookSignature;
import org.sangam.integration.relay.CircuitBreaker;
import org.sangam.integration.relay.DeadLetterStore;
import org.sangam.integration.relay.OutboxRelayService;
import org.sangam.integration.relay.PublisherRegistry;
import org.sangam.integration.relay.WakeupNotifier;

/**
 * SL-39 on PostgreSQL: {@link HttpWebhookPublisher} behind the production {@link OutboxRelayService} (core relay),
 * against a local JDK HttpServer stub: retries keep the idempotency key and re-sign, per-root order holds through a
 * failure, exhausted deliveries are parked and dead-lettered, and a failing destination is auto-disabled (audited),
 * held without consuming attempts, and resumes after the re-enable.
 */
class WebhookRelayIT extends LayerSpecSupport {

    static final String SECRET = "whsec-it";
    HttpServer server;
    int port;
    /** per root: statuses to answer, in order (then 200) */
    final Map<String, List<Integer>> script = new ConcurrentHashMap<>();
    final List<String> received = Collections.synchronizedList(new ArrayList<>());
    final List<Map<String, String>> headers = Collections.synchronizedList(new ArrayList<>());
    final AtomicInteger requests = new AtomicInteger();
    final Outbox outbox = new Outbox(PG);

    @BeforeAll
    void stub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterAll
    void stopStub() {
        server.stop(0);
    }

    private void handle(HttpExchange x) throws java.io.IOException {
        requests.incrementAndGet();
        Map<String, String> h = new ConcurrentHashMap<>();
        x.getRequestHeaders().forEach((k, v) -> h.put(k.toLowerCase(Locale.ROOT), v.get(0)));
        byte[] body = x.getRequestBody().readAllBytes();
        String root = h.get("ce-sangamroot");
        List<Integer> s = script.get(root);
        int status = s == null || s.isEmpty() ? 200 : s.remove(0);
        boolean signed = WebhookSignature.verify(SECRET.getBytes(StandardCharsets.UTF_8), h.get("x-sangam-signature"),
                body, Duration.ofMinutes(5), System.currentTimeMillis() / 1000);
        h.put(":signed", Boolean.toString(signed));
        h.put(":status", Integer.toString(status));
        headers.add(h);
        if (status == 200) {
            received.add(root + "#" + h.get("ce-sangamrootseq"));
        }
        x.sendResponseHeaders(status, -1);
        x.close();
    }

    String dest(String local) {
        return "webhook:" + key(local);
    }

    OutboxRelayService relay(WebhookHealth health, int maxAttempts) {
        HttpTransport transport = new HttpTransport(EgressPolicy.of(List.of(new EgressPolicy.Rule("*", "http", "127.0.0.1",
                port, "/", true)), 0, 1 << 20), null, Duration.ofSeconds(2), Duration.ofSeconds(5), RetryPolicy.none());
        HttpWebhookPublisher.Endpoint ep = new HttpWebhookPublisher.Endpoint(URI.create("http://127.0.0.1:" + port + "/hook"),
                "secret://wh/it", Map.of());
        HttpWebhookPublisher publisher = new HttpWebhookPublisher(transport, d -> d.startsWith("webhook:") ? ep : null,
                SecretResolver.of(Map.of("secret://wh/it", SECRET)), health, Duration.ofHours(72), "urn:sangam:it",
                Clock.systemUTC());
        PublisherRegistry registry = new PublisherRegistry().register("webhook:*", publisher);
        OutboxRelayService.Settings settings = new OutboxRelayService.Settings(
                new Relay.Config("wh-it", 50, Duration.ofSeconds(30), maxAttempts, Duration.ZERO, Duration.ZERO),
                Duration.ofSeconds(1), Duration.ofSeconds(15), 4);
        return new OutboxRelayService(PG, TX, registry, settings, new AuditChain(PG), new DeadLetterStore(PG, TX),
                new CircuitBreaker(1000, Duration.ofSeconds(1)), WakeupNotifier.NONE);
    }

    void drain(OutboxRelayService relay) {
        for (int i = 0; i < 40 && relay.cycle() > 0; i++) {
            // until nothing is claimable
        }
    }

    /** Counts in the isolated product tables (AUDIT_EVENT is cloned; other tables resolve to public). */
    long isoCount(String sql, Object... params) {
        return txLong(c -> Jdbc.one(c, sql, rs -> rs.getLong(1), params).orElse(0L));
    }

    String state(long outboxId, String destination) {
        return inTx(c -> Relay.state(c, outboxId, destination));
    }

    @Test
    void retriesKeepTheIdempotencyKeyReSignAndPreservePerRootOrder() {
        String d = dest("order");
        List<Outbox.Destination> to = List.of(Outbox.Destination.ordered(d));
        Outbox.Appended a1 = inTx(c -> outbox.append(c, "t1", key("rootA"), "E", "{\"n\":1}", to));
        inTx(c -> outbox.append(c, "t1", key("rootA"), "E", "{\"n\":2}", to));
        inTx(c -> outbox.append(c, "t1", key("rootA"), "E", "{\"n\":3}", to));
        script.put(key("rootA"), new ArrayList<>(List.of(500, 503)));   // first event fails twice
        OutboxRelayService relay = relay(new WebhookHealth(PG, TX, new AuditChain(PG), 100), 10);
        drain(relay);
        assertEquals(List.of(key("rootA") + "#1", key("rootA") + "#2", key("rootA") + "#3"),
                received.stream().filter(s -> s.startsWith(key("rootA"))).toList(), "per-root order through the failures");
        List<Map<String, String>> first = headers.stream().filter(h -> key("rootA").equals(h.get("ce-sangamroot"))
                && "1".equals(h.get("ce-sangamrootseq"))).toList();
        assertEquals(3, first.size(), "two failed attempts and the success");
        String eventId = first.get(0).get("ce-id");
        assertTrue(first.stream().allMatch(h -> eventId.equals(h.get("idempotency-key"))), "same key on every attempt");
        assertTrue(headers.stream().allMatch(h -> "true".equals(h.get(":signed"))), "every attempt verifiably signed");
        assertEquals("DONE", state(a1.outboxId(), d));
    }

    @Test
    void exhaustedDeliveriesAreParkedAndDeadLetteredAfterMaxAttempts() {
        String d = dest("dlq");
        Outbox.Appended e = inTx(c -> outbox.append(c, "t1", key("rootD"), "E", "{}", List.of(Outbox.Destination.ordered(d))));
        script.put(key("rootD"), new ArrayList<>(List.of(500, 500, 500, 500, 500, 500)));
        drain(relay(new WebhookHealth(PG, TX, new AuditChain(PG), 100), 4));
        assertEquals("PARKED", state(e.outboxId(), d));
        assertEquals(4, txInt(c -> Jdbc.one(c, "SELECT attempts FROM OUTBOX_DELIVERY WHERE outbox_id=? AND destination=?",
                rs -> rs.getInt(1), e.outboxId(), d).orElseThrow()));
        assertEquals(1L, isoCount("SELECT count(*) FROM INTEGRATION_DEAD_LETTER WHERE kind='OUTBOX' AND ref_id=? AND state='OPEN'",
                e.outboxId() + ":" + d));
    }

    @Test
    void failingDestinationIsDisabledAuditedHeldAndResumesAfterReEnable() {
        String d = dest("disable");
        WebhookHealth health = new WebhookHealth(PG, TX, new AuditChain(PG), 3);
        OutboxRelayService relay = relay(health, 50);
        Outbox.Appended e = inTx(c -> outbox.append(c, "t1", key("rootX"), "E", "{}", List.of(Outbox.Destination.ordered(d))));
        script.put(key("rootX"), new ArrayList<>(List.of(500, 500, 500)));
        drain(relay);
        assertTrue(health.isDisabled(d));
        assertEquals(1L, isoCount("SELECT count(*) FROM AUDIT_EVENT WHERE chain_id=? AND event_type='WEBHOOK_DISABLED'",
                "webhook:" + d));
        int attempts = txInt(c -> Jdbc.one(c, "SELECT attempts FROM OUTBOX_DELIVERY WHERE outbox_id=? AND destination=?",
                rs -> rs.getInt(1), e.outboxId(), d).orElseThrow());
        assertEquals(3, attempts);
        int before = requests.get();
        run(c -> Jdbc.update(c, "UPDATE OUTBOX_DELIVERY SET next_attempt_at=CURRENT_TIMESTAMP WHERE outbox_id=?", e.outboxId()));
        drain(relay);
        assertEquals("PENDING", state(e.outboxId(), d), "held, not dead-lettered");
        assertEquals(before, requests.get(), "a disabled destination gets no requests");
        assertEquals(attempts, txInt(c -> Jdbc.one(c, "SELECT attempts FROM OUTBOX_DELIVERY WHERE outbox_id=? AND destination=?",
                rs -> rs.getInt(1), e.outboxId(), d).orElseThrow()), "no attempt consumed while held");

        assertTrue(health.enable("t1", d, "ops-1", "receiver redeployed"));
        assertEquals(1L, isoCount("SELECT count(*) FROM AUDIT_EVENT WHERE chain_id=? AND event_type='WEBHOOK_ENABLED'",
                "webhook:" + d));
        run(c -> Jdbc.update(c, "UPDATE OUTBOX_DELIVERY SET next_attempt_at=CURRENT_TIMESTAMP WHERE outbox_id=?", e.outboxId()));
        drain(relay);
        assertEquals("DONE", state(e.outboxId(), d));
        assertTrue(received.contains(key("rootX") + "#1"));
    }
}
