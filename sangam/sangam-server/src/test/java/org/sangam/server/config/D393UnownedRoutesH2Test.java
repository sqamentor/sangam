// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * Decision 393, batch C5 of lane R8-393mc (the unowned rows) (d393-PATTERN.md section 3), on the real server (h2, the whole
 * connector and filter chain). Every moved identifier is sent once with a '/' and once with a ':' through its
 * literal {@code by-<x>} route and must reach the application: JSON or problem+json, or a 2xx with no body, never the
 * connector's HTML 400 and never "No such endpoint". The old shape, the value as an encoded path segment, is refused
 * before the application, which is why the values moved.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
@DirtiesContext
class D393UnownedRoutesH2Test {

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

    static String q(String... nameValue) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i + 1 < nameValue.length; i += 2) {
            s.append(s.isEmpty() ? "?" : "&").append(nameValue[i]).append('=')
                    .append(URLEncoder.encode(nameValue[i + 1], StandardCharsets.UTF_8));
        }
        return s.toString();
    }

    static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + env.getRequiredProperty("sangam.security.dev-token.token"))
                .header("Accept", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        if (!"GET".equals(method)) {
            b.header("Idempotency-Key", UUID.randomUUID().toString());
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Sends every call and lists those that did not reach the application. */
    List<String> unreached(List<String[]> calls) throws Exception {
        List<String> wrong = new ArrayList<>();
        for (String[] c : calls) {
            HttpResponse<String> r = send(c[0], c[1], c[2]);
            String type = r.headers().firstValue("content-type").orElse("");
            String where = c[0] + " " + c[1] + " -> " + r.statusCode() + " " + type;
            boolean empty2xx = r.statusCode() / 100 == 2 && r.body().isEmpty();
            if (!empty2xx && !type.startsWith("application/json") && !type.startsWith("application/problem+json")) {
                wrong.add(where + ": not JSON: " + r.body());
                continue;
            }
            if (r.statusCode() == 405 || r.statusCode() == 404 && !r.body().isEmpty()
                    && "No such endpoint".equals(Json.tree(r.body()).path("detail").asText())) {
                wrong.add(where + ": no such route: " + r.body());
            }
            if (r.statusCode() == 401 || r.statusCode() == 403) {
                wrong.add(where + ": refused before the controller: " + r.body());
            }
        }
        return wrong;
    }

    /** The old shape: the value as an encoded path segment never reaches the application. */
    void oldShapeIsRefusedBeforeTheApplication(String method, String path, String body) throws Exception {
        HttpResponse<String> r = send(method, path, body);
        String type = r.headers().firstValue("content-type").orElse("");
        assertFalse(r.statusCode() / 100 == 2, method + " " + path + " -> " + r.statusCode());
        assertFalse(type.startsWith("application/json") && !r.body().contains("No such endpoint")
                && r.statusCode() != 404, method + " " + path + " reached a controller: " + r.statusCode() + " " + r.body());
    }

    @Test
    void c5ValuesWithASlashOrAColonReachTheirRoutes() throws Exception {
        List<String[]> calls = new ArrayList<>();
        for (String v : VALUES) {
            String pi = UUID.randomUUID().toString();
            calls.add(new String[]{"GET", "/process-instances/" + pi + "/ad-hoc/by-ad-hoc-activity-id" + q("adHocActivityId", v), null});
            calls.add(new String[]{"POST", "/process-instances/" + pi + "/ad-hoc/by-ad-hoc-activity-id/start" + q("adHocActivityId", v), "{\"activityId\":\"a\"}"});
            calls.add(new String[]{"POST", "/process-instances/" + pi + "/ad-hoc/by-ad-hoc-activity-id/complete" + q("adHocActivityId", v, "reason", "d393 C5"), "{}"});
            calls.add(new String[]{"GET", "/case-instances/by-id" + q("id", v), null});
            calls.add(new String[]{"GET", "/case-instances/by-id/variables" + q("id", v), null});
            calls.add(new String[]{"POST", "/case-instances/by-id/commands" + q("id", v), "{\"action\":\"complete\",\"reason\":\"d393 C5\"}"});
            calls.add(new String[]{"POST", "/case-instances/by-id/executions/by-execution-id/commands" + q("id", v, "executionId", v), "{\"action\":\"complete\",\"reason\":\"d393 C5\"}"});
            calls.add(new String[]{"GET", "/case-instances/by-id/comments" + q("id", v), null});
            calls.add(new String[]{"POST", "/case-instances/by-id/comments" + q("id", v), "{\"text\":\"d393\"}"});
            calls.add(new String[]{"GET", "/case-instances/by-id/participants" + q("id", v), null});
            calls.add(new String[]{"POST", "/case-instances/by-id/reactivation" + q("id", v), "{\"reason\":\"d393 C5\"}"});
            calls.add(new String[]{"POST", "/decisions/by-key/evaluate" + q("key", v), "{\"variables\":{}}"});
            calls.add(new String[]{"GET", "/documents/by-id" + q("id", v), null});
            calls.add(new String[]{"DELETE", "/documents/by-id" + q("id", v, "reason", "d393 C5"), null});
            calls.add(new String[]{"GET", "/documents/by-id/content" + q("id", v), null});
            calls.add(new String[]{"GET", "/documents/by-id/versions" + q("id", v), null});
            calls.add(new String[]{"POST", "/documents/by-id/legal-hold" + q("id", v), "{\"hold\":true,\"reason\":\"d393 C5\"}"});
            calls.add(new String[]{"DELETE", "/links/by-id" + q("id", v, "reason", "d393 C5"), null});
            calls.add(new String[]{"GET", "/compliance/process-roots/by-root-id/legal-holds" + q("rootId", v), null});
            calls.add(new String[]{"POST", "/compliance/process-roots/by-root-id/legal-holds" + q("rootId", v), "{\"kind\":\"RTI\",\"reason\":\"d393 C5\"}"});
            calls.add(new String[]{"POST", "/compliance/process-roots/by-root-id/legal-holds/by-hold-id/release" + q("rootId", v, "holdId", v), "{\"reason\":\"d393 C5\"}"});
            calls.add(new String[]{"POST", "/catalogue/by-id/by-version/install" + q("id", v, "version", v), "{}"});
            calls.add(new String[]{"POST", "/catalogue/by-id/by-version/install-templates" + q("id", v, "version", v), "{}"});
            calls.add(new String[]{"GET", "/onboarding/tenants/by-tenant/bundle" + q("tenant", v), null});
            calls.add(new String[]{"GET", "/onboarding/promotions/by-tenant/bundle" + q("tenant", v), null});
            calls.add(new String[]{"POST", "/programme-periods/by-id/state" + q("id", v), "{\"state\":\"OPEN\",\"reason\":\"d393 C5\"}"});
        }
        assertEquals(List.of(), unreached(calls), "C5 routes that did not reach the application");
        oldShapeIsRefusedBeforeTheApplication("GET", "/case-instances/" + enc("d393/a/b"), null);
        oldShapeIsRefusedBeforeTheApplication("POST", "/programme-periods/" + enc("MONTHLY/2026"), "{\"state\":\"OPEN\"}");
    }

    @Test
    void c5OldPathRoutesAreGone() throws Exception {
        for (String[] c : new String[][]{
                {"GET", "/case-instances/c5-plain", null},
                {"POST", "/decisions/c5-plain/evaluate", "{\"variables\":{}}"},
                {"GET", "/documents/c5-plain/versions", null},
                {"GET", "/onboarding/tenants/c5-plain/bundle", null},
                {"POST", "/programme-periods/c5-plain/state", "{\"state\":\"OPEN\"}"}}) {
            HttpResponse<String> r = send(c[0], c[1], c[2]);
            assertTrue(r.statusCode() == 404 || r.statusCode() == 405, c[0] + " " + c[1] + " still served: " + r.statusCode() + " " + r.body());
        }
    }
}
