// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.document.ocr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sangam.integration.sidecar.SidecarClient;
import org.sangam.integration.sidecar.SidecarCredentials;

/**
 * Owner decisions 167 and 153, M-103: the server's OCR client fails closed. Missing configuration or credentials, no
 * answer, a refusal or an answer of another shape never yields text; refused input is {@link OcrRejected}, everything
 * else {@link OcrUnavailable}. The fake sidecar runs over plain HTTP (transport mesh) with a synthetic token.
 */
class RemoteOcrClientTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0};
    private static final List<String> LANGS = List.of("eng", "hin");

    @TempDir
    Path dir;

    private String token;
    private Path tokenFile;
    private HttpServer server;
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    void credentials() throws IOException {
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        tokenFile = Files.writeString(dir.resolve("ocr-token"), token + "\n", StandardCharsets.US_ASCII);
    }

    @AfterEach
    void stop() {
        release.countDown();
        if (server != null) {
            server.stop(0);
        }
    }

    private interface Handler {
        void handle(HttpExchange ex) throws Exception;
    }

    private String serve(Handler h) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            try {
                h.handle(ex);
            } catch (Exception e) {
                throw new IOException(e);
            } finally {
                ex.close();
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void answer(HttpExchange ex, int status, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(b);
        }
    }

    private RemoteOcrClient client(String url) {
        return client(url, "10000");
    }

    private RemoteOcrClient client(String url, String timeoutMs) {
        return new RemoteOcrClient(new SidecarClient.Settings(url, timeoutMs, "mesh",
                SidecarCredentials.tokenFile(tokenFile)));
    }

    @Test
    void textIsReturnedAndTheRequestCarriesTokenTypeLanguagesAndBytes() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> type = new AtomicReference<>();
        AtomicReference<String> query = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<byte[]> body = new AtomicReference<>();
        String url = serve(ex -> {
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            type.set(ex.getRequestHeaders().getFirst("Content-Type"));
            query.set(ex.getRequestURI().getRawQuery());
            path.set(ex.getRequestURI().getPath());
            body.set(ex.getRequestBody().readAllBytes());
            answer(ex, 200, "{\"text\":\"Receipt रसीद\",\"engine\":\"tesseract 5\","
                    + "\"languages\":[\"eng\",\"hin\"]}");
        });
        OcrResult r = client(url).recognise(PNG, "image/png", LANGS);
        assertEquals("Receipt रसीद", r.text());
        assertEquals("tesseract 5", r.engine());
        assertEquals(LANGS, r.languages());
        assertEquals("Bearer " + token, auth.get());
        assertEquals("image/png", type.get());
        assertEquals("lang=eng,hin", query.get());
        assertEquals(RemoteOcrClient.RECOGNISE_PATH, path.get());
        assertArrayEquals(PNG, body.get());
    }

    @Test
    void missingConfigurationOrCredentialsIsNotReady() {
        RemoteOcrClient none = new RemoteOcrClient(new SidecarClient.Settings(null, null, null, null));
        assertNotNull(none.notReadyReason());
        assertTrue(none.notReadyReason().contains(RemoteOcrClient.URL_PROPERTY));
        assertThrows(OcrUnavailable.class, () -> none.recognise(PNG, "image/png", LANGS));
        RemoteOcrClient anonymous = new RemoteOcrClient(new SidecarClient.Settings("https://ocr:8086", null, null, null));
        assertNotNull(anonymous.notReadyReason());
        assertThrows(OcrUnavailable.class, () -> anonymous.recognise(PNG, "image/png", LANGS));
        RemoteOcrClient plain = new RemoteOcrClient(new SidecarClient.Settings("http://ocr:8086", null, null,
                SidecarCredentials.tokenFile(tokenFile)));
        assertNotNull(plain.notReadyReason(), "plain http needs transport mesh");
        assertNull(client("https://ocr:8086").notReadyReason());
        assertEquals("https://ocr:8086", client("https://ocr:8086/").endpoint());
        assertNotNull(OcrPort.none("off").notReadyReason());
        assertThrows(OcrUnavailable.class, () -> OcrPort.none("off").recognise(PNG, "image/png", LANGS));
    }

    @Test
    void badInputIsRefusedBeforeSending() {
        RemoteOcrClient c = client("https://ocr:8086");
        assertThrows(OcrRejected.class, () -> c.recognise(new byte[0], "image/png", LANGS));
        assertThrows(OcrRejected.class, () -> c.recognise(PNG, "application/pdf", LANGS));
        assertThrows(OcrRejected.class, () -> c.recognise(PNG, "image/png", List.of()));
        assertThrows(OcrRejected.class, () -> c.recognise(PNG, "image/png", List.of("eng", "eng")));
        assertThrows(OcrRejected.class, () -> c.recognise(PNG, "image/png", List.of("../etc")));
        assertThrows(OcrRejected.class, () -> c.recognise(PNG, "image/png", List.of("ENG")));
    }

    @Test
    void refusedInputIsRejectedAndEveryOtherFailureIsUnavailable() throws Exception {
        for (int status : new int[] {400, 413, 415, 422}) {
            String url = serve(ex -> answer(ex, status, "{\"error\":\"x\"}"));
            OcrRejected e = assertThrows(OcrRejected.class, () -> client(url).recognise(PNG, "image/png", LANGS));
            assertEquals(status, e.status());
            server.stop(0);
            server = null;
        }
        for (int status : new int[] {401, 403, 500, 503, 302}) {
            String url = serve(ex -> answer(ex, status,
                    "{\"text\":\"x\",\"engine\":\"t\",\"languages\":[\"eng\",\"hin\"]}"));
            OcrUnavailable e = assertThrows(OcrUnavailable.class, () -> client(url).recognise(PNG, "image/png", LANGS));
            assertFalse(e.getMessage().contains(token));
            server.stop(0);
            server = null;
        }
    }

    @Test
    void anAnswerOfAnotherShapeIsUnavailableNeverText() throws Exception {
        String[] bodies = {
                "not json",
                "[]",
                "{\"engine\":\"t\",\"languages\":[\"eng\",\"hin\"]}",
                "{\"text\":1,\"engine\":\"t\",\"languages\":[\"eng\",\"hin\"]}",
                "{\"text\":\"x\",\"engine\":\" \",\"languages\":[\"eng\",\"hin\"]}",
                "{\"text\":\"x\",\"engine\":\"t\"}",
                "{\"text\":\"x\",\"engine\":\"t\",\"languages\":[\"eng\"]}",
                "{\"text\":\"x\",\"engine\":\"t\",\"languages\":[\"hin\",\"eng\"]}",
                "{\"text\":\"x\",\"engine\":\"t\",\"languages\":[1,2]}",
        };
        for (String b : bodies) {
            String url = serve(ex -> answer(ex, 200, b));
            Exception e = assertThrows(Exception.class, () -> client(url).recognise(PNG, "image/png", LANGS), b);
            assertInstanceOf(OcrUnavailable.class, e, b);
            server.stop(0);
            server = null;
        }
    }

    @Test
    void noAnswerOrNoServiceIsUnavailable() throws Exception {
        String url = serve(ex -> release.await(30, TimeUnit.SECONDS));
        OcrUnavailable slow = assertThrows(OcrUnavailable.class,
                () -> client(url, "300").recognise(PNG, "image/png", LANGS));
        assertTrue(slow.getMessage().contains("did not answer within 300 ms"), slow.getMessage());
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        OcrUnavailable down = assertThrows(OcrUnavailable.class,
                () -> client("http://127.0.0.1:" + port).recognise(PNG, "image/png", LANGS));
        assertTrue(down.getMessage().contains("cannot be reached"), down.getMessage());
    }
}
