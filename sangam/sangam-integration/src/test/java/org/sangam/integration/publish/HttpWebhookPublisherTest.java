// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.publish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Outbox;
import org.sangam.core.layer.Relay;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.connector.EgressPolicy;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.RetryPolicy;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.integration.relay.DeferPublishException;
import org.sangam.integration.relay.PermanentPublishException;
import org.sangam.integration.schema.IntegrationSchema;

/** SL-39: {@link HttpWebhookPublisher} against a local JDK HttpServer stub; health state on H2 (PostgreSQL mode). */
class HttpWebhookPublisherTest {

    static final Dialect D = Dialect.POSTGRESQL;
    static final String DEST = "webhook:crm";
    static final String SECRET = "whsec-test-1";

    HttpServer server;
    int port;
    final ConcurrentLinkedQueue<Integer> statuses = new ConcurrentLinkedQueue<>();
    final Map<String, String> replyHeaders = new HashMap<>();
    final List<Map<String, String>> seenHeaders = new CopyOnWriteArrayList<>();
    final List<byte[]> seenBodies = new CopyOnWriteArrayList<>();

    String url;
    Connection keepAlive;
    final AtomicLong now = new AtomicLong(Instant.parse("2026-11-08T10:00:00Z").toEpochMilli());
    final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(now.get());
        }
    };

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        port = server.getAddress().getPort();
        url = "jdbc:h2:mem:wh" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        keepAlive = DriverManager.getConnection(url, "sa", "");
        Schema.create(keepAlive, D);
        IntegrationSchema.create(keepAlive, D);
    }

    @AfterEach
    void stop() throws Exception {
        server.stop(0);
        keepAlive.close();
    }

    private void handle(HttpExchange x) throws java.io.IOException {
        Map<String, String> h = new HashMap<>();
        x.getRequestHeaders().forEach((k, v) -> h.put(k.toLowerCase(Locale.ROOT), v.get(0)));
        seenHeaders.add(h);
        seenBodies.add(x.getRequestBody().readAllBytes());
        Integer s = statuses.poll();
        int status = s == null ? 200 : s;
        replyHeaders.forEach((k, v) -> x.getResponseHeaders().add(k, v));
        x.sendResponseHeaders(status, -1);
        x.close();
    }

    Transactions tx() {
        return new Transactions() {
            @Override
            public <T> T inTransaction(Function<Connection, T> work) {
                try (Connection c = DriverManager.getConnection(url, "sa", "")) {
                    c.setAutoCommit(false);
                    try {
                        T r = work.apply(c);
                        c.commit();
                        return r;
                    } catch (RuntimeException e) {
                        c.rollback();
                        throw e;
                    }
                } catch (java.sql.SQLException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
    }

    HttpTransport transport(boolean allowPrivate) {
        return new HttpTransport(EgressPolicy.of(List.of(new EgressPolicy.Rule("*", "http", "127.0.0.1", port, "/",
                allowPrivate)), 0, 1 << 20), null, Duration.ofSeconds(2), Duration.ofSeconds(5), RetryPolicy.none());
    }

    WebhookHealth health(int disableAfter) {
        return new WebhookHealth(D, tx(), new AuditChain(D), disableAfter);
    }

    HttpWebhookPublisher publisher(WebhookHealth health, String secretRef, String path) {
        HttpWebhookPublisher.Endpoint ep = new HttpWebhookPublisher.Endpoint(
                URI.create("http://127.0.0.1:" + port + path), secretRef, Map.of("X-Custom", "1"));
        return new HttpWebhookPublisher(transport(true), d -> d.equals(DEST) ? ep : null,
                SecretResolver.of(Map.of("secret://wh/crm", SECRET, "secret://wh/rotating", "whsec-new,whsec-old")),
                health, Duration.ofHours(72), "urn:sangam:test", clock);
    }

    static Outbox.OutboxEvent event(String root, long seq) {
        return new Outbox.OutboxEvent(seq, UUID.randomUUID(), "t1", root, seq, "sangam.case.updated", "{\"n\":" + seq + "}");
    }

    @Test
    void signsTimestampDotBodyWithTheSubscriptionSecretFromTheSecretPort() throws Exception {
        HttpWebhookPublisher p = publisher(null, "secret://wh/crm", "/hook");
        Outbox.OutboxEvent ev = event("rootA", 1);
        p.publish(DEST, ev);
        Map<String, String> h = seenHeaders.get(0);
        byte[] body = seenBodies.get(0);
        String sig = h.get("x-sangam-signature");
        long t = now.get() / 1000;
        assertTrue(sig.startsWith("t=" + t + ",v1="), sig);
        byte[] secret = SECRET.getBytes(StandardCharsets.UTF_8);
        assertTrue(WebhookSignature.verify(secret, sig, body, Duration.ofMinutes(5), t));
        assertFalse(WebhookSignature.verify(secret, sig, "{\"n\":2}".getBytes(StandardCharsets.UTF_8), Duration.ofMinutes(5), t),
                "a changed body fails");
        assertFalse(WebhookSignature.verify("other".getBytes(StandardCharsets.UTF_8), sig, body, Duration.ofMinutes(5), t),
                "another subscription's secret fails");
        assertFalse(WebhookSignature.verify(secret, sig, body, Duration.ofMinutes(5), t + 301),
                "outside the 5 minute tolerance (replay) fails");
        assertTrue(WebhookSignature.verify(secret, sig, body, Duration.ofMinutes(5), t + 299));
        assertEquals("application/json", h.get("content-type"));
        assertEquals("1", h.get("x-custom"));
    }

    @Test
    void rotatingSecretSignsWithEveryActiveSecret() throws Exception {
        publisher(null, "secret://wh/rotating", "/hook").publish(DEST, event("r", 1));
        String sig = seenHeaders.get(0).get("x-sangam-signature");
        long t = now.get() / 1000;
        assertEquals(2, sig.split("v1=").length - 1);
        for (String s : List.of("whsec-new", "whsec-old")) {
            assertTrue(WebhookSignature.verify(s.getBytes(StandardCharsets.UTF_8), sig, seenBodies.get(0), null, t), s);
        }
    }

    @Test
    void idempotencyKeyIsTheEventIdOnEveryAttemptWhileTheSignatureIsFresh() throws Exception {
        HttpWebhookPublisher p = publisher(null, "secret://wh/crm", "/hook");
        Outbox.OutboxEvent ev = event("rootA", 1);
        statuses.add(500);
        assertThrows(IllegalStateException.class, () -> p.publish(DEST, ev), "5xx is retryable (relay backs off)");
        now.addAndGet(90_000);
        p.publish(DEST, ev);
        assertEquals(2, seenHeaders.size());
        String id = ev.eventId().toString();
        for (Map<String, String> h : seenHeaders) {
            assertEquals(id, h.get("idempotency-key"));
            assertEquals(id, h.get("ce-id"));
            assertEquals("rootA", h.get("ce-sangamroot"));
        }
        assertNotEquals(seenHeaders.get(0).get("x-sangam-signature"), seenHeaders.get(1).get("x-sangam-signature"),
                "each attempt is signed with its own timestamp");
    }

    @Test
    void outcomesAreClassifiedForTheRelay() throws Exception {
        WebhookHealth health = health(100);
        HttpWebhookPublisher p = publisher(health, "secret://wh/crm", "/hook");
        statuses.add(422);
        assertThrows(PermanentPublishException.class, () -> p.publish(DEST, event("r", 1)));
        statuses.add(503);
        assertThrows(IllegalStateException.class, () -> p.publish(DEST, event("r", 2)));
        replyHeaders.put("Retry-After", "7");
        statuses.add(429);
        DeferPublishException d = assertThrows(DeferPublishException.class, () -> p.publish(DEST, event("r", 3)));
        assertEquals(Duration.ofSeconds(7), d.delay(), "the receiver's Retry-After is honoured without an attempt");
        assertEquals(2, health.status(DEST).orElseThrow().consecutiveFailures(), "503 and 429 count, 422 does not");
        replyHeaders.clear();
        p.publish(DEST, event("r", 4));
        assertEquals(0, health.status(DEST).orElseThrow().consecutiveFailures(), "a 2xx ends the failure streak");
        statuses.add(410);
        assertThrows(PermanentPublishException.class, () -> p.publish(DEST, event("r", 5)));
        assertTrue(health.isDisabled(DEST), "410 Gone disables the destination at once");
    }

    @Test
    void ssrfGuardRefusesPrivateAddressesAndUnlistedHostsWithoutSending() {
        HttpWebhookPublisher.Endpoint metadata = new HttpWebhookPublisher.Endpoint(
                URI.create("http://169.254.169.254/latest/meta-data"), "secret://wh/crm", Map.of());
        HttpWebhookPublisher.Endpoint loopback = new HttpWebhookPublisher.Endpoint(
                URI.create("http://127.0.0.1:" + port + "/hook"), "secret://wh/crm", Map.of());
        SecretResolver secrets = SecretResolver.of(Map.of("secret://wh/crm", SECRET));
        // allow-list entry for the stub, but private addresses not allowed: loopback is refused
        HttpWebhookPublisher strict = new HttpWebhookPublisher(transport(false), d -> loopback, secrets, null, null,
                "urn:sangam:test", clock);
        PermanentPublishException e1 = assertThrows(PermanentPublishException.class, () -> strict.publish(DEST, event("r", 1)));
        assertTrue(e1.getMessage().startsWith("EGRESS_DENIED"), e1.getMessage());
        // the cloud metadata address is not on the allow-list at all
        HttpWebhookPublisher unlisted = new HttpWebhookPublisher(transport(true), d -> metadata, secrets, null, null,
                "urn:sangam:test", clock);
        PermanentPublishException e2 = assertThrows(PermanentPublishException.class, () -> unlisted.publish(DEST, event("r", 1)));
        assertTrue(e2.getMessage().startsWith("EGRESS_DENIED"), e2.getMessage());
        assertTrue(seenHeaders.isEmpty(), "nothing was sent");
    }

    @Test
    void unsignedOrUnknownEndpointsArePermanentFailures() {
        assertThrows(PermanentPublishException.class, () -> publisher(null, null, "/hook").publish(DEST, event("r", 1)));
        assertThrows(PermanentPublishException.class, () -> publisher(null, "secret://wh/crm", "/hook")
                .publish("webhook:unknown", event("r", 1)));
        assertTrue(seenHeaders.isEmpty());
    }

    @Test
    void repeatedFailuresDisableAuditHoldThenDeadLetterAndEnableResumes() throws Exception {
        WebhookHealth health = health(3);
        HttpWebhookPublisher p = publisher(health, "secret://wh/crm", "/hook");
        for (int i = 0; i < 3; i++) {
            statuses.add(500);
            assertThrows(IllegalStateException.class, () -> p.publish(DEST, event("r", 1)));
        }
        WebhookHealth.Status s = health.status(DEST).orElseThrow();
        assertEquals(WebhookHealth.State.DISABLED, s.state());
        assertEquals("auto", s.changedBy());
        assertEquals(1L, auditCount(WebhookHealth.DISABLED_EVENT), "exactly one disable event");
        int sent = seenHeaders.size();
        // held: deferred without an attempt and without a request
        DeferPublishException held = assertThrows(DeferPublishException.class, () -> p.publish(DEST, event("r", 1)));
        assertTrue(held.delay().compareTo(Duration.ZERO) > 0);
        assertEquals(sent, seenHeaders.size());
        // after the hold period the delivery is dead-lettered (permanent)
        now.set(s.disabledAt().plus(Duration.ofHours(73)).toEpochMilli());
        PermanentPublishException dead = assertThrows(PermanentPublishException.class, () -> p.publish(DEST, event("r", 1)));
        assertTrue(dead.getMessage().startsWith("WEBHOOK_DISABLED"), dead.getMessage());
        // re-enable path: audited, counter reset, deliveries flow again
        assertThrows(IllegalArgumentException.class, () -> health.enable("t1", DEST, "ops-1", " "));
        assertTrue(health.enable("t1", DEST, "ops-1", "receiver fixed"));
        assertFalse(health.enable("t1", DEST, "ops-1", "again"), "already enabled");
        assertEquals(1L, auditCount(WebhookHealth.ENABLED_EVENT));
        p.publish(DEST, event("r", 1));
        assertEquals(sent + 1, seenHeaders.size());
        assertEquals(0, health.status(DEST).orElseThrow().consecutiveFailures());
        assertTrue(tx().inTransaction(c -> new AuditChain(D).verify(c, "t1", "webhook:" + DEST)).ok());
    }

    @Test
    void relayBackoffIsExponentialCappedAndFullyJittered() {
        Relay.Config cfg = new Relay.Config("n", 10, Duration.ofSeconds(30), 10, Duration.ofMillis(200), Duration.ofSeconds(5));
        assertEquals(0, Relay.backoffMillis(cfg, 1, 0.0));
        assertEquals(200, Relay.backoffMillis(cfg, 1, 0.9999999));
        assertEquals(800, Relay.backoffMillis(cfg, 3, 0.9999999), "200 * 2^2");
        assertEquals(5000, Relay.backoffMillis(cfg, 30, 0.9999999), "capped");
        assertEquals(2500, Relay.backoffMillis(cfg, 30, 0.5), "uniform in [0, ceiling]");
        java.util.Set<Long> spread = new java.util.HashSet<>();
        java.util.Random r = new java.util.Random(42);
        for (int i = 0; i < 50; i++) {
            spread.add(Relay.backoffMillis(cfg, 6, r.nextDouble()));
        }
        assertTrue(spread.size() > 40, "retries of one outage are spread, not synchronised: " + spread.size());
        Relay.Config zero = new Relay.Config("n", 10, Duration.ofSeconds(30), 10, Duration.ZERO, Duration.ZERO);
        assertEquals(0, Relay.backoffMillis(zero, 5, 0.7));
    }

    long auditCount(String type) {
        return tx().inTransaction(c -> Jdbc.one(c, "SELECT COUNT(*) FROM AUDIT_EVENT WHERE chain_id=? AND event_type=?",
                rs -> rs.getLong(1), "webhook:" + DEST, type).orElse(0L));
    }
}
