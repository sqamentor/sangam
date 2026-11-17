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
 * Decision 393, module batches of lane R8-393m (d393-PATTERN.md section 3), on the real server (h2, the whole
 * connector and filter chain). Every moved identifier is sent once with a '/' and once with a ':' through its
 * literal {@code by-<x>} route and must reach the application: JSON or problem+json, or a 2xx with no body, never the
 * connector's HTML 400 and never "No such endpoint". The old shape, the value as an encoded path segment, is refused
 * before the application, which is why the values moved.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
@DirtiesContext
class D393ModuleRoutesH2Test {

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
            if ((r.statusCode() == 401 || r.statusCode() == 403) && !answeredBy(r, c[1])) {
                wrong.add(where + ": refused before the controller: " + r.body());
            }
        }
        return wrong;
    }

    /**
     * A 401 or 403 is the application's own answer (a permission, group or device check in the controller or its
     * service) when its problem names the requested route as {@code instance}; a refusal before routing does not.
     */
    static boolean answeredBy(HttpResponse<String> r, String pathAndQuery) {
        if (r.body().isEmpty()) {
            return false;
        }
        String path = pathAndQuery.contains("?") ? pathAndQuery.substring(0, pathAndQuery.indexOf('?')) : pathAndQuery;
        return ("/api/v1" + path).equals(Json.tree(r.body()).path("instance").asText());
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
    void m1OpsValuesWithASlashOrAColonReachTheirRoutes() throws Exception {
        List<String[]> calls = new ArrayList<>();
        for (String v : VALUES) {
            calls.add(new String[]{"DELETE", "/ops/variables/by-id" + q("id", v, "reason", "d393 M1"), null});
            calls.add(new String[]{"POST", "/ops/sequences/by-name/advance" + q("name", v), "{\"to\":5,\"reason\":\"d393 M1\"}"});
            calls.add(new String[]{"POST", "/ops/topics/by-name/pause" + q("name", v), "{\"reason\":\"d393 M1\"}"});
            calls.add(new String[]{"POST", "/ops/topics/by-name/resume" + q("name", v), "{\"reason\":\"d393 M1\"}"});
            calls.add(new String[]{"PUT", "/ops/cleanup/ttl/by-key" + q("key", v), "{\"ttlDays\":30,\"reason\":\"d393 M1\"}"});
            calls.add(new String[]{"POST", "/ops/notification-failures/by-id/resend" + q("id", v), "{\"reason\":\"d393 M1\"}"});
        }
        assertEquals(List.of(), unreached(calls), "M1 routes that did not reach the application");
        oldShapeIsRefusedBeforeTheApplication("POST", "/ops/topics/" + enc("d393/a/b") + "/pause", "{\"reason\":\"d393 M1\"}");
    }

    @Test
    void m1OpsSecretNamesWithASlashRoundTripAndAColonIsRefusedByTheApplication() throws Exception {
        // SecretAdmin.checkRef allows '/' but not ':' ([A-Za-z0-9][A-Za-z0-9_./-]{0,200}): a ':' name reaches the
        // application and is refused there with 400 VALIDATION_FAILED; a '/' name round-trips.
        HttpResponse<String> colon = send("PUT", "/ops/secrets/by-name" + q("name", "d393:a:b"),
                "{\"name\":\"d393:a:b\",\"value\":\"s3cret\",\"reason\":\"d393 M1\"}");
        assertEquals(400, colon.statusCode(), colon.body());
        assertEquals("VALIDATION_FAILED", Json.tree(colon.body()).path("code").asText(), colon.body());
        for (String v : new String[]{"d393/a/b"}) {
            HttpResponse<String> put = send("PUT", "/ops/secrets/by-name" + q("name", v),
                    "{\"name\":\"" + v + "\",\"value\":\"s3cret\",\"reason\":\"d393 M1\"}");
            assertEquals(204, put.statusCode(), v + ": " + put.body());
            HttpResponse<String> list = send("GET", "/ops/secrets?size=500", null);
            assertEquals(200, list.statusCode(), list.body());
            boolean found = false;
            for (JsonNode row : Json.tree(list.body())) {
                found |= v.equals(row.path("name").asText()) || v.equals(row.path("ref").asText());
            }
            assertTrue(found, v + " is listed after the put: " + list.body());
            HttpResponse<String> del = send("DELETE", "/ops/secrets/by-name" + q("name", v, "reason", "d393 M1"), null);
            assertEquals(204, del.statusCode(), v + ": " + del.body());
        }
    }

    @Test
    void m2RegistryValuesWithASlashOrAColonReachTheirRoutes() throws Exception {
        List<String[]> calls = new ArrayList<>();
        for (String v : VALUES) {
            calls.add(new String[]{"GET", "/registry/assets/by-tag" + q("tag", v), null});
            calls.add(new String[]{"GET", "/registry/assets/classes/by-class-key" + q("classKey", v), null});
            calls.add(new String[]{"POST", "/registry/assets/d393-x1/meters/by-meter-type/readings" + q("meterType", v), "{}"});
            calls.add(new String[]{"GET", "/registry/assets/d393-x1/meters/by-meter-type/readings" + q("meterType", v), null});
            calls.add(new String[]{"GET", "/registry/custody/items/by-label" + q("label", v), null});
            calls.add(new String[]{"GET", "/registry/instruments/public/by-tenant/verify" + q("tenant", v, "code", "d393-code"), null});
            calls.add(new String[]{"POST", "/registry/instruments/d393-x1/transitions/by-name" + q("name", v), "{}"});
            calls.add(new String[]{"PUT", "/registry/number-series/by-series-key" + q("seriesKey", v), "{}"});
            calls.add(new String[]{"GET", "/registry/number-series/by-series-key" + q("seriesKey", v), null});
            calls.add(new String[]{"POST", "/registry/number-series/by-series-key/allocate" + q("seriesKey", v), "{}"});
            calls.add(new String[]{"GET", "/registry/number-series/by-series-key/allocations" + q("seriesKey", v), null});
            calls.add(new String[]{"POST", "/registry/number-series/by-series-key/close-period" + q("seriesKey", v), "{}"});
            calls.add(new String[]{"GET", "/registry/number-series/by-series-key/gaps" + q("seriesKey", v), null});
            calls.add(new String[]{"POST", "/registry/number-series/by-series-key/mark-used" + q("seriesKey", v), "{}"});
            calls.add(new String[]{"POST", "/registry/number-series/by-series-key/void" + q("seriesKey", v), "{}"});
            calls.add(new String[]{"POST", "/registry/objects/d393-x1/transitions/by-name" + q("name", v), "{}"});
            calls.add(new String[]{"GET", "/registry/registers/public/by-tenant/extracts/verify" + q("tenant", v, "code", "d393-code"), null});
            calls.add(new String[]{"PUT", "/registry/registers/by-register-key" + q("registerKey", v), "{}"});
            calls.add(new String[]{"GET", "/registry/registers/by-register-key" + q("registerKey", v), null});
            calls.add(new String[]{"GET", "/registry/registers/by-register-key/books" + q("registerKey", v), null});
            calls.add(new String[]{"POST", "/registry/registers/by-register-key/entries" + q("registerKey", v), "{}"});
            calls.add(new String[]{"GET", "/registry/types/by-type-key" + q("typeKey", v), null});
            calls.add(new String[]{"GET", "/registry/types/by-type-key/versions/1" + q("typeKey", v), null});
            calls.add(new String[]{"POST", "/registry/types/by-type-key/versions/1/retire" + q("typeKey", v), "{}"});
        }
        assertEquals(List.of(), unreached(calls), "M2 routes that did not reach the application");
        oldShapeIsRefusedBeforeTheApplication("GET", "/registry/number-series/" + enc("d393/a/b"), null);
    }

    @Test
    void m3LedgerAndBillingValuesWithASlashOrAColonReachTheirRoutes() throws Exception {
        List<String[]> calls = new ArrayList<>();
        for (String v : VALUES) {
            calls.add(new String[]{"GET", "/billing/by-ledger/accounts" + q("ledger", v), null});
            calls.add(new String[]{"POST", "/billing/by-ledger/collections" + q("ledger", v), "{}"});
            calls.add(new String[]{"GET", "/billing/by-ledger/consumers/by-consumer/arrears" + q("ledger", v, "consumer", v), null});
            calls.add(new String[]{"POST", "/billing/by-ledger/consumers/by-consumer/penalties" + q("ledger", v, "consumer", v), "{}"});
            calls.add(new String[]{"POST", "/billing/by-ledger/invoices" + q("ledger", v), "{}"});
            calls.add(new String[]{"POST", "/billing/by-ledger/refunds" + q("ledger", v), "{}"});
            calls.add(new String[]{"POST", "/billing/by-ledger/unverified-payments" + q("ledger", v), "{}"});
            calls.add(new String[]{"GET", "/billing/by-ledger/unverified-payments" + q("ledger", v), null});
            calls.add(new String[]{"GET", "/billing/by-ledger/unverified-payments/d393-x1" + q("ledger", v), null});
            calls.add(new String[]{"POST", "/billing/by-ledger/unverified-payments/d393-x1/resolution" + q("ledger", v), "{}"});
            calls.add(new String[]{"POST", "/billing/by-ledger/waivers" + q("ledger", v), "{}"});
            calls.add(new String[]{"GET", "/ledger/by-ledger/accounts" + q("ledger", v), null});
            calls.add(new String[]{"POST", "/ledger/by-ledger/freeze-orders" + q("ledger", v), "{}"});
            calls.add(new String[]{"GET", "/ledger/by-ledger/freeze-orders" + q("ledger", v), null});
            calls.add(new String[]{"DELETE", "/ledger/by-ledger/freeze-orders/by-id" + q("ledger", v, "id", v), null});
            calls.add(new String[]{"GET", "/ledger/by-ledger/holds" + q("ledger", v), null});
            calls.add(new String[]{"POST", "/ledger/by-ledger/operations" + q("ledger", v), "{}"});
            calls.add(new String[]{"POST", "/ledger/by-ledger/periods/close" + q("ledger", v), "{}"});
            calls.add(new String[]{"POST", "/ledger/by-ledger/reconcile" + q("ledger", v), "{}"});
        }
        assertEquals(List.of(), unreached(calls), "M3 routes that did not reach the application");
        oldShapeIsRefusedBeforeTheApplication("GET", "/ledger/" + enc("d393/a/b") + "/accounts", null);
    }

    @Test
    void m4ResourcesRostersAndQueuesValuesWithASlashOrAColonReachTheirRoutes() throws Exception {
        List<String[]> calls = new ArrayList<>();
        for (String v : VALUES) {
            calls.add(new String[]{"POST", "/queues/counters/by-counter-id/call" + q("counterId", v), "{}"});
            calls.add(new String[]{"POST", "/queues/counters/by-counter-id/call-next" + q("counterId", v), "{}"});
            calls.add(new String[]{"POST", "/queues/counters/by-counter-id/close" + q("counterId", v), "{}"});
            calls.add(new String[]{"POST", "/queues/counters/by-counter-id/complete" + q("counterId", v), "{}"});
            calls.add(new String[]{"POST", "/queues/counters/by-counter-id/open" + q("counterId", v), "{}"});
            calls.add(new String[]{"POST", "/queues/counters/by-counter-id/pause" + q("counterId", v), "{}"});
            calls.add(new String[]{"POST", "/queues/counters/by-counter-id/recall" + q("counterId", v), "{}"});
            calls.add(new String[]{"POST", "/queues/counters/by-counter-id/skip" + q("counterId", v), "{}"});
            calls.add(new String[]{"POST", "/queues/counters/by-counter-id/start-serving" + q("counterId", v), "{}"});
            calls.add(new String[]{"PUT", "/queues/service-points/by-service-point-id" + q("servicePointId", v), "{}"});
            calls.add(new String[]{"GET", "/queues/service-points/by-service-point-id" + q("servicePointId", v), null});
            calls.add(new String[]{"GET", "/queues/service-points/by-service-point-id/call-events" + q("servicePointId", v), null});
            calls.add(new String[]{"GET", "/queues/service-points/by-service-point-id/counters" + q("servicePointId", v), null});
            calls.add(new String[]{"PUT", "/queues/service-points/by-service-point-id/counters/by-counter-id" + q("servicePointId", v, "counterId", v), "{}"});
            calls.add(new String[]{"GET", "/queues/service-points/by-service-point-id/display" + q("servicePointId", v), null});
            calls.add(new String[]{"POST", "/queues/service-points/by-service-point-id/no-show-sweep" + q("servicePointId", v), "{}"});
            calls.add(new String[]{"GET", "/queues/service-points/by-service-point-id/sla-breaches" + q("servicePointId", v), null});
            calls.add(new String[]{"GET", "/queues/service-points/by-service-point-id/stats" + q("servicePointId", v), null});
            calls.add(new String[]{"POST", "/queues/service-points/by-service-point-id/status" + q("servicePointId", v), "{}"});
            calls.add(new String[]{"POST", "/queues/service-points/by-service-point-id/tokens" + q("servicePointId", v), "{}"});
            calls.add(new String[]{"GET", "/queues/service-points/by-service-point-id/tokens" + q("servicePointId", v), null});
            calls.add(new String[]{"GET", "/resources/bookings/by-ref/by-booking-ref" + q("bookingRef", v), null});
            calls.add(new String[]{"PUT", "/resources/calendars/by-calendar-id" + q("calendarId", v), "{}"});
            calls.add(new String[]{"GET", "/resources/calendars/by-calendar-id" + q("calendarId", v), null});
            calls.add(new String[]{"GET", "/resources/history/BOOKING/by-entity-id" + q("entityId", v), null});
            calls.add(new String[]{"GET", "/resources/items/by-resource-id" + q("resourceId", v), null});
            calls.add(new String[]{"PATCH", "/resources/items/by-resource-id" + q("resourceId", v), "{}"});
            calls.add(new String[]{"GET", "/resources/items/by-resource-id/availability" + q("resourceId", v), null});
            calls.add(new String[]{"GET", "/resources/items/by-resource-id/children" + q("resourceId", v), null});
            calls.add(new String[]{"POST", "/resources/items/by-resource-id/move" + q("resourceId", v), "{}"});
            calls.add(new String[]{"GET", "/resources/items/by-resource-id/slots" + q("resourceId", v), null});
            calls.add(new String[]{"POST", "/resources/items/by-resource-id/status" + q("resourceId", v), "{}"});
            calls.add(new String[]{"DELETE", "/resources/slot-templates/by-template-id" + q("templateId", v), null});
            calls.add(new String[]{"DELETE", "/resources/types/by-type-id" + q("typeId", v), null});
            calls.add(new String[]{"GET", "/rosters/people/by-person-id/duties" + q("personId", v), null});
            calls.add(new String[]{"DELETE", "/rosters/periods/d393-x1/demand/by-demand-id" + q("demandId", v), null});
            calls.add(new String[]{"PUT", "/rosters/periods/d393-x1/members/by-person-id" + q("personId", v), "{}"});
            calls.add(new String[]{"DELETE", "/rosters/periods/d393-x1/members/by-person-id" + q("personId", v), null});
            calls.add(new String[]{"DELETE", "/rosters/rotations/by-rotation-id" + q("rotationId", v), null});
            calls.add(new String[]{"DELETE", "/rosters/shifts/by-shift-id" + q("shiftId", v), null});
        }
        assertEquals(List.of(), unreached(calls), "M4 routes that did not reach the application");
        oldShapeIsRefusedBeforeTheApplication("GET", "/resources/items/" + enc("d393/a/b"), null);
    }

    @Test
    void m5GeoAndFieldValuesWithASlashOrAColonReachTheirRoutes() throws Exception {
        List<String[]> calls = new ArrayList<>();
        for (String v : VALUES) {
            calls.add(new String[]{"GET", "/field/devices/by-id/chain" + q("id", v), null});
            calls.add(new String[]{"POST", "/field/devices/by-id/policy" + q("id", v), "{}"});
            calls.add(new String[]{"POST", "/field/devices/by-id/status" + q("id", v), "{}"});
            calls.add(new String[]{"GET", "/field/evidence/by-id" + q("id", v), null});
            calls.add(new String[]{"GET", "/field/evidence/by-id/content" + q("id", v), null});
            calls.add(new String[]{"POST", "/field/evidence/by-id/reverify" + q("id", v), "{}"});
            calls.add(new String[]{"POST", "/field/schedules/by-id/run" + q("id", v), "{}"});
            calls.add(new String[]{"GET", "/field/sync/by-device-id/pull" + q("deviceId", v), null});
            calls.add(new String[]{"POST", "/field/sync/by-device-id/upload" + q("deviceId", v), "{}"});
            calls.add(new String[]{"GET", "/field/templates/by-key" + q("key", v), null});
            calls.add(new String[]{"POST", "/field/templates/by-key/1/retire" + q("key", v), "{}"});
            calls.add(new String[]{"GET", "/field/units/by-id" + q("id", v), null});
            calls.add(new String[]{"GET", "/field/units/by-id/eta" + q("id", v), null});
            calls.add(new String[]{"GET", "/field/units/by-id/offers" + q("id", v), null});
            calls.add(new String[]{"POST", "/field/units/by-id/pings" + q("id", v), "{}"});
            calls.add(new String[]{"POST", "/field/units/by-id/status" + q("id", v), "{}"});
            calls.add(new String[]{"GET", "/field/units/by-id/trips" + q("id", v), null});
            calls.add(new String[]{"POST", "/geo/external-layers/by-ref/import" + q("ref", v), "{}"});
            calls.add(new String[]{"POST", "/geo/external-layers/by-ref/query" + q("ref", v), "{}"});
            calls.add(new String[]{"GET", "/geo/features/by-id" + q("id", v), null});
            calls.add(new String[]{"DELETE", "/geo/features/by-id" + q("id", v), null});
            calls.add(new String[]{"GET", "/geo/features/by-id/lineage" + q("id", v), null});
            calls.add(new String[]{"GET", "/geo/fences/by-id" + q("id", v), null});
            calls.add(new String[]{"DELETE", "/geo/fences/by-id" + q("id", v), null});
            calls.add(new String[]{"POST", "/geo/fences/by-id/check" + q("id", v), "{}"});
            calls.add(new String[]{"GET", "/geo/layers/by-layer" + q("layer", v), null});
            calls.add(new String[]{"GET", "/geo/layers/by-layer/features" + q("layer", v), null});
            calls.add(new String[]{"POST", "/geo/layers/by-layer/features" + q("layer", v), "{}"});
            calls.add(new String[]{"POST", "/geo/layers/by-layer/import" + q("layer", v), "{}"});
            calls.add(new String[]{"PUT", "/geo/objects/by-type/by-id/geometry" + q("type", v, "id", v), "{}"});
            calls.add(new String[]{"GET", "/geo/objects/by-type/by-id/geometry" + q("type", v, "id", v), null});
        }
        assertEquals(List.of(), unreached(calls), "M5 routes that did not reach the application");
        oldShapeIsRefusedBeforeTheApplication("GET", "/geo/features/" + enc("d393/a/b"), null);
    }
}
