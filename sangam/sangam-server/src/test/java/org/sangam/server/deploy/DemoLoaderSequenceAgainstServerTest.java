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
        if (steps.contains("3b smoke") || steps.contains("4a cases")) {
            // register E-25 point 4: one line per case, so the report lists every case
            stops(ctx).forEach(s -> status.add("DEMO-SEQUENCE | stop | INFO | " + s));
        }
        if (steps.contains("4a cases") && !step(status, "ui seats", () -> uiSeats(ctx, api))) {
            // register E-24 follow-up 1: never an open gap
            wrong.add("ui seats failed");
        }
        if (steps.contains("4a cases") && !step(status, "seat header", () -> seatHeaderCheck(api))) {
            // register E-24: never an open gap
            wrong.add("seat header failed");
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
     * Register E-25 point 4: where each case of the data set (and the two smoke cases, which are among them) stands,
     * read from the engine. Per case: its target, then every open process instance of its tree (the case's own and the
     * processes it called) with the activities it waits at, the open tasks with their seats, every incident and
     * failed job with its message, and every connector step whose committed outcome is not COMPLETED: the variables
     * {@code <prefix>Status} and {@code <prefix>ReasonCode} that ConnectorCallDelegate writes, with the connector id the
     * instance's own deployed model gives that prefix ({@link #connectorsByPrefix}); for the core execution step
     * (prefix {@code op}) the connector is the instance's {@code executionConnector}. {@code waits for connector} names
     * the connector ids found that way, those a message cites, and {@code docs.issue} for a document that could not be
     * issued (DocumentDelegate). A failed job is rolled back to its last asynchronous point, so its activity is where
     * the job starts, not always where the connector is called. A case that was never started, or has ended, says so.
     */
    static List<String> stops(ConfigurableApplicationContext ctx) throws Exception {
        org.operaton.bpm.engine.ProcessEngine engine = ctx.getBean(org.operaton.bpm.engine.ProcessEngine.class);
        java.util.Set<String> known = new java.util.TreeSet<>();
        Matcher ids = Pattern.compile("[a-z][A-Za-z0-9]*\\.[A-Za-z][A-Za-z0-9]*").matcher(connectors(ctx));
        while (ids.find()) {
            known.add(ids.group());
        }
        List<String> out = new ArrayList<>();
        for (JsonNode c : JSON.readTree(DATA.toFile()).path("cases")) {
            String bk = c.path("businessKey").asText();
            String head = c.path("id").asText() + " (" + c.path("process").asText() + ", target " + c.path("target").asText()
                    + (c.path("stopAt").asText("").isEmpty() ? "" : " at " + c.path("stopAt").asText()) + "): ";
            List<org.operaton.bpm.engine.runtime.ProcessInstance> roots = engine.getRuntimeService()
                    .createProcessInstanceQuery().tenantIdIn(TENANT).processInstanceBusinessKey(bk).rootProcessInstances().list();
            if (roots.isEmpty()) {
                long ended = engine.getHistoryService().createHistoricProcessInstanceQuery().tenantIdIn(TENANT)
                        .processInstanceBusinessKey(bk).rootProcessInstances().finished().count();
                out.add(head + (ended > 0 ? "ended" : "not started"));
                continue;
            }
            List<String> at = new ArrayList<>();
            java.util.Set<String> waitsFor = new java.util.TreeSet<>();
            for (org.operaton.bpm.engine.runtime.ProcessInstance root : roots) {
                for (org.operaton.bpm.engine.runtime.ProcessInstance pi : engine.getRuntimeService()
                        .createProcessInstanceQuery().rootProcessInstanceId(root.getId()).list()) {
                    String key = engine.getRepositoryService().getProcessDefinition(pi.getProcessDefinitionId()).getKey();
                    StringBuilder sb = new StringBuilder(key).append(" at ")
                            .append(new java.util.TreeSet<>(engine.getRuntimeService().getActiveActivityIds(pi.getId())));
                    for (org.operaton.bpm.engine.task.Task t : engine.getTaskService().createTaskQuery()
                            .processInstanceId(pi.getId()).list()) {
                        java.util.Set<String> seats = new java.util.TreeSet<>();
                        for (org.operaton.bpm.engine.task.IdentityLink l : engine.getTaskService().getIdentityLinksForTask(t.getId())) {
                            if (l.getGroupId() != null) {
                                seats.add(l.getGroupId());
                            }
                        }
                        sb.append(", open task ").append(t.getTaskDefinitionKey()).append(" for ")
                                .append(t.getAssignee() != null ? t.getAssignee() : seats.toString());
                    }
                    List<String> messages = new ArrayList<>();
                    for (org.operaton.bpm.engine.runtime.Incident i : engine.getRuntimeService().createIncidentQuery()
                            .processInstanceId(pi.getId()).list()) {
                        messages.add("incident " + i.getIncidentType() + " at " + i.getActivityId() + ": " + i.getIncidentMessage());
                    }
                    for (org.operaton.bpm.engine.runtime.Job j : engine.getManagementService().createJobQuery()
                            .processInstanceId(pi.getId()).withException().list()) {
                        messages.add("failed job (retries left " + j.getRetries() + "): " + j.getExceptionMessage());
                    }
                    for (String m : messages) {
                        known.stream().filter(String.valueOf(m)::contains).forEach(waitsFor::add);
                        if (String.valueOf(m).contains("could not be issued")) {
                            waitsFor.add("docs.issue");
                        }
                        String line = String.valueOf(m).replaceAll("\\s+", " ");
                        sb.append(", ").append(line.length() > 260 ? line.substring(0, 260) + "..." : line);
                    }
                    Map<String, Object> variables = engine.getRuntimeService().getVariables(pi.getId());
                    Map<String, String> byPrefix;
                    try (java.io.InputStream model = engine.getRepositoryService().getProcessModel(pi.getProcessDefinitionId())) {
                        byPrefix = connectorsByPrefix(new String(model.readAllBytes(), StandardCharsets.UTF_8));
                    }
                    Map<String, String> open = new java.util.TreeMap<>();
                    variables.forEach((name, value) -> {
                        // a connector step writes both variables (the reason code also when it is null)
                        if (name.endsWith("Status") && value instanceof String status && !"COMPLETED".equals(status)
                                && variables.containsKey(name.substring(0, name.length() - "Status".length()) + "ReasonCode")) {
                            String prefix = name.substring(0, name.length() - "Status".length());
                            String connector = "op".equals(prefix) && variables.get("executionConnector") != null
                                    ? String.valueOf(variables.get("executionConnector")) : byPrefix.get(prefix);
                            open.put(connector == null ? "(prefix " + prefix + ")" : connector,
                                    status + "/" + variables.get(prefix + "ReasonCode"));
                            if (connector != null) {
                                waitsFor.add(connector);
                            }
                        }
                    });
                    if (!open.isEmpty()) {
                        sb.append(", connector steps not completed ").append(open);
                    }
                    if (variables.get("caseState") != null) {
                        sb.append(", case state ").append(variables.get("caseState"));
                    }
                    at.add(sb.toString());
                }
            }
            out.add(head + String.join(" | ", at) + (waitsFor.isEmpty() ? "" : " | waits for connector " + waitsFor));
        }
        return out;
    }

    /**
     * The connector steps of one process model: result prefix to connector id, as ConnectorCallDelegate reads its
     * fields ({@code resultPrefix}, else the last segment of {@code connector}). A connector given as an expression is
     * left out.
     */
    static Map<String, String> connectorsByPrefix(String bpmn) {
        Map<String, String> out = new java.util.TreeMap<>();
        Matcher task = Pattern.compile("<serviceTask\\b[^>]*>(.*?)</serviceTask>", Pattern.DOTALL).matcher(bpmn);
        Pattern field = Pattern.compile("<operaton:field name=\"(connector|resultPrefix)\">\\s*<operaton:string>([^<]*)<");
        while (task.find()) {
            Map<String, String> fields = new LinkedHashMap<>();
            Matcher f = field.matcher(task.group(1));
            while (f.find()) {
                fields.put(f.group(1), f.group(2).strip());
            }
            String connector = fields.get("connector");
            if (connector != null && !connector.contains("${")) {
                out.put(fields.getOrDefault("resultPrefix", connector.substring(connector.lastIndexOf('.') + 1)), connector);
            }
        }
        return out;
    }

    /**
     * Register E-24 (IX.5 rule 1, K7): realm oneifms's tokens carry no seats claim, so the seat a caller names in
     * {@code X-Sangam-Seat} is checked against who holds it on the action date. BA-0002 waits at sn_sanction, seat
     * PAO-1 (when the packs are deployed; as deployed no case exists and the PAO-1 holder's list is empty). The PAO-1
     * holder, Sunita Rathore, naming PAO-1 is shown the seat's tasks. Kavita Sharma holds DDO-1 only: naming PAO-1
     * she is refused the list (403 SEAT_NOT_HELD), and the sanction of the seat's task, sent by her under that header,
     * is refused the same way and the task stays open. It runs last, after every step was judged.
     */
    private static String seatHeaderCheck(String api) throws Exception {
        String bk = URLEncoder.encode("JPR-DDO-0420/BA/2026-27/0002", StandardCharsets.UTF_8);
        List<String> seen = new ArrayList<>();
        HttpResponse<String> holder = seatHeaderGet(api + "/tasks?businessKey=" + bk, "sunita.rathore", "PAO");
        assertEquals(200, holder.statusCode(), "the PAO-1 holder naming PAO-1: " + holder.body());
        JsonNode body = JSON.readTree(holder.body());
        JsonNode list = body.isArray() ? body : body.path("items");
        List<String> keys = new ArrayList<>();
        list.forEach(t -> keys.add(t.path("taskDefinitionKey").asText()));
        seen.add("sunita.rathore (holds PAO-1) with X-Sangam-Seat PAO-1: 200 " + keys);

        HttpResponse<String> other = seatHeaderGet(api + "/tasks?businessKey=" + bk, "kavita.sharma", "DDO");
        String otherCode = other.statusCode() >= 400 ? JSON.readTree(other.body()).path("code").asText() : other.body();
        seen.add("kavita.sharma (holds DDO-1 only) with X-Sangam-Seat PAO-1: " + other.statusCode() + " " + otherCode);
        assertEquals(403, other.statusCode(), String.join("; ", seen));
        assertEquals("SEAT_NOT_HELD", otherCode, String.join("; ", seen));
        if (list.size() == 1) {
            // the same person, the same header: the sanction of seat PAO-1 is not accepted from her
            JsonNode form = JSON.readTree(Path.of("..", "sangam-packs", "src", "main", "resources", "packs", "core",
                    "forms", "sanction.form.json").toAbsolutePath().normalize().toFile());
            Map<String, Object> variables = new LinkedHashMap<>();
            variables.put(form.path("decision").path("variable").asText(),
                    form.path("decision").path("options").get(0).path("value").asText());
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("remarks", "Seat header check of the test.");
            answer.put("variables", variables);
            HttpResponse<String> done = HTTP.send(HttpRequest.newBuilder(
                            URI.create(api + "/tasks/" + list.get(0).path("id").asText() + "/complete"))
                    .header("Authorization", "Bearer " + PresentationSeedAgainstServerTest.token("kavita.sharma", List.of("DDO", "sangam-user")))
                    .header("X-Sangam-Seat", "PAO-1").header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(answer))).build(),
                    HttpResponse.BodyHandlers.ofString());
            String code = done.statusCode() >= 400 ? JSON.readTree(done.body()).path("code").asText() : "";
            seen.add("her completion of that task with " + variables + ": " + done.statusCode() + " " + code);
            assertEquals(403, done.statusCode(), String.join("; ", seen));
            assertEquals("SEAT_NOT_HELD", code, String.join("; ", seen));
            HttpResponse<String> still = seatHeaderGet(api + "/tasks?businessKey=" + bk, "sunita.rathore", "PAO");
            JsonNode after = JSON.readTree(still.body());
            assertEquals(1, (after.isArray() ? after : after.path("items")).size(), "the task is still open for the PAO-1 holder: " + still.body());
            seen.add("the task is still open for the holder");
        }
        return String.join("; ", seen);
    }

    /**
     * Register E-24 follow-up 1, what an officer gets in sangam-ui, through the calls the UI makes: {@code GET /me}
     * without a seat (AuthProvider.loadMe) offers each persona of the presentation exactly its posted seat, as
     * SUBSTANTIVE; then, with that seat in {@code X-Sangam-Seat} (the header AuthProvider sends for a substantive
     * seat), {@code GET /tasks/counts} answers and {@code GET /tasks?section=MY_SEAT} (TaskInbox) lists every open
     * task that waits for that seat. Which tasks wait for which seat is read from the engine (candidate groups).
     */
    private static String uiSeats(ConfigurableApplicationContext ctx, String api) throws Exception {
        PresentationSeedAgainstServerTest.Seed plan = PresentationSeedAgainstServerTest.script(
                PRESENTATION.resolve("seed_presentation.py"), List.of("--dry-run"), Map.of());
        assertEquals(0, plan.exit(), plan.out());
        Map<String, List<String>> roles = new LinkedHashMap<>();
        Matcher r = Pattern.compile("keycloak \\S+: user (\\S+) roles (\\S+) seats").matcher(plan.out());
        while (r.find()) {
            roles.put(r.group(1), List.of(r.group(2).split(",")));
        }
        Map<String, String> postings = new java.util.TreeMap<>();
        Matcher m = Pattern.compile("sangam: assignment (\\S+) -> (\\S+) SUBSTANTIVE").matcher(plan.out());
        while (m.find()) {
            postings.put(m.group(1), m.group(2));
        }
        assertEquals(8, postings.size(), "the presentation's seat holders: " + plan.out());
        // the open tasks per seat, from the engine
        org.operaton.bpm.engine.TaskService engine = ctx.getBean(org.operaton.bpm.engine.ProcessEngine.class).getTaskService();
        Map<String, java.util.Set<String>> waiting = new java.util.TreeMap<>();
        for (org.operaton.bpm.engine.task.Task t : engine.createTaskQuery().tenantIdIn(TENANT).taskUnassigned().list()) {
            for (org.operaton.bpm.engine.task.IdentityLink l : engine.getIdentityLinksForTask(t.getId())) {
                if (org.operaton.bpm.engine.task.IdentityLinkType.CANDIDATE.equals(l.getType()) && l.getGroupId() != null) {
                    waiting.computeIfAbsent(l.getGroupId(), k -> new java.util.TreeSet<>()).add(t.getId());
                }
            }
        }
        int shown = 0;
        List<String> wrong = new ArrayList<>();
        for (Map.Entry<String, String> p : postings.entrySet()) {
            String token = PresentationSeedAgainstServerTest.token(p.getKey(), roles.getOrDefault(p.getKey(), List.of("sangam-user")));
            List<String> offered = new ArrayList<>();
            PresentationSeedAgainstServerTest.get(api + "/me", token).path("seats")
                    .forEach(s -> offered.add(s.path("seatId").asText() + " " + s.path("kind").asText()));
            if (!offered.equals(List.of(p.getValue() + " SUBSTANTIVE"))) {
                wrong.add(p.getKey() + " (posted to " + p.getValue() + ") is offered " + offered + " by /me");
                continue;
            }
            HttpResponse<String> counts = uiGet(api + "/tasks/counts", token, p.getValue());
            HttpResponse<String> list = uiGet(api + "/tasks?section=MY_SEAT&page=0&size=500", token, p.getValue());
            if (counts.statusCode() != 200 || list.statusCode() != 200) {
                wrong.add(p.getKey() + " with X-Sangam-Seat " + p.getValue() + ": counts " + counts.statusCode() + ", list "
                        + list.statusCode() + " " + list.body());
                continue;
            }
            JsonNode body = JSON.readTree(list.body());
            java.util.Set<String> listed = new java.util.TreeSet<>();
            (body.isArray() ? body : body.path("items")).forEach(t -> listed.add(t.path("id").asText()));
            java.util.Set<String> expected = waiting.getOrDefault(p.getValue(), java.util.Set.of());
            if (!listed.containsAll(expected)) {
                wrong.add(p.getKey() + " with X-Sangam-Seat " + p.getValue() + " is shown " + listed.size() + " tasks, not all of the "
                        + expected.size() + " that wait for the seat");
            }
            shown += expected.size();
        }
        assertTrue(wrong.isEmpty(), String.join("; ", wrong));
        return postings.size() + " of " + postings.size() + " personas are offered their seat by GET /me; " + shown
                + " open tasks wait for their seats and each is listed for the seat's holder";
    }

    private static HttpResponse<String> uiGet(String url, String token, String seat) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + token)
                .header("X-Sangam-Seat", seat).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> seatHeaderGet(String url, String user, String packRole) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + PresentationSeedAgainstServerTest.token(user, List.of(packRole, "sangam-user")))
                .header("X-Sangam-Seat", "PAO-1").GET().build(), HttpResponse.BodyHandlers.ofString());
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
