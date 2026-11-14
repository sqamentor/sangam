// SPDX-License-Identifier: Apache-2.0
package org.sangam.ocrsidecar;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sangam.integration.document.ocr.OcrRejected;
import org.sangam.integration.document.ocr.OcrResult;
import org.sangam.integration.document.ocr.OcrUnavailable;
import org.sangam.integration.document.ocr.RemoteOcrClient;
import org.sangam.integration.sidecar.SidecarAuthenticator;
import org.sangam.integration.sidecar.SidecarClient;
import org.sangam.integration.sidecar.SidecarCredentials;
import org.sangam.integration.sidecar.SidecarServer;
import org.sangam.integration.sidecar.SidecarTransport;

/**
 * The OCR sidecar's HTTP surface (decisions 167 and 153) with a fake engine: authentication before the image is read,
 * readiness, language and image checks, limits, the answer shape, and the server's {@link RemoteOcrClient} against it.
 * Plain HTTP on the loopback address (transport mesh) with a synthetic token made by the test.
 */
class OcrServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final byte[] PNG = ImageCheckTest.png(200, 100);
    private final HttpClient http = HttpClient.newHttpClient();

    @TempDir
    Path dir;

    private String token;
    private Path tokenFile;

    @BeforeEach
    void credentials() throws IOException {
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        tokenFile = Files.writeString(dir.resolve("tokens"), token + "\n", StandardCharsets.US_ASCII);
    }

    /** An engine that records its input and answers fixed text. */
    private static final class FakeEngine implements OcrEngine {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<List<String>> languages = new AtomicReference<>();
        final AtomicReference<byte[]> image = new AtomicReference<>();
        volatile Exception failure;

        @Override
        public String name() {
            return "tesseract 5 (fake)";
        }

        @Override
        public String recognise(byte[] bytes, List<String> langs) throws Unreadable, IOException {
            calls.incrementAndGet();
            image.set(bytes);
            languages.set(langs);
            if (failure instanceof Unreadable u) {
                throw u;
            }
            if (failure instanceof IOException e) {
                throw e;
            }
            return "Receipt रसीद";
        }
    }

    private OcrService start(OcrEngine engine, SidecarAuthenticator auth, long maxBytes, long maxPixels)
            throws IOException {
        return new OcrService(engine, List.of("eng", "hin"), maxPixels, new SidecarServer(
                new InetSocketAddress("127.0.0.1", 0), SidecarTransport.MESH, null, auth,
                new SidecarServer.Limits(1, maxBytes, Duration.ofSeconds(30))));
    }

    private OcrService start(OcrEngine engine) throws IOException {
        return start(engine, SidecarAuthenticator.tokenFile(tokenFile), 1 << 20, 1_000_000);
    }

    private HttpResponse<String> post(OcrService s, String query, String type, byte[] body, String authorization)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + s.port() + "/v1/ocr" + query))
                .header("Content-Type", type).POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (authorization != null) {
            b.header("Authorization", authorization);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(OcrService s, String query, String type, byte[] body) throws Exception {
        return post(s, query, type, body, "Bearer " + token);
    }

    @Test
    void anImageIsReadAndTheAnswerCarriesTextEngineAndLanguages() throws Exception {
        FakeEngine engine = new FakeEngine();
        try (OcrService s = start(engine)) {
            HttpResponse<String> r = post(s, "?lang=hin,eng", "image/png", PNG);
            assertEquals(200, r.statusCode(), r.body());
            JsonNode body = JSON.readTree(r.body());
            assertEquals("Receipt रसीद", body.get("text").asText());
            assertEquals("tesseract 5 (fake)", body.get("engine").asText());
            assertEquals("[\"hin\",\"eng\"]", body.get("languages").toString());
            assertEquals(List.of("hin", "eng"), engine.languages.get());
            assertArrayEquals(PNG, engine.image.get());
        }
    }

    @Test
    void missingOrWrongCredentialsAre401AndNoImageIsRead() throws Exception {
        FakeEngine engine = new FakeEngine();
        try (OcrService s = start(engine)) {
            for (String bad : new String[] {null, "Bearer " + token.substring(2), "Basic " + token}) {
                HttpResponse<String> r = post(s, "?lang=eng", "image/png", PNG, bad);
                assertEquals(401, r.statusCode());
                assertFalse(r.body().contains(token));
            }
            assertEquals(0, engine.calls.get());
        }
    }

    @Test
    void withoutTokensTheSidecarIsNotReady() throws Exception {
        try (OcrService s = start(new FakeEngine(), SidecarAuthenticator.none(OcrSidecarMain.PREFIX + "_TOKEN_FILE"),
                1 << 20, 1_000_000)) {
            assertEquals(503, post(s, "?lang=eng", "image/png", PNG).statusCode());
            HttpResponse<String> h = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + s.port()
                    + "/health")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(503, h.statusCode());
            assertEquals("{\"status\":\"NOT_READY\"}", h.body());
        }
    }

    @Test
    void languagesAndImagesAreCheckedBeforeTheEngineRuns() throws Exception {
        FakeEngine engine = new FakeEngine();
        try (OcrService s = start(engine, SidecarAuthenticator.tokenFile(tokenFile), 64, 10_000)) {
            assertEquals(400, post(s, "", "image/png", PNG).statusCode());
            assertEquals(400, post(s, "?lang=", "image/png", PNG).statusCode());
            assertEquals(400, post(s, "?lang=tam", "image/png", PNG).statusCode(), "not offered");
            assertEquals(400, post(s, "?lang=eng,eng", "image/png", PNG).statusCode());
            assertEquals(400, post(s, "?lang=../x", "image/png", PNG).statusCode());
            assertEquals(400, post(s, "?lang=eng&lang=hin", "image/png", PNG).statusCode());
            assertEquals(415, post(s, "?lang=eng", "image/jpeg", PNG).statusCode());
            assertEquals(415, post(s, "?lang=eng", "image/png", "%PDF-1.7".getBytes(StandardCharsets.US_ASCII))
                    .statusCode());
            assertEquals(413, post(s, "?lang=eng", "image/png", new byte[65]).statusCode(), "bytes over the limit");
            assertEquals(413, post(s, "?lang=eng", "image/png", ImageCheckTest.png(101, 100)).statusCode(),
                    "pixels over the limit");
            assertEquals(0, engine.calls.get());
        }
    }

    @Test
    void anUnreadableImageIs422AndAnEngineFailureIs500WithoutDetail() throws Exception {
        FakeEngine engine = new FakeEngine();
        try (OcrService s = start(engine)) {
            engine.failure = new OcrEngine.Unreadable("the engine could not read the image (exit 1)");
            assertEquals(422, post(s, "?lang=eng", "image/png", PNG).statusCode());
            engine.failure = new IOException("secret path /opt/x");
            HttpResponse<String> r = post(s, "?lang=eng", "image/png", PNG);
            assertEquals(500, r.statusCode());
            assertFalse(r.body().contains("/opt/x"));
        }
    }

    @Test
    void theServerClientReadsTheSidecarAndMapsRefusals() throws Exception {
        FakeEngine engine = new FakeEngine();
        try (OcrService s = start(engine)) {
            RemoteOcrClient client = new RemoteOcrClient(new SidecarClient.Settings("http://127.0.0.1:" + s.port(),
                    "10000", "mesh", SidecarCredentials.tokenFile(tokenFile)));
            OcrResult r = client.recognise(PNG, "image/png", List.of("eng", "hin"));
            assertEquals("Receipt रसीद", r.text());
            assertEquals(List.of("eng", "hin"), r.languages());
            assertEquals(400, assertThrows(OcrRejected.class,
                    () -> client.recognise(PNG, "image/png", List.of("tam"))).status());
            assertEquals(415, assertThrows(OcrRejected.class,
                    () -> client.recognise(PNG, "image/jpeg", List.of("eng"))).status());

            Path other = Files.writeString(dir.resolve("other"), token.substring(1) + "x\n", StandardCharsets.US_ASCII);
            RemoteOcrClient wrong = new RemoteOcrClient(new SidecarClient.Settings("http://127.0.0.1:" + s.port(),
                    "10000", "mesh", SidecarCredentials.tokenFile(other)));
            OcrUnavailable e = assertThrows(OcrUnavailable.class, () -> wrong.recognise(PNG, "image/png",
                    List.of("eng")));
            assertTrue(e.getMessage().contains("refused the service credentials"), e.getMessage());
        }
    }

    @Test
    void settingsComeFromTheEnvironment() {
        Map<String, String> env = new HashMap<>();
        env.put("SANGAM_OCR_TRANSPORT", "mesh");
        OcrSidecarMain.Settings d = OcrSidecarMain.Settings.from(env);
        assertEquals(OcrSidecarMain.DEFAULT_PORT, d.sidecar().port());
        assertEquals(OcrSidecarMain.DEFAULT_MAX_BYTES, d.sidecar().maxBytes());
        assertEquals(List.of("eng", "hin"), d.languages());
        assertEquals("tesseract", d.tesseract());
        assertEquals(OcrSidecarMain.DEFAULT_MAX_PIXELS, d.maxPixels());
        env.put(OcrSidecarMain.LANGUAGES_ENV, "eng, hin ,chi_sim");
        env.put(OcrSidecarMain.MAX_PIXELS_ENV, "5000000");
        env.put(OcrSidecarMain.TESSERACT_ENV, "/usr/bin/tesseract");
        OcrSidecarMain.Settings s = OcrSidecarMain.Settings.from(env);
        assertEquals(List.of("eng", "hin", "chi_sim"), s.languages());
        assertEquals(5_000_000, s.maxPixels());
        for (String[] bad : List.of(new String[] {OcrSidecarMain.LANGUAGES_ENV, "eng,eng"},
                new String[] {OcrSidecarMain.LANGUAGES_ENV, "eng,"}, new String[] {OcrSidecarMain.LANGUAGES_ENV, "e/g"},
                new String[] {OcrSidecarMain.MAX_PIXELS_ENV, "0"}, new String[] {OcrSidecarMain.MAX_PIXELS_ENV, "x"},
                new String[] {OcrSidecarMain.TESSERACT_ENV, " "})) {
            Map<String, String> e = new HashMap<>(env);
            e.put(bad[0], bad[1]);
            assertThrows(IllegalArgumentException.class, () -> OcrSidecarMain.Settings.from(e), bad[0] + "=" + bad[1]);
        }
        Map<String, String> tls = new HashMap<>();
        assertThrows(IllegalArgumentException.class, () -> OcrSidecarMain.Settings.from(tls),
                "tls without a key store refuses to start");
    }

    @Test
    void theLanguageParserKeepsTheOrderAndRefusesTheRest() {
        assertEquals(List.of("hin", "eng"), OcrService.languages("x=1&lang=hin,eng", Set.of("eng", "hin")));
        assertThrows(IllegalArgumentException.class, () -> OcrService.languages(null, Set.of("eng")));
        assertThrows(IllegalArgumentException.class, () -> OcrService.languages("lang=eng,", Set.of("eng")));
    }
}
