// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.deploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sangam.core.security.SecurityIdentity;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E-33 follow-ups 2 and 3 (joint decisions EV-M-0966 and EV-M-0968): the four-eyes queue of policy changes over the
 * API, {@code GET /admin/policies/changes}, {@code GET /admin/policies/changes/{id}} and
 * {@code POST /admin/policies/changes/{id}/approve|reject} (contract 0aa6f3f6, CONTRACT-REGISTER rows of 2026-11-21).
 * The server is the demo's: production on, GOI.FIN created on a fresh database by FirstAdminBootstrap with its two
 * first administrators, so six self-service grants of the module defaults wait PENDING, requested by {@code system}.
 * <p>
 * The five cases the IFMS AGREE names: a self-approve is refused 422 SOD_VIOLATION and audited; a self-reject is refused
 * 422; a second person's approve applies, and its audit carries change_id, requested_by and decided_by; another
 * tenant's id is 404; {@code system} never decides.
 */
class PolicyChangeQueueAgainstServerTest {

    static final String TENANT = PacksOfBootstrapTenantAgainstServerTest.TENANT;
    static final String RAJENDRA = "rajendra.sharma";
    static final String MEENAKSHI = "meenakshi.kanwar";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @TempDir
    Path dir;

    @AfterAll
    static void releaseRealm() {
        DemoComposeFragmentStartupTest.RealmIdp.release();
    }

    // ------------------------------------------------------------------ the queue

    @Test
    void theQueueListsTheBootstrapChangesWithWhatTheyApplyAndTheirReasons() throws Exception {
        try (ConfigurableApplicationContext ctx = start("queue")) {
            String admin = admin(RAJENDRA);
            HttpResponse<String> list = send("GET", api(ctx) + "/admin/policies/changes", admin, null);
            assertEquals(200, list.statusCode(), list.body());
            List<JsonNode> items = items(list.body());
            assertEquals(6, items.size(), "the six self-service grants of the module defaults: " + list.body());
            assertEquals("6", list.headers().firstValue("X-Total-Count").orElse(""), "X-Total-Count");
            for (JsonNode c : items) {
                assertEquals("PENDING", c.path("status").asText(), c.toString());
                assertEquals("system", c.path("requestedBy").asText(), c.toString());
                assertEquals("CREATE", c.path("operation").asText(), c.toString());
                assertEquals("module default (self-service)", c.path("requestReason").asText(), c.toString());
                assertTrue(c.path("decisionReason").isNull() || c.path("decisionReason").isMissingNode(), c.toString());
                assertEquals(c.path("policyId").asText(), c.path("policy").path("id").asText(),
                        "the approver sees the definition the change applies: " + c);
                assertFalse(c.path("policy").path("actions").isEmpty(), c.toString());
            }
            String id = items.get(0).path("id").asText();
            HttpResponse<String> one = send("GET", api(ctx) + "/admin/policies/changes/" + id, admin, null);
            assertEquals(200, one.statusCode(), one.body());
            assertEquals(id, JSON.readTree(one.body()).path("id").asText());
            HttpResponse<String> done = send("GET", api(ctx) + "/admin/policies/changes?status=APPLIED", admin, null);
            assertEquals(200, done.statusCode(), done.body());
            assertEquals(0, items(done.body()).size(), "nothing is applied yet");
        }
    }

    // ------------------------------------------------------------------ (c) the five cases of the AGREE

    /** A self-approve is refused 422 SOD_VIOLATION, audited SOD_VIOLATION_BLOCKED, and the change stays PENDING. */
    @Test
    void aSelfApproveIsRefused422AndAudited() throws Exception {
        try (ConfigurableApplicationContext ctx = start("self-approve")) {
            String id = requestAsRajendra(ctx);
            long blocked = count(ctx, "SOD_VIOLATION_BLOCKED");
            HttpResponse<String> r = send("POST", api(ctx) + "/admin/policies/changes/" + id + "/approve", admin(RAJENDRA),
                    Map.of("reason", "my own change"));
            assertEquals(422, r.statusCode(), r.body());
            assertEquals("SOD_VIOLATION", code(r), r.body());
            assertEquals(blocked + 1, count(ctx, "SOD_VIOLATION_BLOCKED"), "the refusal is audited");
            assertTrue(lastPayload(ctx, "SOD_VIOLATION_BLOCKED").contains(id), "the audit names the change");
            assertEquals("PENDING", status(ctx, id), "nothing decided");
        }
    }

