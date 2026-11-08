// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.events.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.ports.events.model.ChannelDefinition;
import org.sangam.ports.support.Json;

/** FC-03 outbound HTTP/webhook adapter against a local JDK HTTP server on 127.0.0.1 (no other network). */
class HttpOutboundAdapterTest {

    record Received(String method, Map<String, String> headers, String body) {
    }

    private HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final AtomicInteger status = new AtomicInteger(204);
    private volatile long delayMs;
    private String base;

    static final ChannelAdapters.Environment ENV = ChannelAdapters.Environment.of(Map.of("hook-secret", "k3y"), Set.of("127.0.0.1"));

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            Map<String, String> h = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            ex.getRequestHeaders().forEach((k, v) -> h.put(k, v.get(0)));
            received.add(new Received(ex.getRequestMethod(), h, new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            int s = status.get();
            if (s == 302) {
                ex.getResponseHeaders().add("Location", base + "/elsewhere");
            }
            ex.sendResponseHeaders(s, -1);
            ex.close();
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    static ChannelDefinition channel(String url, String props) {
        return Json.read("{\"key\":\"out\",\"channelType\":\"http\",\"type\":\"outbound\",\"url\":\"" + url + "\",\"properties\":{"
                + props + "}}", ChannelDefinition.class);
    }

    @Test
    void postsTheBodyWithIdempotencyHeadersAndAVerifiableSignature() throws Exception {
        HttpOutboundAdapter a = new HttpOutboundAdapter(channel(base + "/in", "\"allowPlainHttp\":\"true\",\"secretRef\":\"hook-secret\""), ENV);
        String body = "{\"eventKey\":\"paid\",\"messageId\":\"m-1\"}";
        a.send("acc", "paid", "m-1", body);
        assertEquals(1, received.size());
        Received r = received.get(0);
        assertEquals("POST", r.method());
        assertEquals(body, r.body());
        assertEquals("application/json", r.headers().get("Content-Type"));
        assertEquals("m-1", r.headers().get("Idempotency-Key"));
        assertEquals("m-1", r.headers().get("X-Sangam-Message-Id"));
        assertEquals("paid", r.headers().get("X-Sangam-Event-Key"));
        assertEquals("sha256=" + WebhookInboundEndpoint.hmacHex("k3y", body), r.headers().get("X-Sangam-Signature"));
    }

    @Test
    void withoutSecretRefNoSignatureIsSent() throws Exception {
        new HttpOutboundAdapter(channel(base + "/in", "\"allowPlainHttp\":\"true\""), ENV).send("acc", "paid", "m-2", "{}");
        assertNull(received.get(0).headers().get("X-Sangam-Signature"));
    }

    @Test
    void aNon2xxResponseThrowsSoTheRelayRetriesAndTheRetryCarriesTheSameIdempotencyKey() throws Exception {
        HttpOutboundAdapter a = new HttpOutboundAdapter(channel(base + "/in", "\"allowPlainHttp\":\"true\""), ENV);
        status.set(503);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> a.send("acc", "paid", "m-3", "{}"));
        assertTrue(e.getMessage().contains("503"), e.getMessage());
        status.set(200);
        a.send("acc", "paid", "m-3", "{}");
        assertEquals(2, received.size());
        assertEquals(received.get(0).headers().get("Idempotency-Key"), received.get(1).headers().get("Idempotency-Key"),
                "at-least-once: the consumer dedupes the retry by message id");
    }

    @Test
    void redirectsAreNotFollowed() throws Exception {
        HttpOutboundAdapter a = new HttpOutboundAdapter(channel(base + "/in", "\"allowPlainHttp\":\"true\""), ENV);
        status.set(302);
        assertThrows(IllegalStateException.class, () -> a.send("acc", "paid", "m-4", "{}"));
        assertEquals(1, received.size(), "no second request to the Location target");
    }

    @Test
    void aHangingEndpointTimesOutWithinTheBoundedTimeout() throws Exception {
        HttpOutboundAdapter a = new HttpOutboundAdapter(channel(base + "/in", "\"allowPlainHttp\":\"true\",\"timeoutMs\":\"1\""), ENV);
        delayMs = 4_000;
        long t0 = System.nanoTime();
        assertThrows(java.net.http.HttpTimeoutException.class, () -> a.send("acc", "paid", "m-5", "{}"));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ms >= 900 && ms < 3_500, "timeout clamped to the 1 s floor, took " + ms + " ms");
    }

    @Test
    void constructionRefusesPlainHttpForeignHostsMissingSecretsAndBadTimeouts() {
        IllegalArgumentException plain = assertThrows(IllegalArgumentException.class, () -> new HttpOutboundAdapter(channel(base + "/in", ""), ENV));
        assertTrue(plain.getMessage().contains("https required"));
        IllegalArgumentException egress = assertThrows(IllegalArgumentException.class,
                () -> new HttpOutboundAdapter(channel("https://example.org/in", ""), ENV));
        assertTrue(egress.getMessage().contains("egress allow-list"));
        IllegalStateException secret = assertThrows(IllegalStateException.class,
                () -> new HttpOutboundAdapter(channel(base + "/in", "\"allowPlainHttp\":\"true\",\"secretRef\":\"nope\""), ENV));
        assertTrue(secret.getMessage().contains("nope"));
        assertThrows(IllegalArgumentException.class,
                () -> new HttpOutboundAdapter(channel(base + "/in", "\"allowPlainHttp\":\"true\",\"timeoutMs\":\"soon\""), ENV));
        assertEquals(0, received.size());
    }
}
