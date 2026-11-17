// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
 * Decision 393, module batches M6 to M9 (lane R8-393-modules-b), on the real server (h2, the whole connector and filter
 * chain). Every identifier of these families that may carry '/' or ':' is a required query parameter on a literal
 * {@code by-<x>} route, so a value with both characters reaches the application and is answered in JSON or
 * problem+json; where the route can echo the value, the test reads it back. Before the change the value was a path
 * segment: the servlet connector refuses an encoded '/' (%2F) in a path with an HTML 400 before any application code
 * runs, so none of these values could be addressed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
@DirtiesContext
class ModuleQueryIdRoutesBH2Test {

    /** One value with both characters the decision names. */
    static final String V = "a/b:c";

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

    /** Each call must reach its route: JSON or problem+json, never an unknown route or a refusal before the controller. */
    void everyCallReachesItsRoute(String[][] calls) throws Exception {
        List<String> wrong = new ArrayList<>();
        for (String[] c : calls) {
            HttpResponse<String> r = send(c[0], c[1], c[2]);
            String type = r.headers().firstValue("content-type").orElse("");
            String where = c[0] + " " + c[1] + " -> " + r.statusCode() + " " + type;
            if (r.statusCode() == 204 && r.body().isEmpty()) {
                continue; // the controller answered No Content: only a handler method produces that
            }
            if (!type.startsWith("application/json") && !type.startsWith("application/problem+json")) {
                wrong.add(where + ": not JSON: " + r.body());
                continue;
            }
            JsonNode p = r.body().isBlank() ? Json.tree("{}") : Json.tree(r.body());
            if (r.statusCode() == 405 || (r.statusCode() == 404 && "No such endpoint".equals(p.path("detail").asText()))) {
                wrong.add(where + ": no such route: " + r.body());
            }
            // a public hook refuses an unsigned delivery itself (401 WEBHOOK_SIGNATURE_INVALID): that is the controller
            boolean controllerRefusal = "WEBHOOK_SIGNATURE_INVALID".equals(p.path("code").asText());
            if ((r.statusCode() == 401 || r.statusCode() == 403) && !controllerRefusal) {
                wrong.add(where + ": refused before the controller: " + r.body());
            }
        }
        assertEquals(List.of(), wrong, "routes that did not reach the application with a value carrying '/' and ':'");
    }

    @Test
    void m6KnowledgeBaseAndAgentKeysRoundTrip() throws Exception {
        String kb = "kb/" + UUID.randomUUID().toString().substring(0, 8) + ":rti";
        HttpResponse<String> created = send("POST", "/ai/knowledge-bases/by-kb/versions" + q("kb", kb), null);
        assertEquals(200, created.statusCode(), created.body());
        int version = Json.tree(created.body()).path("version").asInt();
        HttpResponse<String> activated = send("POST", "/ai/knowledge-bases/by-kb/versions/" + version + "/activate"
                + q("kb", kb), null);
        assertEquals(204, activated.statusCode(), "the version created under the same key: " + activated.body());
        everyCallReachesItsRoute(new String[][]{
                {"POST", "/ai/knowledge-bases/by-kb/versions/" + version + "/documents" + q("kb", kb),
                        "{\"documentId\":\"d1\",\"docVersion\":\"1\",\"text\":\"hello\"}"},
                {"POST", "/ai/knowledge-bases/by-kb/search" + q("kb", kb), "{\"query\":\"hello\"}"},
                {"POST", "/ai/knowledge-bases/by-key/retire" + q("key", V), "{\"reason\":\"decision 393 test\"}"},
                {"GET", "/ai/a2a/by-agent/card" + q("agent", V), null},
                {"POST", "/ai/a2a/by-agent" + q("agent", V), "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"GetTask\"}"},
        });
    }