    /** A self-reject is refused 422 SOD_VIOLATION as well (no self-reject, no withdraw). */
    @Test
    void aSelfRejectIsRefused422() throws Exception {
        try (ConfigurableApplicationContext ctx = start("self-reject")) {
            String id = requestAsRajendra(ctx);
            long blocked = count(ctx, "SOD_VIOLATION_BLOCKED");
            HttpResponse<String> r = send("POST", api(ctx) + "/admin/policies/changes/" + id + "/reject", admin(RAJENDRA),
                    Map.of("reason", "withdraw my own change"));
            assertEquals(422, r.statusCode(), r.body());
            assertEquals("SOD_VIOLATION", code(r), r.body());
            assertEquals(blocked + 1, count(ctx, "SOD_VIOLATION_BLOCKED"), "the refusal is audited");
            assertEquals("PENDING", status(ctx, id), "nothing decided");
        }
    }

    /**
     * A second person's approve applies the change in the same request: APPLIED, decided by that person with their
     * reason, the request reason kept, the grant in force, and GRANT_CHANGED carries change_id, requested_by and
     * decided_by. The second person's reject of another change leaves it REJECTED with both reasons, nothing applied.
     */
    @Test
    void aSecondPersonsApproveAppliesAndTheAuditCarriesTheChangeTheRequesterAndTheDecider() throws Exception {
        try (ConfigurableApplicationContext ctx = start("second-person")) {
            String id = requestAsRajendra(ctx);
            HttpResponse<String> r = send("POST", api(ctx) + "/admin/policies/changes/" + id + "/approve", admin(MEENAKSHI),
                    Map.of("reason", "checked against the office order"));
            assertEquals(200, r.statusCode(), r.body());
            JsonNode c = JSON.readTree(r.body());
            assertEquals("APPLIED", c.path("status").asText(), r.body());
            assertEquals(MEENAKSHI, c.path("decidedBy").asText(), r.body());
            assertEquals(RAJENDRA, c.path("requestedBy").asText(), r.body());
            assertEquals("admin API", c.path("requestReason").asText(), "the request reason is kept: " + r.body());
            assertEquals("checked against the office order", c.path("decisionReason").asText(), r.body());
            assertEquals("APPLIED", status(ctx, id));
            String policyId = c.path("policyId").asText();
            assertEquals(List.of("ACTIVE"), PacksOfBootstrapTenantAgainstServerTest.query(ctx,
                    "SELECT status FROM SEC_POLICY WHERE tenant_id=? AND policy_id=?", TENANT, policyId), "the grant is in force");
            String audit = lastPayload(ctx, "GRANT_CHANGED");
            assertTrue(audit.contains("\"change_id\":\"" + id + "\""), audit);
            assertTrue(audit.contains("\"requested_by\":\"" + RAJENDRA + "\""), audit);
            assertTrue(audit.contains("\"decided_by\":\"" + MEENAKSHI + "\""), audit);

            // a change the system requested is decided by any person who holds the permission; reject keeps both reasons
            String system = items(send("GET", api(ctx) + "/admin/policies/changes", admin(RAJENDRA), null).body()).stream()
                    .filter(x -> "system".equals(x.path("requestedBy").asText())).findFirst().orElseThrow().path("id").asText();
            long pendingEvents = count(ctx, "POLICY_CHANGE_PENDING");
            HttpResponse<String> rej = send("POST", api(ctx) + "/admin/policies/changes/" + system + "/reject", admin(RAJENDRA),
                    Map.of("reason", "not for this department"));
            assertEquals(200, rej.statusCode(), rej.body());
            JsonNode rc = JSON.readTree(rej.body());
            assertEquals("REJECTED", rc.path("status").asText(), rej.body());
            assertEquals("module default (self-service)", rc.path("requestReason").asText(), rej.body());
            assertEquals("not for this department", rc.path("decisionReason").asText(), rej.body());
            assertEquals(pendingEvents + 1, count(ctx, "POLICY_CHANGE_PENDING"), "the rejection is audited");
            String rejected = lastPayload(ctx, "POLICY_CHANGE_PENDING");
            assertTrue(rejected.contains("\"operation\":\"REJECT\"") && rejected.contains(system), rejected);
            HttpResponse<String> again = send("POST", api(ctx) + "/admin/policies/changes/" + system + "/approve",
                    admin(MEENAKSHI), Map.of("reason", "too late"));
            assertEquals(409, again.statusCode(), again.body());
            assertEquals("CHANGE_NOT_PENDING", code(again), again.body());
        }
    }

