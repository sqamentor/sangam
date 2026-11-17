// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/** SL-41 over real HTTP (h2 profile): the pack lifecycle endpoints, their validation and problem rendering. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
@DirtiesContext
class PackLifecycleRestH2Test {

    static final String DEV = "Bearer sangam-dev-token-change-me";
    static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    int port;

    HttpResponse<String> send(String method, String path, String auth, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(60)).header("Accept", "application/json");
        if (auth != null) {
            b.header("Authorization", auth);
        }
        if (json != null) {
            b.header("Content-Type", "application/json").header("Idempotency-Key", UUID.randomUUID().toString())
                    .method(method, HttpRequest.BodyPublishers.ofString(json));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void installedListsTheTenantsPackDeployments() throws Exception {
        HttpResponse<String> r = send("GET", "/api/v1/packs/installed?tenant=dev", DEV, null);
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(JSON.readTree(r.body()).isArray(), r.body());
        HttpResponse<String> noTenant = send("GET", "/api/v1/packs/installed", DEV, null);
        assertEquals(400, noTenant.statusCode(), noTenant.body());
        assertEquals("tenant", JSON.readTree(noTenant.body()).path("field").asText(), noTenant.body());
    }

    @Test
    void uninstallAndRollbackOfAPackTheTenantDoesNotHaveAre404() throws Exception {
        for (String op : new String[]{"uninstall", "rollback"}) {
            HttpResponse<String> r = send("POST", "/api/v1/packs/no.such.pack/" + op, DEV,
                    "{\"tenant\":\"dev\",\"reason\":\"SL-41 contract\"}");
            assertEquals(404, r.statusCode(), op + ": " + r.body());
            JsonNode p = JSON.readTree(r.body());
            assertEquals("PACK_NOT_INSTALLED", p.path("code").asText(), r.body());
        }
    }

    @Test
    void aReasonIsRequired() throws Exception {
        HttpResponse<String> r = send("POST", "/api/v1/packs/by-id/uninstall?id=no.such.pack", DEV, "{\"tenant\":\"dev\"}");
        assertEquals(400, r.statusCode(), r.body());
        assertEquals("REASON_REQUIRED", JSON.readTree(r.body()).path("code").asText(), r.body());
    }

    @Test
    void unauthenticatedCallersAreRefused() throws Exception {
        assertEquals(401, send("GET", "/api/v1/packs/installed?tenant=dev", null, null).statusCode());
    }
}
