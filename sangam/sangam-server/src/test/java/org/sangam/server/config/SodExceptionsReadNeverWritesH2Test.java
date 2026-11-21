// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.sangam.rest.adapter.jdbc.JdbcIdentityAdminAdapter;
import org.sangam.rest.port.IdentityAdminPort;
import org.sangam.server.SangamServerApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Register E-2 (CodeQL alert 601, CSRF report bucket 1): {@code GET /admin/sod-exceptions} served by
 * {@link JdbcIdentityAdminAdapter} never writes. Before the fix the GET ran
 * {@code UPDATE SEC_SOD_EXCEPTION SET status='EXPIRED'} for every PENDING or APPROVED exception past its
 * {@code expires_at}; now expiry is the status the read answers and the stored row is left as it is.
 *
 * <p>The adapter is the server's {@link IdentityAdminPort} only on a start without the security module
 * ({@code sangam.modules.security.enabled=false}, profile h2, decision 75); with the module on the port is the
 * security bridge, which is why no server test reached this code before. The test asserts which class serves the
 * port, so it cannot pass against the bridge by accident.
 */
class SodExceptionsReadNeverWritesH2Test {

    /** The development token of profile h2 (application-h2.yaml). */
    private static final String TOKEN = "sangam-dev-token-change-me";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Test
    void listingExpiredExceptionsAnswersExpiredAndLeavesEveryStoredRowAsItWas() throws Exception {
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(SangamServerApplication.class).profiles("h2").run(
                "--server.port=0", "--management.server.port=0",
                "--spring.datasource.url=jdbc:h2:mem:sod-read-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000;MODE=PostgreSQL",
                "--spring.datasource.username=sa", "--spring.datasource.password=", "--spring.datasource.driver-class-name=org.h2.Driver",
                "--operaton.bpm.database.schema-update=true", "--sangam.db.require-rcsi=false", "--sangam.persistence.dev-schema=false",
                "--sangam.engine.allow-multiple-engines-per-jvm=true", "--sangam.modules.security.enabled=false",
                // the ops module's secret administration needs the security module's field encryptor (OpsWiring)
                "--sangam.modules.ops.enabled=false")) {
            assertInstanceOf(JdbcIdentityAdminAdapter.class, ctx.getBean(IdentityAdminPort.class),
                    "without the security module the JDBC adapter serves /admin/sod-exceptions");
            DataSource ds = ctx.getBean(DataSource.class);
            String api = "http://localhost:" + ctx.getEnvironment().getRequiredProperty("local.server.port") + "/api/v1";

            // typical: one pending exception that runs out, one that is still in force
            String lapsed = create(api, "CASE-E2-1/approve", "person-a");
            String inForce = create(api, "CASE-E2-2/approve", "person-b");
            // difficult: an APPROVED exception that runs out (the old UPDATE covered PENDING and APPROVED), and a
            // REJECTED one past its date, which never becomes EXPIRED
            String approvedLapsed = create(api, "CASE-E2-3/pay", "person-c");
            String rejectedLapsed = create(api, "CASE-E2-4", "person-d");
            setStatus(ds, approvedLapsed, "APPROVED");
            setStatus(ds, rejectedLapsed, "REJECTED");
            // time passes: three of the four are now past their expiry
            Instant past = Instant.now().minus(2, ChronoUnit.HOURS);
            for (String id : List.of(lapsed, approvedLapsed, rejectedLapsed)) {
                expireAt(ds, id, past);
            }

            List<String> before = rows(ds);
            assertEquals(4, before.size(), before.toString());

            Map<String, String> listed = statuses(body(get(api, "/admin/sod-exceptions"), 200));
            assertEquals("EXPIRED", listed.get(lapsed), listed.toString());
            assertEquals("EXPIRED", listed.get(approvedLapsed), listed.toString());
            assertEquals("REJECTED", listed.get(rejectedLapsed), listed.toString());
            assertEquals("PENDING", listed.get(inForce), listed.toString());

            // the status filter works on the answered (effective) status
            Map<String, String> expired = statuses(body(get(api, "/admin/sod-exceptions?status=EXPIRED"), 200));
            assertEquals(Map.of(lapsed, "EXPIRED", approvedLapsed, "EXPIRED"), expired);
            Map<String, String> pending = statuses(body(get(api, "/admin/sod-exceptions?status=PENDING"), 200));
            assertEquals(Map.of(inForce, "PENDING"), pending);
            assertFalse(statuses(body(get(api, "/admin/sod-exceptions?status=APPROVED"), 200)).containsKey(approvedLapsed),
                    "an approval past its date is not listed as in force");

            // the property: after five GETs every stored row, every column, is what it was
            assertEquals(before, rows(ds), "GET /admin/sod-exceptions changed SEC_SOD_EXCEPTION");
            assertEquals("PENDING", storedStatus(ds, lapsed));
            assertEquals("APPROVED", storedStatus(ds, approvedLapsed));
        }
    }

    private static String create(String api, String rule, String person) throws Exception {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("rule", rule);
        in.put("personId", person);
        in.put("reason", "Only officer available in the unit this week");
        in.put("expiresAt", Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString());
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(api + "/admin/sod-exceptions"))
                .header("Authorization", "Bearer " + TOKEN).header("Content-Type", "application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(in))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(r.statusCode() / 100 == 2, "POST /admin/sod-exceptions: " + r.statusCode() + " " + r.body());
        JsonNode e = JSON.readTree(r.body());
        assertEquals("PENDING", e.path("status").asText(), r.body());
        return e.path("id").asText();
    }

    private static HttpResponse<String> get(String api, String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(api + path)).header("Authorization", "Bearer " + TOKEN).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode body(HttpResponse<String> r, int status) throws Exception {
        assertEquals(status, r.statusCode(), r.request().uri() + ": " + r.body());
        return JSON.readTree(r.body());
    }

    /** id to status of a listing (a bare array, or a page object with {@code items}). */
    private static Map<String, String> statuses(JsonNode listing) {
        JsonNode items = listing.isArray() ? listing : listing.path("items");
        assertTrue(items.isArray(), listing.toString());
        Map<String, String> m = new LinkedHashMap<>();
        items.forEach(e -> m.put(e.path("id").asText(), e.path("status").asText()));
        return m;
    }

    private static void expireAt(DataSource ds, String id, Instant when) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE SEC_SOD_EXCEPTION SET expires_at=? WHERE exception_id=?")) {
            ps.setTimestamp(1, Timestamp.from(when));
            ps.setString(2, id);
            assertEquals(1, ps.executeUpdate(), id);
        }
    }

    private static void setStatus(DataSource ds, String id, String status) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE SEC_SOD_EXCEPTION SET status=? WHERE exception_id=?")) {
            ps.setString(1, status);
            ps.setString(2, id);
            assertEquals(1, ps.executeUpdate(), id);
        }
    }

    private static String storedStatus(DataSource ds, String id) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT status FROM SEC_SOD_EXCEPTION WHERE exception_id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), id);
                return rs.getString(1);
            }
        }
    }

    /** Every row of SEC_SOD_EXCEPTION with every column, in a stable order. */
    private static List<String> rows(DataSource ds) throws Exception {
        List<String> out = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM SEC_SOD_EXCEPTION ORDER BY exception_id");
             ResultSet rs = ps.executeQuery()) {
            ResultSetMetaData md = rs.getMetaData();
            while (rs.next()) {
                StringBuilder row = new StringBuilder();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    row.append(md.getColumnName(i)).append('=').append(rs.getString(i)).append(';');
                }
                out.add(row.toString());
            }
        }
        return out;
    }
}
