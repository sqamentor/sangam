// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.web;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.integration.document.ocr.OcrPort;
import org.sangam.integration.document.ocr.OcrResult;
import org.sangam.integration.document.ocr.OcrUnavailable;
import org.sangam.rest.modules.ai.AiController;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.web.Problems;
import org.sangam.server.acceptance.AcceptanceIdp;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * D6 (lane R7-sweep, M-103): {@code POST /api/v1/ai/knowledge-bases/by-kb/versions/{version}/ocr?kb=} over real HTTP on the
 * real server (Tomcat multipart parsing, the security chain, the argument resolvers, the problem advices and the error
 * dispatch), where {@code AiOcrControllerTest} calls the controller methods directly. Only the OCR engine is a stub
 * (the {@link OcrPort} bean the server takes in place of the sidecar client), so the test sees exactly which bytes,
 * media type and languages the multipart request delivered.
 * <ol>
 *   <li>a multipart upload creates a PENDING_REVIEW transcript (201, Location) and hands the engine the exact part
 *       bytes, the part's media type and the trimmed languages; the transcript reads back over HTTP;</li>
 *   <li>no {@code file} part is 400 VALIDATION_FAILED, a JSON body is 415 UNSUPPORTED_MEDIA_TYPE, a file over the
 *       servlet multipart limit is 413 PAYLOAD_TOO_LARGE, each as problem JSON, and the engine is never called;</li>
 *   <li>no token is 401, a caller without {@code ai.admin} is 403 ACTION_NOT_PERMITTED, and the engine is never
 *       called;</li>
 *   <li>an engine that is not available is 503 OCR_UNAVAILABLE and records no transcript.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
@DirtiesContext
class AiOcrMultipartHttpH2Test {

