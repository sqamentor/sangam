// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.http;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.LookupResult;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.SecretResolver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SL-18 (4): static service-token headers for an edge access gate come from the secret port, not from config. SL-61:
 * when the port cannot supply a reference at call time, the request is not sent.
 */
class HttpRestConnectorServiceTokenTest {

    @Test
    void defaultHeaderSecretReferencesAreResolvedFromTheSecretPort() throws Exception {
        Map<String, String> seen = new ConcurrentHashMap<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            ex.getRequestHeaders().forEach((k, v) -> seen.put(k.toLowerCase(Locale.ROOT), v.get(0)));
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, 2);
            ex.getResponseBody().write("{}".getBytes());
            ex.close();
        });
        server.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            HttpRestConnector.Config cfg = new HttpRestConnector.Config("edge", base, CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP,
                    null, null, null,
                    Map.of("X-Edge-Client-Id", "secret://edge/client-id", "X-Edge-Client-Secret", "secret://edge/client-secret",
                            "X-Plain", "literal"),
                    Duration.ofSeconds(5), ConnectorProfile.SIMULATOR);
            SecretResolver secrets = SecretResolver.of(Map.of("secret://edge/client-id", "id-123",
                    "secret://edge/client-secret", "s3cr3t"));
            HttpRestConnector c = new HttpRestConnector(cfg, HttpTransport.forSimulator(), secrets);
            ConnectorResult r = c.invoke(new ConnectorRequest("t1", UUID.randomUUID(), "a1", 1, null, null, "invoke",
                    Map.of("method", "GET", "path", "/ping"), Instant.now().plusSeconds(10)));
            assertInstanceOf(ConnectorResult.Success.class, r, String.valueOf(r));
            assertEquals("id-123", seen.get("x-edge-client-id"));
            assertEquals("s3cr3t", seen.get("x-edge-client-secret"));
            assertEquals("literal", seen.get("x-plain"));
        } finally {
            server.stop(0);
        }
    }

    /**
     * SL-61: a reference the secret port cannot supply at call time (unknown, blank, or the port itself failed) is a
     * credential the call cannot get. The request is not sent and the result says so (no effect); no exception leaves
     * the connector, which the invoker would have to record as an unknown outcome although nothing was sent.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("unavailableAtCallTime")
    void aReferenceThePortCannotSupplyAtCallTimeMeansNothingIsSent(String why, Map<String, String> defaultHeaders,
            String authorization, SecretResolver port) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            requests.incrementAndGet();
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });
        server.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            HttpRestConnector c = new HttpRestConnector(new HttpRestConnector.Config("edge", base,
                    CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP, null, "/ops/{operationId}", authorization, defaultHeaders,
                    Duration.ofSeconds(5), ConnectorProfile.LIVE), HttpTransport.forSimulator(), port);
            ConnectorRequest request = new ConnectorRequest("t1", UUID.randomUUID(), "a1", 1, null, null, "invoke",
                    Map.of("body", Map.of("x", 1)), Instant.now().plusSeconds(10));

            ConnectorResult r = c.invoke(request);
            ConnectorResult.TransientFailure t = assertInstanceOf(ConnectorResult.TransientFailure.class, r, why + ": " + r);
            assertTrue(t.noEffect(), why + ": nothing was sent, so the failure has no effect");
            assertTrue(t.message().contains("credential unavailable") && t.message().contains("secret://edge/"),
                    why + ": " + t.message());
            LookupResult l = c.lookup(request);
            LookupResult.Unavailable u = assertInstanceOf(LookupResult.Unavailable.class, l, why + ": " + l);
            assertTrue(u.reason().contains("credential unavailable"), why + ": " + u.reason());
            assertEquals(0, requests.get(), why + ": no request reached the endpoint");
        } finally {
            server.stop(0);
        }
    }

    static Stream<Arguments> unavailableAtCallTime() {
        Map<String, String> edge = Map.of("X-Edge-Client-Id", "secret://edge/client-id");
        SecretResolver failing = reference -> {
            throw new UncheckedIOException(new IOException("vault unreachable"));
        };
        return Stream.of(
                Arguments.of("unknown default-header reference", edge, null, SecretResolver.of(Map.of())),
                Arguments.of("blank default-header value", edge, null,
                        SecretResolver.of(Map.of("secret://edge/client-id", " "))),
                Arguments.of("secret port failure", edge, null, failing),
                Arguments.of("unknown authorization reference", Map.of(), "secret://edge/token", SecretResolver.of(Map.of())));
    }

    /**
     * SL-65: a secret value with leading or trailing whitespace or a control character (typically the line break a file
     * store adds) is refused, never trimmed, since trimming could hide a wrong secret. The request is not sent, and the
     * value is never repeated in the result.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("untidySecrets")
    void aSecretWithWhitespaceOrAControlCharacterIsRefusedNotTrimmed(String why, Map<String, String> defaultHeaders,
            String authorization, String reference, String value) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            requests.incrementAndGet();
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });
        server.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            HttpRestConnector c = new HttpRestConnector(new HttpRestConnector.Config("edge", base,
                    CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP, null, "/ops/{operationId}", authorization, defaultHeaders,
                    Duration.ofSeconds(5), ConnectorProfile.LIVE), HttpTransport.forSimulator(),
                    SecretResolver.of(Map.of(reference, value)));
            ConnectorRequest request = new ConnectorRequest("t1", UUID.randomUUID(), "a1", 1, null, null, "invoke",
                    Map.of("body", Map.of("x", 1)), Instant.now().plusSeconds(10));

            ConnectorResult r = c.invoke(request);
            ConnectorResult.TransientFailure t = assertInstanceOf(ConnectorResult.TransientFailure.class, r, why + ": " + r);
            assertTrue(t.noEffect(), why + ": " + t.message());
            assertTrue(t.message().contains("credential unavailable") && t.message().contains(reference),
                    why + ": " + t.message());
            assertFalse(t.message().contains("s3cr3t"), why + ": the value is repeated: " + t.message());
            LookupResult.Unavailable u = assertInstanceOf(LookupResult.Unavailable.class, c.lookup(request), why);
            assertFalse(u.reason().contains("s3cr3t"), why + ": " + u.reason());
            assertEquals(0, requests.get(), why + ": no request reached the endpoint");
        } finally {
            server.stop(0);
        }
    }

    static Stream<Arguments> untidySecrets() {
        String edgeRef = "secret://edge/client-id";
        Map<String, String> edge = Map.of("X-Edge-Client-Id", edgeRef);
        return Stream.of(
                Arguments.of("trailing newline (file store)", edge, null, edgeRef, "s3cr3t-VALUE\n"),
                Arguments.of("trailing CR LF", edge, null, edgeRef, "s3cr3t-VALUE\r\n"),
                Arguments.of("leading space", edge, null, edgeRef, " s3cr3t-VALUE"),
                Arguments.of("trailing space", edge, null, edgeRef, "s3cr3t-VALUE "),
                Arguments.of("tab inside", edge, null, edgeRef, "s3cr3t\tVALUE"),
                Arguments.of("NUL inside", edge, null, edgeRef, "s3cr3t\u0000VALUE"),
                Arguments.of("authorization with a trailing newline", Map.of(), "secret://edge/token", "secret://edge/token",
                        "Bearer s3cr3t-VALUE\n"));
    }
}