    /** Another tenant's change id is 404 for read and decision alike, and the change is left as it was. */
    @Test
    void anotherTenantsChangeIdIs404() throws Exception {
        try (ConfigurableApplicationContext ctx = start("other-tenant")) {
            String other = UUID.randomUUID().toString();
            try (Connection c = ctx.getBean(javax.sql.DataSource.class).getConnection();
                 var ps = c.prepareStatement("INSERT INTO SEC_POLICY_CHANGE(tenant_id, change_id, policy_id, operation, definition,"
                         + " requested_by, requested_at, status) VALUES ('GOI.OTHER', ?, 'p-other', 'CREATE', '{}', 'someone',"
                         + " CURRENT_TIMESTAMP, 'PENDING')")) {
                ps.setString(1, other);
                assertEquals(1, ps.executeUpdate());
            }
            String admin = admin(RAJENDRA);
            HttpResponse<String> get = send("GET", api(ctx) + "/admin/policies/changes/" + other, admin, null);
            assertEquals(404, get.statusCode(), get.body());
            assertEquals("NOT_FOUND", code(get), get.body());
            for (String act : List.of("approve", "reject")) {
                HttpResponse<String> r = send("POST", api(ctx) + "/admin/policies/changes/" + other + "/" + act, admin,
                        Map.of("reason", "not mine"));
                assertEquals(404, r.statusCode(), act + ": " + r.body());
            }
            HttpResponse<String> none = send("GET", api(ctx) + "/admin/policies/changes/" + UUID.randomUUID(), admin, null);
            assertEquals(404, none.statusCode(), none.body());
            assertFalse(send("GET", api(ctx) + "/admin/policies/changes", admin, null).body().contains(other),
                    "the list holds only the caller's tenant");
            assertEquals(List.of("PENDING"), PacksOfBootstrapTenantAgainstServerTest.query(ctx,
                    "SELECT status FROM SEC_POLICY_CHANGE WHERE tenant_id='GOI.OTHER' AND change_id=?", other));
        }
    }

    /**
     * {@code system} never decides, not even a change a person requested: the decision the API runs
     * ({@code PolicyAdminService.decide}) refuses every system identity with SOD_VIOLATION, audited. Called by
     * reflection, because no route can carry a system identity; the method is the one the routes call.
     */
    @Test
    void systemAsDeciderIsRefused() throws Exception {
        try (ConfigurableApplicationContext ctx = start("system")) {
            String id = requestAsRajendra(ctx);
            org.sangam.security.SangamSecurity security = ctx.getBean(org.sangam.security.SangamSecurity.class);
            java.lang.reflect.Method decide = security.policies().getClass().getMethod("decide", Connection.class,
                    SecurityIdentity.class, String.class, boolean.class, String.class);
            long blocked = count(ctx, "SOD_VIOLATION_BLOCKED");
            for (boolean approve : new boolean[]{true, false}) {
                Throwable thrown = null;
                try (Connection c = ctx.getBean(javax.sql.DataSource.class).getConnection()) {
                    decide.invoke(security.policies(), c, SecurityIdentity.system(TENANT, "any-start-up-step"), id, approve,
                            "a system step");
                } catch (java.lang.reflect.InvocationTargetException e) {
                    thrown = e.getCause();
                }
                assertTrue(thrown instanceof org.sangam.core.security.SangamSecurityException.SodViolation,
                        (approve ? "approve" : "reject") + " by system: " + thrown);
            }
            assertEquals(blocked + 2, count(ctx, "SOD_VIOLATION_BLOCKED"), "each refusal is audited");
            assertEquals("PENDING", status(ctx, id));
        }
    }

