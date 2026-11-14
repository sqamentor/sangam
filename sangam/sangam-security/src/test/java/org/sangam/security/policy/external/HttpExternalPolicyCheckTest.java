// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.policy.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.engine.LockScope;

/**
 * Owner decision 166 (M-083): the HTTP adapter against a loopback policy service. The request carries the OPA input
 * envelope built from the identity and the command (never the command body); every decision form is read; every other
 * answer (status, format, size, silence, refused connection) is {@link ExternalPolicyUnavailable}, never an allow; the
 * credential never appears in an exception; and no call is made while an L2 lock is held. Written for the test phase
 * (not run here).
 */
class HttpExternalPolicyCheckTest {

    static final String TOKEN = "test-token-not-a-secret";
    static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** A policy service that answers every call with {@code status} and {@code body} after {@code delayMs}. */
    URI serve(int status, String body, long delayMs) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/data/sangam/command", (HttpExchange ex) -> {
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            authorizations.add(auth == null ? "" : auth);
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            if (status == 302) {
                ex.getResponseHeaders().add("Location", "http://127.0.0.1:1/elsewhere");
            }
            ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(b);
            }
        });
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/data/sangam/command");
    }

    static HttpExternalPolicyCheck check(URI endpoint, Duration timeout, String token) {
        return new HttpExternalPolicyCheck(new HttpExternalPolicyCheck.Settings(endpoint, Duration.ofSeconds(1), timeout,
                true), token == null ? null : () -> token);
    }

    static ExternalPolicyRequest request() {
        return new ExternalPolicyRequest("t1", "u1", "OIDC", Set.of("clerk", "auditor"), "task.complete", "POST",
                "/tasks/{taskId}/complete", Map.of("taskId", "task-1"));
    }

    @Test
    void theRequestIsTheInputEnvelopeOfIdentityAndCommandOnly() throws Exception {
        URI uri = serve(200, "{\"result\":{\"decision\":\"ALLOW\"}}", 0);
        ExternalPolicyDecision d = check(uri, Duration.ofSeconds(2), TOKEN).check(request());
        assertEquals(ExternalPolicyDecision.Outcome.ALLOW_CONTINUE, d.outcome());
        JsonNode input = JSON.readTree(bodies.get(0)).get("input");
        assertEquals(ExternalPolicyRequest.CONTRACT, input.get("contract").asText());
        assertEquals("t1", input.get("tenant").asText());
        assertEquals("u1", input.at("/actor/id").asText());
        assertEquals("OIDC", input.at("/actor/auth_method").asText());
        assertEquals("[\"auditor\",\"clerk\"]", input.at("/actor/groups").toString());
        assertEquals("task.complete", input.get("action").asText());
        assertEquals("/tasks/{taskId}/complete", input.at("/resource/path_template").asText());
        assertEquals("task-1", input.at("/resource/path_params/taskId").asText());
        assertEquals(Set.of("contract", "tenant", "actor", "action", "resource"),
                Set.copyOf(iterable(input.fieldNames())), "nothing else, in particular no command body");
        assertEquals("Bearer " + TOKEN, authorizations.get(0));
    }

    @Test
    void everyDecisionFormIsRead() throws Exception {
        Map<String, ExternalPolicyDecision.Outcome> forms = Map.of(
                "{\"decision\":\"DENY\",\"reason_code\":\"OUTSIDE_HOURS\"}", ExternalPolicyDecision.Outcome.DENY,
                "{\"decision\":\"Deny\"}", ExternalPolicyDecision.Outcome.DENY,
                "{\"decision\":\"Allow\"}", ExternalPolicyDecision.Outcome.ALLOW_CONTINUE,
                "{\"decision\":\"ALLOW_CONTINUE\"}", ExternalPolicyDecision.Outcome.ALLOW_CONTINUE,
                "{\"result\":{\"allow\":false}}", ExternalPolicyDecision.Outcome.DENY,
                "{\"result\":true}", ExternalPolicyDecision.Outcome.ALLOW_CONTINUE,
                "{\"result\":false}", ExternalPolicyDecision.Outcome.DENY);
        for (var e : forms.entrySet()) {
            ExternalPolicyDecision d = HttpExternalPolicyCheck.parse(e.getKey().getBytes(StandardCharsets.UTF_8), "o");
            assertEquals(e.getValue(), d.outcome(), e.getKey());
        }
        ExternalPolicyDecision deny = HttpExternalPolicyCheck.parse(
                "{\"decision\":\"DENY\",\"reason_code\":\"OUTSIDE_HOURS\",\"policy_id\":\"pol-3\"}"
                        .getBytes(StandardCharsets.UTF_8), "o");
        assertEquals("OUTSIDE_HOURS", deny.reasonCode());
        assertEquals("pol-3", deny.policyId());
    }

    @Test
    void anAnswerThatIsNoDecisionIsUnavailableNeverAnAllow() {
        for (String body : List.of("{}", "{\"result\":{}}", "[]", "not json", "{\"decision\":\"PERMIT_ALL\"}",
                "{\"decision\":true}", "{\"allow\":\"yes\"}", "{\"result\":\"ALLOW\"}")) {
            ExternalPolicyUnavailable e = assertThrows(ExternalPolicyUnavailable.class,
                    () -> HttpExternalPolicyCheck.parse(body.getBytes(StandardCharsets.UTF_8), "o"), body);
            assertEquals(ExternalPolicyUnavailable.Reason.BAD_RESPONSE, e.reason(), body);
        }
    }

    @Test
    void aStatusOtherThan200IsUnavailableAndARedirectIsNotFollowed() throws Exception {
        URI uri = serve(500, "{\"decision\":\"ALLOW\"}", 0);
        ExternalPolicyUnavailable e = assertThrows(ExternalPolicyUnavailable.class,
                () -> check(uri, Duration.ofSeconds(2), null).check(request()));
        assertEquals(ExternalPolicyUnavailable.Reason.BAD_STATUS, e.reason());
        stop();
        URI redirect = serve(302, "", 0);
        ExternalPolicyUnavailable r = assertThrows(ExternalPolicyUnavailable.class,
                () -> check(redirect, Duration.ofSeconds(2), null).check(request()));
        assertEquals(ExternalPolicyUnavailable.Reason.BAD_STATUS, r.reason());
    }

    @Test
    void anOversizedAnswerIsUnavailable() throws Exception {
        URI uri = serve(200, "{\"decision\":\"ALLOW\",\"pad\":\"" + "x".repeat(HttpExternalPolicyCheck.MAX_RESPONSE_BYTES)
                + "\"}", 0);
        ExternalPolicyUnavailable e = assertThrows(ExternalPolicyUnavailable.class,
                () -> check(uri, Duration.ofSeconds(2), null).check(request()));
        assertEquals(ExternalPolicyUnavailable.Reason.BAD_RESPONSE, e.reason());
    }

    @Test
    void aSlowServiceTimesOut() throws Exception {
        URI uri = serve(200, "{\"decision\":\"ALLOW\"}", 3_000);
        long start = System.nanoTime();
        ExternalPolicyUnavailable e = assertThrows(ExternalPolicyUnavailable.class,
                () -> check(uri, Duration.ofMillis(200), null).check(request()));
        assertEquals(ExternalPolicyUnavailable.Reason.TIMEOUT, e.reason());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(2)) < 0,
                "the deadline bounds the wait");
    }

    @Test
    void aRefusedConnectionIsUnavailable() throws Exception {
        URI uri = serve(200, "{}", 0);
        stop();
        server = null;
        ExternalPolicyUnavailable e = assertThrows(ExternalPolicyUnavailable.class,
                () -> check(uri, Duration.ofSeconds(2), null).check(request()));
        assertTrue(e.reason() == ExternalPolicyUnavailable.Reason.TRANSPORT
                || e.reason() == ExternalPolicyUnavailable.Reason.TIMEOUT, e.reason().name());
    }

    @Test
    void anEmptyCredentialIsNotConfiguredAndNoCredentialLeaksIntoAMessage() throws Exception {
        URI uri = serve(503, "", 0);
        ExternalPolicyUnavailable blank = assertThrows(ExternalPolicyUnavailable.class,
                () -> check(uri, Duration.ofSeconds(2), " ").check(request()));
        assertEquals(ExternalPolicyUnavailable.Reason.NOT_CONFIGURED, blank.reason());
        assertTrue(bodies.isEmpty(), "no call without the configured credential");
        ExternalPolicyUnavailable failing = assertThrows(ExternalPolicyUnavailable.class,
                () -> new HttpExternalPolicyCheck(new HttpExternalPolicyCheck.Settings(uri, Duration.ofSeconds(1),
                        Duration.ofSeconds(1), true), () -> {
                    throw new IllegalStateException("cannot read /run/secrets/" + TOKEN);
                }).check(request()));
        assertEquals(ExternalPolicyUnavailable.Reason.NOT_CONFIGURED, failing.reason());
        assertFalse(failing.getMessage().contains(TOKEN));
        assertNull(failing.getCause());
        ExternalPolicyUnavailable status = assertThrows(ExternalPolicyUnavailable.class,
                () -> check(uri, Duration.ofSeconds(2), TOKEN).check(request()));
        assertFalse(status.getMessage().contains(TOKEN));
    }

    @Test
    void noCallIsMadeWhileAnL2LockIsHeld() throws Exception {
        URI uri = serve(200, "{\"decision\":\"ALLOW\"}", 0);
        AtomicInteger before = new AtomicInteger(bodies.size());
        LockScope.enter();
        try {
            assertThrows(LockScope.IllegalRemoteCallInLockScope.class,
                    () -> check(uri, Duration.ofSeconds(2), null).check(request()));
        } finally {
            LockScope.exit();
        }
        assertEquals(before.get(), bodies.size());
    }

    @Test
    void theEndpointMustBeHttpsWithoutCredentialsAndTheTimeoutsBounded() {
        Duration s = Duration.ofSeconds(1);
        assertThrows(IllegalArgumentException.class,
                () -> new HttpExternalPolicyCheck.Settings(URI.create("http://pdp.example/v1"), s, s, false));
        assertThrows(IllegalArgumentException.class,
                () -> new HttpExternalPolicyCheck.Settings(URI.create("https://user:pw@pdp.example/v1"), s, s, false));
        assertThrows(IllegalArgumentException.class,
                () -> new HttpExternalPolicyCheck.Settings(URI.create("ftp://pdp.example/v1"), s, s, true));
        assertThrows(IllegalArgumentException.class,
                () -> new HttpExternalPolicyCheck.Settings(URI.create("https://pdp.example/v1"), Duration.ZERO, s, false));
        assertThrows(IllegalArgumentException.class,
                () -> new HttpExternalPolicyCheck.Settings(URI.create("https://pdp.example/v1"), s, Duration.ofMinutes(2), false));
        assertEquals("https://pdp.example:8443", new HttpExternalPolicyCheck.Settings(
                URI.create("https://pdp.example:8443/v1/data/x?q=1"), s, s, false).origin());
    }

    private static <T> List<T> iterable(java.util.Iterator<T> it) {
        List<T> out = new java.util.ArrayList<>();
        it.forEachRemaining(out::add);
        return out;
    }
}
