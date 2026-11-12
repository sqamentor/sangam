// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.LookupResult;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.SecretResolver;

/**
 * SL-62: header names are case-insensitive. The headers the connector sets itself (Authorization, the idempotency
 * header, X-Sangam-*) replace a default header of the same name in any letter case. The request's own headers (the
 * {@code headers} input, which comes from the process) can neither set those nor replace a header the connector fills
 * from the secret port. The stub records every value of every header, so a second value in another letter case shows.
 */
class HttpRestConnectorHeadersTest {

    HttpServer server;
    URI base;
    /** Every request that reached the stub: header name (any letter case) to all of its values, in order. */
    final List<Map<String, List<String>>> seen = new CopyOnWriteArrayList<>();
    final Map<String, String> port = Map.of("secret://edge/client-id", "edge-id-5Kq", "secret://edge/api-key", "ApiKey k-1");

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", x -> {
            Map<String, List<String>> h = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            x.getRequestHeaders().forEach((k, v) -> h.put(k, List.copyOf(v)));
            seen.add(h);
            x.getRequestBody().readAllBytes();
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().add("Content-Type", "application/json");
            x.sendResponseHeaders(200, body.length);
            x.getResponseBody().write(body);
            x.close();
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private HttpRestConnector connector(Map<String, String> defaultHeaders, String authorization,
                                        HttpRestConnector.Authorizer authorizer, String idempotencyHeader) {
        return new HttpRestConnector(new HttpRestConnector.Config("edge", base, CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP,
                idempotencyHeader, "/ops/{operationId}", authorization, defaultHeaders, Duration.ofSeconds(5),
                ConnectorProfile.LIVE, authorizer, null, null), HttpTransport.forSimulator(), SecretResolver.of(port));
    }

    private static ConnectorRequest request(UUID op, Map<String, Object> input) {
        return new ConnectorRequest("t1", op, "a1", 1, null, null, "invoke", input, Instant.now().plusSeconds(10));
    }