    static final String TENANT = "dev";
    /** The servlet multipart limit of this test (the product default is 256 MB, SANGAM_CONTENT_MAX_FILE). */
    static final int MAX_FILE_BYTES = 64 * 1024;
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    static final ObjectMapper JSON = new ObjectMapper();
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 13, 'I', 'H', 'D', 'R', (byte) 0xff, 0};

    /** What the stub engine received, one entry per call. */
    record Call(byte[] image, String mediaType, List<String> languages) {
    }

    static final List<Call> CALLS = new CopyOnWriteArrayList<>();
    static volatile RuntimeException engineFailure;

    @TestConfiguration
    static class StubOcr {
        @Bean
        OcrPort ocrPort() {
            return new OcrPort() {
                @Override
                public OcrResult recognise(byte[] image, String mediaType, List<String> languages) {
                    CALLS.add(new Call(image, mediaType, languages));
                    RuntimeException f = engineFailure;
                    if (f != null) {
                        throw f;
                    }
                    return new OcrResult("district treasury sanction order", "tesseract 5.3.4", languages);
                }

                @Override
                public String notReadyReason() {
                    return null;
                }
            };
        }
    }

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry r) {
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> AcceptanceIdp.get().issuer());
        r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> AcceptanceIdp.get().jwksUri());
        r.add("sangam.modules.ai.enabled", () -> "true");
        r.add("spring.servlet.multipart.max-file-size", () -> MAX_FILE_BYTES + "B");
        r.add("spring.servlet.multipart.max-request-size", () -> (MAX_FILE_BYTES + 8 * 1024) + "B");
    }

    @LocalServerPort
    int port;

    @BeforeEach
    void reset() {
        CALLS.clear();
        engineFailure = null;
    }

    /** A tenant administrator: holds ai.admin and ai.review (Permissions.MODULE_ADMIN). */
    static String admin() {
        return AcceptanceIdp.get().token(TENANT, "ocr-http-admin", List.of("sangam-admin"));
    }

    static String bearer(String token) {
        return "Bearer " + token;
    }

    HttpResponse<String> send(HttpRequest.Builder b) throws Exception {
        return HTTP.send(b.timeout(Duration.ofSeconds(60)).build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpRequest.Builder at(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    }

    /** A new building version of a fresh knowledge base, created over HTTP. */
    String newVersionPath() throws Exception {
        String kb = "ocr-" + UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> r = send(at("/api/v1/ai/knowledge-bases/by-kb/versions?kb=" + kb)
                .header("Authorization", bearer(admin())).POST(HttpRequest.BodyPublishers.noBody()));
        assertEquals(200, r.statusCode(), r.body());
        return "/api/v1/ai/knowledge-bases/by-kb/versions/" + JSON.readTree(r.body()).path("version").asInt() + "/ocr?kb=" + kb;
    }

    /** One multipart/form-data body: text fields, then an optional file part named {@code file}. */
    record Multipart(String boundary, byte[] body) {
        static Multipart of(Map<String, String> fields, String fileMediaType, byte[] file) {
            String boundary = "sangam-" + UUID.randomUUID();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (Map.Entry<String, String> f : fields.entrySet()) {
                write(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + f.getKey() + "\"\r\n\r\n"
                        + f.getValue() + "\r\n");
            }
            if (file != null) {
                write(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"scan.png\"\r\n"
                        + "Content-Type: " + fileMediaType + "\r\n\r\n");
                out.writeBytes(file);
                write(out, "\r\n");
            }
            write(out, "--" + boundary + "--\r\n");
            return new Multipart(boundary, out.toByteArray());
        }

        private static void write(ByteArrayOutputStream out, String s) {
            out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
        }
    }

    HttpResponse<String> upload(String path, String token, Multipart m) throws Exception {
        HttpRequest.Builder b = at(path).header("Content-Type", "multipart/form-data; boundary=" + m.boundary())
                .POST(HttpRequest.BodyPublishers.ofByteArray(m.body()));
        if (token != null) {
            b.header("Authorization", bearer(token));
        }
        return send(b);
    }

    static Map<String, String> fields() {
        return Map.of("documentId", "scan-1", "docVersion", "3", "languages", "eng, hin");
    }

    static JsonNode problem(HttpResponse<String> r, int status, String code) throws Exception {
        assertEquals(status, r.statusCode(), r.body());
        String type = r.headers().firstValue("content-type").orElse("");
        assertTrue(type.startsWith(Problems.MEDIA_TYPE), "problem JSON, was " + type + ": " + r.body());
        JsonNode body = JSON.readTree(r.body());
        assertEquals(code, body.path("code").asText(), r.body());
        return body;
    }

    @Test
    void aMultipartUploadCreatesAPendingTranscriptFromTheExactPartBytes() throws Exception {
        String path = newVersionPath();
        HttpResponse<String> r = upload(path, admin(), Multipart.of(fields(), "image/png", PNG));
        assertEquals(201, r.statusCode(), r.body());
        JsonNode t = JSON.readTree(r.body());
        String id = t.path("id").asText();
        assertEquals("/api/v1/ai/ocr-transcripts/" + id, r.headers().firstValue("location").orElse(""));
        assertEquals("PENDING_REVIEW", t.path("state").asText(), r.body());
        assertEquals("scan-1", t.path("documentId").asText(), r.body());
        assertEquals("3", t.path("docVersion").asText(), r.body());

        assertEquals(1, CALLS.size(), "one engine call");
        Call c = CALLS.get(0);
        assertArrayEquals(PNG, c.image(), "the engine gets the file part's bytes, nothing of the multipart framing");
        assertEquals("image/png", c.mediaType(), "the part's own Content-Type");
        assertEquals(List.of("eng", "hin"), c.languages());

        HttpResponse<String> read = send(at("/api/v1/ai/ocr-transcripts/" + id).header("Authorization", bearer(admin())).GET());
        assertEquals(200, read.statusCode(), read.body());
        assertEquals(id, JSON.readTree(read.body()).path("id").asText());
        assertEquals("district treasury sanction order", JSON.readTree(read.body()).path("text").asText(), read.body());
    }

    @Test
    void malformedUploadsAreProblemJsonAndNeverReachTheEngine() throws Exception {
        String path = newVersionPath();
        problem(upload(path, admin(), Multipart.of(fields(), null, null)), 400, "VALIDATION_FAILED");

        problem(send(at(path).header("Authorization", bearer(admin())).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"documentId\":\"scan-1\"}"))), 415, "UNSUPPORTED_MEDIA_TYPE");

        byte[] big = new byte[MAX_FILE_BYTES + 1024];
        System.arraycopy(PNG, 0, big, 0, PNG.length);
        problem(upload(path, admin(), Multipart.of(fields(), "image/png", big)), 413, "PAYLOAD_TOO_LARGE");

        assertEquals(List.of(), CALLS, "the engine is never called for a malformed upload");
    }

    @Test
    void anAnonymousCallerIs401AndACallerWithoutAiAdminIs403() throws Exception {
        String path = newVersionPath();
        assertEquals(401, upload(path, null, Multipart.of(fields(), "image/png", PNG)).statusCode());

        String user = AcceptanceIdp.get().token(TENANT, "ocr-http-user", List.of("sangam-user"));
        problem(upload(path, user, Multipart.of(fields(), "image/png", PNG)), 403,
                ApiProblem.notPermitted(AiController.ADMIN).code());

        assertEquals(List.of(), CALLS, "the engine is not called without ai.admin");
    }

    @Test
    void anUnavailableEngineIs503AndRecordsNoTranscript() throws Exception {
        String path = newVersionPath();
        engineFailure = new OcrUnavailable("OCR is not ready: test engine down");
        problem(upload(path, admin(), Multipart.of(Map.of("documentId", "scan-down", "languages", "eng"), "image/png", PNG)),
                503, "OCR_UNAVAILABLE");
        assertEquals(1, CALLS.size(), "the engine was asked once");

        HttpResponse<String> list = send(at("/api/v1/ai/ocr-transcripts").header("Authorization", bearer(admin())).GET());
        assertEquals(200, list.statusCode(), list.body());
        for (JsonNode s : JSON.readTree(list.body())) {
            assertTrue(!"scan-down".equals(s.path("documentId").asText()), "no transcript for a failed run: " + list.body());
        }
    }
}
