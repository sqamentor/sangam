// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.sangam.integration.connector.Json;
import org.sangam.server.acceptance.AcceptanceIdp;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Decision 393 ruling on {@code /scim/v2/Users/{id}}: EXEMPT, because RFC 7644 section 3.2 fixes the resource path and
 * the IdP builds it. Root-level protection: a SCIM user whose id carries ':' (an adopted person id, ScimService
 * create) is read, patched and deleted through the percent-encoded path on the real server (h2, connector and filters).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
@DirtiesContext
class D393ScimColonIdH2Test {

    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    static final String[] VALUES = {"d393/a/b", "d393:a:b"};

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
    DataSource ds;

    HttpResponse<String> scim(String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + env.getRequiredProperty("sangam.security.dev-token.token"))
                .header("Accept", "application/scim+json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (body != null) {
            b.header("Content-Type", "application/scim+json");
        }
        if (!"GET".equals(method)) {
            b.header("Idempotency-Key", UUID.randomUUID().toString());
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aScimUserWhoseIdCarriesAColonIsReadPatchedAndDeletedThroughTheEncodedPath() throws Exception {
        String tenant = env.getProperty("sangam.security.dev-token.tenant", "dev");
        String personId = "scim:d393:" + UUID.randomUUID();
        String subject = "d393-subject-" + UUID.randomUUID();
        try (var c = ds.getConnection(); var ps = c.prepareStatement("INSERT INTO SEC_PERSON(tenant_id, person_id, display_name,"
                + " external_subject, email, status, created_at) VALUES (?,?,?,?,NULL,'ACTIVE',CURRENT_TIMESTAMP)")) {
            ps.setString(1, tenant);
            ps.setString(2, personId);
            ps.setString(3, "D393 colon person");
            ps.setString(4, subject);
            assertEquals(1, ps.executeUpdate());
        }
        HttpResponse<String> created = scim("POST", "/scim/v2/Users", "{\"schemas\":[\"urn:ietf:params:scim:schemas:core:2.0:User\"],"
                + "\"userName\":\"" + subject + "\",\"externalId\":\"" + subject + "\"}");
        assertEquals(201, created.statusCode(), created.body());
        JsonNode u = Json.tree(created.body());
        assertEquals(personId, u.path("id").asText(), "the adopted person id is the SCIM id");

        String path = "/scim/v2/Users/" + URLEncoder.encode(personId, StandardCharsets.UTF_8);
        assertTrue(path.contains("%3A"), path);
        HttpResponse<String> read = scim("GET", path, null);
        assertEquals(200, read.statusCode(), read.body());
        assertEquals(personId, Json.tree(read.body()).path("id").asText());

        HttpResponse<String> patched = scim("PATCH", path, "{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"],"
                + "\"Operations\":[{\"op\":\"replace\",\"path\":\"displayName\",\"value\":\"D393 patched\"}]}");
        assertEquals(200, patched.statusCode(), patched.body());
        assertEquals("D393 patched", Json.tree(patched.body()).path("displayName").asText());

        HttpResponse<String> deleted = scim("DELETE", path, null);
        assertEquals(204, deleted.statusCode(), deleted.body());
        HttpResponse<String> gone = scim("GET", path, null);
        assertEquals(404, gone.statusCode(), gone.body());
    }
}
