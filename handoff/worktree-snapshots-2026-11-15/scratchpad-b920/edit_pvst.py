import sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()


def rep(old, new):
    global s
    if old not in s:
        raise SystemExit('missing: ' + old[:80])
    s = s.replace(old, new, 1)


rep('''import java.net.InetSocketAddress;
import java.net.URI;''', '''import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;''')
rep('''import java.nio.charset.StandardCharsets;
import java.util.List;''', '''import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;''')
rep('''import java.util.concurrent.atomic.AtomicReference;''', '''import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;''')
rep('''import org.junit.jupiter.api.Test;
import org.sangam.integration.document.pdf.PdfRenderer;

/** The HTTP surface of the validator service (decision 114): status codes, limits and the report shape. */
class PdfValidatorServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final byte[] PDF = "%PDF-1.7 fake".getBytes(StandardCharsets.US_ASCII);
    private final HttpClient http = HttpClient.newHttpClient();

    private static PdfValidatorService start(PdfRenderer.Validator v, long maxBytes) throws Exception {
        return new PdfValidatorService(v, "fake 1", new InetSocketAddress("127.0.0.1", 0), 1, maxBytes);
    }

    private HttpResponse<String> post(PdfValidatorService s, String query, byte[] body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + s.port() + "/v1/validate" + query))
                .header("Content-Type", "application/pdf").POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
''', '''import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sangam.integration.document.pdf.PdfRenderer;
import org.sangam.integration.sidecar.SidecarAuthenticator;
import org.sangam.integration.sidecar.SidecarServer;
import org.sangam.integration.sidecar.SidecarTransport;

/**
 * The HTTP surface of the validator service (decisions 114 and 153): authentication before the PDF is read, readiness,
 * status codes, limits and the report shape. The service runs over plain HTTP (transport mesh) on the loopback address
 * with a synthetic token made by the test.
 */
class PdfValidatorServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final byte[] PDF = "%PDF-1.7 fake".getBytes(StandardCharsets.US_ASCII);
    private final HttpClient http = HttpClient.newHttpClient();

    @TempDir
    Path dir;

    private String token;
    private Path tokenFile;

    @BeforeEach
    void credentials() throws IOException {
        token = newToken();
        tokenFile = Files.writeString(dir.resolve("tokens"), token + "\\n", StandardCharsets.US_ASCII);
    }

    private static String newToken() {
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private PdfValidatorService start(PdfRenderer.Validator v, long maxBytes) throws Exception {
        return start(v, SidecarAuthenticator.tokenFile(tokenFile), new SidecarServer.Limits(1, maxBytes,
                Duration.ofSeconds(30)));
    }

    private static PdfValidatorService start(PdfRenderer.Validator v, SidecarAuthenticator auth,
                                             SidecarServer.Limits limits) throws Exception {
        return new PdfValidatorService(v, "fake 1", new SidecarServer(new InetSocketAddress("127.0.0.1", 0),
                SidecarTransport.MESH, null, auth, limits));
    }

    private HttpResponse<String> post(PdfValidatorService s, String query, byte[] body) throws Exception {
        return post(s, query, body, "Bearer " + token);
    }

    private HttpResponse<String> post(PdfValidatorService s, String query, byte[] body, String authorization)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + s.port() + "/v1/validate"
                + query)).header("Content-Type", "application/pdf").POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (authorization != null) {
            b.header("Authorization", authorization);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> health(PdfValidatorService s) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + s.port() + "/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static PdfRenderer.Validator never() {
        return (pdf, required) -> {
            throw new AssertionError("must not validate");
        };
    }

    @Test
    void missingOrWrongCredentialsAre401AndThePdfIsNeverValidated() throws Exception {
        try (PdfValidatorService s = start(never(), 1024)) {
            for (String bad : new String[] {null, "Bearer", "Basic " + token, "Bearer " + token + "x", token}) {
                HttpResponse<String> r = post(s, "?conformance=PDF_A_2B", PDF, bad);
                assertEquals(401, r.statusCode(), String.valueOf(bad));
                assertEquals("Bearer", r.headers().firstValue("WWW-Authenticate").orElse(""));
                assertFalse(r.body().contains(token));
            }
            assertEquals(401, post(s, "", PDF, null).statusCode(), "authentication comes before the query check");
        }
    }

    @Test
    void withoutCallerTokensTheServiceIsNotReady() throws Exception {
        try (PdfValidatorService s = start(never(), SidecarAuthenticator.none(PdfValidatorMain.TOKEN_FILE_ENV),
                new SidecarServer.Limits(1, 1024, Duration.ofSeconds(30)))) {
            assertEquals(503, post(s, "?conformance=PDF_A_2B", PDF).statusCode());
            HttpResponse<String> h = health(s);
            assertEquals(503, h.statusCode());
            assertEquals("NOT_READY", JSON.readTree(h.body()).get("status").asText());
        }
    }

    @Test
    void aRotatedTokenIsAcceptedAndARemovedOneRevokedWithoutARestart() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        try (PdfValidatorService s = start((pdf, required) -> {
            runs.incrementAndGet();
            return new PdfRenderer.Report(true, "fake 1", List.of());
        }, 1024)) {
            String old = token;
            String next = newToken();
            Files.writeString(tokenFile, old + "\\n" + next + "\\n", StandardCharsets.US_ASCII);
            assertEquals(200, post(s, "?conformance=PDF_A_2B", PDF, "Bearer " + next).statusCode());
            assertEquals(200, post(s, "?conformance=PDF_A_2B", PDF, "Bearer " + old).statusCode());
            Files.writeString(tokenFile, next + "\\n", StandardCharsets.US_ASCII);
            assertEquals(401, post(s, "?conformance=PDF_A_2B", PDF, "Bearer " + old).statusCode(), "revoked");
            assertEquals(2, runs.get());
        }
    }

    @Test
    void timedOutAndBusyValidationsAre503NeverAReport() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        PdfRenderer.Validator stuck = (pdf, required) -> {
            started.countDown();
            while (release.getCount() > 0) {
                try {
                    release.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    // a stuck engine that ignores the cancellation keeps its slot
                }
            }
            return new PdfRenderer.Report(true, "fake 1", List.of());
        };
        try (PdfValidatorService s = start(stuck, SidecarAuthenticator.tokenFile(tokenFile),
                new SidecarServer.Limits(1, 1024, Duration.ofMillis(500)))) {
            HttpResponse<String> timedOut = post(s, "?conformance=PDF_A_2B", PDF);
            assertEquals(503, timedOut.statusCode());
            assertFalse(timedOut.body().contains("findings"));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            HttpResponse<String> busy = post(s, "?conformance=PDF_A_2B", PDF);
            assertEquals(503, busy.statusCode(), "the stuck validation still holds the only slot");
            release.countDown();
        }
    }
''')
rep('''    void missingOrUnknownConformanceIs400AndOtherMethodsAre405() throws Exception {
        PdfRenderer.Validator never = (pdf, required) -> {
            throw new AssertionError("must not validate");
        };
        try (PdfValidatorService s = start(never, 1024)) {''', '''    void missingOrUnknownConformanceIs400AndOtherMethodsAre405() throws Exception {
        try (PdfValidatorService s = start(never(), 1024)) {''')
