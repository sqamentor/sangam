// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * SL-65: a request the HTTP client refuses to build (a header name or value, the method or the URI is not valid HTTP) was
 * not sent. The transport says so (no effect) instead of throwing, which a connector caller would have to record as an
 * unknown outcome, and the reason never repeats a header value, which may be a credential.
 */
class HttpTransportRequestBuildTest {

    HttpServer server;
    URI base;
    final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", x -> {
            requests.incrementAndGet();
            x.sendResponseHeaders(204, -1);
            x.close();
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/op");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    /** Three attempts allowed: a refused build must not use them (the same call would be refused again). */
    private static HttpTransport transport() {
        return new HttpTransport(EgressPolicy.simulatorLoopback(), null, Duration.ofSeconds(2), Duration.ofSeconds(5),
                new RetryPolicy(3, Duration.ofMillis(1), Duration.ofMillis(5), 0.0));
    }

    static Stream<Arguments> unbuildable() {
        return Stream.of(
                Arguments.of("secret with a trailing newline", "POST", Map.of("X-Edge-Token", "s3cret-token-VALUE\n"),
                        "s3cret-token-VALUE"),
                Arguments.of("CR LF injection in a value", "POST", Map.of("X-Trace", "a\r\nX-Injected: 1"), "X-Injected"),
                Arguments.of("NUL in a value", "POST", Map.of("X-Trace", "a\u0000secret-part"), "secret-part"),
                Arguments.of("character above U+00FF in a value", "POST", Map.of("X-Trace", "pay€secret-part"),
                        "secret-part"),
                Arguments.of("space in a name", "POST", Map.of("X Bad", "v"), null),
                Arguments.of("line break in a name", "POST", Map.of("X-A\r\nX-Injected", "v"), "\n"),
                Arguments.of("method that is not a token", "GE T", Map.of(), null),
                Arguments.of("method CONNECT", "CONNECT", Map.of(), null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unbuildable")
    void aRequestTheClientRefusesToBuildIsNoEffectNotRetriedAndNeverEchoesAValue(String why, String method,
            Map<String, String> headers, String mustNotAppear) {
        HttpTransport.Outcome o = transport().execute("t1",
                new HttpTransport.Call(method, base, headers, new byte[]{1}, null, true), 0);
        HttpTransport.Outcome.NoEffect ne = assertInstanceOf(HttpTransport.Outcome.NoEffect.class, o, why + ": " + o);
        assertTrue(ne.reason().startsWith("INVALID_REQUEST"), why + ": " + ne.reason());
        assertEquals(1, ne.attempts(), why + ": refused once, not retried");
        if (mustNotAppear != null) {
            assertFalse(ne.reason().contains(mustNotAppear), why + ": the reason repeats the value: " + ne.reason());
        }
        assertFalse(ne.reason().contains("\r") || ne.reason().contains("\n"), why + ": the reason is one line");
        assertEquals(0, requests.get(), why + ": nothing was sent");
    }

    @Test
    void aValidRequestIsStillSent() {
        // obs-text (ISO-8859-1) and spaces inside a value are valid HTTP and go out as before
        HttpTransport.Outcome o = transport().execute("t1",
                new HttpTransport.Call("POST", base, Map.of("X-Trace", "café au lait"), new byte[]{1}, null, true), 0);
        assertEquals(204, assertInstanceOf(HttpTransport.Outcome.Response.class, o, String.valueOf(o)).status());
        assertEquals(1, requests.get());
    }
}
