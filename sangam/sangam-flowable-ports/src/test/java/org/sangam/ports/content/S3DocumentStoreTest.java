// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.content;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

import org.junit.jupiter.api.Test;

/**
 * SG-03 (owner decision Q10(a)): {@link S3DocumentStore} with an injected HTTP client and clock: request shape,
 * AWS Signature V4 (recomputed here from the published algorithm), status mapping, size limit, key validation.
 */
class S3DocumentStoreTest {

    static final Instant NOW = Instant.parse("2026-11-12T08:30:00Z");
    static final URI ENDPOINT = URI.create("http://s3.local:9000");

    /** An HttpClient that records each request and answers with the next scripted status and body. */
    static final class StubHttp extends HttpClient {
        final List<HttpRequest> requests = new ArrayList<>();
        final List<Integer> statuses = new ArrayList<>();
        final List<byte[]> bodies = new ArrayList<>();

        StubHttp answer(int status, String body) {
            statuses.add(status);
            bodies.add(body.getBytes(StandardCharsets.UTF_8));
            return this;
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest req, HttpResponse.BodyHandler<T> handler) {
            requests.add(req);
            int status = statuses.remove(0);
            byte[] body = bodies.remove(0);
            HttpHeaders headers = HttpHeaders.of(Map.of(), (a, b) -> true);
            HttpResponse.BodySubscriber<T> sub = handler.apply(new HttpResponse.ResponseInfo() {
                @Override
                public int statusCode() {
                    return status;
                }

                @Override
                public HttpHeaders headers() {
                    return headers;
                }

                @Override
                public Version version() {
                    return Version.HTTP_1_1;
                }
            });
            sub.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {
                }

                @Override
                public void cancel() {
                }
            });
            sub.onNext(List.of(ByteBuffer.wrap(body)));
            sub.onComplete();
            T value = sub.getBody().toCompletableFuture().join();
            return new HttpResponse<>() {
                @Override
                public int statusCode() {
                    return status;
                }

                @Override
                public HttpRequest request() {
                    return req;
                }

                @Override
                public Optional<HttpResponse<T>> previousResponse() {
                    return Optional.empty();
                }

                @Override
                public HttpHeaders headers() {
                    return headers;
                }

                @Override
                public T body() {
                    return value;
                }

                @Override
                public Optional<SSLSession> sslSession() {
                    return Optional.empty();
                }

                @Override
                public URI uri() {
                    return req.uri();
                }

                @Override
                public Version version() {
                    return Version.HTTP_1_1;
                }
            };
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> h) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> h,
                                                                HttpResponse.PushPromiseHandler<T> p) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            return null;
        }

        @Override
        public SSLParameters sslParameters() {
            return null;
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }
    }

    static S3DocumentStore store(StubHttp http, long maxBytes) {
        return new S3DocumentStore(ENDPOINT, "docs", "ap-south-1", "AKIDEXAMPLE", "secret/key", maxBytes, http,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    static String header(HttpRequest r, String name) {
        return r.headers().firstValue(name).orElse(null);
    }

    @Test
    void putSendsAConditionalSignedRequestAndReturnsSizeAndHash() throws Exception {
        StubHttp http = new StubHttp().answer(200, "");
        byte[] body = "hello s3".getBytes(StandardCharsets.UTF_8);
        var stored = store(http, 1024).put("t1/a b/doc.pdf", new ByteArrayInputStream(body), "application/pdf");
        String hash = hex(sha256(body));
        assertEquals(body.length, stored.sizeBytes());
        assertEquals(hash, stored.sha256Hex());
        assertEquals("t1/a b/doc.pdf", stored.key());
        HttpRequest r = http.requests.get(0);
        assertEquals("PUT", r.method());
        assertEquals(URI.create("http://s3.local:9000/docs/t1/a%20b/doc.pdf"), r.uri());
        assertEquals("*", header(r, "If-None-Match"), "an existing key is never overwritten");
        assertEquals(hash, header(r, "x-amz-content-sha256"));
        assertEquals("20261112T083000Z", header(r, "x-amz-date"));
        assertEquals(body.length, r.bodyPublisher().orElseThrow().contentLength());
        String expected = "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20261112/ap-south-1/s3/aws4_request,"
                + " SignedHeaders=content-type;host;x-amz-content-sha256;x-amz-date, Signature="
                + sigV4("PUT", "/docs/t1/a%20b/doc.pdf", "application/pdf", hash);
        assertEquals(expected, header(r, "Authorization"));
    }

    @Test
    void getAndHeadAndDeleteMapTheStatuses() throws Exception {
        StubHttp http = new StubHttp().answer(200, "content").answer(404, "").answer(200, "").answer(404, "")
                .answer(404, "").answer(500, "");
        S3DocumentStore s = store(http, 1024);
        try (InputStream in = s.get("k").orElseThrow()) {
            assertArrayEquals("content".getBytes(StandardCharsets.UTF_8), in.readAllBytes());
        }
        assertTrue(s.get("missing").isEmpty());
        assertTrue(s.exists("k"));
        assertFalse(s.exists("missing"));
        s.delete("gone"); // 404 on delete is fine (already deleted)
        assertThrows(IOException.class, () -> s.delete("k"));
        HttpRequest get = http.requests.get(0);
        assertEquals("GET", get.method());
        assertEquals(hex(sha256(new byte[0])), header(get, "x-amz-content-sha256"));
        assertEquals("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20261112/ap-south-1/s3/aws4_request,"
                + " SignedHeaders=host;x-amz-content-sha256;x-amz-date, Signature="
                + sigV4("GET", "/docs/k", null, hex(sha256(new byte[0]))), header(get, "Authorization"));
        assertEquals("HEAD", http.requests.get(2).method());
        assertEquals("DELETE", http.requests.get(4).method());
    }

    @Test
    void anExistingKeyIsAConflictAndOtherFailuresAreIoErrors() {
        StubHttp http = new StubHttp().answer(412, "").answer(503, "");
        S3DocumentStore s = store(http, 1024);
        assertThrows(FileAlreadyExistsException.class,
                () -> s.put("k", new ByteArrayInputStream(new byte[]{1}), null));
        IOException e = assertThrows(IOException.class, () -> s.put("k2", new ByteArrayInputStream(new byte[]{1}), null));
        assertTrue(e.getMessage().contains("503"), e.getMessage());
        assertEquals("application/octet-stream", header(http.requests.get(0), "Content-Type"));
    }

    @Test
    void objectsOverTheLimitAndUnsafeKeysNeverReachTheStore() {
        StubHttp http = new StubHttp();
        S3DocumentStore s = store(http, 4);
        assertThrows(IOException.class, () -> s.put("k", new ByteArrayInputStream(new byte[5]), null));
        assertThrows(IllegalArgumentException.class, () -> s.get("../other-tenant/x"));
        assertThrows(IllegalArgumentException.class, () -> s.exists(" "));
        assertTrue(http.requests.isEmpty());
    }

    /** AWS Signature V4 for a path-style request with the store's header set (independent of the class under test). */
    static String sigV4(String method, String path, String contentType, String payloadHash) throws Exception {
        String amzDate = "20261112T083000Z";
        String day = "20261112";
        StringBuilder headers = new StringBuilder();
        String signed;
        if (contentType != null) {
            headers.append("content-type:").append(contentType).append('\n');
            signed = "content-type;host;x-amz-content-sha256;x-amz-date";
        } else {
            signed = "host;x-amz-content-sha256;x-amz-date";
        }
        headers.append("host:s3.local:9000\n").append("x-amz-content-sha256:").append(payloadHash).append('\n')
                .append("x-amz-date:").append(amzDate).append('\n');
        String canonical = method + "\n" + path + "\n\n" + headers + "\n" + signed + "\n" + payloadHash;
        String scope = day + "/ap-south-1/s3/aws4_request";
        String toSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n"
                + hex(sha256(canonical.getBytes(StandardCharsets.UTF_8)));
        byte[] k = hmac(("AWS4" + "secret/key").getBytes(StandardCharsets.UTF_8), day);
        k = hmac(k, "ap-south-1");
        k = hmac(k, "s3");
        k = hmac(k, "aws4_request");
        return hex(hmac(k, toSign));
    }

    static byte[] sha256(byte[] b) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(b);
    }

    static byte[] hmac(byte[] key, String data) throws Exception {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(key, "HmacSHA256"));
        return m.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    static String hex(byte[] b) {
        return HexFormat.of().formatHex(b);
    }
}
