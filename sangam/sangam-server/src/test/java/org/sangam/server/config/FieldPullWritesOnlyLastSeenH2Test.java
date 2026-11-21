// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
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
 * Register P-2 (CodeQL alert 375, CSRF report bucket 4; owner decision 2026-11-20: keep on GET):
 * {@code GET /field/sync/by-device-id/pull} records that the device made contact and changes nothing else. The test
 * compares every row of every table of the server's database before and after the GET: the one difference allowed
 * is the column {@code last_seen_at} of that device's own {@code FLD_DEVICE} row.
 *
 * <p>The server runs its background work (job executor, relays, sweepers) during the test. A table that changes while
 * no request is made is that background work; such tables are found by a control interval first, printed, and left
 * out of the comparison. {@code FLD_DEVICE} may never be among them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
@DirtiesContext
class FieldPullWritesOnlyLastSeenH2Test {

    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

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
    DataSource ds;

    @Test
    void aPullChangesLastSeenAtOfItsOwnDeviceAndNothingElse() throws Exception {
        String device = "p2-device-" + UUID.randomUUID();
        String other = "p2-other-" + UUID.randomUUID();
        for (String id : List.of(device, other)) {
            HttpResponse<String> reg = send("POST", "/field/devices",
                    "{\"deviceId\":\"" + id + "\",\"platform\":\"android\",\"appVersion\":\"1.0.0\"}");
            assertEquals(2, reg.statusCode() / 100, "POST /field/devices: " + reg.statusCode() + " " + reg.body());
        }
        // a known, old contact time, so the change is seen whatever the clock resolution
        Instant old = Instant.parse("2026-01-01T00:00:00Z");
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE FLD_DEVICE SET last_seen_at=? WHERE device_id IN (?, ?)")) {
            ps.setTimestamp(1, Timestamp.from(old));
            ps.setString(2, device);
            ps.setString(3, other);
            assertEquals(2, ps.executeUpdate());
        }

        // control interval: what the server's own background work changes while no request is made
        Map<String, List<String>> idle1 = snapshot();
        Thread.sleep(1500);
        Map<String, List<String>> idle2 = snapshot();
        Set<String> background = changed(idle1, idle2);
        System.out.println("PARKED-DEC P-2 | tables compared: " + idle2.size() + " | changed with no request (left out): "
                + background);
        assertTrue(!background.contains("FLD_DEVICE"), "FLD_DEVICE changes without a request: " + background);

        Map<String, List<String>> before = snapshot();
        Map<String, String> deviceBefore = deviceRow(device);
        String otherBefore = deviceRow(other).toString();

        // typical: the first pull, and a later pull with a cursor
        for (String query : List.of("?deviceId=" + device, "?deviceId=" + device + "&cursor=7")) {
            HttpResponse<String> pull = send("GET", "/field/sync/by-device-id/pull" + query, null);
            assertEquals(200, pull.statusCode(), "GET pull" + query + ": " + pull.body());
        }
        // difficult: a device that is not registered is refused and records nothing
        HttpResponse<String> unknown = send("GET", "/field/sync/by-device-id/pull?deviceId=p2-not-registered", null);
        assertEquals(4, unknown.statusCode() / 100, "pull of an unregistered device: " + unknown.statusCode() + " " + unknown.body());

        Map<String, List<String>> after = snapshot();
        Map<String, String> deviceAfter = deviceRow(device);

        Set<String> differing = changed(before, after);
        differing.removeAll(background);
        System.out.println("PARKED-DEC P-2 | tables changed by the three GETs: " + differing);
        assertEquals(Set.of("FLD_DEVICE"), differing, "the pull changed more than FLD_DEVICE");

        Set<String> columns = new TreeSet<>();
        for (Map.Entry<String, String> e : deviceBefore.entrySet()) {
            if (!String.valueOf(e.getValue()).equals(String.valueOf(deviceAfter.get(e.getKey())))) {
                columns.add(e.getKey());
            }
        }
        System.out.println("PARKED-DEC P-2 | FLD_DEVICE columns changed for the pulling device: " + columns
                + " | last_seen_at " + deviceBefore.get("LAST_SEEN_AT") + " -> " + deviceAfter.get("LAST_SEEN_AT"));
        assertEquals(Set.of("LAST_SEEN_AT"), columns, "the pull changed other columns of its device row");
        assertNotEquals(deviceBefore.get("LAST_SEEN_AT"), deviceAfter.get("LAST_SEEN_AT"));
        assertEquals(deviceBefore.keySet(), deviceAfter.keySet());
        assertEquals(otherBefore, deviceRow(other).toString(), "another device's row changed");
        assertEquals(rowCount(before, "FLD_DEVICE"), rowCount(after, "FLD_DEVICE"), "no device row is added or removed");
    }

    private static int rowCount(Map<String, List<String>> snapshot, String table) {
        return snapshot.get(table).size();
    }

    private static Set<String> changed(Map<String, List<String>> a, Map<String, List<String>> b) {
        Set<String> out = new TreeSet<>();
        Set<String> names = new TreeSet<>(a.keySet());
        names.addAll(b.keySet());
        for (String t : names) {
            if (!String.valueOf(a.get(t)).equals(String.valueOf(b.get(t)))) {
                out.add(t);
            }
        }
        return out;
    }

    /** Every base table of the database: table name to its rows (all columns), sorted. */
    private Map<String, List<String>> snapshot() throws Exception {
        Map<String, List<String>> out = new TreeMap<>();
        try (Connection c = ds.getConnection()) {
            List<String[]> tables = new ArrayList<>();
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT TABLE_SCHEMA, TABLE_NAME FROM INFORMATION_SCHEMA.TABLES"
                         + " WHERE TABLE_TYPE = 'BASE TABLE' AND TABLE_SCHEMA <> 'INFORMATION_SCHEMA'")) {
                while (rs.next()) {
                    tables.add(new String[]{rs.getString(1), rs.getString(2)});
                }
            }
            assertTrue(tables.size() > 50, "the server's schema is expected here, found " + tables.size() + " tables");
            for (String[] t : tables) {
                List<String> rows = new ArrayList<>();
                try (Statement s = c.createStatement();
                     ResultSet rs = s.executeQuery("SELECT * FROM \"" + t[0] + "\".\"" + t[1] + "\"")) {
                    int n = rs.getMetaData().getColumnCount();
                    while (rs.next()) {
                        StringBuilder row = new StringBuilder();
                        for (int i = 1; i <= n; i++) {
                            row.append(rs.getString(i)).append('\u001f');
                        }
                        rows.add(row.toString());
                    }
                }
                Collections.sort(rows);
                out.put(t[1].toUpperCase(java.util.Locale.ROOT), rows);
            }
        }
        return out;
    }

    private Map<String, String> deviceRow(String deviceId) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM FLD_DEVICE WHERE device_id=?")) {
            ps.setString(1, deviceId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "no FLD_DEVICE row for " + deviceId);
                ResultSetMetaData md = rs.getMetaData();
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    row.put(md.getColumnName(i).toUpperCase(java.util.Locale.ROOT), rs.getString(i));
                }
                return row;
            }
        }
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
}
