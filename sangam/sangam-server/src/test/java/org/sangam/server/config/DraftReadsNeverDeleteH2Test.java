// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.sangam.server.acceptance.AcceptanceIdp;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Round 9 CSRF (owner rule 5): reading form drafts never changes state. Before the fix, {@code GET /forms/drafts} and
 * {@code GET /forms/drafts/{id}} deleted every expired draft of the tenant (lazy expiry, X.2.5.8 rule 4). Now an
 * expired draft is simply not returned by a read, and expired rows are deleted on the draft write paths.
 *
 * <ul>
 *   <li>a legitimate GET (bearer header) still answers 200 and still hides an expired draft;</li>
 *   <li>the GET leaves every row in place (no write on a read);</li>
 *   <li>a forged cross-site GET (no Authorization header, the token only in a cookie and the query) is 401 and leaves
 *       every row in place;</li>
 *   <li>the next draft write deletes the expired row.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
@DirtiesContext
class DraftReadsNeverDeleteH2Test {

    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry r) {
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> AcceptanceIdp.get().issuer());
        r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> AcceptanceIdp.get().jwksUri());
    }

    @LocalServerPort
    int port;
    @Autowired
    DataSource dataSource;

    String url(String path) {
        return "http://localhost:" + port + "/api/v1" + path;
    }

    HttpResponse<String> call(String method, String path, String token, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url(path))).timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json");
        b.method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    String createDraft(String token, String formKey) throws Exception {
        HttpResponse<String> r = call("POST", "/forms/drafts", token,
                "{\"formKey\":\"" + formKey + "\",\"ownerKind\":\"PERSON\",\"data\":{\"a\":1}}");
        assertEquals(201, r.statusCode(), r.body());
        return r.headers().firstValue("Location").orElseThrow().replaceAll(".*/", "");
    }

    void expire(String draftId) throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE FORM_DRAFT SET expires_at=? WHERE draft_id=?")) {
            ps.setTimestamp(1, Timestamp.from(Instant.now().minus(Duration.ofDays(1))));
            ps.setString(2, draftId);
            assertEquals(1, ps.executeUpdate());
        }
    }

    boolean stored(String draftId) throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM FORM_DRAFT WHERE draft_id=?")) {
            ps.setString(1, draftId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) == 1;
            }
        }
    }

    @Test
    void readsHideExpiredDraftsWithoutDeletingThemAndAForgedReadIsRefused() throws Exception {
        String token = AcceptanceIdp.get().token("dev", "draft-reader", List.of("sangam-user"));
        String expired = createDraft(token, "csrf/expired");
        String live = createDraft(token, "csrf/live");
        expire(expired);

        HttpResponse<String> list = call("GET", "/forms/drafts", token, null);
        assertEquals(200, list.statusCode(), list.body());
        assertTrue(list.body().contains(live), "the live draft is listed");
        assertFalse(list.body().contains(expired), "an expired draft is not listed");
        assertEquals(404, call("GET", "/forms/drafts/" + expired, token, null).statusCode(),
                "an expired draft is not found");
        assertEquals(200, call("GET", "/forms/drafts/" + live, token, null).statusCode());
        assertTrue(stored(expired), "a GET never deletes: the expired row is still stored after the reads");

        HttpRequest forged = HttpRequest.newBuilder(URI.create(url("/forms/drafts?access_token=" + token)))
                .header("Origin", "https://evil.example").header("Cookie", "access_token=" + token).GET().build();
        assertEquals(401, HTTP.send(forged, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertTrue(stored(expired), "a forged GET changes nothing");

        createDraft(token, "csrf/next-write");
        assertFalse(stored(expired), "the next draft write deletes the expired row");
        assertTrue(stored(live));
    }
}
