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
 * UI-DEMO-1 on the real server (h2, the whole connector and filter chain): every route whose value may carry a '/'
 * takes that value as a query parameter, so a form key (rti/request), business key (BK/2026/0001), case-file or
 * registry number, deployment resource name (packs/x/y.bpmn), config profile id (RJ.PWD/DIV-1), custom-field object
 * type (form:rti/request), work step name or fiscal period (2026/2027) reaches the application and is answered in
 * JSON or problem+json. Before the change the value was a path segment: the servlet connector refuses an encoded '/'
 * (%2F) in a path with an HTML 400 before any application code runs, so none of these values could be addressed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
@DirtiesContext
class SlashValueRoutesH2Test {

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
    void everyValueWithASlashOrAColonReachesItsRouteAndIsAnsweredInJson() throws Exception {
        String formKey = "rti/request";
        String bk = "BK/2026/0001";
        String profile = "RJ.PWD/DIV-1";
        String objectType = "form:" + formKey;
        String[][] calls = {
                {"GET", "/deployments/dep-ui-demo-1/resource" + q("name", "packs/x/y.bpmn"), null},
                {"GET", "/audit/timeline" + q("businessKey", bk), null},
                {"GET", "/audit/evidence-pack" + q("businessKey", bk), null},
                {"GET", "/forms/by-key" + q("key", formKey), null},
                {"POST", "/forms/by-key/validate" + q("key", formKey), "{}"},
                {"GET", "/case-files/by-number" + q("number", "CF/2026/000001"), null},
                {"GET", "/registry/objects/by-number" + q("number", "REG/DIV-1/2026-27/000001X"), null},
                {"GET", "/config/profiles/versions" + q("id", profile), null},
                {"GET", "/config/profiles/version" + q("id", profile, "version", "1"), null},
                {"PUT", "/config/profiles/version" + q("id", profile, "version", "1"), "{}"},
                {"POST", "/config/profiles/version/submit" + q("id", profile, "version", "1"), "{}"},
                {"POST", "/config/profiles/version/approve" + q("id", profile, "version", "1"), "{}"},
                {"POST", "/config/profiles/version/reject" + q("id", profile, "version", "1"), "{}"},
                {"POST", "/config/profiles/version/activate" + q("id", profile, "version", "1"), "{}"},
                {"GET", "/config/profiles/history" + q("id", profile), null},
                {"GET", "/custom-fields/versions" + q("objectType", objectType, "key", "x_code"), null},
                {"POST", "/custom-fields/retire" + q("objectType", objectType, "key", "x_code"), "{}"},
                {"GET", "/work/w-ui-demo-1/step" + q("stepName", "a/b:c ~!?", "workerId", "w1", "lockVersion", "1",
                        "attemptId", "1"), null},
                {"PUT", "/work/w-ui-demo-1/step" + q("stepName", "a/b:c ~!?"), "{}"},
                {"POST", "/ledger/L-UI-DEMO-1/periods/close" + q("period", "2026/2027"), "{}"},
        };
        List<String> wrong = new ArrayList<>();
        for (String[] c : calls) {
            HttpResponse<String> r = send(c[0], c[1], c[2]);
            String type = r.headers().firstValue("content-type").orElse("");
            String where = c[0] + " " + c[1] + " -> " + r.statusCode() + " " + type;
            if (!type.startsWith("application/json") && !type.startsWith("application/problem+json")) {
                wrong.add(where + ": not JSON: " + r.body());
                continue;
            }
            if (r.statusCode() == 404 || r.statusCode() == 405) {
                JsonNode p = Json.tree(r.body());
                if ("No such endpoint".equals(p.path("detail").asText()) || r.statusCode() == 405) {
                    wrong.add(where + ": no such route: " + r.body());
                }
            }
            if (r.statusCode() == 401 || r.statusCode() == 403) {
                wrong.add(where + ": refused before the controller: " + r.body());
            }
        }
        assertEquals(List.of(), wrong, "routes that did not reach the application with a '/' value");
    }

    @Test
    void aBusinessKeyWithSlashesReadsItsTimeline() throws Exception {
        HttpResponse<String> r = send("GET", "/audit/timeline" + q("businessKey", "BK/2026/0001"), null);
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.headers().firstValue("content-type").orElse("").startsWith("application/json"), r.body());
        assertTrue(Json.tree(r.body()).isArray(), "the timeline entries of that business key: " + r.body());
    }
}
