// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.deploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.history.HistoricActivityInstance;
import org.operaton.bpm.engine.history.HistoricVariableInstance;
import org.operaton.bpm.engine.runtime.Incident;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Register L-2: GIA-0001 (Rs 25 lakh on head 2210-00-101-01-00-31) must not end at {@code bl_hold} of the bill once
 * the funds are distributed through every stage. On a server started from the demo fragment's settings in production
 * mode, with the packs of GOI.FIN deployed, seeded and funded exactly as the live sequence does (step 3a of
 * {@link DemoLoaderSequenceAgainstServerTest}: ALLOCATE at the first stage, then DISTRIBUTE through every stage to
 * DDO-1 at the consuming stage), the funds step of the shipped bill model is run with the values GIA-0001 brings to
 * it: {@code bl_funds} asks {@code ledger.available} (bound to the tenant's real ledger, no simulator in production
 * mode) with {@code ledgerId, headOfAccount, ddoCode}, and the gateway {@code bl_fundsGw} decides between
 * {@code bl_hold} ({@code fundsAvailable < amount}), the rejection (availability not confirmed) and
 * {@code bl_encumber}.
 * <p>
 * The instance is started at {@code bl_funds_clear}, the step before the funds query, because on this code the
 * whole journey cannot reach it yet: the sanction of the GIA case waits for seat CAA, which no persona holds, and
 * the bill stops earlier at the duplicate check (register E-26, lane SANDBOX-BILL). The variables are those of the
 * data set's case plus the three defaults {@code bl_defaults} gives a bill that does not carry them
 * ({@code ledgerId=BUDGET}, {@code ddoCode=DDO-1}; the GIA case names no DDO code).
 * <p>
 * The control shows the test can tell: the same step for a head on which nothing was distributed does not reach
 * {@code bl_encumber}.
 */
class GiaFundsThroughEveryStageAgainstServerTest {

    private static final String TENANT = "GOI.FIN";
    private static final Path DATA = Path.of("..", "deploy", "presentation", "data", "ifms-jaipur-fy2026-27.json").toAbsolutePath().normalize();
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;

    @AfterAll
    static void releaseRealm() {
        DemoComposeFragmentStartupTest.RealmIdp.release();
    }

    @Test
    void aGrantFundedThroughEveryStagePassesTheFundsCheckOfTheBill() throws Exception {
        DemoComposeFragmentStartupTest.Keys keys = new DemoComposeFragmentStartupTest.Keys(dir);
        Map<String, String> bootstrap = Map.of("SANGAM_KEYS_BOOTSTRAP", "true",
                "SANGAM_KEYS_FINGERPRINT_EXPORT", dir.resolve("key-fingerprints.txt").toString(),
                "SANGAM_KEYS_ATTESTATION", "", "SANGAM_KEYS_CUSTODIAN_PUBLIC_KEY", "");
        List<String> settings = new ArrayList<>(DemoLoaderSequenceAgainstServerTest.PACKS_AT_START);
        settings.add("--sangam.compliance.log-archive.root=" + dir.resolve("compliance-logs"));
        try (ConfigurableApplicationContext ctx = DemoComposeFragmentStartupTest.start(keys, bootstrap, settings, "h2", "oidc")) {
            DemoLoaderSequenceAgainstServerTest.sequence(ctx, "L-2: seed and funds", List.of("2c seed", "3a funds"));
            ProcessEngine engine = ctx.getBean(ProcessEngine.class);

            JsonNode gia = caseOf("GIA-0001");
            Map<String, Object> funded = variables(gia);
            funded.putIfAbsent("ledgerId", "BUDGET");
            funded.putIfAbsent("ddoCode", "DDO-1");
            Outcome grant = fundsStep(engine, "LOADER-WALK/L-2/GIA-0001", funded);
            System.out.println("LOADER-WALK | L-2 | GIA-0001 head " + funded.get("headOfAccount") + " holder " + funded.get("ddoCode")
                    + " amount " + funded.get("amount") + " | " + grant);
            assertEquals("COMPLETED", grant.fundsStatus, "ledger.available answered: " + grant);
            assertFalse(grant.reached.contains("bl_hold"), "GIA-0001 is not held for budget: " + grant);
            assertTrue(grant.reached.contains("bl_encumber"), "GIA-0001 goes on to the encumbrance: " + grant);

            // control: the same amount on a head of the same ledger on which nothing was allocated or distributed
            Map<String, Object> unfunded = new LinkedHashMap<>(funded);
            unfunded.put("headOfAccount", "2210-00-101-01-00-99");
            Outcome control = fundsStep(engine, "LOADER-WALK/L-2/control", unfunded);
            System.out.println("LOADER-WALK | L-2 | control head " + unfunded.get("headOfAccount") + " holder " + unfunded.get("ddoCode")
                    + " amount " + unfunded.get("amount") + " | " + control);
            assertFalse(control.reached.contains("bl_encumber"), "a head without funds does not pass the funds check: " + control);

            // for the record, not asserted: a contingent bill of the data set as it is (its own head and DDO code)
            Map<String, Object> bill = variables(caseOf("BILL-0001"));
            bill.putIfAbsent("ledgerId", "BUDGET");
            bill.putIfAbsent("ddoCode", "DDO-1");
            Outcome contingent = fundsStep(engine, "LOADER-WALK/L-2/BILL-0001", bill);
            System.out.println("LOADER-WALK | L-2 | INFO BILL-0001 head " + bill.get("headOfAccount") + " holder " + bill.get("ddoCode")
                    + " amount " + bill.get("amount") + " | " + contingent);
        } finally {
            org.sangam.packs.sim.DemoSandbox.install(java.util.Set.of());
        }
    }

