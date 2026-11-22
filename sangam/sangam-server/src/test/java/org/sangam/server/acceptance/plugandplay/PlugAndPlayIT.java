// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.plugandplay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.sangam.server.acceptance.AbstractAcceptanceIT;

import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Plug-and-play acceptance (ACCEPTANCE-PLUG-AND-PLAY.md, AT-1..AT-7) inside the acceptance server: the scenario data
 * of {@code sangam-onboarding} ({@code acceptance/plugandplay/AT-n.json}, a copy), with every tenant id given a
 * per-run suffix so reruns and other ITs never collide. This class knows no country, department or sector.
 *
 * <p>Common proof per scenario, all over REST with OIDC tokens of distinct persons: dry run ok and leaves no run
 * behind; AWAITING_APPROVAL; the maker's own approval 422; a stale hash 409; the checker's approval brings the run to
 * LIVE with the tenant ACTIVE. Business runs act as a person of the new tenant over /api/v1.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PlugAndPlayIT extends AbstractAcceptanceIT {

    static final String CHECKER = "acc-pnp-checker";
    static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();

    @LocalServerPort
    int port;

    /** AT-6: the example plugin, built and signed for this run with its own trusted key (no manual install step). */
    @org.springframework.test.context.DynamicPropertySource
    static void signedExamplePlugin(org.springframework.test.context.DynamicPropertyRegistry r) {
        r.add("sangam.plugins.folder", SignedExamplePlugin::folder);
    }

    AccHttp checker() {
        // SL-70: onboarding is a platform operation, so the checker is a platform-realm administrator
        return AccHttp.local(port, platformBearer("acc", CHECKER, ADMIN_GROUPS));
    }

    AccHttp maker() {
        return AccHttp.local(port, "acceptance-token");
    }

    /** Scenario data with every {@code tenant.id}, {@code source} and {@code targetTenant} suffixed for this run. */
    static JsonNode scenario(String id) throws Exception {
        try (InputStream in = PlugAndPlayIT.class.getResourceAsStream("/acceptance/plugandplay/" + id + ".json")) {
            String text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            ObjectNode s = (ObjectNode) AccHttp.JSON.readTree(text);
            suffix(s.at("/request/tenant"), "id");
            if (s.has("source")) {
                s.put("source", s.path("source").asText() + RUN);
            }
            suffix(s.path("import"), "targetTenant");
            return s;
        }
    }

    private static void suffix(JsonNode node, String field) {
        if (node instanceof ObjectNode o && o.hasNonNull(field)) {
            o.put(field, o.path(field).asText() + RUN);
        }
    }

    private JsonNode await(String runId, String... statuses) throws Exception {
        long end = System.currentTimeMillis() + 600_000;
        JsonNode run;
        do {
            run = maker().call("GET", "/api/v1/onboarding/runs/" + runId, null);
            for (String s : statuses) {
                if (s.equals(run.path("status").asText())) {
                    return run;
                }
            }
            assertThat(run.path("status").asText()).as(run.toString()).isNotEqualTo("FAILED");
            Thread.sleep(500);
        } while (System.currentTimeMillis() < end);
        throw new AssertionError("run stayed " + run.path("status") + ": " + run);
    }

    private void onboard(JsonNode request) throws Exception {
        JsonNode dry = maker().call("POST", "/api/v1/onboarding/dry-run", request);
        assertThat(dry.path("ok").asBoolean()).as("dry run problems: " + dry.path("problems")).isTrue();
        assertThat(maker().call("GET", "/api/v1/onboarding/runs?tenant=" + request.at("/tenant/id").asText(), null).size())
                .as("the dry run must leave nothing behind").isZero();
        goLive(maker().call("POST", "/api/v1/onboarding/runs", request).path("id").asText());
    }

    private void goLive(String id) throws Exception {
        JsonNode run = await(id, "AWAITING_APPROVAL", "WAITING_FOR_FIX");
        assertThat(run.path("status").asText()).as("readiness problems: " + run.at("/readiness/problems"))
                .isEqualTo("AWAITING_APPROVAL");
        String hash = run.at("/readiness/hash").asText();
        maker().call("POST", "/api/v1/onboarding/runs/" + id + "/approve", Map.of("presentedHash", hash), 422);
        checker().call("POST", "/api/v1/onboarding/runs/" + id + "/approve", Map.of("presentedHash", "stale"), 409);
        checker().call("POST", "/api/v1/onboarding/runs/" + id + "/approve", Map.of("presentedHash", hash));
        assertThat(await(id, "LIVE").at("/steps/GO_LIVE/output/tenantStatus").asText()).isEqualTo("ACTIVE");
    }

    /** Starts the scenario's process over REST as a person of the new tenant and answers its tasks until it ends. */
    private void business(JsonNode s) throws Exception {
        JsonNode biz = s.path("business");
        if (biz.isMissingNode()) {
            return;
        }
        String tenant = s.at("/request/tenant/id").asText();
        AccHttp t = AccHttp.local(port, bearer(tenant, "acc-pnp-officer", ADMIN_GROUPS));
        String key = "AT-" + UUID.randomUUID();
        Map<String, Object> start = new LinkedHashMap<>();
        start.put("key", biz.path("process").asText());
        start.put("businessKey", key);
        start.put("variables", biz.path("variables"));
        String pid = t.call("POST", "/api/v1/process-instances", start).path("id").asText();
        long end = System.currentTimeMillis() + 300_000;
        while (System.currentTimeMillis() < end) {
            JsonNode inst = t.call("GET", "/api/v1/process-instances/" + pid, null);
            if (inst.path("ended").asBoolean()) {
                assertThat(biz.path("reachesWaitState").asBoolean())
                        .as("the scenario expects a wait state, the run ended (e.g. rejected): " + inst + probe(key)).isFalse();
                // ended through its model, not terminated (cancelled or deleted)
                assertThat(inst.path("state").asText()).as(inst.toString()).isEqualTo("COMPLETED");
                return;
            }
            JsonNode tasks = t.call("GET", "/api/v1/tasks?businessKey=" + key, null);
            if (tasks.isEmpty() && biz.path("reachesWaitState").asBoolean()) {
                Thread.sleep(2000);
                JsonNode again = t.call("GET", "/api/v1/process-instances/" + pid, null);
                assertThat(again.path("state").asText()).as(again.toString()).isNotEqualTo("CANCELLED");
                assertThat(again.path("ended").asBoolean()).as("the run must wait, not end: " + again + probe(key)).isFalse();
                // a run held by a failed job or an incident (e.g. an unresolvable call activity) is not a wait state
                JsonNode stuck = t.call("GET", "/api/v1/process-instances?businessKey=" + key + "&withIncidents=true", null);
                assertThat(stuck.size()).as("the run waits in an incident: " + stuck + probe(key)).isZero();
                // the scenario's connector outcomes (e.g. the bill type resolved by the catalogue): proves the
                // process data reached the connectors instead of running on defaults
                JsonNode vars = t.call("GET", "/api/v1/process-instances/" + pid + "/variables", null);
                biz.path("expectVariables").fields().forEachRemaining(e -> assertThat(vars.path(e.getKey()))
                        .as(e.getKey() + " in " + vars).isEqualTo(e.getValue()));
                return;
            }
            for (JsonNode task : tasks) {
                String tid = task.path("id").asText();
                // K3: only the holder (or an unassigned candidate) may act; the officer takes the task first
                t.call("POST", "/api/v1/tasks/" + tid + "/claim", null);
                Map<String, Object> done = new LinkedHashMap<>();
                done.put("payloadHash", t.call("GET", "/api/v1/tasks/" + tid, null).path("payloadHash").asText());
                done.put("decision", biz.path("decision").asText("APPROVED"));
                done.put("variables", biz.path("taskVariables"));
                t.call("POST", "/api/v1/tasks/" + tid + "/complete", done);
            }
            Thread.sleep(500);
        }
        throw new AssertionError("process " + pid + " did not finish");
    }
    @org.springframework.beans.factory.annotation.Autowired
    org.operaton.bpm.engine.ProcessEngine probeEngine;

    /** DIAG ONLY, never merged: states and reason codes by allow-list, no other variable value. */
    private String probe(String key) {
        StringBuilder b = new StringBuilder("\nAT-PROBE begin");
        try {
            org.operaton.bpm.engine.HistoryService h = probeEngine.getHistoryService();
            for (var p : h.createHistoricProcessInstanceQuery().processInstanceBusinessKey(key).list()) {
                b.append("\nAT-PROBE process=").append(p.getProcessDefinitionKey()).append(" state=").append(p.getState());
                for (var a : h.createHistoricActivityInstanceQuery().processInstanceId(p.getId()).list()) {
                    if (a.getActivityType().toLowerCase().contains("endevent") || a.getEndTime() == null) {
                        b.append("\nAT-PROBE   activity=").append(a.getActivityId()).append(" type=").append(a.getActivityType())
                                .append(a.getEndTime() == null ? " open" : " ended");
                    }
                }
                for (String name : java.util.List.of("caseState", "state", "payeeStatus", "payeeReasonCode",
                        "rejectionReasonCodes", "docValid", "clsValid", "dupStatus", "fundsStatus", "paoOutcome",
                        "routingDecisionKey")) {
                    for (var v : h.createHistoricVariableInstanceQuery().processInstanceId(p.getId()).variableName(name).list()) {
                        b.append("\nAT-PROBE   ").append(name).append('=').append(masked(String.valueOf(v.getValue())));
                    }
                }
                for (var i : h.createHistoricIncidentQuery().processInstanceId(p.getId()).list()) {
                    b.append("\nAT-PROBE   incident activity=").append(i.getActivityId()).append(" message=")
                            .append(masked(i.getIncidentMessage()));
                }
                for (var j : h.createHistoricJobLogQuery().processInstanceId(p.getId()).failureLog().list()) {
                    b.append("\nAT-PROBE   failed job activity=").append(j.getActivityId());
                    String[] trace = String.valueOf(h.getHistoricJobLogExceptionStacktrace(j.getId())).split("\n");
                    b.append("\nAT-PROBE     exception=").append(masked(trace[0]));
                    for (String line : trace) {
                        if (line.startsWith("Caused by")) {
                            b.append("\nAT-PROBE     ").append(masked(line));
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            b.append("\nAT-PROBE failed ").append(masked(e.toString()));
        }
        return b.append("\nAT-PROBE end").toString();
    }

    private static String masked(String s) {
        return s == null ? "null" : s.replaceAll("[0-9]{6,}", "<digits>");
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
        onboard(s.path("request"));
        String tenant = s.at("/request/tenant/id").asText();
        JsonNode profile = maker().call("GET", "/api/v1/config/profiles?scope=" + tenant, null);
        assertThat(profile.at("/data" + s.at("/expect/pointer").asText()).asText()).as("resolved profile carries the override")
                .isEqualTo(s.at("/expect/value").asText());
        String parent = s.at("/request/tenant/parentId").asText();
        JsonNode parentProfile = maker().call("GET", "/api/v1/config/profiles?scope=" + parent, null);
        assertThat(parentProfile.at("/data" + s.at("/expect/pointer").asText()).asText()).as("parent untouched")
                .isNotEqualTo(s.at("/expect/value").asText());
    }

    @Test
    @Order(3)
    void at3PrivateHospital() throws Exception {
        // AT-3 (reachesWaitState false): owner decision 2026-11-10, ACCEPTANCE-PLUG-AND-PLAY.md L4–L6
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
        JsonNode plugins = maker().call("GET", "/api/v1/admin/plugins", null);
        for (JsonNode p : plugins) {
            loaded |= pluginId.equals(p.path("id").asText()) && "LOADED".equals(p.path("status").asText())
                    && SignedExamplePlugin.KEY.equals(p.path("signedBy").asText(""));
        }
        // AT-6 set-up is automated (SignedExamplePlugin): the signed jar must be loaded, verified by the run's key
        assertThat(loaded).as("signed example plugin not LOADED (signedBy " + SignedExamplePlugin.KEY + "): " + plugins)
                .isTrue();
        onboard(s.path("request"));
        business(s);
    }

    @Test
    @Order(7)
    @SuppressWarnings("unchecked")
    void at7ExportImportDiff() throws Exception {
        JsonNode s = scenario("AT-7");
        String source = s.at("/source").asText();
        boolean live = false;
        for (JsonNode run : maker().send("GET", "/api/v1/onboarding/runs?tenant=" + source, null).body()) {
            live |= "LIVE".equals(run.path("status").asText());
        }
        // a run that stopped before GO_LIVE has no ACTIVE profile to export (AT-1 reports that failure itself)
        assumeTrue(live, "AT-1 did not bring its tenant LIVE; AT-7 needs it");
        JsonNode exported = maker().call("GET", "/api/v1/onboarding/tenants/by-tenant/bundle?tenant=" + source, null);
        assertThat(exported.path("signature").isMissingNode()).as("the bundle must be signed").isFalse();
        Map<String, Object> imp = AccHttp.JSON.convertValue(s.path("import"), Map.class);
        imp.put("bundle", exported);
        imp.put("dryRun", true);
        JsonNode dry = maker().call("POST", "/api/v1/onboarding/bundles/import", imp);
        assertThat(dry.at("/dryRun/ok").asBoolean()).as(dry.toString()).isTrue();
        imp.put("dryRun", false);
        goLive(maker().call("POST", "/api/v1/onboarding/bundles/import", imp).at("/run/id").asText());

        Map<String, Object> tampered = AccHttp.JSON.convertValue(exported, Map.class);
        ((Map<String, Object>) tampered.get("body")).put("sourceTenant", "tampered");
        imp.put("bundle", tampered);
        imp.put("dryRun", true);
        maker().call("POST", "/api/v1/onboarding/bundles/import", imp, 422);

        JsonNode target = maker().call("GET", "/api/v1/onboarding/tenants/by-tenant/bundle?tenant=" + s.at("/import/targetTenant").asText(), null);
        JsonNode diff = maker().call("POST", "/api/v1/onboarding/bundles/diff", Map.of("left", exported, "right", target));
        for (JsonNode e : diff) {
            String path = e.path("path").asText();
            boolean allowed = false;
            for (JsonNode a : s.path("allowedDifferences")) {
                allowed |= path.contains(a.asText());
            }
            assertThat(allowed).as("unexpected difference: " + e).isTrue();
        }
    }
}
