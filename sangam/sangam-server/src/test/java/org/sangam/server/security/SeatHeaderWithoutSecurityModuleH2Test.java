// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.sangam.server.SangamServerApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Register E-24, the development start without the security module ({@code sangam.modules.security.enabled=false},
 * profile h2, decision 75): no {@code SecurityIdentityFilter} runs, so the seat directory is the default admin store
 * ({@link AdminStoreSeatHoldings} through {@link PrincipalAttributeFilter}). The seat a caller names is accepted only
 * while an assignment of that person to it is in force; without it the header is refused (403 SEAT_NOT_HELD), as with
 * the module on. The caller is the h2 profile's development identity (dev-admin, tenant dev).
 */
class SeatHeaderWithoutSecurityModuleH2Test {

    /** The development token of profile h2 (application-h2.yaml). */
    private static final String TOKEN = "sangam-dev-token-change-me";
    private static final String SEAT = "X-Sangam-Seat";
    private static final String ACTING = "X-Sangam-Acting-For-Seat";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Test
    void theAdminStoreIsTheSeatDirectory() throws Exception {
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(SangamServerApplication.class).profiles("h2").run(
                "--server.port=0", "--management.server.port=0",
                "--spring.datasource.url=jdbc:h2:mem:seat-off-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000;MODE=PostgreSQL",
                "--spring.datasource.username=sa", "--spring.datasource.password=", "--spring.datasource.driver-class-name=org.h2.Driver",
                "--operaton.bpm.database.schema-update=true", "--sangam.db.require-rcsi=false", "--sangam.persistence.dev-schema=false",
                "--sangam.engine.allow-multiple-engines-per-jvm=true", "--sangam.modules.security.enabled=false",
                // the ops module's secret administration needs the security module's field encryptor (OpsWiring)
                "--sangam.modules.ops.enabled=false")) {
            String api = "http://localhost:" + ctx.getEnvironment().getRequiredProperty("local.server.port") + "/api/v1";
            JsonNode me = body(send(api, "GET", "/me", Map.of(), null), 200);
            String person = me.path("principalId").asText();
            String unit = body(send(api, "POST", "/admin/org-units", Map.of(), map("code", "SO-UNIT", "name", "Seat check unit")), 201)
                    .path("id").asText();
            String own = seat(api, unit, "SO-OWN");
            String charge = seat(api, unit, "SO-CHARGE");

            // no assignment yet: neither seat is accepted
            assertSeatNotHeld(send(api, "GET", "/me", Map.of(SEAT, own), null));
            assertSeatNotHeld(send(api, "GET", "/me", Map.of(ACTING, charge), null));

            String today = LocalDate.now(ZoneOffset.UTC).toString();
            String posting = body(send(api, "POST", "/admin/assignments", Map.of(), map("seatId", own, "personId", person,
                    "kind", "SUBSTANTIVE", "validFrom", today)), 201).path("id").asText();
            assertEquals(own, body(send(api, "GET", "/me", Map.of(SEAT, own), null), 200).path("seatId").asText());
            assertSeatNotHeld(send(api, "GET", "/me", Map.of(SEAT, charge), null));
            assertSeatNotHeld(send(api, "GET", "/me", Map.of(ACTING, own), null));   // held substantively, not acted for

            body(send(api, "POST", "/admin/assignments", Map.of(), map("seatId", charge, "personId", person,
                    "kind", "ADDITIONAL_CHARGE", "actedForSeatId", charge, "validFrom", today,
                    "validTo", LocalDate.now(ZoneOffset.UTC).plusDays(3).toString())), 201);
            JsonNode acting = body(send(api, "GET", "/me", Map.of(SEAT, own, ACTING, charge), null), 200);
            assertEquals(charge, acting.path("actingForSeatId").asText(), acting.toString());
            assertEquals(charge, body(send(api, "GET", "/me", Map.of(SEAT, charge), null), 200).path("seatId").asText());

            // relieved: the ended assignment gives nothing, the additional charge stays
            HttpResponse<String> ended = send(api, "POST", "/admin/assignments/" + posting + "/end", Map.of(), map("reason", "Relieved"));
            assertTrue(ended.statusCode() / 100 == 2, ended.statusCode() + " " + ended.body());
            assertSeatNotHeld(send(api, "GET", "/me", Map.of(SEAT, own), null));
            assertEquals(200, send(api, "GET", "/me", Map.of(ACTING, charge), null).statusCode());
            assertEquals(200, send(api, "GET", "/me", Map.of(), null).statusCode());
        }
    }

    private static String seat(String api, String unit, String code) throws Exception {
        return body(send(api, "POST", "/admin/seats", Map.of(), map("code", code, "designation", "Officer", "name", code,
                "orgUnitId", unit)), 201).path("id").asText();
    }

    private static void assertSeatNotHeld(HttpResponse<String> r) throws Exception {
        assertEquals(403, r.statusCode(), r.request().headers().map() + ": " + r.body());
        assertEquals("SEAT_NOT_HELD", JSON.readTree(r.body()).path("code").asText(), r.body());
    }

    /** The JSON body of an answer with the expected status (201 stands for any 2xx of a create). */
    private static JsonNode body(HttpResponse<String> r, int status) throws Exception {
        String what = r.request().method() + " " + r.request().uri() + ": " + r.statusCode() + " " + r.body();
        if (status == 201) {
            assertTrue(r.statusCode() / 100 == 2, what);
        } else {
            assertEquals(status, r.statusCode(), what);
        }
        return JSON.readTree(r.body());
    }

    private static HttpResponse<String> send(String api, String method, String path, Map<String, String> headers, Object json)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(api + path)).header("Authorization", "Bearer " + TOKEN);
        headers.forEach(b::header);
        if (method.equals("GET")) {
            b.GET();
        } else {
            b.header("Content-Type", "application/json").header("Idempotency-Key", UUID.randomUUID().toString())
                    .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(json)));
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