    @Test
    void m7ComplianceAndPortalKeysReachTheirRoutes() throws Exception {
        everyCallReachesItsRoute(new String[][]{
                {"GET", "/compliance/notices/by-key" + q("key", V), null},
                {"POST", "/compliance/notices/by-key/publish" + q("key", V), "{\"purposes\":[\"P1\"]}"},
                {"PUT", "/compliance/purposes/by-code" + q("code", V), "{}"},
                {"POST", "/compliance/consent-managers/by-cm/artefacts" + q("cm", V), "{}"},
                {"POST", "/compliance/contacts/by-role" + q("role", V), "{}"},
                {"GET", "/portal/services/by-key/form" + q("key", V), null},
                {"GET", "/portal/services/by-key/notice" + q("key", V), null},
        });
    }

    @Test
    void m8ModelFilesEventKeysQueriesMocksAndPacksRoundTrip() throws Exception {
        HttpResponse<String> lease = send("POST", "/models/by-file/lease/acquire" + q("file", V), "{}");
        assertEquals(200, lease.statusCode(), lease.body());
        assertEquals(V, Json.tree(lease.body()).path("fileId").asText(), "the lease of that very file: " + lease.body());
        String channel = "ns:d393-" + UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> made = send("POST", "/events/channels", "{\"key\":\"" + channel
                + "\",\"name\":\"d393\",\"direction\":\"INBOUND\",\"transport\":\"WEBHOOK\",\"active\":true,\"config\":{\"secretRef\":\"env:D393_HOOK\"}}");
        assertEquals(201, made.statusCode(), made.body());
        HttpResponse<String> read = send("GET", "/events/channels/by-key" + q("key", channel), null);
        assertEquals(200, read.statusCode(), read.body());
        assertEquals(channel, Json.tree(read.body()).path("key").asText(), "the channel with ':' in its key: " + read.body());
        everyCallReachesItsRoute(new String[][]{
                {"GET", "/models/by-file/lease" + q("file", V), null},
                {"POST", "/models/by-file/lease/release" + q("file", V), "{\"fence\":0}"},
                {"POST", "/models/by-file/lease/request-takeover" + q("file", V), "{}"},
                {"PUT", "/events/channels/by-key" + q("key", channel), "{\"key\":\"" + channel
                        + "\",\"name\":\"d393\",\"direction\":\"INBOUND\",\"transport\":\"WEBHOOK\",\"active\":true,\"config\":{\"secretRef\":\"env:D393_HOOK\"}}"},
                {"POST", "/events/channels/by-key/retire" + q("key", V), "{\"reason\":\"decision 393 test\"}"},
                {"GET", "/events/definitions/by-key" + q("key", V), null},
                {"POST", "/events/definitions/by-key/retire" + q("key", V), "{\"reason\":\"decision 393 test\"}"},
                {"POST", "/events/by-channel" + q("channel", V), "{\"specversion\":\"1.0\",\"id\":\"e1\",\"source\":\"t\","
                        + "\"type\":\"t\"}"},
                {"POST", "/hooks/eventgrid/dev/by-channel" + q("channel", V), "[]"},
                {"GET", "/queries/by-name" + q("name", "data:" + V), null},
                {"PATCH", "/service-mocks/by-id" + q("id", V), "{\"enabled\":true}"},
                {"POST", "/packs/by-id/uninstall" + q("id", V), "{\"tenant\":\"dev\",\"reason\":\"decision 393 test\"}"},
                {"POST", "/packs/by-id/rollback" + q("id", V), "{\"tenant\":\"dev\",\"reason\":\"decision 393 test\"}"},
                {"GET", "/packs/by-id/document-slots" + q("id", V), null},
        });
    }

    @Test
    void m9ConfigIdsAndHookChannelsReachTheirRoutes() throws Exception {
        everyCallReachesItsRoute(new String[][]{
                {"GET", "/config/master/by-scheme/by-segment" + q("scheme", V, "segment", V), null},
                {"POST", "/config/document-types/by-id/check" + q("id", V), "{\"document\":{}}"},
                {"POST", "/config/crosswalks/by-id/map" + q("id", V), "{\"code\":\"1\"}"},
                {"POST", "/hooks/fhir/by-channel" + q("channel", V), "{\"resourceType\":\"Bundle\"}"},
                {"POST", "/hooks/sms-dlt/dev/by-connector-id/delivery-reports" + q("connectorId", V),
                        "{\"clientRef\":\"x\",\"status\":\"DELIVERED\"}"},
        });
    }
}
