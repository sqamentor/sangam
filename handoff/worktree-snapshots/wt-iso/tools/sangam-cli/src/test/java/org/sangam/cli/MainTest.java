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
        String stored = Files.readString(dir.resolve("c/cli.json"));
        assertTrue(stored.contains("\"T\"") && stored.contains("https://s.example\""), stored);
        assertFalse(stored.contains("secret"));
        answer(200, "{\"token_endpoint\":\"https://i.example/token\"}");
        assertEquals(Main.AUTH, main(Map.of()).run(new String[]{"login", "--server", "https://s.example", "--issuer", "https://i.example", "--client", "c", "--secret-env", "MISSING"}));
    }
}
