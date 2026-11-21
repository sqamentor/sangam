// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.deploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.model.bpmn.Bpmn;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Register E-29, E-31, E-32: the server of the seat tests, started as on demo.ifms.dev (the demo fragment's settings,
 * production mode, {@link DemoComposeFragmentStartupTest} harness, H2), with tokens shaped like realm oneifms's (no
 * {@code seats} claim unless a test asks for one). Seats, persons and postings go through the product's REST API as a
 * tenant administrator. Each subclass seeds what it needs in {@link #seed()}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class SeatLeftoversHarness {

    static final String TENANT = "GOI.FIN";
    static final String OFFICE = "SL-OFFICE";
    static final String SEAT = "X-Sangam-Seat";
    static final String ACTING = "X-Sangam-Acting-For-Seat";
    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final List<String> USER = List.of("sangam-user");
    static final List<String> ADMIN = List.of("sangam-tenant-admin", "sangam-user");

    @TempDir
    static Path dir;

    ConfigurableApplicationContext ctx;
    String api;
    /** The first administrator of the tenant (sangam.security.first-admins of the fragment). */
    String admin;
    ProcessEngine engine;

    @BeforeAll
    void startServer() throws Exception {
        DemoComposeFragmentStartupTest.Keys keys = new DemoComposeFragmentStartupTest.Keys(dir);
        Map<String, String> bootstrap = Map.of("SANGAM_KEYS_BOOTSTRAP", "true",
                "SANGAM_KEYS_FINGERPRINT_EXPORT", dir.resolve("key-fingerprints.txt").toString(),
                "SANGAM_KEYS_ATTESTATION", "", "SANGAM_KEYS_CUSTODIAN_PUBLIC_KEY", "");
        List<String> settings = new ArrayList<>(List.of("--sangam.packs.deploy-on-start=false",
                "--sangam.compliance.log-archive.root=" + dir.resolve("compliance-logs")));
        settings.addAll(moreSettings());
        ctx = DemoComposeFragmentStartupTest.start(keys, bootstrap, settings, "h2", "oidc");
        api = "http://localhost:" + ctx.getEnvironment().getRequiredProperty("local.server.port") + "/api/v1";
        admin = token("rajendra.sharma", ADMIN);
        engine = ctx.getBean(ProcessEngine.class);
        ok(send("PUT", "/admin/org-levels", admin, Map.of(), Map.of("tenantId", TENANT, "levels", List.of("OFFICE"))));
        ok(send("POST", "/admin/org-units", admin, Map.of(), map("id", OFFICE, "code", OFFICE, "name", "Seat leftovers office",
                "level", "OFFICE", "parentId", null)));
        engine.getRepositoryService().createDeployment().tenantId(TENANT).name("seat-leftovers")
                .addModelInstance("seatleft.bpmn", Bpmn.createExecutableProcess("seatleft").operatonHistoryTimeToLive(1)
                        .startEvent().userTask("act").name("Act for the seat").operatonCandidateGroups("${seat}")
                        .endEvent().done()).deploy();
        seed();
    }

    /** Further command-line settings of the server start. */
    List<String> moreSettings() {
        return List.of();
    }

    abstract void seed() throws Exception;

    @AfterAll
    void stopServer() {
        try {
            if (ctx != null) {
                ctx.close();
            }
        } finally {
            ctx = null;
            engine = null;
            DemoComposeFragmentStartupTest.RealmIdp.release();
        }
    }

    // ------------------------------------------------------------------ seeding

    static String today() {
        return LocalDate.now(ZoneOffset.UTC).toString();
    }

    static String inDays(int days) {
        return LocalDate.now(ZoneOffset.UTC).plusDays(days).toString();
    }

    void seat(String id) throws Exception {
        ok(send("POST", "/admin/seats", admin, Map.of(), map("id", id, "code", id, "designation", "Officer",
                "name", id, "orgUnitId", OFFICE, "reportsToSeatId", null, "roles", List.of())));
    }

    /** First sign-in of a person (JIT person, as on the demo). */
    void signIn(String person) throws Exception {
        assertEquals(200, send("GET", "/me", token(person, USER), Map.of(), null).statusCode(), person + " signs in");
    }

    /** One posting through {@code POST /admin/assignments}; returns its id. */
    String post(String seat, String person, String kind, String from, String to) throws Exception {
        HttpResponse<String> r = send("POST", "/admin/assignments", admin, Map.of(), map("seatId", seat, "personId", person,
                "kind", kind, "validFrom", from, "validTo", to, "orderRef", "Office order: seat leftovers"));
        ok(r);
        return JSON.readTree(r.body()).path("id").asText();
    }

    /** A user task that waits for the seat (candidate group = the seat id, as the packs route a step); its id. */
    String taskFor(String seat, String businessKey) {
        String pi = engine.getRuntimeService().createProcessInstanceByKey("seatleft").processDefinitionTenantId(TENANT)
                .businessKey(businessKey).setVariable("seat", seat).execute().getId();
        return engine.getTaskService().createTaskQuery().processInstanceId(pi).singleResult().getId();
    }

    // ------------------------------------------------------------------ reads

    /** The audit events of the tenant of one type, each as "chain envelope payload" text, oldest first (read-only). */
    List<String> events(String type) throws Exception {
        List<String> out = new ArrayList<>();
        try (var c = ctx.getBean(javax.sql.DataSource.class).getConnection();
             var ps = c.prepareStatement("SELECT e.chain_id, e.envelope, p.payload FROM AUDIT_EVENT e"
                     + " LEFT JOIN AUDIT_PAYLOAD p ON p.event_id = e.event_id WHERE e.tenant_id=? AND e.event_type=?"
                     + " ORDER BY e.occurred_at, e.seq")) {
            ps.setString(1, TENANT);
            ps.setString(2, type);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1) + " " + rs.getString(2) + " " + rs.getString(3));
                }
            }
        }
        return out;
    }

    /** The seats {@code GET /me} offers under the given headers, by seat id. */
    Map<String, JsonNode> seatsOf(String person, Map<String, String> headers) throws Exception {
        HttpResponse<String> me = send("GET", "/me", token(person, USER), headers, null);
        assertEquals(200, me.statusCode(), me.body());
        Map<String, JsonNode> out = new LinkedHashMap<>();
        JSON.readTree(me.body()).path("seats").forEach(s -> out.put(s.path("seatId").asText(), s));
        return out;
    }

    /** The business keys of a 200 task list, sorted. */
    static List<String> keys(HttpResponse<String> r) throws Exception {
        assertEquals(200, r.statusCode(), r.body());
        JsonNode body = JSON.readTree(r.body());
        List<String> keys = new ArrayList<>();
        (body.isArray() ? body : body.path("items")).forEach(t -> keys.add(t.path("businessKey").asText()));
        keys.sort(null);
        return keys;
    }

    static String code(HttpResponse<String> r) throws Exception {
        return r.body() == null || r.body().isBlank() ? "" : JSON.readTree(r.body()).path("code").asText();
    }

    static void assertProblem(int status, String code, HttpResponse<String> r, String what) throws Exception {
        assertEquals(status + " " + code, r.statusCode() + " " + code(r), what + ": " + r.body());
    }

    // ------------------------------------------------------------------ HTTP

    static void ok(HttpResponse<String> r) {
        assertTrue(r.statusCode() / 100 == 2, r.request().method() + " " + r.request().uri() + ": " + r.statusCode() + " " + r.body());
    }

    HttpResponse<String> send(String method, String path, String token, Map<String, String> headers, Object body)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(api + path)).header("Authorization", "Bearer " + token);
        headers.forEach(b::header);
        if (method.equals("GET")) {
            b.GET();
        } else {
            b.header("Content-Type", "application/json").header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                    .method(method, HttpRequest.BodyPublishers.ofString(body == null ? "{}" : JSON.writeValueAsString(body)));
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static String token(String user, List<String> roles) throws Exception {
        return DemoComposeFragmentStartupTest.RealmIdp.get().tokenWithoutSub(TENANT, user, roles);
    }

    static String token(String user, List<String> roles, Map<String, Object> claims) throws Exception {
        return DemoComposeFragmentStartupTest.RealmIdp.get().tokenWithoutSub(TENANT, user, roles, claims);
    }

    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** A map that may hold null values (JSON null), which {@code Map.of} refuses. */
    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