rep('''    void aBodyOverTheLimitIs413WithoutValidating() throws Exception {
        PdfRenderer.Validator never = (pdf, required) -> {
            throw new AssertionError("must not validate");
        };
        try (PdfValidatorService s = start(never, 8)) {
            assertEquals(413, post(s, "?conformance=PDF_A_2B", new byte[9]).statusCode());
        }
    }''', '''    void aBodyOverTheLimitIs413WithoutValidating() throws Exception {
        try (PdfValidatorService s = start(never(), 8)) {
            assertEquals(413, post(s, "?conformance=PDF_A_2B", new byte[9]).statusCode());
        }
    }

    @Test
    void aMalformedPdfIsAFindingOfTheValidatorNotAnErrorOfTheService() throws Exception {
        try (PdfValidatorService s = start((pdf, required) -> new PdfRenderer.Report(false, "fake 1",
                List.of("PARSE: not a PDF")), 1024)) {
            HttpResponse<String> r = post(s, "?conformance=PDF_A_2B", "not a pdf".getBytes(StandardCharsets.US_ASCII));
            assertEquals(200, r.statusCode());
            assertFalse(JSON.readTree(r.body()).get("ok").booleanValue());
        }
    }''')
rep('''    void healthAnswersUpWithTheValidatorName() throws Exception {
        try (PdfValidatorService s = start((pdf, required) -> new PdfRenderer.Report(true, "fake 1", List.of()), 1024)) {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + s.port() + "/health")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r.statusCode());
            JsonNode body = JSON.readTree(r.body());
            assertEquals("UP", body.get("status").asText());
            assertEquals("fake 1", body.get("validator").asText());
        }
    }''', '''    void healthIsUnauthenticatedAndAnswersOnlyTheStatus() throws Exception {
        try (PdfValidatorService s = start((pdf, required) -> new PdfRenderer.Report(true, "fake 1", List.of()), 1024)) {
            HttpResponse<String> r = health(s);
            assertEquals(200, r.statusCode());
            JsonNode body = JSON.readTree(r.body());
            assertEquals("UP", body.get("status").asText());
            assertEquals(1, body.size(), "no version or other detail for unauthenticated callers");
        }
    }''')