    private record Outcome(String fundsStatus, Object fundsAvailable, Object reasonCode, TreeSet<String> reached, List<String> incidents) {
    }

    /**
     * Starts the shipped bill at the step before its funds query, waits until the gateway after the query has decided
     * (or the step failed), reads what happened from the engine's history and removes the instance again.
     */
    private static Outcome fundsStep(ProcessEngine engine, String businessKey, Map<String, Object> variables) throws Exception {
        ProcessInstance pi = engine.getRuntimeService().createProcessInstanceByKey("sangam-ifms-bill")
                .processDefinitionTenantId(TENANT).businessKey(businessKey).setVariables(variables)
                .startBeforeActivity("bl_funds_clear").execute();
        TreeSet<String> reached = new TreeSet<>();
        List<String> incidents = new ArrayList<>();
        long until = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < until) {
            reached.clear();
            for (HistoricActivityInstance a : engine.getHistoryService().createHistoricActivityInstanceQuery()
                    .processInstanceId(pi.getId()).list()) {
                reached.add(a.getActivityId());
            }
            incidents.clear();
            for (Incident i : engine.getRuntimeService().createIncidentQuery().processInstanceId(pi.getId()).list()) {
                incidents.add(i.getActivityId() + ": " + i.getIncidentMessage());
            }
            if (reached.contains("bl_hold") || reached.contains("bl_encumber") || reached.contains("bl_rjFunds_code") || !incidents.isEmpty()) {
                break;
            }
            Thread.sleep(500);
        }
        Outcome out = new Outcome(String.valueOf(variable(engine, pi.getId(), "fundsStatus")), variable(engine, pi.getId(), "fundsAvailable"),
                variable(engine, pi.getId(), "fundsReasonCode"), new TreeSet<>(reached), List.copyOf(incidents));
        if (engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(pi.getId()).count() > 0) {
            engine.getRuntimeService().deleteProcessInstance(pi.getId(), "L-2 funds check of the test", true, true);
        }
        return out;
    }

    private static Object variable(ProcessEngine engine, String instance, String name) {
        HistoricVariableInstance v = engine.getHistoryService().createHistoricVariableInstanceQuery()
                .processInstanceId(instance).variableName(name).singleResult();
        return v == null ? null : v.getValue();
    }

    private static JsonNode caseOf(String id) throws Exception {
        for (JsonNode c : JSON.readTree(DATA.toFile()).path("cases")) {
            if (id.equals(c.path("id").asText())) {
                return c;
            }
        }
        throw new AssertionError("no case " + id + " in the data set");
    }

    private static Map<String, Object> variables(JsonNode c) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : c.path("variables").properties()) {
            JsonNode v = e.getValue();
            out.put(e.getKey(), v.isIntegralNumber() ? (Object) v.longValue() : v.isBoolean() ? (Object) v.booleanValue() : v.asText());
        }
        return out;
    }
}
