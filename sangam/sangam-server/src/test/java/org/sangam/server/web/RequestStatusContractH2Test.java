// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Transactions;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.security.SangamSecurity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * approve-401 and T35, over real HTTP (h2 profile, embedded Tomcat: MockMvc has no container error dispatch).
 *
 * <ul>
 *   <li>A server controller's {@code ApiProblem} reaches the caller as that problem. Before, the PlugAndPlayIT maker's
 *       own go-live approval (422 SEGREGATION_OF_DUTIES) left {@code OnboardingController} unhandled and the caller saw
 *       401 "Full authentication is required to access this resource" with instance {@code /error}.</li>
 *   <li>A request that passed the security chain keeps its real status when the container renders it on the error
 *       dispatch, for the dev token and for API tokens ({@code sgm_}) as for JWTs: Sangam's own token filters store
 *       the authentication for the whole request. Before, that dispatch was anonymous and answered 401.</li>
 *   <li>An event definition or channel may omit its optional numbers and flags: the DTOs give them documented
 *       defaults (SL-81), while Jackson 3's strict default stays for every other DTO and a missing required number
 *       is a 400 naming the field. Before, T35's event definition without the server-assigned {@code version} and
 *       {@code revision} was 400 MALFORMED_BODY.</li>
 *   <li>A start that is unique on the business key needs only {@code businessKeyPointer}. Before, the event bridge
 *       refused T35's definition: 422 "startUniqueReference 'billId' is not a correlation parameter".</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
@DirtiesContext
class RequestStatusContractH2Test {

    static final String DEV = "Bearer sangam-dev-token-change-me";
    static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    int port;

    @Autowired
    SangamSecurity security;

    @Autowired
    @Qualifier("sangamProductTransactions")
    Transactions tx;

    HttpResponse<String> send(String method, String path, String authorization, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(60)).header("Accept", "application/json");
        if (authorization != null) {
            b.header("Authorization", authorization);
        }
        if (json != null) {
            b.header("Content-Type", "application/json").header("Idempotency-Key", UUID.randomUUID().toString())
                    .method(method, HttpRequest.BodyPublishers.ofString(json));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static String unknownPath() {
        return "/api/v1/no-such-endpoint-" + Long.toString(System.nanoTime(), 36);
    }

    @Test
    void serverControllerProblemReachesTheCallerAsTheProblem() throws Exception {
        String path = "/api/v1/onboarding/runs/" + UUID.randomUUID() + "/approve";
        HttpResponse<String> r = send("POST", path, DEV, "{\"presentedHash\":\"stale\"}");
        assertEquals(404, r.statusCode(), r.body());
        assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("application/problem+json"),
                r.headers().map().toString());
        JsonNode p = JSON.readTree(r.body());
        assertEquals("NOT_FOUND", p.path("code").asText(), r.body());
        assertEquals(path, p.path("instance").asText(), "answered on the request itself, not on /error");
    }

    @Test
    void errorDispatchKeepsTheRealStatusForTheDevToken() throws Exception {
        HttpResponse<String> r = send("GET", unknownPath(), DEV, null);
        assertEquals(404, r.statusCode(), r.body());
    }

    @Test
    void errorDispatchKeepsTheRealStatusForAnApiToken() throws Exception {
        // the dev person exists once the dev token was used (just-in-time provisioning of the dev tenant)
        assertEquals(200, send("GET", "/api/v1/tasks", DEV, null).statusCode());
        SecurityIdentity owner = new SecurityIdentity("dev", "dev-admin", "dev-admin", Set.of(), Set.of(), Set.of(),
                Set.of(), "DEV_TOKEN", null, null, false, Map.of());
        String token = tx.inTransaction(c -> security.tokens().issue(c, owner, "dev-admin", "approve-401 regression",
                Set.of(), Instant.now().plusSeconds(3600))).plaintext();
        HttpResponse<String> known = send("GET", "/api/v1/tasks", "Bearer " + token, null);
        assertNotEquals(401, known.statusCode(), "the API token authenticates: " + known.body());
        HttpResponse<String> r = send("GET", unknownPath(), "Bearer " + token, null);
        assertEquals(404, r.statusCode(), r.body());
        // a bad API token is still refused before anything runs
        assertEquals(401, send("GET", unknownPath(), "Bearer sgm_nosuchtoken.secret", null).statusCode());
    }

