// SPDX-License-Identifier: Apache-2.0
package org.sangam.onboarding.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Plug-and-play acceptance, black box over REST against a running server, as planned in ACCEPTANCE-PLUG-AND-PLAY.md.
 * Each scenario is data ({@code acceptance/AT-n.json}); this class knows no country, department or sector. Skipped unless
 * {@code SANGAM_AT_URL}, {@code SANGAM_AT_MAKER_TOKEN} and {@code SANGAM_AT_CHECKER_TOKEN} are set. A scenario's business
 * run also needs {@code SANGAM_AT_TENANT_TOKEN_<tenant with non-alphanumerics as _>}.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PlugAndPlayAcceptanceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static String url;

    @BeforeAll
    static void configured() {
        url = System.getenv("SANGAM_AT_URL");
        assumeTrue(url != null && System.getenv("SANGAM_AT_MAKER_TOKEN") != null
                && System.getenv("SANGAM_AT_CHECKER_TOKEN") != null, "acceptance environment not configured");
    }

    private static JsonNode call(String token, String method, String path, Object body, int... expected) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url + "/api/v1" + path)).timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json");
        if (!"GET".equals(method)) {
            b.header("Idempotency-Key", UUID.randomUUID().toString());
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        boolean ok = expected.length == 0 ? r.statusCode() / 100 == 2 : java.util.Arrays.stream(expected).anyMatch(x -> x == r.statusCode());
        assertTrue(ok, method + " " + path + " -> " + r.statusCode() + " " + r.body());
        return r.body().isBlank() ? JSON.nullNode() : JSON.readTree(r.body());
    }

    private static String maker() {
        return System.getenv("SANGAM_AT_MAKER_TOKEN");
    }

    private static String checker() {
        return System.getenv("SANGAM_AT_CHECKER_TOKEN");
    }

    private static JsonNode scenario(String id) throws Exception {
        try (InputStream in = PlugAndPlayAcceptanceTest.class.getResourceAsStream("/acceptance/" + id + ".json")) {
            return JSON.readTree(in);
        }
    }

    private static JsonNode await(String runId, String... statuses) throws Exception {
        long end = System.currentTimeMillis() + 600_000;
        JsonNode run;
        do {
            run = call(maker(), "GET", "/onboarding/runs/" + runId, null);
            for (String s : statuses) {
                if (s.equals(run.path("status").asText())) {
                    return run;
                }
            }
            assertFalse("FAILED".equals(run.path("status").asText()), run.toString());
            Thread.sleep(500);
        } while (System.currentTimeMillis() < end);
        throw new AssertionError("run stayed " + run.path("status") + ": " + run);
    }

    /** The whole path: dry run, start, readiness, checks on maker-checker, go-live. */
    private static void onboard(JsonNode request) throws Exception {
        JsonNode dry = call(maker(), "POST", "/onboarding/dry-run", request);
        assertTrue(dry.path("ok").asBoolean(), dry.path("problems").toString());
        assertEquals(0, call(maker(), "GET", "/onboarding/runs?tenant=" + request.at("/tenant/id").asText(), null).size(),
                "the dry run must leave nothing behind");
        goLive(call(maker(), "POST", "/onboarding/runs", request).path("id").asText());
    }

    private static void goLive(String id) throws Exception {
        JsonNode run = await(id, "AWAITING_APPROVAL", "WAITING_FOR_FIX");
        assertEquals("AWAITING_APPROVAL", run.path("status").asText(), run.at("/readiness/problems").toString());
        Map<String, Object> approve = Map.of("presentedHash", run.at("/readiness/hash").asText());
        call(maker(), "POST", "/onboarding/runs/" + id + "/approve", approve, 422);
        call(checker(), "POST", "/onboarding/runs/" + id + "/approve", Map.of("presentedHash", "stale"), 409);
        call(checker(), "POST", "/onboarding/runs/" + id + "/approve", approve);
        assertEquals("ACTIVE", await(id, "LIVE").at("/steps/GO_LIVE/output/tenantStatus").asText());
    }

    /** Starts the scenario's process as the tenant and answers its tasks until it ends. */
    private static void business(JsonNode s) throws Exception {
        JsonNode biz = s.path("business");
        if (biz.isMissingNode()) {
            return;
        }
        String tenant = s.at("/request/tenant/id").asText();
        String token = System.getenv("SANGAM_AT_TENANT_TOKEN_" + tenant.replaceAll("[^A-Za-z0-9]", "_"));
        assumeTrue(token != null, "no tenant token for the business run of " + tenant);
        String key = "AT-" + UUID.randomUUID();
        Map<String, Object> start = new LinkedHashMap<>();
        start.put("key", biz.path("process").asText());
        start.put("businessKey", key);
        start.put("variables", biz.path("variables"));
        String pid = call(token, "POST", "/process-instances", start).path("id").asText();
        long end = System.currentTimeMillis() + 300_000;
        while (System.currentTimeMillis() < end) {
            JsonNode inst = call(token, "GET", "/process-instances/" + pid, null);
            if (inst.path("ended").asBoolean()) {
                return;
            }
            JsonNode tasks = call(token, "GET", "/tasks?businessKey=" + key, null);
            if (tasks.isEmpty() && biz.path("reachesWaitState").asBoolean()) {
                Thread.sleep(2000);
                JsonNode again = call(token, "GET", "/process-instances/" + pid, null);
                assertFalse("CANCELLED".equals(again.path("state").asText()), again.toString());
                return;
            }
            for (JsonNode t : tasks) {
                Map<String, Object> done = new LinkedHashMap<>();
                done.put("payloadHash", call(token, "GET", "/tasks/" + t.path("id").asText(), null).path("payloadHash").asText());
                done.put("decision", biz.path("decision").asText("APPROVED"));
                done.put("variables", biz.path("taskVariables"));
                call(token, "POST", "/tasks/" + t.path("id").asText() + "/complete", done);
            }
            Thread.sleep(500);
        }
        throw new AssertionError("process " + pid + " did not finish");
    }

    private void simple(String id) throws Exception {
        JsonNode s = scenario(id);
        onboard(s.path("request"));
        business(s);
    }

    @Test
    @Order(1)
    void at1NewGovernmentFromZero() throws Exception {
        simple("AT-1");
    }

    @Test
    @Order(2)
    void at2DepartmentWithRuleException() throws Exception {
        JsonNode s = scenario("AT-2");
        simple("AT-2");
        JsonNode profile = call(maker(), "GET", "/config/profiles?scope=" + s.at("/request/tenant/id").asText(), null);
        assertEquals(s.at("/expect/value").asText(), profile.at("/data" + s.at("/expect/pointer").asText()).asText());
    }

    @Test
    @Order(3)
    void at3PrivateHospital() throws Exception {
        simple("AT-3");
    }

    @Test
    @Order(4)
    void at4PrivateBank() throws Exception {
        simple("AT-4");
    }

    @Test
    @Order(5)
    void at5ForeignTreasury() throws Exception {
        simple("AT-5");
    }

    @Test
    @Order(6)
    void at6SignedPluginConnector() throws Exception {
        JsonNode s = scenario("AT-6");
        String pluginId = s.at("/plugin").asText();
        boolean loaded = false;
        for (JsonNode p : call(maker(), "GET", "/admin/plugins", null)) {
            loaded |= pluginId.equals(p.path("id").asText()) && "LOADED".equals(p.path("status").asText()) && !p.path("signedBy").isNull()
                    && !p.path("signedBy").asText("").isBlank();
        }
        assumeTrue(loaded, "signed example plugin not installed: see the test plan");
        onboard(s.path("request"));
        business(s);
    }

    @Test
    @Order(7)
    void at7ExportImportDiff() throws Exception {
        JsonNode s = scenario("AT-7");
        String source = s.at("/source").asText();
        JsonNode exported = call(maker(), "GET", "/onboarding/tenants/" + source + "/bundle", null);
        assertFalse(exported.path("signature").isMissingNode(), "the bundle must be signed");
        Map<String, Object> imp = JSON.convertValue(s.path("import"), Map.class);
        imp.put("bundle", exported);
        imp.put("dryRun", true);
        JsonNode dry = call(maker(), "POST", "/onboarding/bundles/import", imp);
        assertTrue(dry.at("/dryRun/ok").asBoolean(), dry.toString());
        imp.put("dryRun", false);
        goLive(call(maker(), "POST", "/onboarding/bundles/import", imp).at("/run/id").asText());

        // a changed bundle is refused
        Map<String, Object> tampered = JSON.convertValue(exported, Map.class);
        ((Map<String, Object>) tampered.get("body")).put("sourceTenant", "tampered");
        imp.put("bundle", tampered);
        imp.put("dryRun", true);
        call(maker(), "POST", "/onboarding/bundles/import", imp, 422);

        JsonNode target = call(maker(), "GET", "/onboarding/tenants/" + s.at("/import/targetTenant").asText() + "/bundle", null);
        JsonNode diff = call(maker(), "POST", "/onboarding/bundles/diff", Map.of("left", exported, "right", target));
        for (JsonNode e : diff) {
            String path = e.path("path").asText();
            boolean allowed = false;
            for (JsonNode a : s.path("allowedDifferences")) {
                allowed |= path.contains(a.asText());
            }
            assertTrue(allowed, "unexpected difference: " + e);
        }
    }
}