    // ------------------------------------------------------------------ helpers

    private ConfigurableApplicationContext start(String name) throws Exception {
        return PacksOfBootstrapTenantAgainstServerTest.Host.h2(dir.resolve(name)).startAsDeployed();
    }

    /** Rajendra requests a grant through POST /admin/policies (four-eyes: PENDING); the id of that change. */
    private static String requestAsRajendra(ConfigurableApplicationContext ctx) throws Exception {
        HttpResponse<String> created = send("POST", api(ctx) + "/admin/policies", admin(RAJENDRA), Map.of("effect", "GRANT",
                "principalType", "GROUP", "principalId", "clerks", "resourceType", "PROCESS_INSTANCE", "resourceId", "*",
                "actions", List.of("READ")));
        assertTrue(created.statusCode() == 200 || created.statusCode() == 201, created.body());
        List<String> ids = PacksOfBootstrapTenantAgainstServerTest.query(ctx, "SELECT change_id FROM SEC_POLICY_CHANGE"
                + " WHERE tenant_id=? AND requested_by=? AND status='PENDING'", TENANT, RAJENDRA);
        assertEquals(1, ids.size(), "one pending change requested by " + RAJENDRA);
        return ids.get(0);
    }

    private static String admin(String person) throws Exception {
        return PresentationSeedAgainstServerTest.token(person, List.of("sangam-tenant-admin", "sangam-user"));
    }

    private static String api(ConfigurableApplicationContext ctx) {
        return "http://localhost:" + ctx.getEnvironment().getRequiredProperty("local.server.port") + "/api/v1";
    }

    private static String status(ConfigurableApplicationContext ctx, String id) throws java.sql.SQLException {
        List<String> s = PacksOfBootstrapTenantAgainstServerTest.query(ctx,
                "SELECT status FROM SEC_POLICY_CHANGE WHERE tenant_id=? AND change_id=?", TENANT, id);
        return s.isEmpty() ? null : s.get(0);
    }

    /** Events of a type for the tenant, on any of its chains (the denial chain included). */
    private static long count(ConfigurableApplicationContext ctx, String type) throws java.sql.SQLException {
        return Long.parseLong(PacksOfBootstrapTenantAgainstServerTest.query(ctx,
                "SELECT CAST(COUNT(*) AS VARCHAR(20)) FROM AUDIT_EVENT WHERE tenant_id=? AND event_type=?", TENANT, type).get(0));
    }

    /** The payload of the tenant's newest event of a type. */
    private static String lastPayload(ConfigurableApplicationContext ctx, String type) throws java.sql.SQLException {
        List<String> p = PacksOfBootstrapTenantAgainstServerTest.query(ctx, "SELECT p.payload FROM AUDIT_EVENT e JOIN"
                + " AUDIT_PAYLOAD p ON p.event_id = e.event_id WHERE e.tenant_id=? AND e.event_type=?"
                + " ORDER BY e.occurred_at DESC, e.seq DESC", TENANT, type);
        assertFalse(p.isEmpty(), "an event " + type);
        return p.get(0);
    }

    private static List<JsonNode> items(String body) throws Exception {
        JsonNode n = JSON.readTree(body);
        JsonNode list = n.isArray() ? n : n.path("items");
        List<JsonNode> out = new ArrayList<>();
        list.forEach(out::add);
        return out;
    }

    private static String code(HttpResponse<String> r) {
        try {
            return JSON.readTree(r.body()).path("code").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private static HttpResponse<String> send(String method, String url, String token, Object body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + token)
                .header("Accept", "application/json");
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        if (!method.equals("GET")) {
            b.header("Idempotency-Key", UUID.randomUUID().toString());
        }
        return HTTP.send(b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }
}
