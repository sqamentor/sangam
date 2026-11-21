// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.sangam.integration.connector.Json;
import org.sangam.rest.web.CommandCatalog;
import org.sangam.server.acceptance.AcceptanceIdp;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Register P-1 (owner decision 2026-11-20, "Move to POST"; CodeQL alert 298): generating the compliance evidence
 * report inserts a {@code CMP_REPORT} row and writes the audit event {@code CMP_EVIDENCE_REPORT_GENERATED}, so it is a
 * {@code POST /api/v1/compliance/reports} with a JSON body, and a GET of that path writes nothing. On the real server
 * (h2, the whole connector and filter chain); every case counts the two kinds of rows before and after.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
@DirtiesContext
class ComplianceReportIsAPostH2Test {

    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    static final String PATH = "/compliance/reports";
    static final String FROM = "2026-11-01T00:00:00Z";
    static final String TO = "2026-11-21T00:00:00Z";
    static final String PERIOD = "\"from\":\"" + FROM + "\",\"to\":\"" + TO + "\"";

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry r) {
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> AcceptanceIdp.get().issuer());
        r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> AcceptanceIdp.get().jwksUri());
    }

    @LocalServerPort
    int port;
    @Autowired
    Environment env;
    @Autowired
    DataSource dataSource;

    /** Rows of CMP_REPORT and audit events CMP_EVIDENCE_REPORT_GENERATED, all tenants. */
    record Rows(long reports, long audits) {
    }

    Rows rows() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            return new Rows(count(c, "SELECT COUNT(*) FROM CMP_REPORT"),
                    count(c, "SELECT COUNT(*) FROM AUDIT_EVENT WHERE event_type = 'CMP_EVIDENCE_REPORT_GENERATED'"));
        }
    }

    static long count(Connection c, String sql) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    String storedHash(String reportId) throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT report_hash FROM CMP_REPORT WHERE report_id = ?")) {
            ps.setString(1, reportId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    HttpResponse<String> send(String method, String pathAndQuery, String body, String accept, String key, boolean token)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + pathAndQuery))
                .timeout(Duration.ofSeconds(120)).header("Accept", accept)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (token) {
            b.header("Authorization", "Bearer " + env.getRequiredProperty("sangam.security.dev-token.token"));
        }
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        if (key != null) {
            b.header("Idempotency-Key", key);
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> post(String body) throws Exception {
        return send("POST", PATH, body, "application/json", UUID.randomUUID().toString(), true);
    }

    static String type(HttpResponse<String> r) {
        return r.headers().firstValue("content-type").orElse("");
    }

    static String where(HttpResponse<String> r) {
        return r.statusCode() + " " + type(r) + " " + (r.body().length() > 400 ? r.body().substring(0, 400) : r.body());
    }

    @Test
    @SuppressWarnings("unchecked")
    void theContractDeclaresThePostAsAnIdempotentCommandAndNoGet() throws Exception {
        var match = new CommandCatalog().match("POST", "/api/v1" + PATH);
        assertTrue(match.isPresent(), "POST " + PATH + " is not an idempotent command of the contract");
        assertEquals("compliance.report.generate", match.get().command().commandType());
        LoaderOptions options = new LoaderOptions();
        options.setCodePointLimit(64 * 1024 * 1024);
        Map<String, Object> contract;
        try (InputStream in = CommandCatalog.class.getClassLoader().getResourceAsStream(CommandCatalog.CONTRACT_RESOURCE)) {
            contract = (Map<String, Object>) new Yaml(new SafeConstructor(options)).load(in);
        }
        Map<String, Object> path = (Map<String, Object>) ((Map<String, Object>) contract.get("paths")).get(PATH);
        assertEquals(java.util.Set.of("post"), path.keySet(), "operations of " + PATH + " in the contract");
        Map<String, Object> post = (Map<String, Object>) path.get("post");
        assertEquals("generateComplianceReport", post.get("operationId"));
        assertEquals("audit.read", post.get("x-sangam-permission"));
    }

    @Test
    void aGetAnswers405AndWritesNothing() throws Exception {
        Rows before = rows();
        String query = "?from=" + FROM + "&to=" + TO;
        for (String q : new String[]{query, query + "&verifyChains=true&format=json", query + "&format=html", ""}) {
            HttpResponse<String> r = send("GET", PATH + q, null, "application/json, text/html", null, true);
            assertEquals(405, r.statusCode(), "GET " + PATH + q + " -> " + where(r));
            assertEquals("METHOD_NOT_ALLOWED", Json.tree(r.body()).path("code").asText(), where(r));
            assertTrue(r.headers().firstValue("X-Sangam-Report-Hash").isEmpty(), "a refused GET carries no report hash");
        }
        assertEquals(before, rows(), "a GET of " + PATH + " wrote a report row or a report audit event");
    }

    @Test
    void aPostGeneratesExactlyOneReportAndOneAuditEvent() throws Exception {
        Rows before = rows();
        HttpResponse<String> r = post("{" + PERIOD + ",\"verifyChains\":true,\"format\":\"json\"}");
        assertEquals(200, r.statusCode(), where(r));
        assertTrue(type(r).startsWith("application/json"), where(r));
        JsonNode report = Json.tree(r.body());
        String id = report.path("reportId").asText();
        String hash = report.path("hash").asText();
        assertTrue(!id.isEmpty(), "reportId missing: " + where(r));
        assertTrue(hash.matches("[0-9a-fA-F]{64}"), "hash is not a SHA-256: " + hash);
        assertEquals(hash, r.headers().firstValue("X-Sangam-Report-Hash").orElse(null), "header and body hash differ");
        assertTrue(report.path("content").isObject(), "content missing: " + where(r));
        assertEquals(hash, storedHash(id), "CMP_REPORT keeps the hash of the report that was answered");
        assertEquals(new Rows(before.reports() + 1, before.audits() + 1), rows());
    }

    @Test
    void onlyThePeriodIsRequiredVerifyChainsAndFormatHaveDefaults() throws Exception {
        Rows before = rows();
        HttpResponse<String> r = post("{" + PERIOD + "}");
        assertEquals(200, r.statusCode(), where(r));
        assertTrue(type(r).startsWith("application/json"), "format defaults to json: " + where(r));
        JsonNode report = Json.tree(r.body());
        assertEquals(report.path("hash").asText(), r.headers().firstValue("X-Sangam-Report-Hash").orElse(null));
        assertTrue(report.path("content").isObject(), where(r));
        HttpResponse<String> unverified = post("{" + PERIOD + ",\"verifyChains\":false}");
        assertEquals(200, unverified.statusCode(), where(unverified));
        assertEquals(new Rows(before.reports() + 2, before.audits() + 2), rows());
    }

    @Test
    void formatHtmlAnswersTheHtmlRenderingWithItsHash() throws Exception {
        Rows before = rows();
        HttpResponse<String> r = send("POST", PATH, "{" + PERIOD + ",\"format\":\"html\"}", "*/*",
                UUID.randomUUID().toString(), true);
        assertEquals(200, r.statusCode(), where(r));
        assertTrue(type(r).startsWith("text/html"), where(r));
        assertTrue(r.body().toLowerCase(java.util.Locale.ROOT).contains("<html"), where(r));
        String hash = r.headers().firstValue("X-Sangam-Report-Hash").orElse("");
        assertTrue(hash.matches("[0-9a-fA-F]{64}"), "X-Sangam-Report-Hash missing on the HTML answer: " + hash);
        assertEquals(new Rows(before.reports() + 1, before.audits() + 1), rows());
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM CMP_REPORT WHERE report_hash = ?")) {
            ps.setString(1, hash);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(1, rs.getLong(1), "the HTML answer's hash is the stored report's hash");
            }
        }
    }

    @Test
    void aMissingOrUnreadablePeriodIs400AndWritesNothing() throws Exception {
        Rows before = rows();
        String[][] bad = {
                {"{\"to\":\"" + TO + "\"}", "from"},
                {"{\"from\":\"" + FROM + "\"}", "to"},
                {"{}", "from"},
                {"{\"from\":null,\"to\":\"" + TO + "\",\"format\":\"html\"}", "from"},
        };
        for (String[] b : bad) {
            HttpResponse<String> r = post(b[0]);
            assertEquals(400, r.statusCode(), b[0] + " -> " + where(r));
            JsonNode problem = Json.tree(r.body());
            assertEquals("VALIDATION_FAILED", problem.path("code").asText(), b[0] + " -> " + where(r));
            assertTrue(problem.path("detail").asText().contains(b[1]), "the problem names " + b[1] + ": " + where(r));
        }
        // a period that is not an instant, and no body at all: refused before the controller's own checks
        for (String body : new String[]{"{\"from\":\"yesterday\",\"to\":\"" + TO + "\"}", "{\"from\":\"" + FROM + "\",\"to\":", null}) {
            HttpResponse<String> r = post(body);
            assertEquals(400, r.statusCode(), body + " -> " + where(r));
        }
        // the old shape, the period in the query, is not read: the body carries it
        HttpResponse<String> oldQuery = send("POST", PATH + "?from=" + FROM + "&to=" + TO, "{}", "application/json",
                UUID.randomUUID().toString(), true);
        assertEquals(400, oldQuery.statusCode(), "the period is read from the body only: " + where(oldQuery));
        assertEquals(before, rows(), "a refused request wrote a report row or a report audit event");
    }

    @Test
    void withoutATokenNothingIsGenerated() throws Exception {
        Rows before = rows();
        HttpResponse<String> r = send("POST", PATH, "{" + PERIOD + "}", "application/json", UUID.randomUUID().toString(), false);
        assertEquals(401, r.statusCode(), where(r));
        assertEquals(before, rows());
    }

    @Test
    void theSameIdempotencyKeyAndBodyGenerateOnce() throws Exception {
        Rows before = rows();
        String key = UUID.randomUUID().toString();
        String body = "{" + PERIOD + ",\"verifyChains\":true,\"format\":\"json\"}";
        HttpResponse<String> first = send("POST", PATH, body, "application/json", key, true);
        assertEquals(200, first.statusCode(), where(first));
        assertTrue(first.headers().firstValue("Idempotent-Replayed").isEmpty(), "the first answer is not a replay");
        HttpResponse<String> second = send("POST", PATH, body, "application/json", key, true);
        assertEquals(200, second.statusCode(), where(second));
        assertEquals("true", second.headers().firstValue("Idempotent-Replayed").orElse(null), where(second));
        JsonNode a = Json.tree(first.body());
        JsonNode b = Json.tree(second.body());
        assertEquals(a.path("reportId").asText(), b.path("reportId").asText(), "the replay answers the stored report");
        assertEquals(a.path("hash").asText(), b.path("hash").asText());
        assertEquals(new Rows(before.reports() + 1, before.audits() + 1), rows(), "the replay generated a second report");

        // the same key with another period is refused and generates nothing
        HttpResponse<String> other = send("POST", PATH, "{\"from\":\"" + FROM + "\",\"to\":\"" + Instant.parse(TO).plusSeconds(3600)
                + "\"}", "application/json", key, true);
        assertTrue(other.statusCode() == 409 || other.statusCode() == 422, where(other));
        assertEquals("IDEMPOTENCY_KEY_REUSED", Json.tree(other.body()).path("code").asText(), where(other));
        assertEquals(new Rows(before.reports() + 1, before.audits() + 1), rows());

        // a new key generates a new report
        HttpResponse<String> fresh = post(body);
        assertEquals(200, fresh.statusCode(), where(fresh));
        assertNotEquals(a.path("reportId").asText(), Json.tree(fresh.body()).path("reportId").asText());
        assertEquals(new Rows(before.reports() + 2, before.audits() + 2), rows());
    }

    @Test
    void aReplayOfTheHtmlFormatGeneratesNoSecondReport() throws Exception {
        Rows before = rows();
        String key = UUID.randomUUID().toString();
        String body = "{" + PERIOD + ",\"format\":\"html\"}";
        HttpResponse<String> first = send("POST", PATH, body, "*/*", key, true);
        assertEquals(200, first.statusCode(), where(first));
        HttpResponse<String> second = send("POST", PATH, body, "*/*", key, true);
        assertEquals(200, second.statusCode(), where(second));
        assertEquals("true", second.headers().firstValue("Idempotent-Replayed").orElse(null), where(second));
        assertEquals(first.body(), second.body(), "the replay answers the stored rendering");
        // observation for the lane report (IdempotencyFilter.replay): media type and report-hash header of a replay
        System.out.println("P-1 OBSERVED html replay: content-type=" + type(second) + " X-Sangam-Report-Hash="
                + second.headers().firstValue("X-Sangam-Report-Hash").orElse("(absent)") + "; first: content-type="
                + type(first));
        assertEquals(new Rows(before.reports() + 1, before.audits() + 1), rows());
    }
}