i = s.index('    @Test\n    void settingsComeFromTheEnvironmentAndInvalidValuesStopTheStart()')
s = s[:i] + '''    @Test
    void settingsComeFromTheEnvironmentAndTlsIsTheDefault() {
        assertThrows(IllegalArgumentException.class, () -> PdfValidatorMain.settings(Map.of()),
                "tls without a key store refuses to start");
        Map<String, String> env = new HashMap<>();
        env.put(PdfValidatorMain.TRANSPORT_ENV, "mesh");
        var d = PdfValidatorMain.settings(env);
        assertEquals("0.0.0.0", d.bind());
        assertEquals(PdfValidatorMain.DEFAULT_PORT, d.port());
        assertEquals(PdfValidatorService.DEFAULT_MAX_BYTES, d.maxBytes());
        assertEquals(PdfValidatorMain.DEFAULT_PROCESSING_TIMEOUT, d.processingTimeout());
        assertTrue(d.threads() >= 1);
        assertTrue(d.authenticator().notReadyReason().contains(PdfValidatorMain.TOKEN_FILE_ENV));
        env.put(PdfValidatorMain.PORT_ENV, "9000");
        env.put(PdfValidatorMain.THREADS_ENV, "3");
        env.put(PdfValidatorMain.MAX_BYTES_ENV, "1048576");
        env.put(PdfValidatorMain.BIND_ENV, "127.0.0.1");
        env.put(PdfValidatorMain.PROCESSING_TIMEOUT_ENV, "60000");
        env.put(PdfValidatorMain.TOKEN_FILE_ENV, "/run/secrets/pdf-validator-tokens");
        var s = PdfValidatorMain.settings(env);
        assertEquals("127.0.0.1", s.bind());
        assertEquals(9000, s.port());
        assertEquals(new SidecarServer.Limits(3, 1_048_576, Duration.ofSeconds(60)), s.limits());
        assertEquals(Path.of("/run/secrets/pdf-validator-tokens"), s.tokenFile());
        for (String key : List.of(PdfValidatorMain.PORT_ENV, PdfValidatorMain.THREADS_ENV, PdfValidatorMain.MAX_BYTES_ENV,
                PdfValidatorMain.PROCESSING_TIMEOUT_ENV)) {
            Map<String, String> bad = new HashMap<>(env);
            bad.put(key, "0");
            assertThrows(IllegalArgumentException.class, () -> PdfValidatorMain.settings(bad), key);
        }
        Map<String, String> blank = new HashMap<>(env);
        blank.put(PdfValidatorMain.BIND_ENV, " ");
        assertThrows(IllegalArgumentException.class, () -> PdfValidatorMain.settings(blank));
        Map<String, String> meshWithKeys = new HashMap<>(env);
        meshWithKeys.put(PdfValidatorMain.TLS_KEYSTORE_ENV, "/run/secrets/validator.p12");
        assertThrows(IllegalArgumentException.class, () -> PdfValidatorMain.settings(meshWithKeys));
    }
}
'''
open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('ok')
