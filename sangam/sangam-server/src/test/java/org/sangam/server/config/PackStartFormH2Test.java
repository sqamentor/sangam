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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.integration.connector.Json;
import org.sangam.server.acceptance.AcceptanceIdp;
import org.sangam.server.bridge.PacksTenantDeployer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * UI-DEMO-1 (decision 400 (3)) on the real server (h2, packs deployed): a pack start form is served. The start catalogue
 * names each start form as sangam:&lt;key&gt;; GET /forms/by-key answers it with the pack's form document as its
 * layout (fields with id, type, required and label), for every catalogue entry that has one. Before the fix the form
 * adapter looked only for &lt;key&gt;.form and &lt;key&gt;.schema.json resources, so every pack start form answered 404 and the
 * start screen showed "Not available" instead of the form.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
@DirtiesContext
class PackStartFormH2Test {

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
    PacksTenantDeployer packs;

    /** The packs are deployed per tenant, as onboarding does (PacksTenantDeployer); the dev token's tenant is dev. */
    @BeforeEach
    void deployPacks() {
        packs.ensureDeployed("dev");
    }

    static String q(String... nameValue) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i + 1 < nameValue.length; i += 2) {
            s.append(s.isEmpty() ? "?" : "&").append(nameValue[i]).append('=')
                    .append(URLEncoder.encode(nameValue[i + 1], StandardCharsets.UTF_8));
        }
        return s.toString();
    }

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


    @Test
    void everyStartCatalogueFormIsServedWithItsFields() throws Exception {
        HttpResponse<String> cat = send("GET", "/start-catalogue", null);
        assertEquals(200, cat.statusCode(), cat.body());
        List<String> failed = new ArrayList<>();
        int forms = 0;
        for (JsonNode e : Json.MAPPER.readTree(cat.body())) {
            String key = e.path("formKey").asText("");
            if (!key.startsWith("sangam:")) {
                continue;
            }
            forms++;
            HttpResponse<String> r = send("GET", "/forms/by-key" + q("key", key), null);
            JsonNode fields = r.statusCode() == 200 ? Json.MAPPER.readTree(r.body()).path("layout").path("fields") : null;
            if (fields == null || !fields.isArray()) {
                failed.add(key + " -> " + r.statusCode());
            }
        }
        assertTrue(forms > 100, "the catalogue names pack start forms: " + forms);
        assertEquals(List.of(), failed, "start forms not served");
    }

    @Test
    void theExamCycleFormCarriesItsTypedFieldsAndAnUnknownKeyIsNotFound() throws Exception {
        HttpResponse<String> r = send("GET", "/forms/by-key" + q("key", "sangam:sec-edu-examination/notify"), null);
        assertEquals(200, r.statusCode(), r.body());
        JsonNode f = Json.MAPPER.readTree(r.body());
        boolean duration = false;
        for (JsonNode fd : f.path("layout").path("fields")) {
            duration |= "registrationWindow".equals(fd.path("id").asText()) && "duration".equals(fd.path("type").asText());
        }
        assertTrue(duration, r.body());
        assertTrue(f.path("versionHash").asText().length() == 64, r.body());
        assertEquals(404, send("GET", "/forms/by-key" + q("key", "sangam:no-such/form"), null).statusCode());
    }
}
