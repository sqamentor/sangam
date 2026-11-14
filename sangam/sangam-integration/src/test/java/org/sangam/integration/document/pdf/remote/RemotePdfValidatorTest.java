// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.document.pdf.remote;

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
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sangam.integration.document.pdf.PdfDocument;
import org.sangam.integration.document.pdf.PdfRenderer;
import org.sangam.integration.document.pdf.PdfRendering;
import org.sangam.integration.sidecar.SidecarClient;
import org.sangam.integration.sidecar.SidecarCredentials;

/**
 * Owner decisions 114 and 153, SL-178: the server's client of the separate validator service fails closed. No service
 * or no credentials configured keeps PDF output off; no answer, refused credentials, a bad status or an unreadable
 * report is {@link PdfRendering.Unavailable} ({@code 422 FORMAT_NOT_AVAILABLE}); only a report the service sent is
 * returned. The fake services here run over plain HTTP, so the clients use transport mesh; the token is synthetic.
 */
class RemotePdfValidatorTest {

    private static final byte[] PDF = "%PDF-1.7 sample".getBytes(StandardCharsets.US_ASCII);
    private static final PdfDocument DOC = new PdfDocument("T", "en", null, null,
            List.of(new PdfDocument.Paragraph("x")), Map.of());

    private static String token;
    private static Path tokenFile;

    @TempDir
    Path dir;

    private HttpServer server;
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    void credentials() throws IOException {
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        tokenFile = Files.writeString(dir.resolve("validator-token"), token + "\n", StandardCharsets.US_ASCII);
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

    private static RemotePdfValidator client(String url) {
        return client(url, Duration.ofSeconds(10), "mesh");
    }

    private static RemotePdfValidator client(String url, Duration timeout, String transport) {
        return new RemotePdfValidator(new SidecarClient.Settings(url, Long.toString(timeout.toMillis()), transport,
                SidecarCredentials.tokenFile(tokenFile)));
    }

    private static PdfRenderer renderer() {
        return new PdfRenderer() {
            @Override
            public String name() {
                return "fake";
            }

            @Override
            public Set<Conformance> conformance() {
                return REQUIRED;
            }

            @Override
            public byte[] render(PdfDocument document) {
                return PDF;
            }
        };
    }

    @Test
    void withoutAConfiguredServicePdfOutputStaysOff() {
        for (String url : new String[] {null, "", "   "}) {
            RemotePdfValidator v = client(url);
            assertNotNull(v.notReadyReason());
            assertTrue(v.notReadyReason().contains(RemotePdfValidator.URL_PROPERTY), v.notReadyReason());
            assertNull(v.endpoint());
            PdfRendering r = new PdfRendering(renderer(), v);
            assertFalse(r.available());
            assertTrue(r.unavailableReason().contains("PDF validator is not ready"), r.unavailableReason());
            assertThrows(PdfRendering.Unavailable.class, () -> r.render(DOC));
            assertThrows(PdfRendering.Unavailable.class, () -> v.validate(PDF, PdfRenderer.REQUIRED));
        }
    }

    @Test
    void anUnusableConfigurationIsNotReadyRatherThanAGuess() {
        assertNotNull(client("ftp://validator:21").notReadyReason());
        assertNotNull(client("validator:8085").notReadyReason());
        assertNotNull(client("http://user:secret@validator:8085").notReadyReason());
        assertFalse(client("http://user:secret@validator:8085").notReadyReason().contains("secret"));
        assertNotNull(client("http://validator:8085/?x=1").notReadyReason());
        assertNotNull(client("http://validator:8085/#f").notReadyReason());
        assertNotNull(client("http:// bad").notReadyReason());
        assertNotNull(client("http://validator:8085", Duration.ZERO, "mesh").notReadyReason());
        RemotePdfValidator ok = client("https://validator.internal:8443/base/", Duration.ofSeconds(1), null);
        assertNull(ok.notReadyReason());
        assertEquals("https://validator.internal:8443/base", ok.endpoint());
    }

    @Test
    void plainHttpNeedsTheMeshTransportAndCredentialsAreRequired() throws IOException {
        RemotePdfValidator tls = client("http://validator:8085", Duration.ofSeconds(1), null);
        assertNotNull(tls.notReadyReason());
        assertTrue(tls.notReadyReason().contains("https"), tls.notReadyReason());
        assertNull(client("http://validator:8085", Duration.ofSeconds(1), "mesh").notReadyReason());
        assertNotNull(client("https://validator:8443", Duration.ofSeconds(1), "none").notReadyReason());

        RemotePdfValidator anonymous = new RemotePdfValidator(new SidecarClient.Settings("https://validator:8443", null,
                null, null));
        assertNotNull(anonymous.notReadyReason());
        assertThrows(PdfRendering.Unavailable.class, () -> anonymous.validate(PDF, PdfRenderer.REQUIRED));

        Files.writeString(tokenFile, "tiny-value\n", StandardCharsets.US_ASCII);
        Files.setLastModifiedTime(tokenFile, java.nio.file.attribute.FileTime.fromMillis(
                Files.getLastModifiedTime(tokenFile).toMillis() + 5_000));
        RemotePdfValidator weak = client("https://validator:8443");
        assertNotNull(weak.notReadyReason());
        assertFalse(weak.notReadyReason().contains("tiny-value"), "the reason never shows the token");
        assertThrows(PdfRendering.Unavailable.class, () -> weak.validate(PDF, PdfRenderer.REQUIRED));
    }

    @Test
    void everyCallCarriesTheBearerTokenAndARefusalIsUnavailable() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        String url = serve(ex -> {
            authorization.set(ex.getRequestHeaders().getFirst("Authorization"));
            ex.getResponseHeaders().set("WWW-Authenticate", "Bearer");
            answer(ex, 401, "{\"error\":\"unauthorised\"}");
        });
        PdfRendering.Unavailable e = assertThrows(PdfRendering.Unavailable.class,
                () -> client(url).validate(PDF, PdfRenderer.REQUIRED));
        assertEquals("Bearer " + token, authorization.get());
        assertTrue(e.getMessage().contains("refused the service credentials"), e.getMessage());
        assertFalse(e.getMessage().contains(token), "the token never appears in an error");
    }

