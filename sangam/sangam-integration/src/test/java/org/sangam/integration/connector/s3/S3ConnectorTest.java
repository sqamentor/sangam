// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.s3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.LookupResult;
import org.sangam.integration.connector.EgressPolicy;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.RetryPolicy;
import org.sangam.integration.connector.SecretResolver;

/**
 * M-105 S3 connector against an in-memory S3 stand-in on 127.0.0.1: requests are SigV4-signed with the payload hash,
 * a put records its operationId as user metadata and the lookup finds exactly that operation's object, answers map onto
 * success / business / transient results, keys outside the safe set are refused before anything is sent, and a missing
 * credential is a no-effect failure.
 */
class S3ConnectorTest {

    record Req(String method, String path, Map<String, String> headers) {
    }

    record Obj(byte[] body, Map<String, String> headers) {
    }

    HttpServer server;
    final List<Req> requests = new CopyOnWriteArrayList<>();
    final Map<String, Obj> objects = new ConcurrentHashMap<>();
    volatile int forcedStatus;
    volatile String forcedBody = "";
    String base;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange x) throws IOException {
        Map<String, String> h = new LinkedHashMap<>();
        x.getRequestHeaders().forEach((k, v) -> h.put(k.toLowerCase(Locale.ROOT), String.join(",", v)));
        String path = x.getRequestURI().getRawPath();
        requests.add(new Req(x.getRequestMethod(), path, h));
        byte[] in = x.getRequestBody().readAllBytes();
        if (forcedStatus != 0) {
            byte[] b = forcedBody.getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(forcedStatus, b.length == 0 ? -1 : b.length);
            if (b.length > 0) {
                x.getResponseBody().write(b);
            }
            x.close();
            return;
        }
        switch (x.getRequestMethod()) {
            case "PUT" -> {
                Map<String, String> meta = new LinkedHashMap<>();
                h.forEach((k, v) -> {
                    if (k.startsWith("x-amz-meta-") || k.equals("content-type")) {
                        meta.put(k, v);
                    }
                });
                objects.put(path, new Obj(in, meta));
                x.getResponseHeaders().add("ETag", "\"e1\"");
                x.sendResponseHeaders(200, -1);
            }
            case "GET", "HEAD" -> {
                Obj o = objects.get(path);
                if (o == null) {
                    byte[] b = "<Error><Code>NoSuchKey</Code></Error>".getBytes(StandardCharsets.UTF_8);
                    if (x.getRequestMethod().equals("HEAD")) {
                        x.sendResponseHeaders(404, -1);
                    } else {
                        x.sendResponseHeaders(404, b.length);
                        x.getResponseBody().write(b);
                    }
                } else {
                    o.headers().forEach((k, v) -> x.getResponseHeaders().add(k, v));
                    x.getResponseHeaders().add("ETag", "\"e1\"");
                    if (x.getRequestMethod().equals("HEAD")) {
                        x.sendResponseHeaders(200, -1);
                    } else {
                        x.sendResponseHeaders(200, o.body().length);
                        x.getResponseBody().write(o.body());
                    }
                }
            }
            case "DELETE" -> {
                objects.remove(path);
                x.sendResponseHeaders(204, -1);
            }
            default -> x.sendResponseHeaders(405, -1);
        }
        x.close();
    }

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-12T10:00:00Z"), ZoneOffset.UTC);
    static final SecretResolver SECRETS = SecretResolver.of(Map.of(
            "secret://s3/access", "AKIDEXAMPLE", "secret://s3/secret", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"));

    S3Connector connector(String prefix, long maxPut, SecretResolver secrets) {
        HttpTransport t = new HttpTransport(EgressPolicy.of(List.of(EgressPolicy.Rule.parse("*", base, true)), 0, 1 << 20),
                null, Duration.ofSeconds(2), Duration.ofSeconds(5), RetryPolicy.none());
        S3ObjectClient.Settings s = new S3ObjectClient.Settings(URI.create(base), "bucket1", "us-east-1", true,
                "secret://s3/access", "secret://s3/secret", null);
        return new S3Connector(new S3Connector.Config("files", prefix, maxPut, ConnectorProfile.LIVE),
                new S3ObjectClient(s, t, secrets, CLOCK));
    }

    static ConnectorRequest req(UUID op, String operation, Map<String, Object> input) {
        return new ConnectorRequest("t1", op, op.toString(), 1, null, null, operation, new LinkedHashMap<>(input),
                Instant.now().plusSeconds(10));
    }

    @Test
    void aPutIsSignedCarriesItsOperationIdAndTheLookupFindsExactlyThatOperation() {
        S3Connector c = connector("in/", 0, SECRETS);
        UUID op = UUID.randomUUID();
        ConnectorRequest put = req(op, "put", Map.of("key", "a/b.txt", "content", "hello", "contentType", "text/plain",
                "metadata", Map.of("origin", "test")));
        ConnectorResult r = c.invoke(put);
        ConnectorResult.Success s = assertInstanceOf(ConnectorResult.Success.class, r);
        assertEquals("in/a/b.txt", s.output().get("key"));
        assertEquals(5, s.output().get("size"));

        Req sent = requests.get(0);
        assertEquals("PUT", sent.method());
        assertEquals("/bucket1/in/a/b.txt", sent.path(), "path-style addressing with the key prefix");
        assertTrue(sent.headers().get("authorization").startsWith("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20261112/"
                + "us-east-1/s3/aws4_request"), sent.headers().get("authorization"));
        assertEquals(S3ObjectClient.sha256Hex("hello".getBytes(StandardCharsets.UTF_8)),
                sent.headers().get("x-amz-content-sha256"));
        assertEquals(op.toString(), sent.headers().get("x-amz-meta-" + S3Connector.OPERATION_META));
        assertEquals("test", sent.headers().get("x-amz-meta-origin"));

        LookupResult found = c.lookup(put);
        assertInstanceOf(LookupResult.Found.class, found);
        assertTrue(((LookupResult.Found) found).succeeded());

        LookupResult other = c.lookup(req(UUID.randomUUID(), "put", Map.of("key", "a/b.txt", "content", "x")));
        assertInstanceOf(LookupResult.NotFound.class, other, "another operation's object is not this put's effect");
        assertInstanceOf(LookupResult.NotFound.class, c.lookup(req(op, "put", Map.of("key", "missing", "content", "x"))));
    }

    @Test
    void getHeadAndDeleteReportTheObjectAndAMissingKey() {
        S3Connector c = connector("", 0, SECRETS);
        c.invoke(req(UUID.randomUUID(), "put", Map.of("key", "doc.json", "content", "{\"a\":1}")));

        ConnectorResult.Success got = assertInstanceOf(ConnectorResult.Success.class,
                c.invoke(req(UUID.randomUUID(), "get", Map.of("key", "doc.json", "encoding", "utf8"))));
        assertEquals("{\"a\":1}", got.output().get("content"));

        ConnectorResult.Success head = assertInstanceOf(ConnectorResult.Success.class,
                c.invoke(req(UUID.randomUUID(), "head", Map.of("key", "doc.json"))));
        assertEquals(true, head.output().get("exists"));

        assertInstanceOf(ConnectorResult.Success.class, c.invoke(req(UUID.randomUUID(), "delete", Map.of("key", "doc.json"))));
        ConnectorResult.Success gone = assertInstanceOf(ConnectorResult.Success.class,
                c.invoke(req(UUID.randomUUID(), "head", Map.of("key", "doc.json"))));
        assertEquals(false, gone.output().get("exists"));
        ConnectorResult.BusinessFailure nf = assertInstanceOf(ConnectorResult.BusinessFailure.class,
                c.invoke(req(UUID.randomUUID(), "get", Map.of("key", "doc.json"))));
        assertEquals("NOT_FOUND", nf.code());
    }

    @Test
    void storeAnswersMapOntoBusinessTransientAndUnknownResults() {
        S3Connector c = connector("", 0, SECRETS);
        ConnectorRequest put = req(UUID.randomUUID(), "put", Map.of("key", "k", "content", "x"));

        forcedStatus = 403;
        forcedBody = "<Error><Code>AccessDenied</Code></Error>";
        assertEquals("S3_AccessDenied", assertInstanceOf(ConnectorResult.BusinessFailure.class, c.invoke(put)).code());

        forcedStatus = 503;
        forcedBody = "";
        assertTrue(assertInstanceOf(ConnectorResult.TransientFailure.class, c.invoke(put)).noEffect(),
                "503 SlowDown: the request was not applied");

        forcedStatus = 500;
        assertFalse(assertInstanceOf(ConnectorResult.TransientFailure.class, c.invoke(put)).noEffect(),
                "a 5xx may have applied the put: retried with the same operationId, never reported as no effect");
    }

    @Test
    void unsafeKeysOversizedObjectsAndReservedMetadataAreRefusedBeforeAnythingIsSent() {
        S3Connector c = connector("", 4, SECRETS);
        for (String key : List.of("../x", "a//b", "/lead", "trail/", "a b", "ä", "a/./b", "")) {
            ConnectorResult r = c.invoke(req(UUID.randomUUID(), "put", Map.of("key", key, "content", "x")));
            assertEquals("INVALID_REQUEST", assertInstanceOf(ConnectorResult.BusinessFailure.class, r).code(), key);
        }
        assertEquals("INVALID_REQUEST", assertInstanceOf(ConnectorResult.BusinessFailure.class,
                c.invoke(req(UUID.randomUUID(), "put", Map.of("key", "k", "content", "12345")))).code());
        assertEquals("INVALID_REQUEST", assertInstanceOf(ConnectorResult.BusinessFailure.class,
                c.invoke(req(UUID.randomUUID(), "put", Map.of("key", "k", "content", "x",
                        "metadata", Map.of(S3Connector.OPERATION_META, "forged"))))).code());
        assertEquals("INVALID_REQUEST", assertInstanceOf(ConnectorResult.BusinessFailure.class,
                c.invoke(req(UUID.randomUUID(), "put", Map.of("key", "k", "content", "x", "contentBase64", "eA==")))).code());
        assertTrue(requests.isEmpty());
        assertThrows(IllegalArgumentException.class, () -> S3ObjectClient.checkKey("a/../b"));
        // '=' is a key character (partitioned lake layouts, M-057); the other characters outside the safe set stay refused
        S3ObjectClient.checkKey("tenant=T1/date=2026-11-12/e.json");
        for (String key : List.of("a&b", "a+b", "a?b", "a#b", "a%3Db", "a;b", "a:b", "a@b", "a$b", "a,b")) {
            assertThrows(IllegalArgumentException.class, () -> S3ObjectClient.checkKey(key), key);
        }
    }

    @Test
    void aMissingCredentialIsANoEffectFailureAndNothingIsSent() {
        S3Connector c = connector("", 0, SecretResolver.of(Map.of("secret://s3/access", "AKIDEXAMPLE")));
        ConnectorResult r = c.invoke(req(UUID.randomUUID(), "put", Map.of("key", "k", "content", "x")));
        assertTrue(assertInstanceOf(ConnectorResult.TransientFailure.class, r).noEffect());
        assertTrue(requests.isEmpty());
        assertInstanceOf(LookupResult.Unavailable.class,
                c.lookup(req(UUID.randomUUID(), "put", Map.of("key", "k", "content", "x"))));
    }
}
