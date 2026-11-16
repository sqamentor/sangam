// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

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
 * R7-ui sweep, task-user role: the dev token as a tenant identity holding only sangam-user (no supervisor group), as
 * a clerk signs in. The workspace queries of such a user add the default visibility rule (assignee, candidate user or
 * candidate group/seat) as an Operaton OR branch. OperatonWorkspaceAdapter put the branch's criteria and endOr() on
 * the outer query, which the engine refuses ("cannot set endOr() before or()"), so GET /tasks/counts (the inbox
 * counters) and GET /field/visits answered 500 for every non-supervisor; a supervisor skips the rule and never saw it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
@DirtiesContext
class TaskUserWorkspaceH2Test {

    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry r) {
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> AcceptanceIdp.get().issuer());
        r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> AcceptanceIdp.get().jwksUri());
        // a tenant (not platform) identity with the task-user group only
        r.add("sangam.security.platform-issuer", () -> "");
        r.add("sangam.security.dev-token.groups", () -> "sangam-user");
    }

    @LocalServerPort
    int port;
    @Autowired
    Environment env;

    HttpResponse<String> get(String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + env.getRequiredProperty("sangam.security.dev-token.token"))
                .header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void theCallerIsATaskUserNotASupervisor() throws Exception {
        HttpResponse<String> me = get("/me");
        assertEquals(200, me.statusCode(), me.body());
        JsonNode groups = Json.tree(me.body()).path("groups");
        assertEquals(1, groups.size(), me.body());
        assertEquals("sangam-user", groups.get(0).asText(), me.body());
    }

    @Test
    void aTaskUserReadsTheInboxCounters() throws Exception {
        HttpResponse<String> r = get("/tasks/counts");
        assertEquals(200, r.statusCode(), r.body());
        JsonNode counts = Json.tree(r.body());
        for (String view : List.of("POOL", "ESCALATED", "RETURNED", "ALL")) {
            assertTrue(counts.has(view), view + " in " + r.body());
        }
    }

    @Test
    void aTaskUserReadsTheFieldVisits() throws Exception {
        HttpResponse<String> r = get("/field/visits");
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(Json.tree(r.body()).isArray(), r.body());
    }
}