    @Test
    void aNotReadyOrBusyValidatorIsUnavailableNeverAPass() throws Exception {
        String url = serve(ex -> answer(ex, 503,
                "{\"ok\":true,\"validator\":\"x\",\"checked\":[\"PDF_A_2B\",\"PDF_UA_1\"],\"findings\":[]}"));
        PdfRendering.Unavailable e = assertThrows(PdfRendering.Unavailable.class,
                () -> client(url).validate(PDF, PdfRenderer.REQUIRED));
        assertTrue(e.getMessage().contains("HTTP 503"), e.getMessage());
    }

    @Test
    void aPassingReportIsReturnedAndTheRequestCarriesTheBytesAndTheLevels() throws Exception {
        AtomicReference<String> query = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> type = new AtomicReference<>();
        AtomicReference<byte[]> body = new AtomicReference<>();
        String url = serve(ex -> {
            path.set(ex.getRequestURI().getPath());
            query.set(ex.getRequestURI().getRawQuery());
            type.set(ex.getRequestHeaders().getFirst("Content-Type"));
            body.set(ex.getRequestBody().readAllBytes());
            answer(ex, 200, "{\"ok\":true,\"validator\":\"veraPDF 1.30.4\",\"checked\":[\"PDF_A_2B\",\"PDF_UA_1\"],\"findings\":[]}");
        });
        PdfRendering r = new PdfRendering(renderer(), client(url + "/"));
        assertTrue(r.available(), String.valueOf(r.unavailableReason()));
        PdfRendering.Rendered out = r.render(DOC);
        assertTrue(out.validation().ok());
        assertEquals("veraPDF 1.30.4", out.validation().validator());
        assertEquals(RemotePdfValidator.VALIDATE_PATH, path.get());
        assertEquals("conformance=PDF_A_2B,PDF_UA_1", query.get());
        assertEquals("application/pdf", type.get());
        assertArrayEquals(PDF, body.get());
    }

    @Test
    void aRefusingReportIsInvalidWithTheServiceFindings() throws Exception {
        String url = serve(ex -> {
            ex.getRequestBody().readAllBytes();
            answer(ex, 200, "{\"ok\":false,\"validator\":\"veraPDF 1.30.4\",\"checked\":[\"PDF_A_2B\",\"PDF_UA_1\"],"
                    + "\"findings\":[\"PDF_UA_1 ISO_14289_1 7.2-34: no /Lang\"]}");
        });
        PdfRendering.Invalid e = assertThrows(PdfRendering.Invalid.class,
                () -> new PdfRendering(renderer(), client(url)).render(DOC));
        assertEquals(List.of("PDF_UA_1 ISO_14289_1 7.2-34: no /Lang"), e.report().findings());
    }

