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

/**
 * SL-80: an event definition names a nested correlation pointer and a nested business key the same way,
 * {@code /bill/id -> bill.id} (the event-registry convention). Before, a unique start on the nested business key
 * declared a correlation parameter named {@code bill/id}. h2 profile, real HTTP, the server's ports event bridge.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
@DirtiesContext
class CorrelationNamingH2Test {

    static final String DEV = "Bearer sangam-dev-token-change-me";
    static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    int port;

    HttpResponse<String> post(String path, String json) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(60)).header("Authorization", DEV).header("Accept", "application/json")
                .header("Content-Type", "application/json").header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(json)).build();
        return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
    }

    static String key() {
        return "sl80-" + Long.toString(System.nanoTime(), 36);
    }

    @Test
    void aNestedBusinessKeyIsDeclaredWithTheDottedName() throws Exception {
        HttpResponse<String> r = post("/api/v1/events/definitions", "{\"key\":\"" + key() + "\",\"name\":\"nested\","
                + "\"startProcessKey\":\"sl80-start\",\"uniqueStart\":true,\"businessKeyPointer\":\"/bill/id\","
                + "\"correlationParameters\":[\"/bill/head\"]}");
        assertEquals(201, r.statusCode(), r.body());
        JsonNode d = JSON.readTree(r.body());
        assertEquals("[\"bill.head\",\"bill.id\"]", d.path("correlationParameters").toString(), r.body());
        assertEquals("/bill/id", d.path("businessKeyPointer").asText(), r.body());
    }

    @Test
    void aPointerWithoutAnUnambiguousNameIsRefusedNamingTheField() throws Exception {
        HttpResponse<String> r = post("/api/v1/events/definitions", "{\"key\":\"" + key() + "\",\"name\":\"dot\","
                + "\"messageName\":\"sangam.test.sl80\",\"correlationParameters\":[\"/bill.no\"]}");
        assertEquals(400, r.statusCode(), r.body());
        JsonNode p = JSON.readTree(r.body());
        assertEquals("VALIDATION_FAILED", p.path("code").asText(), r.body());
        assertEquals("correlationParameters", p.path("field").asText(), r.body());
        assertTrue(p.path("detail").asText().contains("/bill.no"), r.body());
    }
}
