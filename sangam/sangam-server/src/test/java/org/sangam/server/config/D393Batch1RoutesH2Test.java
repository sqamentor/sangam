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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
 * Decision 393 batch 1 on the real server (h2, the whole connector and filter chain): a process or decision definition
 * id (key:version:uuid), a user id (an IdP principal id), a group id (an IdP group path such as /staff/clerks), a role
 * id (pack:clerk) and an ops-admin secret reference travel as required query parameters on literal by-&lt;name&gt; routes,
 * so a value with '/' or ':' reaches the application and is answered in JSON or problem+json. Before the change the
 * value was a path segment, and the servlet connector refuses an encoded '/' (%2F) with an HTML 400 before any
 * application code runs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
@DirtiesContext
class D393Batch1RoutesH2Test {

    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry r) {
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> AcceptanceIdp.get().issuer());
        r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> AcceptanceIdp.get().jwksUri());
    }

    static final String XML = "application/xml, application/problem+json";

    @LocalServerPort
    int port;
    @Autowired
    Environment env;

    static String q(String... nameValue) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i + 1 < nameValue.length; i += 2) {
            s.append(s.isEmpty() ? "?" : "&").append(nameValue[i]).append('=')
                    .append(URLEncoder.encode(nameValue[i + 1], StandardCharsets.UTF_8));
        }
        return s.toString();
    }

    HttpResponse<String> send(String method, String path, String body) throws Exception {
        return send(method, path, body, "application/json");
    }

    HttpResponse<String> send(String method, String path, String body, String accept) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + env.getRequiredProperty("sangam.security.dev-token.token"))
                .header("Accept", accept)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (body != null) {
            b.header("Content-Type", "application/json").header("Idempotency-Key", UUID.randomUUID().toString());
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Every moved route, once with a '/' value and once with a ':' value. */
    @Test
    void everyMovedRouteTakesASlashOrAColonValueAndAnswersInJson() throws Exception {
        List<String> wrong = new ArrayList<>();
        for (String v : List.of("d393/a/b", "d393:a:b")) {
            String[][] calls = {
                    {"GET", "/process-definitions/by-id" + q("id", v), null},
                    {"GET", "/process-definitions/by-id/xml" + q("id", v), null, XML},
                    {"GET", "/decision-definitions/by-id/xml" + q("id", v), null, XML},
                    {"PUT", "/ops-admin/secrets/by-ref" + q("ref", v), "{\"value\":\"d393-not-a-secret\"}"},
                    {"DELETE", "/ops-admin/secrets/by-ref" + q("ref", v), null},
                    {"POST", "/process-definitions/by-id/suspension" + q("id", v), "{\"suspended\":true,\"reason\":\"d393\"}"},
                    {"PUT", "/admin/users/by-id" + q("id", v), "{\"displayName\":\"D393\"}"},
                    {"POST", "/admin/users/by-id/disable" + q("id", v), "{\"reason\":\"d393\"}"},
                    {"PUT", "/admin/groups/by-id" + q("id", v), "{\"name\":\"D393\"}"},
                    {"GET", "/admin/groups/by-id/members" + q("id", v), null},
                    {"POST", "/admin/groups/by-id/members/by-user-id" + q("id", v, "userId", v), null},
                    {"DELETE", "/admin/groups/by-id/members/by-user-id" + q("id", v, "userId", v), null},
                    {"PUT", "/admin/roles/by-role-id" + q("roleId", v), "{\"roleId\":\"" + v + "\",\"grants\":[]}"},
            };
            for (String[] c : calls) {
                HttpResponse<String> r = send(c[0], c[1], c[2], c.length > 3 ? c[3] : "application/json");
                String type = r.headers().firstValue("content-type").orElse("");
                String where = c[0] + " " + c[1] + " -> " + r.statusCode() + " " + type;
                if (r.statusCode() == 200 && c.length > 3 && type.startsWith("application/xml")) {
                    continue;
                }
                if (!type.startsWith("application/json") && !type.startsWith("application/problem+json")) {
                    wrong.add(where + ": not JSON: " + r.body());
                    continue;
                }
                if (r.statusCode() == 405 || (r.statusCode() == 404
                        && "No such endpoint".equals(Json.tree(r.body()).path("detail").asText()))) {
                    wrong.add(where + ": no such route: " + r.body());
                }
                if (r.statusCode() == 401 || r.statusCode() == 406) {
                    wrong.add(where + ": refused before the controller: " + r.body());
                }
            }
        }
        assertEquals(List.of(), wrong, "routes that did not reach the application with a '/' or ':' value");
    }

    /** A group whose id is an IdP-style path is renamed and its members are listed through the by-id routes. */
    @Test
    void aGroupWithASlashInItsIdIsRenamedAndItsMembersListed() throws Exception {
        String id = "/d393/clerks-" + UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> created = send("POST", "/admin/groups", "{\"id\":\"" + id + "\",\"name\":\"Clerks\"}");
        assertEquals(201, created.statusCode(), created.body());
        HttpResponse<String> renamed = send("PUT", "/admin/groups/by-id" + q("id", id), "{\"name\":\"Senior clerks\"}");
        assertEquals(200, renamed.statusCode(), renamed.body());
        JsonNode g = Json.tree(renamed.body());
        assertEquals(id, g.path("id").asText(), renamed.body());
        assertEquals("Senior clerks", g.path("name").asText(), renamed.body());
        HttpResponse<String> members = send("GET", "/admin/groups/by-id/members" + q("id", id), null);
        assertEquals(200, members.statusCode(), members.body());
        assertTrue(Json.tree(members.body()).isArray(), members.body());
    }
}
