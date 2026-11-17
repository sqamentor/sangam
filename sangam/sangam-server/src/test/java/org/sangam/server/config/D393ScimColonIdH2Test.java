// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
 * Decision 393 on {@code /scim/v2/Users/{id}}: the path is fixed by RFC 7644 section 3.2 and built by the IdP (EXEMPT), and
 * option (a) (owner "Go ahead" 2026-11-17) makes the SCIM id opaque and server-assigned, so a person whose id holds '/'
 * or ':' (LDAP sync, bootstrap) is still reachable: Tomcat refuses %2F in a path. On the real server (h2, connector and
 * filters), an adopted person with such an id is read, patched and deleted at its {@code meta.location}, and a group
 * whose member is that person round-trips in SCIM ids.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
@DirtiesContext
class D393ScimColonIdH2Test {

    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

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
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)   // SCIM is served at /scim/v2 (ScimController), outside /api/v1)
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

    String person(String tenant, String personId, String subject) throws Exception {
        try (var c = ds.getConnection(); var ps = c.prepareStatement("INSERT INTO SEC_PERSON(tenant_id, person_id, display_name,"
                + " external_subject, email, status, created_at) VALUES (?,?,?,?,NULL,'ACTIVE',CURRENT_TIMESTAMP)")) {
            ps.setString(1, tenant);
            ps.setString(2, personId);
            ps.setString(3, "D393 " + personId);
            ps.setString(4, subject);
            assertEquals(1, ps.executeUpdate());
        }
        HttpResponse<String> created = scim("POST", "/scim/v2/Users", "{\"schemas\":[\"urn:ietf:params:scim:schemas:core:2.0:User\"],"
                + "\"userName\":\"" + subject + "\",\"externalId\":\"" + subject + "\"}");
        assertEquals(201, created.statusCode(), created.body());
        JsonNode u = Json.tree(created.body());
        String id = u.path("id").asText();
        assertNotEquals(personId, id, "the SCIM id is server-assigned, not the person id");
        assertTrue(id.matches("[A-Za-z0-9._~-]+"), id);
        assertEquals("/scim/v2/Users/" + id, u.path("meta").path("location").asText());
        assertEquals(personId, u.path("urn:sangam:params:scim:schemas:extension:2.0:User").path("personId").asText(),
                u.toString());
        return id;
    }

    @Test
    void anAdoptedPersonWhoseIdHoldsSlashesAndColonsIsReadPatchedAndDeletedAtItsLocation() throws Exception {
        String tenant = env.getProperty("sangam.security.dev-token.tenant", "dev");
        String personId = "ldap/d393:" + UUID.randomUUID() + "/b";
        String id = person(tenant, personId, "d393-subject-" + UUID.randomUUID());
        String path = "/scim/v2/Users/" + id;

        HttpResponse<String> read = scim("GET", path, null);
        assertEquals(200, read.statusCode(), read.body());
        assertEquals(id, Json.tree(read.body()).path("id").asText());
        HttpResponse<String> listed = scim("GET", "/scim/v2/Users?filter="
                + URLEncoder.encode("id eq \"" + id + "\"", StandardCharsets.UTF_8), null);
        assertEquals(200, listed.statusCode(), listed.body());
        assertEquals(1, Json.tree(listed.body()).path("totalResults").asInt(), listed.body());

        HttpResponse<String> patched = scim("PATCH", path, "{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"],"
                + "\"Operations\":[{\"op\":\"replace\",\"path\":\"displayName\",\"value\":\"D393 patched\"}]}");
        assertEquals(200, patched.statusCode(), patched.body());
        assertEquals("D393 patched", Json.tree(patched.body()).path("displayName").asText());

        HttpResponse<String> deleted = scim("DELETE", path, null);
        assertEquals(204, deleted.statusCode(), deleted.body());
        HttpResponse<String> gone = scim("GET", path, null);
        assertEquals(404, gone.statusCode(), gone.body());
    }

    @Test
    void aGroupWhoseMemberHasASlashedPersonIdRoundTrips() throws Exception {
        String tenant = env.getProperty("sangam.security.dev-token.tenant", "dev");
        String a = person(tenant, "ldap/d393g/" + UUID.randomUUID(), "d393-g-" + UUID.randomUUID());
        HttpResponse<String> created = scim("POST", "/scim/v2/Groups", "{\"schemas\":[\"urn:ietf:params:scim:schemas:core:2.0:Group\"],"
                + "\"displayName\":\"D393 " + UUID.randomUUID() + "\",\"members\":[{\"value\":\"" + a + "\"}]}");
        assertEquals(201, created.statusCode(), created.body());
        JsonNode g = Json.tree(created.body());
        assertEquals(a, g.path("members").path(0).path("value").asText(), created.body());
        String gpath = g.path("meta").path("location").asText();
        HttpResponse<String> read = scim("GET", gpath, null);
        assertEquals(200, read.statusCode(), read.body());
        assertEquals(a, Json.tree(read.body()).path("members").path(0).path("value").asText(), read.body());
        HttpResponse<String> replaced = scim("PUT", gpath, read.body());
        assertEquals(200, replaced.statusCode(), replaced.body());
        assertEquals(1, Json.tree(replaced.body()).path("members").size(), replaced.body());
        HttpResponse<String> user = scim("GET", "/scim/v2/Users/" + a, null);
        assertEquals(g.path("id").asText(), Json.tree(user.body()).path("groups").path(0).path("value").asText(), user.body());
    }
}