    @Test
    void withoutAuthenticationItStaysUnauthenticated() throws Exception {
        assertEquals(401, send("GET", unknownPath(), null, null).statusCode());
        assertEquals(401, send("GET", "/api/v1/tasks", null, null).statusCode());
    }

    @Test
    void anOptionalNumberOrFlagMayBeOmitted() throws Exception {
        String key = "contract-" + Long.toString(System.nanoTime(), 36);
        HttpResponse<String> r = send("POST", "/api/v1/events/definitions", DEV,
                "{\"key\":\"" + key + "\",\"name\":\"request contract\",\"messageName\":\"sangam.test.contract\"}");
        assertEquals(201, r.statusCode(), r.body());
        JsonNode d = JSON.readTree(r.body());
        assertEquals(1, d.path("version").asInt(), "server-assigned version: " + r.body());
        assertEquals(1, d.path("revision").asLong(), "server-assigned revision: " + r.body());
        assertEquals(false, d.path("uniqueStart").asBoolean(true), r.body());
    }

    @Test
    void aStartUniqueOnTheBusinessKeyNeedsNoSeparateCorrelationParameter() throws Exception {
        // T35's definition: uniqueStart with only a businessKeyPointer (the REST contract); the ports registry needs
        // the unique-start reference among the correlation parameters, which the bridge now declares
        String key = "unique-" + Long.toString(System.nanoTime(), 36);
        HttpResponse<String> r = send("POST", "/api/v1/events/definitions", DEV, "{\"key\":\"" + key
                + "\",\"name\":\"unique start\",\"startProcessKey\":\"contract-start\",\"uniqueStart\":true,"
                + "\"businessKeyPointer\":\"/billId\"}");
        assertEquals(201, r.statusCode(), r.body());
        JsonNode d = JSON.readTree(r.body());
        assertEquals(true, d.path("uniqueStart").asBoolean(false), r.body());
        assertEquals("/billId", d.path("businessKeyPointer").asText(), r.body());
    }

    @Test
    void aMissingRequiredNumberIsRefusedNamingTheField() throws Exception {
        // SL-81: Jackson 3's strict default stays (no global leniency); the 400 says which field
        HttpResponse<String> r = send("PUT", "/api/v1/ops/cleanup/ttl/no-such-key", DEV, "{\"reason\":\"SL-81 contract\"}");
        assertEquals(400, r.statusCode(), r.body());
        JsonNode p = JSON.readTree(r.body());
        assertEquals("VALIDATION_FAILED", p.path("code").asText(), r.body());
        assertEquals("ttlDays", p.path("field").asText(), r.body());
        assertTrue(p.path("detail").asText().startsWith("ttlDays "), r.body());
    }

    @Test
    void aChannelMayOmitItsOptionalFlagAndRevisionAndGetsTheDocumentedDefaults() throws Exception {
        String n = Long.toString(System.nanoTime(), 36);
        assertEquals(201, send("POST", "/api/v1/events/definitions", DEV, "{\"key\":\"chdef-" + n
                + "\",\"messageName\":\"sangam.test.channel\"}").statusCode());
        HttpResponse<String> r = send("POST", "/api/v1/events/channels", DEV, "{\"key\":\"chdef-ch-" + n
                + "\",\"direction\":\"INBOUND\",\"transport\":\"KAFKA\",\"eventDefinitionKeys\":[\"chdef-" + n + "\"]}");
        assertEquals(201, r.statusCode(), r.body());
        JsonNode c = JSON.readTree(r.body());
        assertEquals(false, c.path("active").asBoolean(true), "documented default: inactive until activated " + r.body());
        assertTrue(c.path("revision").asLong() >= 1, "server-assigned revision: " + r.body());
    }

    @Test
    void aBodyThatIsNotJsonIsStillRefused() throws Exception {
        HttpResponse<String> r = send("POST", "/api/v1/events/definitions", DEV, "{\"key\":");
        assertEquals(400, r.statusCode(), r.body());
        assertEquals("MALFORMED_BODY", JSON.readTree(r.body()).path("code").asText(), r.body());
    }
}
