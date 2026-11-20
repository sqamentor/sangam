// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.deploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EV-M-0932, the rebuild gate: the whole live sequence of demo.ifms.dev against a server started from the demo
 * fragment's settings in production mode, on a tenant exactly as there (GOI.FIN by FirstAdminBootstrap only, no
 * onboarding, profile null, level OFFICE defined, the earlier loader's org unit refusal stored and compacted). Every
 * step goes through the REST API or the shipped scripts, unchanged, with tokens shaped like realm oneifms's:
 * <ol>
 *   <li>2c: {@code seed_presentation.py} (level types, org unit, roles, 10 seats, 8 postings);</li>
 *   <li>3a: Rs 25 lakh on ledger BUDGET, FY 2026-27, head 2210-00-101-01-00-31: ALLOCATE at the first stage, then
 *       DISTRIBUTE through every stage to DDO-1 at the consuming stage ({@code POST /ledger/by-ledger/operations});</li>
 *   <li>3b: {@code demo_preflight.py smoke} (BILL-0005 waits at pc_dealing, GPF-0002 at sn_sanction);</li>
 *   <li>4a: {@code load_presentation_data.py}: the 15 cases of the data set, each in its expected end state.</li>
 * </ol>
 * Each step is reported as one {@code DEMO-SEQUENCE} line (step, PASS or FAIL, evidence); a step runs even when an
 * earlier one failed, so one run shows every gap.
 * <p>
 * {@link #OPEN_GAPS}: the steps that are known not to pass yet, each with its recorded cause. Such a step is run and
 * reported but does not fail the test; when it passes, the test fails until the step is taken off the list, so the
 * list cannot go stale. Every other step must pass.
 */
class DemoLoaderSequenceAgainstServerTest {

    /**
     * Steps that do not pass on this code, found on 2026-11-20 (EV-M-0932), each needing a decision outside this lane:
     * <ul>
     *   <li>as deployed, no pack is deployed for GOI.FIN (PacksWiring: deploy-on-start is on only under profile h2;
     *       FirstAdminBootstrap creates the tenant without the pack hook), so no case starts (404);</li>
     *   <li>with the packs deployed: every bill stops at an incident because nothing answers {@code ifms.duplicateBill}
     *       in production mode (CONNECTOR_NOT_CONFIGURED; the sandbox answers payment rails only), and the tasks of
     *       seats no persona holds (CAA, GRO, PIO-1) are done by nobody.</li>
     * </ul>
     */
    static final java.util.Set<String> OPEN_GAPS = java.util.Set.of("3b smoke", "4a cases");

    private static final String TENANT = "GOI.FIN";
    private static final Path PRESENTATION = Path.of("..", "deploy", "presentation").toAbsolutePath().normalize();
    private static final Path DATA = PRESENTATION.resolve("data").resolve("ifms-jaipur-fy2026-27.json");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    /** The stages of ledger BUDGET, top first (profile GOI > IN > REGIME-WESTMINSTER-SUPPLY); the last one consumes. */
    private static final List<String> STAGES = List.of("APPROPRIATION", "ALLOCATION", "RELEASE", "AUTHORISATION", "SUB_ALLOTMENT");
    private static final String HEAD = "2210-00-101-01-00-31";
    private static final String RUPEES = "2500000.00";
    /** The settings that deploy the packs for GOI.FIN at start (configuration only, no code change). */
    static final List<String> PACKS_AT_START = List.of("--sangam.packs.deploy-on-start=true", "--sangam.packs.tenants=" + TENANT);

    @TempDir
    Path dir;

    @AfterAll
    static void releaseRealm() {
        DemoComposeFragmentStartupTest.RealmIdp.release();
    }

    /**
     * The server as deployed on demo.ifms.dev. The fragment's profiles are {@code postgres,oidc}; this class runs on
     * the H2 file database, where {@code sangam.packs.deploy-on-start} would default to true (PacksWiring: on only
     * under profile h2), so it is set to the value the fragment's profiles give: false.
     */
    @Test
    void theLiveSequenceOnTheServerAsDeployed() throws Exception {
        run(List.of("--sangam.packs.deploy-on-start=false"));
    }

    /** The same, with the packs deployed for GOI.FIN at start. */
    @Test
    void theLiveSequenceWithThePacksDeployedForTheTenantAtStart() throws Exception {
        run(PACKS_AT_START);
    }

    private void run(List<String> settings) throws Exception {
        DemoComposeFragmentStartupTest.Keys keys = new DemoComposeFragmentStartupTest.Keys(dir);
        Map<String, String> bootstrap = Map.of("SANGAM_KEYS_BOOTSTRAP", "true",
                "SANGAM_KEYS_FINGERPRINT_EXPORT", dir.resolve("key-fingerprints.txt").toString(),
                "SANGAM_KEYS_ATTESTATION", "", "SANGAM_KEYS_CUSTODIAN_PUBLIC_KEY", "");
        List<String> all = new ArrayList<>(settings);
        // the fragment does not set the log archive; keep this test's segments out of the module directory
        all.add("--sangam.compliance.log-archive.root=" + dir.resolve("compliance-logs"));
        try (ConfigurableApplicationContext ctx = DemoComposeFragmentStartupTest.start(keys, bootstrap, all, "h2", "oidc")) {
            sequence(ctx, String.join(" ", settings), List.of("2c seed", "3a funds", "3b smoke", "4a cases"));
        } finally {
            org.sangam.packs.sim.DemoSandbox.install(java.util.Set.of());
        }
    }

    /**
     * The named steps against a started server, in the live order. Fails with the status list when a step that must
     * pass failed, or when a step of {@link #OPEN_GAPS} passed (take it off the list then).
     */
    static void sequence(ConfigurableApplicationContext ctx, String label, List<String> steps) throws Exception {
        String api = "http://localhost:" + ctx.getEnvironment().getRequiredProperty("local.server.port") + "/api/v1";
        Map<String, Step> all = new LinkedHashMap<>();
        all.put("2c seed", () -> {
            PresentationSeedAgainstServerTest.seedAndVerify(ctx, PresentationSeedAgainstServerTest.Earlier.AS_ON_LIVE);
            return "10 seats, 8 postings, a rerun changes nothing";
        });
        all.put("3a funds", () -> fund(api));
        all.put("3b smoke", () -> {
            PresentationSeedAgainstServerTest.Seed s = PresentationSeedAgainstServerTest.script(
                    PRESENTATION.resolve("demo_preflight.py"), List.of("smoke"), personaEnv(api));
            assertEquals(0, s.exit(), "demo_preflight.py smoke:\n" + s.out());
            return "both smoke cases wait at their task";
        });
        all.put("4a cases", () -> {
            PresentationSeedAgainstServerTest.Seed s = PresentationSeedAgainstServerTest.script(
                    PRESENTATION.resolve("load_presentation_data.py"), List.of(), personaEnv(api));
            String states = endStates(api, s.out());
            assertEquals(0, s.exit(), "load_presentation_data.py:\n" + s.out());
            return states;
        });
        List<String> status = new ArrayList<>();
        List<String> wrong = new ArrayList<>();
        for (String name : steps) {
            boolean passed = step(status, name, all.get(name));
            if (passed == OPEN_GAPS.contains(name)) {
                wrong.add(passed ? name + " passes now: take it off OPEN_GAPS" : name + " failed");
            }
        }
        status.add("DEMO-SEQUENCE | connectors | INFO | " + connectors(ctx));
        if (steps.contains("4a cases")) {
            status.add("DEMO-SEQUENCE | seat header | INFO | " + seatHeaderProbe(api));
        }
        String report = "DEMO-SEQUENCE [" + label + "] open gaps " + new java.util.TreeSet<>(OPEN_GAPS) + "\n" + String.join("\n", status);
        System.out.println(report);
        assertTrue(wrong.isEmpty(), wrong + "\n" + report);
    }

    /**
     * The connectors the packs of the data set's processes declare (their own pack, plus core, ifms-bill and
     * ifms-pao-chain, which they call), and who answers each for GOI.FIN on this server: a bound integration
     * connector, the sandbox simulator (DemoSandbox: payment rails and the statement), or nobody. In production
     * mode an unbound connector fails with CONNECTOR_NOT_CONFIGURED (no simulator fallback, IntegrationWiring).
     */
    static String connectors(ConfigurableApplicationContext ctx) throws Exception {
        Path packs = Path.of("..", "sangam-packs", "src", "main", "resources", "packs").toAbsolutePath().normalize();
        java.util.Set<String> processes = new java.util.TreeSet<>();
        JSON.readTree(DATA.toFile()).path("cases").forEach(c -> processes.add(c.path("process").asText()));
        java.util.Set<String> ids = new java.util.TreeSet<>();
        try (var dirs = java.nio.file.Files.list(packs)) {
            for (Path pack : dirs.filter(java.nio.file.Files::isDirectory).toList()) {
                boolean used = List.of("core", "ifms-bill", "ifms-pao-chain").contains(pack.getFileName().toString());
                try (var files = java.nio.file.Files.list(pack)) {
                    for (Path f : files.filter(x -> x.toString().endsWith(".bpmn")).toList()) {
                        String xml = java.nio.file.Files.readString(f, StandardCharsets.UTF_8);
                        used |= processes.stream().anyMatch(p -> xml.contains("<process id=\"" + p + "\""));
                    }
                }
                if (used && java.nio.file.Files.exists(pack.resolve("pack.json"))) {
                    JSON.readTree(pack.resolve("pack.json").toFile()).path("connectors").forEach(c -> ids.add(c.path("id").asText()));
                }
            }
        }
        var bindings = ctx.getBean(org.sangam.integration.packs.PackConnectorBindings.class);
        List<String> bound = new ArrayList<>();
        List<String> sandbox = new ArrayList<>();
        List<String> nobody = new ArrayList<>();
        for (String id : ids) {
            boolean sim = org.sangam.packs.sim.DemoSandbox.isSandbox(TENANT)
                    && (org.sangam.packs.sim.DemoSandbox.PAYMENT_CONNECTORS.contains(id)
                    || org.sangam.packs.sim.DemoSandbox.STATEMENT_CONNECTORS.contains(id));
            (sim ? sandbox : bindings.binding(TENANT, id).isPresent() ? bound : nobody).add(id);
        }
        return "bound " + bound + "; sandbox " + sandbox + "; NOT CONFIGURED " + nobody;
    }

    /**
     * A fact for the report, not an assertion (the ruling is not this lane's): realm oneifms's tokens carry no seats
     * claim, and PrincipalAttributeFilter checks {@code X-Sangam-Seat} only against that claim. This reads the open
     * tasks of BA-0002 (it waits at sn_sanction, seat PAO-1) as the PAO-1 holder and as Kavita Sharma (DDO-1), each
     * naming seat PAO-1 in the header, and says what each was shown.
     */
    private static String seatHeaderProbe(String api) {
        try {
            String bk = URLEncoder.encode("JPR-DDO-0420/BA/2026-27/0002", StandardCharsets.UTF_8);
            List<String> seen = new ArrayList<>();
            for (String[] who : new String[][]{{"sunita.rathore", "PAO"}, {"kavita.sharma", "DDO"}}) {
                HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(api + "/tasks?businessKey=" + bk))
                        .header("Authorization", "Bearer " + PresentationSeedAgainstServerTest.token(who[0], List.of(who[1], "sangam-user")))
                        .header("X-Sangam-Seat", "PAO-1").GET().build(), HttpResponse.BodyHandlers.ofString());
                JsonNode body = r.statusCode() == 200 ? JSON.readTree(r.body()) : null;
                JsonNode list = body == null ? null : body.isArray() ? body : body.path("items");
                List<String> keys = new ArrayList<>();
                if (list != null) {
                    list.forEach(t -> keys.add(t.path("taskDefinitionKey").asText()));
                }
                seen.add(who[0] + " with X-Sangam-Seat PAO-1: " + r.statusCode() + " " + keys);
            }
            return String.join("; ", seen);
        } catch (Exception e) {
            return "not read: " + e;
        }
    }

    /** Whether the loader printed {@code waits at <task>} under the case's own heading. */
    private static boolean waitsAt(String loaderOut, String caseId, String task) {
        boolean mine = false;
        for (String line : loaderOut.split("\\R")) {
            if (line.startsWith("  ") && !line.startsWith("    ")) {
                mine = line.startsWith("  " + caseId + " ");
            } else if (mine && line.strip().equals("waits at " + task)) {
                return true;
            }
        }
        return false;
    }

    private interface Step {
        String run() throws Exception;
    }

    private static boolean step(List<String> status, String name, Step s) {
        try {
            status.add("DEMO-SEQUENCE | " + name + " | PASS | " + s.run());
            return true;
        } catch (Exception | AssertionError e) {
            status.add("DEMO-SEQUENCE | " + name + " | FAIL | " + String.valueOf(e.getMessage()).strip());
            return false;
        }
    }

    /** 3a: ALLOCATE at the first stage, then one DISTRIBUTE per further stage; DDO-1 holds the consuming stage. */
    private static String fund(String api) throws Exception {
        String admin = PresentationSeedAgainstServerTest.token("rajendra.sharma", List.of("sangam-tenant-admin", "sangam-user"));
        String period = JSON.readTree(DATA.toFile()).path("fiscalYear").asText();
        assertEquals("2026-27", period);
        Map<String, Object> at = account(period, "-", STAGES.get(0));
        operation(api, admin, "ALLOCATE", at, null);
        for (int i = 1; i < STAGES.size(); i++) {
            Map<String, Object> next = account(period, i == STAGES.size() - 1 ? "DDO-1" : "-", STAGES.get(i));
            operation(api, admin, "DISTRIBUTE", at, next);
            at = next;
        }
        JsonNode accounts = PresentationSeedAgainstServerTest.get(api + "/ledger/by-ledger/accounts?ledger=BUDGET&period=" + period
                + "&line=" + HEAD + "&holder=DDO-1&stage=" + STAGES.get(STAGES.size() - 1), admin);
        assertEquals(1, accounts.size(), accounts.toString());
        assertEquals(0, new java.math.BigDecimal(RUPEES).compareTo(new java.math.BigDecimal(accounts.get(0).path("available").asText())),
                "Rs 25 lakh available to DDO-1: " + accounts);
        return "ALLOCATE + " + (STAGES.size() - 1) + " DISTRIBUTE applied; available to DDO-1 at " + STAGES.get(STAGES.size() - 1)
                + " = " + accounts.get(0).path("available").asText() + " " + accounts.get(0).path("currency").asText();
    }

    private static Map<String, Object> account(String period, String holder, String stage) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("period", period);
        a.put("line", HEAD);
        a.put("holder", holder);
        a.put("stage", stage);
        return a;
    }

    private static void operation(String api, String admin, String op, Map<String, Object> account, Map<String, Object> target)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("operation", op);
        body.put("account", account);
        if (target != null) {
            body.put("target", target);
        }
        body.put("amount", RUPEES);
        body.put("reference", "BUDGET/" + account.get("period") + "/" + HEAD + "/" + op + "/" + account.get("stage")
                + (target == null ? "" : ">" + target.get("stage")));
        body.put("auditRef", "Budget distribution order, FY 2026-27");
        // the three flags are required booleans of the request (400 VALIDATION_FAILED when one is missing)
        body.put("fromCommitment", false);
        body.put("fromEncumbrance", false);
        body.put("holdIfShort", false);
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(api + "/ledger/by-ledger/operations?ledger=BUDGET"))
                .header("Authorization", "Bearer " + admin).header("Content-Type", "application/json")
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), op + " " + account + ": " + r.body());
        assertTrue(JSON.readTree(r.body()).path("applied").asBoolean(), op + " " + account + " applied: " + r.body());
    }

    /** One token per persona (its realm roles, as the seed's plan lists them), under the name the loader reads. */
    private static Map<String, String> personaEnv(String api) throws Exception {
        PresentationSeedAgainstServerTest.Seed plan = PresentationSeedAgainstServerTest.script(
                PRESENTATION.resolve("seed_presentation.py"), List.of("--dry-run"), Map.of());
        assertEquals(0, plan.exit(), plan.out());
        Map<String, String> env = new LinkedHashMap<>();
        env.put("SANGAM_API_BASE", api);
        // how long a case may show no task before the loader reads it as settled (its default is 30 s)
        env.put("SANGAM_PRESENTATION_SETTLE_SECONDS", "12");
        Matcher m = Pattern.compile("keycloak \\S+: user (\\S+) roles (\\S+) seats").matcher(plan.out());
        while (m.find()) {
            env.put("SANGAM_PRESENTATION_TOKEN_" + m.group(1).toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_'),
                    PresentationSeedAgainstServerTest.token(m.group(1), List.of(m.group(2).split(","))));
        }
        assertTrue(env.size() > 9, "persona tokens: " + env.keySet());
        return env;
    }

    /** 4a: each case of the data set against its target (COMPLETED: ended; every other target: still open). */
    private static String endStates(String api, String loaderOut) throws Exception {
        String admin = PresentationSeedAgainstServerTest.token("rajendra.sharma", List.of("sangam-tenant-admin", "sangam-user"));
        List<String> wrong = new ArrayList<>();
        int cases = 0;
        for (JsonNode c : JSON.readTree(DATA.toFile()).path("cases")) {
            cases++;
            String id = c.path("id").asText();
            JsonNode found = PresentationSeedAgainstServerTest.get(api + "/process-instances?businessKey="
                    + URLEncoder.encode(c.path("businessKey").asText(), StandardCharsets.UTF_8), admin);
            // the case's own instance: the one no other instance called (its called processes share the business key)
            List<JsonNode> roots = new ArrayList<>();
            (found.isArray() ? found : found.path("items")).forEach(x -> {
                if (x.path("superProcessInstanceId").isNull() || x.path("superProcessInstanceId").isMissingNode()) {
                    roots.add(x);
                }
            });
            if (roots.size() != 1) {
                wrong.add(id + ": " + roots.size() + " instances");
                continue;
            }
            // the list leaves activeActivityIds empty; the instance itself names where it stands
            JsonNode i = PresentationSeedAgainstServerTest.get(api + "/process-instances/" + roots.get(0).path("id").asText(), admin);
            String target = c.path("target").asText();
            boolean ended = i.path("ended").asBoolean();
            String stopAt = c.path("stopAt").asText("");
            // a RUNNING case waits at its task inside a called process; the loader's own line says it saw the task
            boolean right = "COMPLETED".equals(target) ? ended
                    : !ended && (stopAt.isEmpty() || loaderOut.contains(id + " ") && waitsAt(loaderOut, id, stopAt));
            if (!right) {
                wrong.add(id + ": target " + target + (stopAt.isEmpty() ? "" : " at " + stopAt) + ", is state="
                        + i.path("state").asText() + " ended=" + ended + " at " + i.path("activeActivityIds"));
            }
        }
        assertEquals(15, cases, "the data set's cases");
        assertTrue(wrong.isEmpty(), wrong.size() + " of " + cases + " cases not in their end state: " + wrong
                + "\nloader output:\n" + loaderOut);
        return cases + " of " + cases + " cases in their expected end state";
    }
}
