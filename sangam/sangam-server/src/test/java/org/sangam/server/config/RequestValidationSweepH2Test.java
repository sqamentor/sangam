// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
 * UI-DEMO 2026-11-16 sweep, on the real server (h2, the whole filter chain and ProblemHandler): a request the caller
 * got wrong is a 400 VALIDATION_FAILED problem that names what is wrong, never a 404 for a lookup of "null" or a 500.
 * <ul>
 * <li>S5: GET /rosters/swaps lists the swaps of one roster period. periodId was optional, so a call without it looked
 * up the period "null" and answered 404 "roster period not found: null"; it is now a required query parameter.</li>
 * <li>S8: a typed variable whose value is not of its declared type ({@code Date} in the engine's
 * {@code 2026-11-23T03:36:12.000+0000} text) reached {@code Instant.parse} unguarded and POST /process-instances
 * answered 500 DateTimeParseException.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
@DirtiesContext
class RequestValidationSweepH2Test {

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

    HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + env.getRequiredProperty("sangam.security.dev-token.token"))
                .header("Accept", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (body != null) {
            b.header("Content-Type", "application/json").header("Idempotency-Key", UUID.randomUUID().toString());
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static void validationProblem(HttpResponse<String> r, String names) throws Exception {
        assertEquals(400, r.statusCode(), r.body());
        assertTrue(r.headers().firstValue("content-type").orElse("").startsWith("application/problem+json"), r.body());
        JsonNode p = Json.tree(r.body());
        assertEquals("VALIDATION_FAILED", p.path("code").asText(), r.body());
        assertTrue(r.body().contains(names), "the problem names " + names + ": " + r.body());
    }

    @Test
    void s5RosterSwapsWithoutAPeriodIsAValidationProblem() throws Exception {
        validationProblem(send("GET", "/rosters/swaps", null), "periodId");
        HttpResponse<String> unknown = send("GET", "/rosters/swaps?periodId=no-such-period", null);
        assertEquals(404, unknown.statusCode(), "an unknown period is still not found: " + unknown.body());
    }

    @Test
    void s8ATypedVariableOfTheWrongFormIsAValidationProblemNotA500() throws Exception {
        validationProblem(send("POST", "/process-instances",
                "{\"key\":\"any\",\"variables\":{\"due\":{\"type\":\"Date\",\"value\":\"2026-11-23T03:36:12.000+0000\"}}}"),
                "Date");
        validationProblem(send("POST", "/process-instances",
                "{\"key\":\"any\",\"variables\":{\"ok\":{\"type\":\"Boolean\",\"value\":\"yes\"}}}"), "Boolean");
    }
}