    @Test
    void aRefusedConnectionIsUnavailable() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        RemotePdfValidator v = client("http://127.0.0.1:" + port);
        assertNull(v.notReadyReason());
        PdfRendering.Unavailable e = assertThrows(PdfRendering.Unavailable.class,
                () -> new PdfRendering(renderer(), v).render(DOC));
        assertTrue(e.getMessage().contains("cannot be reached"), e.getMessage());
    }

    @Test
    void noAnswerWithinTheTimeoutIsUnavailable() throws Exception {
        String url = serve(ex -> release.await(30, TimeUnit.SECONDS));
        RemotePdfValidator v = client(url, Duration.ofMillis(300), "mesh");
        PdfRendering.Unavailable e = assertThrows(PdfRendering.Unavailable.class, () -> v.validate(PDF, PdfRenderer.REQUIRED));
        assertTrue(e.getMessage().contains("did not answer within 300 ms"), e.getMessage());
    }

    @Test
    void aStatusOtherThan200IsUnavailableEvenWithAPassingBody() throws Exception {
        String url = serve(ex -> answer(ex, 500,
                "{\"ok\":true,\"validator\":\"x\",\"checked\":[\"PDF_A_2B\",\"PDF_UA_1\"],\"findings\":[]}"));
        PdfRendering.Unavailable e = assertThrows(PdfRendering.Unavailable.class,
                () -> client(url).validate(PDF, PdfRenderer.REQUIRED));
        assertTrue(e.getMessage().contains("HTTP 500"), e.getMessage());
    }

    @Test
    void anUnreadableOrInconsistentReportIsUnavailableNeverAPass() throws Exception {
        String[] bodies = {
                "not json",
                "[]",
                "{\"ok\":\"true\",\"validator\":\"x\",\"checked\":[\"PDF_A_2B\",\"PDF_UA_1\"],\"findings\":[]}",
                "{\"ok\":true,\"checked\":[\"PDF_A_2B\",\"PDF_UA_1\"],\"findings\":[]}",
                "{\"ok\":true,\"validator\":\" \",\"checked\":[\"PDF_A_2B\",\"PDF_UA_1\"],\"findings\":[]}",
                "{\"ok\":true,\"validator\":\"x\",\"checked\":[\"PDF_A_2B\",\"PDF_UA_1\"]}",
                "{\"ok\":true,\"validator\":\"x\",\"findings\":[]}",
                "{\"ok\":true,\"validator\":\"x\",\"checked\":[\"PDF_A_2B\"],\"findings\":[]}",
                "{\"ok\":true,\"validator\":\"x\",\"checked\":[\"PDF_A_2B\",\"PDF_UA_1\"],\"findings\":[\"a finding\"]}",
                "{\"ok\":false,\"validator\":\"x\",\"checked\":[\"PDF_A_2B\",\"PDF_UA_1\"],\"findings\":[1]}",
        };
        for (String b : bodies) {
            String url = serve(ex -> answer(ex, 200, b));
            Exception e = assertThrows(Exception.class, () -> client(url).validate(PDF, PdfRenderer.REQUIRED), b);
            assertInstanceOf(PdfRendering.Unavailable.class, e, b);
            server.stop(0);
            server = null;
        }
    }

    @Test
    void emptyBytesAreAFindingAndNoLevelIsAProgrammingError() {
        RemotePdfValidator v = client("http://127.0.0.1:1");
        assertFalse(v.validate(new byte[0], PdfRenderer.REQUIRED).ok());
        assertThrows(IllegalArgumentException.class, () -> v.validate(PDF, Set.of()));
    }

    @Test
    void theInstalledValidatorIsThisClientAndBlankConfigurationKeepsItOff() {
        String before = System.getProperty(RemotePdfValidator.URL_PROPERTY);
        String fonts = System.getProperty("sangam.pdf.fonts");
        try {
            System.setProperty(RemotePdfValidator.URL_PROPERTY, " ");
            System.setProperty("sangam.pdf.fonts", String.join(",",
                    java.nio.file.Path.of("..", "deploy", "fonts", "NotoSans-Regular.ttf").toAbsolutePath().toString(),
                    java.nio.file.Path.of("..", "deploy", "fonts", "NotoSansDevanagari-Regular.ttf").toAbsolutePath().toString()));
            PdfRendering installed = PdfRendering.installed();
            assertFalse(installed.available());
            assertTrue(installed.unavailableReason().contains("no PDF validator service is configured"),
                    installed.unavailableReason());
        } finally {
            restore(RemotePdfValidator.URL_PROPERTY, before);
            restore("sangam.pdf.fonts", fonts);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
