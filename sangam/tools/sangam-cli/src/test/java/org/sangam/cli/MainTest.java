// SPDX-License-Identifier: Apache-2.0
package org.sangam.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLSession;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class MainTest {

    @TempDir
    Path dir;
    List<HttpRequest> sent = new ArrayList<>();
    Deque<Object[]> answers = new ArrayDeque<>();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();

    static HttpResponse<String> response(int status, String body, HttpRequest r) {
        return new HttpResponse<>() {
            public int statusCode() {
                return status;
            }

            public HttpRequest request() {
                return r;
            }

            public Optional<HttpResponse<String>> previousResponse() {
                return Optional.empty();
            }

            public HttpHeaders headers() {
                return HttpHeaders.of(Map.of(), (a, b) -> true);
            }

            public String body() {
                return body;
            }

            public Optional<SSLSession> sslSession() {
                return Optional.empty();
            }

            public URI uri() {
                return r.uri();
            }

            public HttpClient.Version version() {
                return HttpClient.Version.HTTP_1_1;
            }
        };
    }

    Main main(Map<String, String> env) {
        return new Main(r -> {
            sent.add(r);
            Object[] a = answers.poll();
            if (a == null) {
                throw new IOException("refused");
            }
            return response((int) a[0], (String) a[1], r);
        }, dir.resolve("c/cli.json"), new PrintStream(out), new PrintStream(err), env::get);
    }

    void answer(int status, String body) {
        answers.add(new Object[]{status, body});
    }

    int run(String... args) {
        return main(Map.of("SANGAM_TOKEN", "tok")).run(args);
    }

    @Test
    void exitCodesAreStable() {
        assertEquals(Main.USAGE, run());
        assertEquals(Main.USAGE, run("nonsense"));
        assertEquals(Main.USAGE, run("list", "tasks"), "no server");
        answer(200, "[]");
        assertEquals(Main.OK, run("list", "tasks", "--server", "http://localhost:1", "--query", "a=b c"));
        assertEquals("http://localhost:1/api/v1/tasks?a=b+c", sent.get(0).uri().toString());
        assertEquals("Bearer tok", sent.get(0).headers().firstValue("Authorization").orElseThrow());
        answer(403, "{}");
        assertEquals(Main.AUTH, run("get", "/x", "--server", "http://localhost:1"));
        answer(409, "{}");
        assertEquals(Main.REFUSED, run("get", "/x", "--server", "http://localhost:1"));
        assertEquals(Main.NETWORK, run("get", "/x", "--server", "http://localhost:1"));
        assertEquals(Main.USAGE, run("get", "/../admin", "--server", "http://localhost:1"));
    }

    @Test
    void testRunFailsOnFailedCases() {
        answer(200, "{\"passed\":1,\"failed\":2}");
        assertEquals(Main.REFUSED, run("test", "run", "pkg", "--server", "http://localhost:1"));
        answer(200, "{\"passed\":3,\"failed\":0}");
        assertEquals(Main.OK, run("test", "run", "pkg", "--server", "http://localhost:1"));
    }

    @Test
    void startSendsIdempotencyKey() {
        answer(201, "{}");
        assertEquals(Main.OK, run("start", "k", "--server", "http://localhost:1", "--var", "a=1", "--idempotency-key", "ci-1"));
        assertEquals("ci-1", sent.get(0).headers().firstValue("Idempotency-Key").orElseThrow());
    }

    @Test
    void deployIsMultipart() throws Exception {
        Path f = dir.resolve("m.bpmn");
        Files.writeString(f, "<x/>");
        answer(201, "{}");
        assertEquals(Main.OK, run("deploy", f.toString(), "--server", "http://localhost:1"));
        assertTrue(sent.get(0).headers().firstValue("Content-Type").orElseThrow().startsWith("multipart/form-data"));
    }

    @Test
    void servicePrincipalLoginStoresTokenAndRefusesPlainHttp() throws Exception {
        assertEquals(Main.USAGE, run("login", "--server", "http://example.org", "--issuer", "https://i", "--client", "c"));
        answer(200, "{\"token_endpoint\":\"https://i.example/token\"}");
        answer(200, "{\"access_token\":\"T\"}");
        assertEquals(Main.OK, main(Map.of("S", "secret")).run(new String[]{"login", "--server", "https://s.example/", "--issuer", "https://i.example", "--client", "c", "--secret-env", "S"}));
        // M-093: cli.json keeps settings only; the token lives in the token store (here the owner-only file fallback).
        String settings = Files.readString(dir.resolve("c/cli.json"));
        assertTrue(settings.contains("https://s.example\""), settings);
        assertFalse(settings.contains("\"T\""), "no token in cli.json: " + settings);
        assertFalse(settings.contains("secret\""), "only the variable name is stored: " + settings);
        assertTrue(settings.contains("\"secretEnv\":\"S\""), settings);
        String account = TokenStore.account("https://s.example", "c");
        String stored = Files.readString(dir.resolve("c/credentials/" + account + ".json"));
        assertTrue(stored.contains("\"T\""), stored);
        assertFalse(stored.contains("secret\""));
        assertFalse(out.toString().contains("\"T\"") || err.toString().contains("\"T\""), "the token is never printed");
        answer(200, "{\"token_endpoint\":\"https://i.example/token\"}");
        assertEquals(Main.AUTH, main(Map.of()).run(new String[]{"login", "--server", "https://s.example", "--issuer", "https://i.example", "--client", "c", "--secret-env", "MISSING"}));
    }

    // ---- M-093: refresh ------------------------------------------------------------------------

    Instant now = Instant.parse("2026-11-12T10:00:00Z");

    Main session(Map<String, String> env) {
        return new Main(r -> {
            sent.add(r);
            Object[] a = answers.poll();
            if (a == null) {
                throw new IOException("refused");
            }
            return response((int) a[0], (String) a[1], r);
        }, dir.resolve("c/cli.json"), new PrintStream(out), new PrintStream(err), env::get, null, Clock.fixed(now, ZoneOffset.UTC));
    }

    void loginWithRefresh(long expiresIn) {
        answer(200, "{\"token_endpoint\":\"https://i.example/token\"}");
        answer(200, "{\"access_token\":\"A1\",\"refresh_token\":\"R1\",\"expires_in\":" + expiresIn + "}");
        assertEquals(Main.OK, session(Map.of("S", "secret")).run(new String[]{"login", "--server", "https://s.example", "--issuer", "https://i.example", "--client", "c", "--secret-env", "S"}));
        sent.clear();
    }

    static String form(HttpRequest r) {
        return r.bodyPublisher().map(p -> {
            java.util.concurrent.CompletableFuture<String> body = new java.util.concurrent.CompletableFuture<>();
            p.subscribe(new java.util.concurrent.Flow.Subscriber<>() {
                final StringBuilder b = new StringBuilder();

                public void onSubscribe(java.util.concurrent.Flow.Subscription s) {
                    s.request(Long.MAX_VALUE);
                }

                public void onNext(java.nio.ByteBuffer item) {
                    byte[] x = new byte[item.remaining()];
                    item.get(x);
                    b.append(new String(x, java.nio.charset.StandardCharsets.UTF_8));
                }

                public void onError(Throwable t) {
                    body.completeExceptionally(t);
                }

                public void onComplete() {
                    body.complete(b.toString());
                }
            });
            return body.join();
        }).orElse("");
    }

    @Test
    void validTokenIsUsedWithoutRefresh() {
        loginWithRefresh(3600);
        answer(200, "[]");
        assertEquals(Main.OK, session(Map.of()).run(new String[]{"list", "tasks"}));
        assertEquals(1, sent.size());
        assertEquals("Bearer A1", sent.get(0).headers().firstValue("Authorization").orElseThrow());
    }

    @Test
    void tokenNearExpiryIsRefreshedFirstAndRotatedRefreshTokenIsKept() throws Exception {
        loginWithRefresh(30); // inside the 60 s skew
        answer(200, "{\"access_token\":\"A2\",\"refresh_token\":\"R2\",\"expires_in\":3600}");
        answer(200, "[]");
        assertEquals(Main.OK, session(Map.of("S", "secret")).run(new String[]{"list", "tasks"}));
        assertEquals("https://i.example/token", sent.get(0).uri().toString());
        String f = form(sent.get(0));
        assertTrue(f.contains("grant_type=refresh_token") && f.contains("refresh_token=R1") && f.contains("client_id=c"), f);
        assertTrue(f.contains("client_secret=secret"), "a confidential client authenticates the refresh: " + f);
        assertEquals("Bearer A2", sent.get(1).headers().firstValue("Authorization").orElseThrow());
        String stored = Files.readString(dir.resolve("c/credentials/" + TokenStore.account("https://s.example", "c") + ".json"));
        assertTrue(stored.contains("\"R2\"") && !stored.contains("\"R1\""), "the rotated refresh token replaces the old one");
        assertFalse(out.toString().contains("A2") || err.toString().contains("A2") || err.toString().contains("R2"));
    }

    @Test
    void unauthorizedTriggersOneRefreshAndTheSameRequestIsRetried() {
        loginWithRefresh(3600);
        answer(401, "{}");
        answer(200, "{\"access_token\":\"A2\",\"expires_in\":3600}");
        answer(201, "{}");
        assertEquals(Main.OK, session(Map.of("S", "secret")).run(new String[]{"start", "k", "--idempotency-key", "ci-1"}));
        assertEquals(3, sent.size());
        assertEquals("Bearer A1", sent.get(0).headers().firstValue("Authorization").orElseThrow());
        assertEquals("Bearer A2", sent.get(2).headers().firstValue("Authorization").orElseThrow());
        assertEquals("ci-1", sent.get(2).headers().firstValue("Idempotency-Key").orElseThrow(), "the retry is the same operation");
        assertEquals(1, sent.get(2).headers().allValues("Authorization").size());
    }

    @Test
    void failedRefreshEndsWithAuthAndNamesOnlyTheErrorCode() {
        loginWithRefresh(3600);
        answer(401, "{}");
        answer(400, "{\"error\":\"invalid_grant\",\"error_description\":\"R1 expired\"}");
        assertEquals(Main.AUTH, session(Map.of()).run(new String[]{"get", "/x"}));
        assertTrue(err.toString().contains("invalid_grant"), err.toString());
        assertFalse(err.toString().contains("R1") || out.toString().contains("R1"), "no token material is printed");
        assertEquals(2, sent.size(), "no second retry");
    }

    @Test
    void servicePrincipalWithoutRefreshTokenRepeatsClientCredentials() {
        answer(200, "{\"token_endpoint\":\"https://i.example/token\"}");
        answer(200, "{\"access_token\":\"A1\",\"expires_in\":10}");
        assertEquals(Main.OK, session(Map.of("S", "secret")).run(new String[]{"login", "--server", "https://s.example", "--issuer", "https://i.example", "--client", "c", "--secret-env", "S"}));
        sent.clear();
        answer(200, "{\"access_token\":\"A2\",\"expires_in\":300}");
        answer(200, "[]");
        assertEquals(Main.OK, session(Map.of("S", "secret")).run(new String[]{"list", "tasks"}));
        String f = form(sent.get(0));
        assertTrue(f.contains("grant_type=client_credentials") && f.contains("client_secret=secret"), f);
        assertEquals("Bearer A2", sent.get(1).headers().firstValue("Authorization").orElseThrow());
    }

    @Test
    void sangamTokenOverridesAndIsNeverRenewed() {
        loginWithRefresh(3600);
        answer(401, "{}");
        assertEquals(Main.AUTH, session(Map.of("SANGAM_TOKEN", "E")).run(new String[]{"get", "/x"}));
        assertEquals(1, sent.size());
        assertEquals("Bearer E", sent.get(0).headers().firstValue("Authorization").orElseThrow());
    }

    @Test
    void legacyCliJsonTokenIsMovedOutOfTheSettingsFile() throws Exception {
        Files.createDirectories(dir.resolve("c"));
        Files.writeString(dir.resolve("c/cli.json"), "{\"server\":\"https://s.example\",\"accessToken\":\"OLD\"}");
        answer(200, "[]");
        assertEquals(Main.OK, session(Map.of()).run(new String[]{"list", "tasks"}));
        assertEquals("Bearer OLD", sent.get(0).headers().firstValue("Authorization").orElseThrow());
        assertFalse(Files.readString(dir.resolve("c/cli.json")).contains("OLD"));
        assertTrue(Files.readString(dir.resolve("c/credentials/" + TokenStore.account("https://s.example", "") + ".json")).contains("OLD"));
    }

    @Test
    void logoutForgetsTokensButKeepsTheServer() throws Exception {
        loginWithRefresh(3600);
        assertEquals(Main.OK, session(Map.of()).run(new String[]{"logout"}));
        assertTrue(out.toString().contains("\"hadTokens\":true"), out.toString());
        assertFalse(Files.exists(dir.resolve("c/credentials/" + TokenStore.account("https://s.example", "c") + ".json")));
        String settings = Files.readString(dir.resolve("c/cli.json"));
        assertTrue(settings.contains("https://s.example") && !settings.contains("account"), settings);
    }

    @Test
    void plainHttpTokenEndpointIsRefusedForRefresh() throws Exception {
        loginWithRefresh(10);
        String cfg = Files.readString(dir.resolve("c/cli.json")).replace("https://i.example/token", "http://i.example/token");
        Files.writeString(dir.resolve("c/cli.json"), cfg);
        assertEquals(Main.USAGE, session(Map.of()).run(new String[]{"list", "tasks"}));
        assertTrue(sent.isEmpty(), "no refresh token sent over plain http");
    }
}