    private static void assertInvalidRequest(ConnectorResult r, String name) {
        ConnectorResult.BusinessFailure f = assertInstanceOf(ConnectorResult.BusinessFailure.class, r, name + ": " + r);
        assertEquals("INVALID_REQUEST", f.code(), name);
        assertTrue(f.message().toLowerCase(Locale.ROOT).contains(name.toLowerCase(Locale.ROOT)), name + ": " + f.message());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Authorization", "authorization", "AUTHORIZATION", "aUtHoRiZaTiOn", "Idempotency-Key",
            "idempotency-key", "IDEMPOTENCY-KEY", "X-Sangam-Operation-Id", "x-sangam-operation-id", "X-SANGAM-ATTEMPT-ID",
            "x-Sangam-Tenant"})
    void aRequestHeaderCannotSetAHeaderTheConnectorOwnsInAnyLetterCase(String name) {
        // with and without connector authentication: process input never carries a credential or a Sangam header
        for (HttpRestConnector c : List.of(connector(Map.of(), "Bearer abc", null, null),
                connector(Map.of(), null, null, null))) {
            UUID op = UUID.randomUUID();
            assertInvalidRequest(c.invoke(request(op, Map.of("headers", Map.of(name, "forged"), "body", Map.of("x", 1)))),
                    name);
            LookupResult l = c.lookup(request(op, Map.of("headers", Map.of(name, "forged"))));
            LookupResult.Unavailable u = assertInstanceOf(LookupResult.Unavailable.class, l, name + ": " + l);
            assertTrue(u.reason().contains("INVALID_REQUEST") && u.reason().contains(name), name + ": " + u.reason());
        }
        assertTrue(seen.isEmpty(), name + ": nothing was sent");
    }

    @Test
    void aConfiguredIdempotencyHeaderBelongsToTheConnectorInAnyLetterCase() {
        HttpRestConnector c = connector(Map.of("x-request-id", "from-config"), null, null, "X-Request-Id");
        assertInvalidRequest(c.invoke(request(UUID.randomUUID(), Map.of("headers", Map.of("X-REQUEST-ID", "forged")))),
                "X-REQUEST-ID");
        assertTrue(seen.isEmpty(), "nothing was sent");
        UUID op = UUID.randomUUID();
        assertInstanceOf(ConnectorResult.Success.class, c.invoke(request(op, Map.of())));
        assertEquals(List.of(op.toString()), seen.get(0).get("X-Request-Id"), "the operation id, once");
    }

    @ParameterizedTest
    @ValueSource(strings = {"X-Edge-Client-Id", "x-edge-client-id", "X-EDGE-CLIENT-ID"})
    void aRequestHeaderCannotReplaceAHeaderTheConnectorFillsFromTheSecretPort(String name) {
        HttpRestConnector c = connector(Map.of("X-Edge-Client-Id", "secret://edge/client-id"), null, null, null);
        assertInvalidRequest(c.invoke(request(UUID.randomUUID(), Map.of("headers", Map.of(name, "forged")))), name);
        assertTrue(seen.isEmpty(), name + ": nothing was sent");
    }

    @Test
    void theConnectorsOwnHeadersReplaceDefaultHeadersOfAnyLetterCase() {
        Map<String, String> defaults = Map.of("authorization", "from-config", "IDEMPOTENCY-KEY", "from-config",
                "x-sangam-operation-id", "from-config", "X-SANGAM-ATTEMPT-ID", "from-config");
        UUID op = UUID.randomUUID();
        assertInstanceOf(ConnectorResult.Success.class, connector(defaults, "Bearer abc", null, null)
                .invoke(request(op, Map.of())));
        Map<String, List<String>> h = seen.get(0);
        assertEquals(List.of("Bearer abc"), h.get("Authorization"));
        assertEquals(List.of(op.toString()), h.get("Idempotency-Key"));
        assertEquals(List.of(op.toString()), h.get("X-Sangam-Operation-Id"));
        assertEquals(List.of("a1"), h.get("X-Sangam-Attempt-Id"));

        // a dynamic credential (an Authorizer such as OAuth2 client credentials) replaces it the same way
        assertInstanceOf(ConnectorResult.Success.class, connector(defaults, null, tenant -> "Bearer dynamic", null)
                .invoke(request(UUID.randomUUID(), Map.of())));
        assertEquals(List.of("Bearer dynamic"), seen.get(1).get("Authorization"));
    }

    @Test
    void withoutConnectorAuthenticationAConfiguredAuthorizationHeaderIsSentOnce() {
        // configuration is trusted: an API with its own scheme gets it from a default header, by reference
        assertInstanceOf(ConnectorResult.Success.class, connector(Map.of("Authorization", "secret://edge/api-key"), null,
                null, null).invoke(request(UUID.randomUUID(), Map.of())));
        assertEquals(List.of("ApiKey k-1"), seen.get(0).get("Authorization"));
    }

    @Test
    void ordinaryHeadersAreCaseInsensitiveAndTheRequestsValueWins() {
        HttpRestConnector c = connector(Map.of("X-Trace", "from-config", "Accept", "application/json"), null, null, null);
        assertInstanceOf(ConnectorResult.Success.class, c.invoke(request(UUID.randomUUID(), Map.of("headers",
                Map.of("x-trace", "from-request", "content-type", "application/xml"), "body", "<a/>"))));
        Map<String, List<String>> h = seen.get(0);
        assertEquals(List.of("from-request"), h.get("X-Trace"));
        assertEquals(List.of("application/xml"), h.get("Content-Type"), "no second, default Content-Type");
        assertEquals(List.of("application/json"), h.get("Accept"));
    }

    @Test
    void aHeaderNameGivenTwiceInDifferentLetterCaseOrWithoutANameIsRefused() {
        Map<String, Object> twice = new LinkedHashMap<>();
        twice.put("X-Trace", "1");
        twice.put("x-trace", "2");
        assertInvalidRequest(connector(Map.of(), null, null, null).invoke(request(UUID.randomUUID(),
                Map.of("headers", twice))), "x-trace");
        ConnectorResult nameless = connector(Map.of(), null, null, null).invoke(request(UUID.randomUUID(),
                Map.of("headers", Map.of(" ", "1"))));
        assertEquals("INVALID_REQUEST", assertInstanceOf(ConnectorResult.BusinessFailure.class, nameless,
                String.valueOf(nameless)).code());
        assertTrue(seen.isEmpty(), "nothing was sent");

        IllegalArgumentException config = assertThrows(IllegalArgumentException.class,
                () -> connector(Map.of("X-Trace", "1", "x-trace", "2"), null, null, null));
        assertTrue(config.getMessage().toLowerCase(Locale.ROOT).contains("x-trace"), config.getMessage());
    }

    // ---- SL-65: what is not valid HTTP is refused before anything is sent, and a value is never repeated -------------

    static Stream<Arguments> invalidRequestHeaders() {
        return Stream.of(
                Arguments.of("CR LF injection in a value", "X-Trace", "a\r\nX-Injected: 1", "X-Injected"),
                Arguments.of("NUL in a value", "X-Trace", "a\u0000secret-part", "secret-part"),
                Arguments.of("character above U+00FF in a value", "X-Trace", "pay€secret-part", "secret-part"),
                Arguments.of("space in a name", "X Bad", "v", null),
                Arguments.of("line break in a name", "X-A\r\nX-Injected", "v", null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRequestHeaders")
    void aRequestHeaderThatIsNotValidHttpIsRefusedBeforeAnythingIsSent(String why, String name, String value,
            String mustNotAppear) {
        ConnectorResult r = connector(Map.of(), null, null, null).invoke(request(UUID.randomUUID(),
                Map.of("headers", Map.of(name, value), "body", Map.of("x", 1))));
        ConnectorResult.BusinessFailure f = assertInstanceOf(ConnectorResult.BusinessFailure.class, r, why + ": " + r);
        assertEquals("INVALID_REQUEST", f.code(), why);
        if (mustNotAppear != null) {
            assertFalse(f.message().contains(mustNotAppear), why + ": the message repeats the value: " + f.message());
        }
        assertFalse(f.message().contains("\r") || f.message().contains("\n"), why + ": the message is one line");
        assertTrue(seen.isEmpty(), why + ": nothing was sent");
    }

    @ParameterizedTest
    @ValueSource(strings = {"GE T", "CONNECT", "GET\r\nX-Injected: 1"})
    void aMethodThatIsNotValidHttpIsRefusedBeforeAnythingIsSent(String method) {
        ConnectorResult r = connector(Map.of(), null, null, null).invoke(request(UUID.randomUUID(),
                Map.of("method", method)));
        assertEquals("INVALID_REQUEST", assertInstanceOf(ConnectorResult.BusinessFailure.class, r, method + ": " + r)
                .code());
        assertTrue(seen.isEmpty(), "nothing was sent");
    }

    @Test
    void aConfigurationThatIsNotValidHttpIsRefusedWhenTheConnectorIsBuilt() {
        assertThrows(IllegalArgumentException.class, () -> connector(Map.of("X Bad", "v"), null, null, null));
        IllegalArgumentException value = assertThrows(IllegalArgumentException.class,
                () -> connector(Map.of("X-Trace", "v\r\nX-Injected: 1"), null, null, null));
        assertFalse(value.getMessage().contains("X-Injected"), value.getMessage());
        assertThrows(IllegalArgumentException.class, () -> new HttpRestConnector.Config("edge", base,
                CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP, null, null, null, Map.of(), Duration.ofSeconds(5),
                ConnectorProfile.LIVE, null, null, "GE T"));
    }

    @Test
    void anAuthorizationValueThatIsNotValidHttpMeansNothingIsSentAndIsNotRepeated() {
        // for example an OAuth2 token endpoint that answered with a line break in the token
        ConnectorResult r = connector(Map.of(), null, tenant -> "Bearer tok-SECRET\n", null)
                .invoke(request(UUID.randomUUID(), Map.of()));
        ConnectorResult.TransientFailure t = assertInstanceOf(ConnectorResult.TransientFailure.class, r, String.valueOf(r));
        assertTrue(t.noEffect(), t.message());
        assertFalse(t.message().contains("tok-SECRET"), "the token is not repeated: " + t.message());
        assertTrue(seen.isEmpty(), "nothing was sent");
    }
}
