// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.sidecar;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Owner decision 153, transport and limits: the sidecar authenticates before reading the body, is not ready without
 * credentials, bounds size, concurrency and time, serves HTTPS unless mesh is chosen, and its client accepts only
 * {@code https} (or {@code http} with mesh), never follows a redirect and never turns a refusal into a success. Every
 * token, key and password here is synthetic and made by the test.
 */
class SidecarServerTest {

    private static final Duration LONG = Duration.ofSeconds(30);

    @TempDir
    Path dir;

    private final HttpClient http = HttpClient.newHttpClient();

    private SidecarServer server(SidecarAuthenticator auth, SidecarServer.Limits limits) throws IOException {
        return new SidecarServer(new InetSocketAddress("127.0.0.1", 0), SidecarTransport.MESH, null, auth, limits);
    }

    private HttpResponse<String> post(SidecarServer s, String path, String authorization, byte[] body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + s.port() + path))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (authorization != null) {
            b.header("Authorization", authorization);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> health(SidecarServer s) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + s.port() + "/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** A route that echoes the body it read, counting how often it ran. */
    private static void echo(SidecarServer s, AtomicInteger calls) {
        s.route("POST", "/v1/echo", ex -> {
            calls.incrementAndGet();
            try {
                byte[] in = s.body(ex);
                SidecarServer.send(ex, 200, Map.of("bytes", in.length));
            } catch (SidecarServer.TooLarge e) {
                SidecarServer.send(ex, 413, Map.of("error", e.getMessage()));
            }
        });
    }

    @Test
    void missingOrWrongCredentialsAre401BeforeTheHandlerRuns() throws Exception {
        String t = SidecarAuthTest.token();
        AtomicInteger calls = new AtomicInteger();
        try (SidecarServer s = server(SidecarAuthenticator.tokenFile(SidecarAuthTest.write(dir.resolve("t"), t)),
                new SidecarServer.Limits(2, 1024, LONG))) {
            echo(s, calls);
            s.start();
            for (String bad : new String[] {null, "", "Bearer " + SidecarAuthTest.token(), "Basic " + t, t}) {
                HttpResponse<String> r = post(s, "/v1/echo", bad, new byte[10]);
                assertEquals(401, r.statusCode(), String.valueOf(bad));
                assertEquals("Bearer", r.headers().firstValue("WWW-Authenticate").orElse(""));
                assertFalse(r.body().contains(t));
            }
            assertEquals(0, calls.get(), "no handler ran, so no body was read, for a refused caller");
            HttpResponse<String> ok = post(s, "/v1/echo", "Bearer " + t, new byte[10]);
            assertEquals(200, ok.statusCode());
            assertTrue(ok.body().contains("10"), ok.body());
            assertEquals(1, calls.get());
            assertEquals(404, post(s, "/v1/echo/x", "Bearer " + t, new byte[1]).statusCode());
            HttpResponse<String> get = http.send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + s.port() + "/v1/echo")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(405, get.statusCode());
        }
    }

    @Test
    void withoutCredentialsTheSidecarIsNotReadyAndRefusesEverything() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (SidecarServer s = server(SidecarAuthenticator.none("X_TOKEN_FILE"), new SidecarServer.Limits(1, 1024, LONG))) {
            echo(s, calls);
            s.start();
            assertEquals(503, post(s, "/v1/echo", "Bearer " + SidecarAuthTest.token(), new byte[1]).statusCode());
            assertEquals(503, post(s, "/v1/echo", null, new byte[1]).statusCode());
            HttpResponse<String> h = health(s);
            assertEquals(503, h.statusCode());
            assertEquals("{\"status\":\"NOT_READY\"}", h.body());
            assertEquals(0, calls.get());
        }
    }

    @Test
    void healthIsUpWithUsableCredentialsAndSaysNothingElse() throws Exception {
        try (SidecarServer s = server(SidecarAuthenticator.tokenFile(SidecarAuthTest.write(dir.resolve("t"),
                SidecarAuthTest.token())), new SidecarServer.Limits(1, 1024, LONG)).start()) {
            HttpResponse<String> h = health(s);
            assertEquals(200, h.statusCode());
            assertEquals("{\"status\":\"UP\"}", h.body());
        }
    }

    @Test
    void anOversizedBodyIs413() throws Exception {
        String t = SidecarAuthTest.token();
        try (SidecarServer s = server(SidecarAuthenticator.tokenFile(SidecarAuthTest.write(dir.resolve("t"), t)),
                new SidecarServer.Limits(1, 8, LONG))) {
            echo(s, new AtomicInteger());
            s.start();
            assertEquals(200, post(s, "/v1/echo", "Bearer " + t, new byte[8]).statusCode());
            assertEquals(413, post(s, "/v1/echo", "Bearer " + t, new byte[9]).statusCode());
        }
    }

    @Test
    void workBeyondTheConcurrencyIsBusyAndSlowWorkTimesOut() throws Exception {
        String t = SidecarAuthTest.token();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        try (SidecarServer s = server(SidecarAuthenticator.tokenFile(SidecarAuthTest.write(dir.resolve("t"), t)),
                new SidecarServer.Limits(1, 1024, Duration.ofSeconds(20)))) {
            s.route("POST", "/v1/work", ex -> {
                try {
                    String r = s.process(() -> {
                        started.countDown();
                        release.await(20, TimeUnit.SECONDS);
                        return "done";
                    });
                    SidecarServer.send(ex, 200, Map.of("r", r));
                } catch (SidecarServer.Busy e) {
                    SidecarServer.send(ex, 503, Map.of("error", "busy"));
                } catch (Exception e) {
                    SidecarServer.send(ex, 500, Map.of("error", "failed"));
                }
            });
            s.start();
            CompletableFuture<HttpResponse<String>> first = CompletableFuture.supplyAsync(() -> {
                try {
                    return post(s, "/v1/work", "Bearer " + t, new byte[1]);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            assertTrue(started.await(10, TimeUnit.SECONDS));
            assertEquals(503, post(s, "/v1/work", "Bearer " + t, new byte[1]).statusCode());
            release.countDown();
            assertEquals(200, first.get(20, TimeUnit.SECONDS).statusCode());
        }
        try (SidecarServer s = server(SidecarAuthenticator.none("X"), new SidecarServer.Limits(1, 1024,
                Duration.ofMillis(200)))) {
            assertThrows(SidecarServer.TimedOut.class, () -> s.process(() -> {
                Thread.sleep(10_000);
                return "late";
            }));
        }
    }

    @Test
    void limitsAndTransportAreChecked() {
        assertThrows(IllegalArgumentException.class, () -> new SidecarServer.Limits(0, 1, LONG));
        assertThrows(IllegalArgumentException.class, () -> new SidecarServer.Limits(1, 0, LONG));
        assertThrows(IllegalArgumentException.class, () -> new SidecarServer.Limits(1, 1, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new SidecarServer(new InetSocketAddress("127.0.0.1", 0),
                SidecarTransport.TLS, null, SidecarAuthenticator.none("X"), new SidecarServer.Limits(1, 1, LONG)));
    }

    @Test
    void settingsRefuseTlsWithoutAKeyStoreAndMeshWithOne() throws IOException {
        Map<String, String> env = new HashMap<>();
        assertThrows(IllegalArgumentException.class, () -> SidecarSettings.from("S", env, 8000, 1024, LONG));
        env.put("S_TRANSPORT", "mesh");
        SidecarSettings mesh = SidecarSettings.from("S", env, 8000, 1024, LONG);
        assertEquals(SidecarTransport.MESH, mesh.transport());
        assertEquals("0.0.0.0", mesh.bind());
        assertEquals(8000, mesh.port());
        assertNull(mesh.tokenFile());
        assertTrue(mesh.authenticator().notReadyReason().contains("S_TOKEN_FILE"));
        env.put("S_TLS_KEYSTORE", "/run/secrets/ks.p12");
        assertThrows(IllegalArgumentException.class, () -> SidecarSettings.from("S", env, 8000, 1024, LONG));
        env.put("S_TRANSPORT", "tls");
        assertThrows(IllegalArgumentException.class, () -> SidecarSettings.from("S", env, 8000, 1024, LONG));
        env.put("S_TLS_KEYSTORE_PASSWORD_FILE", "/run/secrets/ks.pass");
        env.put("S_TOKEN_FILE", "/run/secrets/tokens");
        env.put("S_PORT", "9443");
        env.put("S_THREADS", "3");
        env.put("S_MAX_BYTES", "2048");
        env.put("S_PROCESSING_TIMEOUT_MS", "5000");
        SidecarSettings tls = SidecarSettings.from("S", env, 8000, 1024, LONG);
        assertEquals(SidecarTransport.TLS, tls.transport());
        assertEquals(new SidecarServer.Limits(3, 2048, Duration.ofSeconds(5)), tls.limits());
        assertEquals(Path.of("/run/secrets/tokens"), tls.tokenFile());
        for (String[] bad : List.of(new String[] {"S_PORT", "0"}, new String[] {"S_PORT", "x"},
                new String[] {"S_THREADS", "0"}, new String[] {"S_MAX_BYTES", "-1"}, new String[] {"S_BIND", " "},
                new String[] {"S_PROCESSING_TIMEOUT_MS", "0"}, new String[] {"S_TRANSPORT", "plain"})) {
            Map<String, String> e = new HashMap<>(env);
            e.put(bad[0], bad[1]);
            assertThrows(IllegalArgumentException.class, () -> SidecarSettings.from("S", e, 8000, 1024, LONG), bad[0]);
        }
    }

    @Test
    void theClientAcceptsHttpsOrMeshOnlyAndNeverATokenInTheUrl() throws IOException {
        SidecarCredentials creds = SidecarCredentials.tokenFile(SidecarAuthTest.write(dir.resolve("c"),
                SidecarAuthTest.token()));
        assertNotNull(client("http://side:8085", null, creds).notReadyReason(), "plain http needs mesh");
        assertTrue(client("http://side:8085", null, creds).notReadyReason().contains("153"));
        assertNull(client("http://side:8085", "mesh", creds).notReadyReason());
        assertNull(client("https://side:8443/", null, creds).notReadyReason());
        assertEquals("https://side:8443", client("https://side:8443/", null, creds).endpoint());
        assertNotNull(client("https://side:8443", "plain", creds).notReadyReason());
        assertNotNull(client("https://side:8443/?token=x", null, creds).notReadyReason());
        assertNotNull(client("https://u:p@side:8443", null, creds).notReadyReason());
        assertNotNull(client("https://side:8443", null, null).notReadyReason(), "no credentials, not ready");
        SidecarClient.CallFailed e = assertThrows(SidecarClient.CallFailed.class,
                () -> client("https://side:8443", null, null).post("/x", null, "a/b", "a/b", new byte[1], 10));
        assertTrue(e.getMessage().contains("not ready"));
    }

    @Test
    void theClientReportsRefusedCredentialsAndRedirectsAsFailures() throws Exception {
        String accepted = SidecarAuthTest.token();
        try (SidecarServer s = server(SidecarAuthenticator.tokenFile(SidecarAuthTest.write(dir.resolve("t"), accepted)),
                new SidecarServer.Limits(1, 1024, LONG))) {
            s.route("POST", "/v1/moved", ex -> {
                ex.getResponseHeaders().set("Location", "http://127.0.0.1:1/elsewhere");
                ex.sendResponseHeaders(307, -1);
            });
            echo(s, new AtomicInteger());
            s.start();
            String url = "http://127.0.0.1:" + s.port();
            SidecarClient wrong = client(url, "mesh", SidecarCredentials.tokenFile(SidecarAuthTest.write(
                    dir.resolve("wrong"), SidecarAuthTest.token())));
            SidecarClient.CallFailed refused = assertThrows(SidecarClient.CallFailed.class,
                    () -> wrong.post("/v1/echo", null, "application/octet-stream", "application/json", new byte[3], 1024));
            assertEquals(401, refused.status());
            assertTrue(refused.getMessage().contains("refused the service credentials"));

            SidecarClient right = client(url, "mesh", SidecarCredentials.tokenFile(SidecarAuthTest.write(
                    dir.resolve("right"), accepted)));
            assertEquals(200, right.post("/v1/echo", null, "application/octet-stream", "application/json",
                    new byte[3], 1024).status());
            SidecarClient.CallFailed moved = assertThrows(SidecarClient.CallFailed.class,
                    () -> right.post("/v1/moved", null, "a/b", "a/b", new byte[1], 1024));
            assertEquals(307, moved.status());
            SidecarClient.CallFailed big = assertThrows(SidecarClient.CallFailed.class,
                    () -> right.post("/v1/echo", null, "a/b", "a/b", new byte[3], 2));
            assertTrue(big.getMessage().contains("more than 2 bytes"));
        }
    }

    @Test
    void httpsRoundTripWithAKeyStoreAndAPinnedTrustStore() throws Exception {
        Path keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win") ? "keytool.exe" : "keytool");
        assumeTrue(Files.isExecutable(keytool), "keytool of the running JDK");
        String pass = SidecarAuthTest.token();
        Path ks = dir.resolve("server.p12");
        Path passFile = dir.resolve("server.pass");
        Files.writeString(passFile, pass + "\n", StandardCharsets.US_ASCII);
        run(keytool.toString(), "-genkeypair", "-alias", "sidecar", "-keyalg", "EC", "-groupname", "secp256r1",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-validity", "2",
                "-storetype", "PKCS12", "-keystore", ks.toString(), "-storepass", pass, "-keypass", pass);
        SSLContext serverTls = SidecarServer.tls(ks, passFile);

        KeyStore trust = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(ks)) {
            trust.load(in, pass.toCharArray());
        }
        KeyStore anchors = KeyStore.getInstance("PKCS12");
        anchors.load(null, null);
        anchors.setCertificateEntry("sidecar", trust.getCertificate("sidecar"));
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(anchors);
        SSLContext clientTls = SSLContext.getInstance("TLS");
        clientTls.init(null, tmf.getTrustManagers(), null);

        String t = SidecarAuthTest.token();
        try (SidecarServer s = new SidecarServer(new InetSocketAddress("127.0.0.1", 0), SidecarTransport.TLS, serverTls,
                SidecarAuthenticator.tokenFile(SidecarAuthTest.write(dir.resolve("t"), t)),
                new SidecarServer.Limits(1, 1024, LONG))) {
            echo(s, new AtomicInteger());
            s.start();
            SidecarClient c = new SidecarClient("test sidecar", "TEST_URL", new SidecarClient.Settings(
                    "https://127.0.0.1:" + s.port(), "10000", null, SidecarCredentials.tokenFile(
                    SidecarAuthTest.write(dir.resolve("ct"), t))), LONG, clientTls);
            SidecarClient.Answer a = c.post("/v1/echo", null, "a/b", "application/json", new byte[4], 1024);
            assertEquals(200, a.status());
            assertArrayEquals("{\"bytes\":4}".getBytes(StandardCharsets.UTF_8), a.body());

            SidecarClient untrusting = new SidecarClient("test sidecar", "TEST_URL", new SidecarClient.Settings(
                    "https://127.0.0.1:" + s.port(), "10000", null, SidecarCredentials.tokenFile(
                    dir.resolve("ct"))), LONG);
            SidecarClient.CallFailed e = assertThrows(SidecarClient.CallFailed.class,
                    () -> untrusting.post("/v1/echo", null, "a/b", "a/b", new byte[1], 1024),
                    "a certificate the client does not trust is a failure, never a fallback to plain HTTP");
            assertTrue(e.getMessage().contains("cannot be reached"), e.getMessage());
        }
    }

    private static SidecarClient client(String url, String transport, SidecarCredentials creds) {
        return new SidecarClient("test sidecar", "TEST_URL", new SidecarClient.Settings(url, "10000", transport, creds),
                LONG);
    }

    private static void run(String... command) throws Exception {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "keytool finished");
        assertEquals(0, p.exitValue(), "keytool exit code");
    }
}
