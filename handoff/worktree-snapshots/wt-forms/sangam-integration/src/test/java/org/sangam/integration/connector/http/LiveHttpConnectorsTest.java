// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.integration.connector.ConnectorRegistry;
import org.sangam.integration.connector.SecretResolver;

/** SL-17: configuration-built LIVE HTTP connector against a local JDK HttpServer stub. */
class LiveHttpConnectorsTest {

    HttpServer server;
    String base;
    final Map<String, Integer> statusByPath = new ConcurrentHashMap<>();
    final List<Map<String, String>> seen = new CopyOnWriteArrayList<>();
    final AtomicInteger tokenCalls = new AtomicInteger();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange x) throws java.io.IOException {
        String path = x.getRequestURI().getPath();
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);   // the connector appends the request's default path "/"
        }
        Map<String, String> h = new java.util.HashMap<>();
        x.getRequestHeaders().forEach((k, v) -> h.put(k.toLowerCase(java.util.Locale.ROOT), v.get(0)));
        h.put(":path", path);
        seen.add(h);
        byte[] body;
        int status;
        if (path.equals("/token")) {
            tokenCalls.incrementAndGet();
            status = 200;
            body = "{\"access_token\":\"tok-1\",\"expires_in\":3600}".getBytes(StandardCharsets.UTF_8);
        } else if (path.equals("/slow")) {
            try {
                Thread.sleep(3_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            status = 200;
            body = "{}".getBytes(StandardCharsets.UTF_8);
        } else {
            status = statusByPath.getOrDefault(path, 200);
            body = ("{\"result\":{\"ref\":\"REF-42\",\"amount\":7},\"status\":\"" + status + "\"}").getBytes(StandardCharsets.UTF_8);
        }
        x.getResponseHeaders().add("Content-Type", "application/json");
        x.sendResponseHeaders(status, body.length);
        x.getResponseBody().write(body);
        x.close();
    }

    private LiveHttpConnectors.Spec spec(String cls, String path, LiveHttpConnectors.Auth auth, boolean allowPrivate,
                                        Duration readTimeout) {
        return new LiveHttpConnectors.Spec("pay", base + path, "POST", cls, null, null, auth, null,
                Duration.ofSeconds(2), readTimeout, 1, List.of(), allowPrivate, Map.of(),
                Map.of("amount", "/result/amount"), "/result/ref");
    }

    private static ConnectorRequest request(UUID op) {
        return new ConnectorRequest("t1", op, "att-1", 1, "root-1", "bk-1", "pay", Map.of("body", Map.of("x", 1)),
                Instant.now().plusSeconds(30));
    }

    @Test
    void successMapsOutputAndProviderRefAndSendsTheOperationIdAsIdempotencyKey() {
        HttpRestConnector c = LiveHttpConnectors.build("t1", spec("a", "/pay", LiveHttpConnectors.Auth.NONE, true,
                Duration.ofSeconds(2)), SecretResolver.of(Map.of()), false);
        assertEquals(ConnectorProfile.LIVE, c.descriptor().profile());
        UUID op = UUID.randomUUID();
        ConnectorResult r = c.invoke(request(op));
        ConnectorResult.Success s = assertInstanceOf(ConnectorResult.Success.class, r, String.valueOf(r));
        assertEquals("REF-42", s.providerRef());
        assertEquals(7, ((Map<?, ?>) s.output().get("mapped")).get("amount"));
        assertEquals(op.toString(), seen.get(0).get("idempotency-key"));
        // registered per tenant through the existing registry
        ConnectorRegistry reg = new ConnectorRegistry().register("t1", c);
        assertTrue(reg.find("t1", "pay").isPresent());
        assertFalse(reg.find("t2", "pay").isPresent());
    }

    @Test
    void readTimeoutAfterSendIsOutcomeUnknown() {
        HttpRestConnector c = LiveHttpConnectors.build("t1", spec("c", "/slow", LiveHttpConnectors.Auth.NONE, true,
                Duration.ofMillis(500)), SecretResolver.of(Map.of()), false);
        ConnectorResult r = c.invoke(request(UUID.randomUUID()));
        assertInstanceOf(ConnectorResult.OutcomeUnknown.class, r, String.valueOf(r));
    }

    @Test
    void clientErrorIsBusinessFailure() {
        statusByPath.put("/bad", 422);
        HttpRestConnector c = LiveHttpConnectors.build("t1", spec("a", "/bad", LiveHttpConnectors.Auth.NONE, true,
                Duration.ofSeconds(2)), SecretResolver.of(Map.of()), false);
        ConnectorResult.BusinessFailure f = assertInstanceOf(ConnectorResult.BusinessFailure.class,
                c.invoke(request(UUID.randomUUID())));
        assertEquals("HTTP_422", f.code());
    }

    @Test
    void serverErrorIsRetryable() {
        statusByPath.put("/down", 500);
        HttpRestConnector c = LiveHttpConnectors.build("t1", spec("a", "/down", LiveHttpConnectors.Auth.NONE, true,
                Duration.ofSeconds(2)), SecretResolver.of(Map.of()), false);
        assertInstanceOf(ConnectorResult.TransientFailure.class, c.invoke(request(UUID.randomUUID())));
        statusByPath.put("/busy", 503);
        HttpRestConnector c2 = LiveHttpConnectors.build("t1", spec("c", "/busy", LiveHttpConnectors.Auth.NONE, true,
                Duration.ofSeconds(2)), SecretResolver.of(Map.of()), false);
        ConnectorResult.TransientFailure t = assertInstanceOf(ConnectorResult.TransientFailure.class,
                c2.invoke(request(UUID.randomUUID())));
        assertTrue(t.noEffect(), "503 is a no-effect transient failure even for class (c)");
    }

    @Test
    void loopbackIsRefusedUnlessExplicitlyAllowed() {
        HttpRestConnector c = LiveHttpConnectors.build("t1", spec("a", "/pay", LiveHttpConnectors.Auth.NONE, false,
                Duration.ofSeconds(2)), SecretResolver.of(Map.of()), false);
        ConnectorResult.TransientFailure t = assertInstanceOf(ConnectorResult.TransientFailure.class,
                c.invoke(request(UUID.randomUUID())));
        assertTrue(t.noEffect());
        assertTrue(t.message().contains("EGRESS_DENIED"), t.message());
        assertTrue(seen.isEmpty(), "nothing reached the stub");
    }

    @Test
    void bearerAndOauth2HeadersAreSent() {
        HttpRestConnector bearer = LiveHttpConnectors.build("t1", spec("a", "/pay",
                new LiveHttpConnectors.Auth(LiveHttpConnectors.AuthType.BEARER, "secret://t/token", null, null, null, null),
                true, Duration.ofSeconds(2)), SecretResolver.of(Map.of("secret://t/token", "abc")), false);
        assertInstanceOf(ConnectorResult.Success.class, bearer.invoke(request(UUID.randomUUID())));
        assertEquals("Bearer abc", seen.get(0).get("authorization"));

        HttpRestConnector oauth = LiveHttpConnectors.build("t1", spec("a", "/pay",
                new LiveHttpConnectors.Auth(LiveHttpConnectors.AuthType.OAUTH2_CLIENT_CREDENTIALS, null, base + "/token",
                        "client-1", "secret://t/cs", "pay"), true, Duration.ofSeconds(2)),
                SecretResolver.of(Map.of("secret://t/cs", "s3")), false);
        assertInstanceOf(ConnectorResult.Success.class, oauth.invoke(request(UUID.randomUUID())));
        assertInstanceOf(ConnectorResult.Success.class, oauth.invoke(request(UUID.randomUUID())));
        assertEquals(1, tokenCalls.get(), "token cached");
        assertEquals("Bearer tok-1", seen.get(seen.size() - 1).get("authorization"));
    }

    @Test
    void productionRefusesHttpUrlsAndMissingOrLiteralSecrets() {
        SecretResolver none = SecretResolver.of(Map.of());
        IllegalStateException http = assertThrows(IllegalStateException.class, () -> LiveHttpConnectors.build("t1",
                spec("a", "/pay", LiveHttpConnectors.Auth.NONE, true, null), none, true));
        assertTrue(http.getMessage().contains("https"), http.getMessage());

        LiveHttpConnectors.Spec https = new LiveHttpConnectors.Spec("pay", "https://api.example.org/v1", "POST", "a", null,
                null, new LiveHttpConnectors.Auth(LiveHttpConnectors.AuthType.BEARER, "secret://t/missing", null, null, null,
                null), null, null, null, 1, List.of(), false, Map.of(), Map.of(), null);
        IllegalStateException missing = assertThrows(IllegalStateException.class,
                () -> LiveHttpConnectors.build("t1", https, none, true));
        assertTrue(missing.getMessage().contains("cannot be resolved"), missing.getMessage());

        LiveHttpConnectors.Spec literal = new LiveHttpConnectors.Spec("pay", "https://api.example.org/v1", "POST", "a", null,
                null, new LiveHttpConnectors.Auth(LiveHttpConnectors.AuthType.BEARER, "plain-token", null, null, null, null),
                null, null, null, 1, List.of(), false, Map.of(), Map.of(), null);
        assertThrows(IllegalStateException.class, () -> LiveHttpConnectors.build("t1", literal, none, true));

        LiveHttpConnectors.Spec noKey = new LiveHttpConnectors.Spec("pay", "https://api.example.org/v1", "POST", "a", null,
                null, new LiveHttpConnectors.Auth(LiveHttpConnectors.AuthType.MTLS, null, null, null, null, null),
                null, null, null, 1, List.of(), false, Map.of(), Map.of(), null);
        assertThrows(IllegalStateException.class, () -> LiveHttpConnectors.build("t1", noKey, none, true));

        // with a resolvable reference the same production spec builds
        LiveHttpConnectors.build("t1", https, SecretResolver.of(Map.of("secret://t/missing", "x")), true);
    }
}
