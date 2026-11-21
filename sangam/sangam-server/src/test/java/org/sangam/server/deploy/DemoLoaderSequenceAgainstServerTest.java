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

    /**
     * Register E-26 Follow-up 2 (vi), OPEN DECISION (sync board REQUEST): a payee's bank account is a sensitive
     * identifier, and the party registry holds its clear value only in the compliance national-id vault (decision 389,
     * RegistryWiring). The demo fragment configures no such vault, so on the host every registration of a payee is
     * refused (409 "no vault service is configured to hold clear identifier values (type BANK_ACCOUNT)") and, with an
     * unregistered payee refused, no bill gets past its payee check. This setting, a test value and no secret, switches
     * the vault on for the packs-deployed runs of this test ONLY, to show the journey once the host has one; it is not
     * in the fragment and is not proposed for it here. On an attested host it is a new key like any other: the start
     * with it is refused (KEY_ATTESTATION_INVALID) until the custodian attests it (CI 44876928751), so the attested
     * runs add it as a key rotation (PacksOfBootstrapTenantAgainstServerTest.Host#addKey).
     */
    static final String PAYEE_CUSTODY_FOR_THIS_TEST = "--sangam.compliance.national-id-vault.hmac-pepper="
            + java.util.Base64.getEncoder().encodeToString("E-26 payee custody, this test only".getBytes(StandardCharsets.UTF_8));

    /**
     * Whether the started server can hold a payee's bank account, read from its beans and not only from its settings
     * (CI 44878472595: with the national-id vault set, the registry still answered 409 "no vault service"): the
     * national-id vault beans, the registry's identifier vault, and, when the vault is set but the registry does not
     * use it, the order in which the beans involved were completed and their recorded dependencies.
     */
    static String custody(ConfigurableApplicationContext ctx) {
        boolean set = ctx.getEnvironment().containsProperty("sangam.compliance.national-id-vault.hmac-pepper");
        String[] vaults = ctx.getBeanNamesForType(org.sangam.compliance.ports.CompliancePorts.NationalIdVault.class);
        String registry = ctx.getBean(org.sangam.registry.RegistryModule.class).ports().vault().getClass().getSimpleName();
        String text = (set ? "national-id vault set for this test only (not in the demo fragment; open decision, register"
                + " E-26 Follow-up 2)" : "no national-id vault (as the demo fragment): a payee's bank account cannot be"
                + " registered") + "; national-id vault beans " + java.util.Arrays.toString(vaults)
                + "; the registry's identifier vault " + registry;
        if (set && !"RegistryCustodyVault".equals(registry)) {
            org.springframework.beans.factory.config.ConfigurableListableBeanFactory bf = ctx.getBeanFactory();
            List<String> done = List.of(bf.getSingletonNames());
            List<String> beans = List.of("sangamNationalIdVault", "sangamFieldEncryptor", "sangamSecurity", "processEngine",
                    "sangamComplianceConfig", "sangamCompliancePrincipalKey", "sangamCompliance", "sangamRegistryModule");
            text += "; completed in the order " + beans.stream().map(n -> n + "#" + done.indexOf(n)).toList()
                    + "; sangamRegistryModule is needed by " + java.util.Arrays.toString(bf.getDependentBeans("sangamRegistryModule"))
                    + "; it needs " + java.util.Arrays.toString(bf.getDependenciesForBean("sangamRegistryModule"))
                    + "; sangamNationalIdVault needs " + java.util.Arrays.toString(bf.getDependenciesForBean("sangamNationalIdVault"))
                    + "; sangamFieldEncryptor needs " + java.util.Arrays.toString(bf.getDependenciesForBean("sangamFieldEncryptor"))
                    + "; sangamSecurity needs " + java.util.Arrays.toString(bf.getDependenciesForBean("sangamSecurity"))
                    + "; beans in creation when sangamRegistryModule began " + CreationProbe.chainAtRegistry;
        }
        return text;
    }

    /** Test setting: the start records which beans were in creation when the registry module's creation began. */
    static final String CREATION_PROBE = "--context.initializer.classes=" + CreationProbe.class.getName();

    /**
     * A test-only context initializer (Spring Boot's {@code context.initializer.classes}): a bean post-processor that
     * keeps the stack of singletons in creation on the main thread and, when {@code sangamRegistryModule} begins,
     * records that stack (outermost first). It changes no bean.
     */
    public static final class CreationProbe
            implements org.springframework.context.ApplicationContextInitializer<ConfigurableApplicationContext> {
        static volatile String chainAtRegistry = "(not recorded)";

        @Override
        public void initialize(ConfigurableApplicationContext ctx) {
            chainAtRegistry = "(registry module not created)";
            java.util.Deque<String> open = new java.util.ArrayDeque<>();
            ctx.getBeanFactory().addBeanPostProcessor(
                    new org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessor() {
                        @Override
                        public Object postProcessBeforeInstantiation(Class<?> type, String name) {
                            if (Thread.currentThread().getName().equals("main")) {
                                if ("sangamRegistryModule".equals(name)) {
                                    List<String> chain = new ArrayList<>(open);
                                    java.util.Collections.reverse(chain);
                                    chainAtRegistry = chain.toString();
                                }
                                open.push(name);
                            }
                            return null;
                        }

                        @Override
                        public Object postProcessAfterInitialization(Object bean, String name) {
                            if (Thread.currentThread().getName().equals("main") && name.equals(open.peek())) {
                                open.pop();
                            }
                            return bean;
                        }
                    });
        }
    }

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
        run(List.of("--sangam.packs.deploy-on-start=false"), OPEN_GAPS);
    }

    /** The same, with the packs deployed for GOI.FIN at start. */
    @Test
    void theLiveSequenceWithThePacksDeployedForTheTenantAtStart() throws Exception {
        List<String> settings = new ArrayList<>(PACKS_AT_START);
        settings.add(PAYEE_CUSTODY_FOR_THIS_TEST);
        settings.add(CREATION_PROBE);
        run(settings, PACKS_GAPS);
    }

    private void run(List<String> settings, java.util.Set<String> gaps) throws Exception {
        DemoComposeFragmentStartupTest.Keys keys = new DemoComposeFragmentStartupTest.Keys(dir);
        Map<String, String> bootstrap = Map.of("SANGAM_KEYS_BOOTSTRAP", "true",
                "SANGAM_KEYS_FINGERPRINT_EXPORT", dir.resolve("key-fingerprints.txt").toString(),
                "SANGAM_KEYS_ATTESTATION", "", "SANGAM_KEYS_CUSTODIAN_PUBLIC_KEY", "");
        List<String> all = new ArrayList<>(settings);
        // the fragment does not set the log archive; keep this test's segments out of the module directory
        all.add("--sangam.compliance.log-archive.root=" + dir.resolve("compliance-logs"));
        try (ConfigurableApplicationContext ctx = DemoComposeFragmentStartupTest.start(keys, bootstrap, all, "h2", "oidc")) {
            sequence(ctx, String.join(" ", settings), List.of("2c seed", "3a funds", "3b smoke", "4a cases"), gaps);
        } finally {
            org.sangam.packs.sim.DemoSandbox.install(java.util.Set.of());
        }
    }

    /**
     * The named steps against a started server, in the live order. Fails with the status list when a step that must
     * pass failed, or when a step of {@link #OPEN_GAPS} passed (take it off the list then).
     */
    static void sequence(ConfigurableApplicationContext ctx, String label, List<String> steps) throws Exception {
        sequence(ctx, label, steps, OPEN_GAPS);
    }

    /**
     * Register E-26: the open gaps of a server whose fragment deploys the packs of GOI.FIN. The smoke step is no gap
     * there (after step {@code 3a bill funds}), and with the case step come two steps that are never a gap:
     * {@code bill journey} (the cases that end in a bill stand at their targets, but for {@link #BILL_JOURNEY_OPEN})
     * and {@code sandbox marks} (each simulated outside check says so). The case step stays a gap: the tasks of the
     * seats no persona holds (CAA, GRO, PIO-1) are done by nobody, and the cases of {@link #BILL_JOURNEY_OPEN}.
     */
    static final java.util.Set<String> PACKS_GAPS = java.util.Set.of("4a cases");

    /** The cases of the data set that end in a bill (register E-26). */
    static final List<String> BILL_JOURNEY = List.of("BILL-0001", "BILL-0002", "BILL-0003", "BILL-0004", "BILL-0005",
            "GPF-0001", "ADV-0001");

    /**
     * The bill cases that do not reach their target on this code, each with its recorded cause (PostgreSQL, CI run
     * 44863850318). Like an open gap: such a case is reported, and when it stands at its target the step fails until
     * the case is taken off this list. Neither cause is a connector the sandbox could answer.
     */
    static final Map<String, String> BILL_JOURNEY_OPEN = Map.of(
            "BILL-0003", "target PAYMENT_HELD: the sandbox pays unless the case carries sim_pfms_payment=PENDING; the data"
                    + " set has it under h2Directives only and load_presentation_data.py does not send it, so the bill is"
                    + " paid and closed. SANGAM.md defines no payment-hold directive for a hosted load (Follow-up 2"
                    + " (iii)), so none is added: a named gap",
            "ADV-0001", "IFMS ledger RECOVERY not available: the advance's recovery schedule and the bill it calls ask"
                    + " ledger RECOVERY ('ledger RECOVERY not defined for GOI', LEDGER_REQUEST_INVALID), so the bill is"
                    + " rejected FUNDS_NOT_CONFIRMED; closes when the IFMS ledger connector exists (Follow-up 2 (iv))");

    /**
     * Follow-up 2 (v): the cases the presentation data's own set-up makes reachable (load_presentation_data.py,
     * section {@code setup} of the data set: payees registered in the party registry, budget released through
     * ALLOCATE and DISTRIBUTE). Never a gap: each must stand at its target.
     */
    static final List<String> SETUP_CASES = List.of("BA-0001");

    /** {@link #sequence(ConfigurableApplicationContext, String, List)} with the open gaps of this server. */
    static void sequence(ConfigurableApplicationContext ctx, String label, List<String> steps, java.util.Set<String> gaps)
            throws Exception {
        String[] loaderOut = {""};
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
            loaderOut[0] = s.out();
            String states = endStates(api, s.out());
            assertEquals(0, s.exit(), "load_presentation_data.py:\n" + s.out());
            return states;
        });
        List<String> status = new ArrayList<>();
        List<String> wrong = new ArrayList<>();
        // a gap set other than the as-deployed one is that of a server whose packs are deployed (register E-26)
        boolean packs = gaps != OPEN_GAPS;
        for (String name : steps) {
            if (packs && "3b smoke".equals(name) && !step(status, "3a bill funds", () -> fundBills(api))) {
                wrong.add("3a bill funds failed");
            }
            boolean passed = step(status, name, all.get(name));
            if (passed == gaps.contains(name)) {
                wrong.add(passed ? name + " passes now: take it off the open gaps" : name + " failed");
            }
        }
        status.add("DEMO-SEQUENCE | connectors | INFO | " + connectors(ctx));
        status.add("DEMO-SEQUENCE | payee custody | INFO | " + custody(ctx));
        if (steps.contains("3b smoke") || steps.contains("4a cases")) {
            // register E-25 point 4: one line per case, so the report lists every case
            stops(ctx).forEach(s -> status.add("DEMO-SEQUENCE | stop | INFO | " + s));
        }
        if (steps.contains("4a cases")) {
            // register E-26: where each case ended, read from the engine and the audit chain (a rejected case has
            // ended too, and is not at a COMPLETED target)
            List<End> ends = ends(ctx, loaderOut[0]);
            ends.forEach(e -> status.add("DEMO-SEQUENCE | end | INFO | " + e.id() + " (target " + e.target() + "): "
                    + (e.atTarget() ? "AT TARGET" : "NOT AT TARGET") + ": " + e.text()));
            status.add("DEMO-SEQUENCE | targets | INFO | " + ends.stream().filter(End::atTarget).count() + " of " + ends.size()
                    + " cases at their target; not at target " + ends.stream().filter(e -> !e.atTarget()).map(End::id).toList());
            if (packs) {
                probes(ctx).forEach(p -> status.add("DEMO-SEQUENCE | probe | INFO | " + p));
                boolean passed = step(status, "bill journey", () -> billJourney(ends));
                if (passed == gaps.contains("bill journey")) {
                    wrong.add(passed ? "bill journey passes now: take it off the open gaps" : "bill journey failed");
                }
                if (!step(status, "sandbox marks", () -> sandboxMarks(ctx, api))) {
                    wrong.add("sandbox marks failed");
                }
                if (!step(status, "data set-up", () -> dataSetup(api, ends, loaderOut[0]))) {
                    // Follow-up 2 (v), (vi): never an open gap
                    wrong.add("data set-up failed");
                }
            }
        }
        if (steps.contains("4a cases") && !step(status, "ui seats", () -> uiSeats(ctx, api))) {
            // register E-24 follow-up 1: never an open gap
            wrong.add("ui seats failed");
        }
        if (steps.contains("4a cases") && !step(status, "seat header", () -> seatHeaderCheck(api))) {
            // register E-24: never an open gap
            wrong.add("seat header failed");
        }
        String report = "DEMO-SEQUENCE [" + label + "] open gaps " + new java.util.TreeSet<>(gaps) + "\n" + String.join("\n", status);
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
        var registered = ctx.getBean(org.sangam.integration.connector.ConnectorRegistry.class);
        List<String> bound = new ArrayList<>();
        List<String> sandbox = new ArrayList<>();
        List<String> nobody = new ArrayList<>();
        for (String id : ids) {
            boolean sim = org.sangam.packs.sim.DemoSandbox.simulates(TENANT, id);
            // register E-26: a binding to an integration connector that is not registered here answers nothing
            var binding = bindings.binding(TENANT, id);
            boolean answered = binding.isPresent() && registered.find(TENANT, binding.get().connectorId()).isPresent();
            (sim ? sandbox : answered ? bound : nobody).add(id + (binding.isPresent() && !answered && !sim
                    ? " (bound to " + binding.get().connectorId() + ", which is not registered)" : ""));
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

    /** Where one case of the data set ended: whether that is its target, and the evidence. */
    record End(String id, String target, boolean atTarget, String text) {
    }

    /**
     * Register E-26: each case against its target, from the engine and the case's audit chain (the chain of a pack
     * event is the business key, PacksAuditSink). COMPLETED: the case ended and its chain says CASE_CLOSED and no
     * CASE_REJECTED (a rejected case has ended too). RUNNING: open, and the loader saw the stop task. PAYMENT_HELD:
     * open, no task for anyone, no incident, past the PAO (the chain has the payment request EXT_OP_REQUESTED).
     * WAITING: open, no task, no incident, in the declared case state.
     */
    static List<End> ends(ConfigurableApplicationContext ctx, String loaderOut) throws Exception {
        org.operaton.bpm.engine.ProcessEngine engine = ctx.getBean(org.operaton.bpm.engine.ProcessEngine.class);
        awaitOperationResults(engine);
        List<End> out = new ArrayList<>();
        for (JsonNode c : JSON.readTree(DATA.toFile()).path("cases")) {
            String id = c.path("id").asText();
            String bk = c.path("businessKey").asText();
            String target = c.path("target").asText();
            String stopAt = c.path("stopAt").asText("");
            String label = target + (stopAt.isEmpty() ? "" : " at " + stopAt);
            long started = engine.getHistoryService().createHistoricProcessInstanceQuery().tenantIdIn(TENANT)
                    .processInstanceBusinessKey(bk).rootProcessInstances().count();
            if (started == 0) {
                out.add(new End(id, label, false, "not started"));
                continue;
            }
            List<org.operaton.bpm.engine.runtime.ProcessInstance> roots = engine.getRuntimeService()
                    .createProcessInstanceQuery().tenantIdIn(TENANT).processInstanceBusinessKey(bk).rootProcessInstances().list();
            boolean ended = roots.isEmpty();
            List<String> events = auditTypes(ctx, bk);
            List<String> tasks = new ArrayList<>();
            List<String> incidents = new ArrayList<>();
            java.util.Set<String> at = new java.util.TreeSet<>();
            Object state = null;
            for (org.operaton.bpm.engine.runtime.ProcessInstance root : roots) {
                state = engine.getRuntimeService().getVariable(root.getId(), "caseState");
                for (org.operaton.bpm.engine.runtime.ProcessInstance pi : engine.getRuntimeService()
                        .createProcessInstanceQuery().rootProcessInstanceId(root.getId()).list()) {
                    at.addAll(engine.getRuntimeService().getActiveActivityIds(pi.getId()));
                    engine.getTaskService().createTaskQuery().processInstanceId(pi.getId()).list()
                            .forEach(t -> tasks.add(t.getTaskDefinitionKey()));
                    engine.getRuntimeService().createIncidentQuery().processInstanceId(pi.getId()).list()
                            .forEach(i -> incidents.add(i.getActivityId() + ": " + String.valueOf(i.getIncidentMessage())
                                    .replaceAll("\\s+", " ")));
                }
            }
            boolean rejected = events.contains("CASE_REJECTED");
            String text = (ended ? "ended" + (rejected ? ", REJECTED" : events.contains("CASE_CLOSED") ? ", CLOSED" : "")
                    : "open at " + at + ", case state " + state + (tasks.isEmpty() ? "" : ", open tasks " + tasks)
                    + (incidents.isEmpty() ? "" : ", incidents " + incidents));
            if (rejected) {
                text += " (" + rejection(ctx, bk) + "; connector steps not completed " + stepsNotCompleted(engine, bk) + ")";
            }
            boolean right = switch (target) {
                case "COMPLETED" -> ended && events.contains("CASE_CLOSED") && !rejected;
                case "RUNNING" -> !ended && incidents.isEmpty() && waitsAt(loaderOut, id, stopAt);
                case "PAYMENT_HELD" -> !ended && tasks.isEmpty() && incidents.isEmpty() && paymentRequested(ctx, bk);
                case "WAITING" -> !ended && tasks.isEmpty() && incidents.isEmpty()
                        && c.path("expectState").asText().equals(String.valueOf(state));
                default -> false;
            };
            out.add(new End(id, label, right, text));
        }
        return out;
    }

    /**
     * The result of an operation a bound connector executes comes back through the outbox relay and the inbox, some
     * seconds after the step that submitted it. So that an end state is not read in between, this waits, at most 90
     * seconds, while a case stands at core execution's {@code ex_wait} with the operation of a connector the installed
     * gateway answers still PENDING. (An operation the sandbox holds PENDING, a held payment, is not waited for.)
     */
    private static void awaitOperationResults(org.operaton.bpm.engine.ProcessEngine engine) throws InterruptedException {
        long until = System.nanoTime() + java.time.Duration.ofSeconds(90).toNanos();
        while (System.nanoTime() < until) {
            boolean pending = false;
            for (org.operaton.bpm.engine.runtime.ProcessInstance pi : engine.getRuntimeService().createProcessInstanceQuery()
                    .tenantIdIn(TENANT).processDefinitionKey("sangam-core-execution").list()) {
                Map<String, Object> v = engine.getRuntimeService().getVariables(pi.getId());
                pending |= engine.getRuntimeService().getActiveActivityIds(pi.getId()).contains("ex_wait")
                        && "PENDING".equals(v.get("opStatus"))
                        && !org.sangam.packs.sim.DemoSandbox.simulates(TENANT, String.valueOf(v.get("opConnector")));
            }
            if (!pending) {
                return;
            }
            Thread.sleep(2000);
        }
    }

    /**
     * Register E-26: what the connectors that ARE bound answer for the bill cases' own inputs, asked directly (read
     * only): the party registry for each case's payee, and the ledger for the account each bill is charged to. A bill
     * is rejected when its payee check fails and waits for budget when the account holds less than the bill.
     */
    static List<String> probes(ConfigurableApplicationContext ctx) throws Exception {
        var bindings = ctx.getBean(org.sangam.integration.packs.PackConnectorBindings.class);
        var registry = ctx.getBean(org.sangam.integration.connector.ConnectorRegistry.class);
        List<String> out = new ArrayList<>();
        java.util.Set<String> asked = new java.util.LinkedHashSet<>();
        for (JsonNode c : JSON.readTree(DATA.toFile()).path("cases")) {
            if (!BILL_JOURNEY.contains(c.path("id").asText())) {
                continue;
            }
            JsonNode v = c.path("variables");
            Map<String, Object> payee = new LinkedHashMap<>();
            for (String k : List.of("partyId", "payee", "payeeAccount", "ifsc")) {
                if (v.hasNonNull(k)) {
                    payee.put(k, v.path(k).asText());
                }
            }
            Map<String, Object> account = new LinkedHashMap<>();
            account.put("ledgerId", "sangam-ifms-advance".equals(c.path("process").asText()) ? "RECOVERY" : "BUDGET");
            account.put("headOfAccount", v.path("headOfAccount").asText("2054-00-001-01-00-13"));
            account.put("ddoCode", v.path("ddoCode").asText("DDO-1"));
            Map<String, Object> byAccount = new LinkedHashMap<>(payee);
            byAccount.remove("partyId");
            for (Map.Entry<String, Map<String, Object>> q : List.of(Map.entry("registry.party|payee.resolve", payee),
                    Map.entry("registry.party|payee.resolve", byAccount), Map.entry("ledger.available|available", account))) {
                String connector = q.getKey().substring(0, q.getKey().indexOf('|'));
                String operation = q.getKey().substring(q.getKey().indexOf('|') + 1);
                if (!asked.add(connector + q.getValue())) {
                    continue;
                }
                String answer;
                try {
                    var conn = registry.find(TENANT, bindings.binding(TENANT, connector).orElseThrow().connectorId()).orElseThrow();
                    answer = String.valueOf(conn.invoke(new org.sangam.core.integration.ConnectorRequest(TENANT,
                            java.util.UUID.randomUUID(), "query", 1, "probe", "probe/" + c.path("id").asText(), operation,
                            q.getValue(), java.time.Instant.now().plusSeconds(30))));
                } catch (RuntimeException e) {
                    answer = "threw " + e;
                }
                out.add(connector + " " + operation + " " + q.getValue() + " (amount of " + c.path("id").asText() + ": "
                        + v.path("amount").asText() + "): " + answer.replaceAll("\\s+", " "));
            }
            if (out.stream().noneMatch(x -> x.startsWith("payee directory"))) {
                // the connector keeps only the message of what the directory threw; asked directly, the cause shows
                try (var con = ctx.getBean(javax.sql.DataSource.class).getConnection()) {
                    // what RegistryPayeeDirectory.resolve does for a payee named by account: a match probe
                    Map<String, Object> probe = new LinkedHashMap<>();
                    probe.put("kind", "PERSON");
                    probe.put("name", byAccount.get("payee"));
                    probe.put("identifiers", List.of(new LinkedHashMap<>(Map.of("type", "BANK_ACCOUNT", "value",
                            byAccount.get("payeeAccount") + "@" + byAccount.get("ifsc")))));
                    out.add("payee directory resolve " + byAccount + ": matches "
                            + ctx.getBean(org.sangam.registry.party.PartyService.class).findMatches(con,
                            org.sangam.registry.RegistryContext.of(TENANT, "system:payments").withPurpose("payment"), probe));
                } catch (RuntimeException e) {
                    List<String> trace = new ArrayList<>();
                    for (Throwable t = e; t != null && trace.size() < 4; t = t.getCause()) {
                        trace.add(t + " at " + (t.getStackTrace().length == 0 ? "?" : t.getStackTrace()[0]));
                    }
                    out.add("payee directory resolve " + byAccount + ": threw " + trace);
                }
            }
        }
        // BA-0001 distributes to (2054-00-097-01-00-01, DDO-1) at AUTHORISATION from the stage above it, RELEASE
        try {
            Map<String, Object> parent = new LinkedHashMap<>();
            parent.put("ledgerId", "BUDGET");
            parent.put("headOfAccount", "2054-00-097-01-00-01");
            parent.put("holder", "-");
            parent.put("stage", "RELEASE");
            var conn = registry.find(TENANT, bindings.binding(TENANT, "ledger.available").orElseThrow().connectorId()).orElseThrow();
            out.add("ledger.available available " + parent + " (BA-0001 distributes 250000000 from it): "
                    + conn.invoke(new org.sangam.core.integration.ConnectorRequest(TENANT, java.util.UUID.randomUUID(), "query", 1,
                    "probe", "probe/BA-0001", "available", parent, java.time.Instant.now().plusSeconds(30))));
        } catch (RuntimeException e) {
            out.add("ledger.available of BA-0001's source account: threw " + e);
        }
        // the relay's deliveries of the tenant: how many per destination, state and attempts, and the errors kept
        try (var con = ctx.getBean(javax.sql.DataSource.class).getConnection()) {
            try (var ps = con.prepareStatement("SELECT destination, state, attempts, COUNT(*) FROM OUTBOX_DELIVERY WHERE tenant_id=?"
                    + " GROUP BY destination, state, attempts ORDER BY destination, state, attempts")) {
                ps.setString(1, TENANT);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add("outbox delivery " + rs.getString(1) + " " + rs.getString(2) + " attempts " + rs.getInt(3) + ": "
                                + rs.getLong(4));
                    }
                }
            }
            try (var ps = con.prepareStatement("SELECT destination, state, attempts, last_error FROM OUTBOX_DELIVERY WHERE tenant_id=?"
                    + " AND last_error IS NOT NULL ORDER BY outbox_id")) {
                ps.setString(1, TENANT);
                ps.setMaxRows(6);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String err = String.valueOf(rs.getString(4)).replaceAll("\\s+", " ");
                        out.add("outbox delivery error " + rs.getString(1) + " " + rs.getString(2) + " attempts " + rs.getInt(3) + ": "
                                + (err.length() > 300 ? err.substring(0, 300) + "..." : err));
                    }
                }
            }
        }
        // the operations of bound connectors that have not completed, and every budget distribution, as stored
        try (var con = ctx.getBean(javax.sql.DataSource.class).getConnection();
             var ps = con.prepareStatement("SELECT * FROM PACK_OPERATION WHERE tenant_id=? AND (status<>'COMPLETED' OR connector='ledger.distribute')")) {
            ps.setString(1, TENANT);
            try (var rs = ps.executeQuery()) {
                var md = rs.getMetaData();
                java.util.Set<String> shown = java.util.Set.of("connector", "operation", "status", "reason_code", "dispatched",
                        "generation", "business_key", "result_json", "cancel_requested");
                while (rs.next()) {
                    Map<String, Object> row = new java.util.TreeMap<>();
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        String name = md.getColumnLabel(i).toLowerCase(Locale.ROOT);
                        if (shown.contains(name)) {
                            row.put(name, rs.getString(i));
                        }
                    }
                    out.add("pack operation " + row);
                }
            }
        }
        return out;
    }

    /** The event types of the case's audit chain, in order. */
    static List<String> auditTypes(ConfigurableApplicationContext ctx, String businessKey) throws Exception {
        List<String> out = new ArrayList<>();
        audit(ctx, businessKey).forEach(e -> out.add(e.path("eventType").asText()));
        return out;
    }

    /** The case's audit chain, in order: one node per event, {@code eventType} and {@code envelope} (its payload). */
    static List<JsonNode> audit(ConfigurableApplicationContext ctx, String businessKey) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        try (var c = ctx.getBean(javax.sql.DataSource.class).getConnection();
             var ps = c.prepareStatement("SELECT e.event_type, p.payload FROM AUDIT_EVENT e JOIN AUDIT_PAYLOAD p"
                     + " ON p.event_id = e.event_id WHERE e.tenant_id=? AND e.chain_id=? ORDER BY e.seq")) {
            ps.setString(1, TENANT);
            ps.setString(2, businessKey);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    com.fasterxml.jackson.databind.node.ObjectNode n = JSON.createObjectNode();
                    n.put("eventType", rs.getString(1));
                    n.set("envelope", JSON.readTree(rs.getString(2)));
                    out.add(n);
                }
            }
        }
        return out;
    }

    /** The first value of {@code field} anywhere in an audit envelope, or a missing node. */
    static JsonNode field(JsonNode envelope, String field) {
        JsonNode found = envelope.findValue(field);
        return found == null ? com.fasterxml.jackson.databind.node.MissingNode.getInstance() : found;
    }

    /** Why a case was rejected: the reasons of its CASE_REJECTED event and the reason codes of its failed operations. */
    private static String rejection(ConfigurableApplicationContext ctx, String businessKey) throws Exception {
        List<String> why = new ArrayList<>();
        for (JsonNode e : audit(ctx, businessKey)) {
            String type = e.path("eventType").asText();
            if ("CASE_REJECTED".equals(type)) {
                why.add("reasons " + field(e.path("envelope"), "reasons").asText(field(e.path("envelope"), "reasonCodes").asText("?")));
            } else if ("EXT_OP_FAILED".equals(type)) {
                why.add(field(e.path("envelope"), "connector").asText("?") + " FAILED "
                        + field(e.path("envelope"), "reasonCode").asText("?"));
            }
        }
        return String.join("; ", why);
    }

    /**
     * The connector steps of a case (all its instances, ended or not) whose outcome is not COMPLETED, from the
     * engine's history: {@code <prefix>Status=<status>/<reason code>}, as ConnectorCallDelegate writes them.
     */
    private static Map<String, String> stepsNotCompleted(org.operaton.bpm.engine.ProcessEngine engine, String businessKey) {
        Map<String, Object> vars = new java.util.TreeMap<>();
        for (org.operaton.bpm.engine.history.HistoricProcessInstance pi : engine.getHistoryService()
                .createHistoricProcessInstanceQuery().tenantIdIn(TENANT).processInstanceBusinessKey(businessKey).list()) {
            for (org.operaton.bpm.engine.history.HistoricVariableInstance v : engine.getHistoryService()
                    .createHistoricVariableInstanceQuery().processInstanceId(pi.getId()).list()) {
                if (v.getName().endsWith("Status") || v.getName().endsWith("ReasonCode")) {
                    vars.put(v.getName(), v.getValue());
                }
            }
        }
        Map<String, String> out = new java.util.TreeMap<>();
        vars.forEach((name, value) -> {
            String prefix = name.endsWith("Status") ? name.substring(0, name.length() - "Status".length()) : null;
            if (prefix != null && value instanceof String s && !"COMPLETED".equals(s) && vars.containsKey(prefix + "ReasonCode")) {
                out.put(name, s + "/" + vars.get(prefix + "ReasonCode"));
            }
        });
        return out;
    }

    /** Whether the case's chain has the request of a payment (a money-out rail of the sandbox). */
    private static boolean paymentRequested(ConfigurableApplicationContext ctx, String businessKey) throws Exception {
        for (JsonNode e : audit(ctx, businessKey)) {
            if ("EXT_OP_REQUESTED".equals(e.path("eventType").asText())
                    && org.sangam.packs.sim.DemoSandbox.PAYMENT_CONNECTORS.contains(field(e.path("envelope"), "connector").asText())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Register E-26: the cases that end in a bill stand at their targets, each but those of
     * {@link #BILL_JOURNEY_OPEN}; one of those that does stand at its target must be taken off that list.
     */
    private static String billJourney(List<End> ends) {
        List<String> wrong = new ArrayList<>();
        List<String> open = new ArrayList<>();
        int n = 0;
        int at = 0;
        for (End e : ends) {
            if (!BILL_JOURNEY.contains(e.id())) {
                continue;
            }
            n++;
            boolean known = BILL_JOURNEY_OPEN.containsKey(e.id());
            if (e.atTarget()) {
                at++;
                if (known) {
                    wrong.add(e.id() + " stands at its target now: take it off BILL_JOURNEY_OPEN");
                }
            } else if (known) {
                open.add(e.id() + " (target " + e.target() + "): " + e.text() + " [recorded cause: " + BILL_JOURNEY_OPEN.get(e.id()) + "]");
            } else {
                wrong.add(e.id() + " (target " + e.target() + "): " + e.text());
            }
        }
        assertEquals(BILL_JOURNEY.size(), n, "the bill cases of the data set");
        assertTrue(wrong.isEmpty(), wrong.size() + " of " + n + " bill cases wrong: " + wrong);
        return at + " of " + n + " bill cases at their target; not at target, with a recorded cause: " + open;
    }

    /**
     * Follow-up 2 (v) and (vi): what the data set's own set-up must have done on the server, through the API only,
     * after two runs of the loader (the smoke run and the full run): every payee of the data set is exactly one party
     * of the registry, found by its bank account (a second run registers nobody twice); the loader says what it
     * released and that the amounts are assumed; and each case of {@link #SETUP_CASES} stands at its target.
     */
    private static String dataSetup(String api, List<End> ends, String loaderOut) throws Exception {
        String admin = PresentationSeedAgainstServerTest.token("rajendra.sharma", List.of("sangam-tenant-admin", "sangam-user"));
        List<String> wrong = new ArrayList<>();
        JsonNode data = JSON.readTree(DATA.toFile());
        int payees = 0;
        for (JsonNode p : data.path("payees")) {
            String account = p.path("payeeAccount").asText() + "@" + p.path("ifsc").asText();
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(api + "/registry/parties?idType=BANK_ACCOUNT&idValue="
                    + URLEncoder.encode(account, StandardCharsets.UTF_8))).header("Authorization", "Bearer " + admin)
                    .header("X-Purpose", "payment").GET().build(), HttpResponse.BodyHandlers.ofString());
            JsonNode found = r.statusCode() == 200 ? JSON.readTree(r.body()) : null;
            int n = found == null ? -1 : (found.isArray() ? found : found.path("items")).size();
            if (n != 1) {
                wrong.add("payee " + p.path("employeeId").asText() + ": " + n + " parties hold its account (HTTP "
                        + r.statusCode() + "), expected exactly 1");
            }
            payees++;
        }
        if (payees == 0) {
            wrong.add("the data set names no payee");
        }
        List<String> released = new ArrayList<>();
        for (String line : loaderOut.split("\\R")) {
            if (line.contains("set-up budget")) {
                released.add(line.strip());
            }
        }
        if (released.isEmpty() || released.stream().anyMatch(l -> !l.contains("ASSUMED"))) {
            wrong.add("the loader does not report the budget it released, each amount marked ASSUMED: " + released);
        }
        for (End e : ends) {
            if (SETUP_CASES.contains(e.id()) && !e.atTarget()) {
                wrong.add(e.id() + " (target " + e.target() + "): " + e.text());
            }
        }
        assertTrue(wrong.isEmpty(), String.join("; ", wrong));
        return payees + " payees each one party of the registry by account; " + released + "; " + SETUP_CASES + " at target";
    }

    /**
     * Register E-26 and its follow-up 1: a simulated outside check never reads as an answer of the outside system. For
     * every bill case: the duplicate-bill check has exactly one audit record per bill version, it names the connector
     * and says {@code simulated=true} and {@code sandbox=true}, and the case holds {@code dupSandbox=true} while it is
     * open; every document issued says the same. {@code GET /me} says {@code sandbox=true} for the tenant, which is
     * what shows the UI's Sandbox badge on every page (Layout.tsx SandboxBadge), as for W-5's payments.
     */
    private static String sandboxMarks(ConfigurableApplicationContext ctx, String api) throws Exception {
        List<String> wrong = new ArrayList<>();
        int checks = 0;
        int documents = 0;
        Map<String, Integer> timelineCounts = new java.util.TreeMap<>();
        for (JsonNode c : JSON.readTree(DATA.toFile()).path("cases")) {
            if (!BILL_JOURNEY.contains(c.path("id").asText())) {
                continue;
            }
            String id = c.path("id").asText();
            int mine = 0;
            for (JsonNode e : audit(ctx, c.path("businessKey").asText())) {
                JsonNode env = e.path("envelope");
                String connector = field(env, "connector").asText("");
                if (!"ifms.duplicateBill".equals(connector) && !"docs.issue".equals(connector)) {
                    continue;
                }
                if (!field(env, "simulated").asBoolean(false) || !field(env, "sandbox").asBoolean(false)) {
                    wrong.add(id + ": " + e.path("eventType").asText() + " of " + connector + " without simulated=true and sandbox=true");
                }
                if ("ifms.duplicateBill".equals(connector)) {
                    mine++;
                    checks++;
                } else {
                    documents++;
                }
            }
            if (mine != 1) {
                wrong.add(id + ": " + mine + " audit records of the simulated duplicate check, expected 1");
            }
            // Follow-up 1 (IFMS joint condition): what the UI shows. The case and task views link to the Case timeline
            // (GET /audit/timeline, Audit.tsx Timeline, its "Details" of each event). There a simulated check and a
            // simulated document must read as simulated exactly as a W-5 payment does: simulated=true, sandbox=true.
            for (String gap : timelineMarks(api, c.path("businessKey").asText(), id, timelineCounts)) {
                wrong.add(gap);
            }
        }
        if (timelineCounts.getOrDefault("ifms.duplicateBill", 0) != checks) {
            wrong.add("Case timeline shows " + timelineCounts.getOrDefault("ifms.duplicateBill", 0)
                    + " simulated duplicate checks, the audit chain holds " + checks);
        }
        if (timelineCounts.getOrDefault("docs.issue", 0) != documents) {
            wrong.add("Case timeline shows " + timelineCounts.getOrDefault("docs.issue", 0)
                    + " simulated documents, the audit chain holds " + documents);
        }
        if (timelineCounts.getOrDefault("pfms.payment", 0) == 0) {
            wrong.add("Case timeline shows no W-5 payment event of pfms.payment to compare the marking with");
        }
        JsonNode me = PresentationSeedAgainstServerTest.get(api + "/me",
                PresentationSeedAgainstServerTest.token("kavita.sharma", List.of("DDO", "sangam-user")));
        if (!me.path("sandbox").asBoolean(false)) {
            wrong.add("GET /me of kavita.sharma does not say sandbox=true: " + me.path("sandbox"));
        }
        assertTrue(wrong.isEmpty(), String.join("; ", wrong));
        return checks + " simulated duplicate checks and " + documents + " simulated documents, each audited with its connector,"
                + " simulated=true and sandbox=true; GET /me says sandbox=true (the UI's Sandbox badge); the Case timeline"
                + " (GET /audit/timeline) shows them so, as it shows the W-5 payments: " + timelineCounts;
    }

    /** The connectors whose events the Case timeline must mark simulated: the two E-26 checks and W-5's rail. */
    private static final List<String> TIMELINE_SIMULATED = List.of("ifms.duplicateBill", "docs.issue", "pfms.payment");

    /**
     * Follow-up 1: the events of one case as the UI's Case timeline reads them ({@code GET /audit/timeline}, the
     * tenant administrator's token); every event naming a connector of {@link #TIMELINE_SIMULATED} must carry
     * {@code simulated=true} and {@code sandbox=true} in what the timeline's "Details" shows. Counts per connector.
     */
    private static List<String> timelineMarks(String api, String businessKey, String id, Map<String, Integer> counts) throws Exception {
        String admin = PresentationSeedAgainstServerTest.token("rajendra.sharma", List.of("sangam-tenant-admin", "sangam-user"));
        JsonNode page = PresentationSeedAgainstServerTest.get(api + "/audit/timeline?businessKey="
                + URLEncoder.encode(businessKey, StandardCharsets.UTF_8) + "&includeHistory=true&size=200", admin);
        JsonNode items = page.isArray() ? page : page.path("items");
        List<String> wrong = new ArrayList<>();
        for (JsonNode e : items) {
            JsonNode details = e.has("details") ? e.path("details") : e.path("payload");
            String connector = field(details, "connector").asText("");
            if (!TIMELINE_SIMULATED.contains(connector)) {
                continue;
            }
            counts.merge(connector, 1, Integer::sum);
            if (!field(details, "simulated").asBoolean(false) || !field(details, "sandbox").asBoolean(false)) {
                wrong.add(id + ": Case timeline event " + e.path("type").asText() + " of " + connector
                        + " does not show simulated=true and sandbox=true: " + details);
            }
        }
        return wrong;
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
        return fund(api, HEAD, "DDO-1", RUPEES);
    }

    /** ALLOCATE {@code rupees} on {@code head} at the first stage, then DISTRIBUTE down to {@code holder}. */
    private static String fund(String api, String head, String holder, String rupees) throws Exception {
        String admin = PresentationSeedAgainstServerTest.token("rajendra.sharma", List.of("sangam-tenant-admin", "sangam-user"));
        String period = JSON.readTree(DATA.toFile()).path("fiscalYear").asText();
        assertEquals("2026-27", period);
        Map<String, Object> at = account(period, head, "-", STAGES.get(0));
        operation(api, admin, "ALLOCATE", at, null, rupees, holder);
        for (int i = 1; i < STAGES.size(); i++) {
            Map<String, Object> next = account(period, head, i == STAGES.size() - 1 ? holder : "-", STAGES.get(i));
            operation(api, admin, "DISTRIBUTE", at, next, rupees, holder);
            at = next;
        }
        JsonNode accounts = PresentationSeedAgainstServerTest.get(api + "/ledger/by-ledger/accounts?ledger=BUDGET&period=" + period
                + "&line=" + head + "&holder=" + holder + "&stage=" + STAGES.get(STAGES.size() - 1), admin);
        assertEquals(1, accounts.size(), accounts.toString());
        assertEquals(0, new java.math.BigDecimal(rupees).compareTo(new java.math.BigDecimal(accounts.get(0).path("available").asText())),
                "Rs " + rupees + " available to " + holder + ": " + accounts);
        return "ALLOCATE + " + (STAGES.size() - 1) + " DISTRIBUTE applied; available to " + holder + " at " + STAGES.get(STAGES.size() - 1)
                + " = " + accounts.get(0).path("available").asText() + " " + accounts.get(0).path("currency").asText();
    }

    /**
     * Register E-26: the budget the bill cases are charged to. Step 3a allots head 2210-00-101-01-00-31 to DDO-1 only;
     * a bill asks the ledger for its own head and drawing officer (bl_funds) and waits for budget when that account
     * holds less than the bill. Per account (head of account, {@code ddoCode}; the bill pack's defaults where the case
     * names none) the sum of the bill cases charged to ledger BUDGET, allotted the way step 3a allots.
     */
    private static String fundBills(String api) throws Exception {
        Map<String, Long> paise = new java.util.TreeMap<>();
        for (JsonNode c : JSON.readTree(DATA.toFile()).path("cases")) {
            // an advance is charged to ledger RECOVERY (av_defaults), which is not this ledger
            if (BILL_JOURNEY.contains(c.path("id").asText()) && !"sangam-ifms-advance".equals(c.path("process").asText())) {
                JsonNode v = c.path("variables");
                paise.merge(v.path("headOfAccount").asText("2054-00-001-01-00-13") + "|" + v.path("ddoCode").asText("DDO-1"),
                        v.path("amount").asLong(), Long::sum);
            }
        }
        List<String> done = new ArrayList<>();
        for (Map.Entry<String, Long> e : paise.entrySet()) {
            String head = e.getKey().substring(0, e.getKey().indexOf('|'));
            String holder = e.getKey().substring(e.getKey().indexOf('|') + 1);
            String rupees = java.math.BigDecimal.valueOf(e.getValue(), 2).toPlainString();
            fund(api, head, holder, rupees);
            done.add(head + " to " + holder + ": Rs " + rupees);
        }
        return "allotted " + done;
    }

    private static Map<String, Object> account(String period, String head, String holder, String stage) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("period", period);
        a.put("line", head);
        a.put("holder", holder);
        a.put("stage", stage);
        return a;
    }

    private static void operation(String api, String admin, String op, Map<String, Object> account, Map<String, Object> target,
                                  String rupees, String holder) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("operation", op);
        body.put("account", account);
        if (target != null) {
            body.put("target", target);
        }
        body.put("amount", rupees);
        // step 3a's own references are kept as they are on the live host (head 2210-00-101-01-00-31, DDO-1)
        body.put("reference", "BUDGET/" + account.get("period") + "/" + account.get("line") + "/" + op + "/" + account.get("stage")
                + (target == null ? "" : ">" + target.get("stage")) + ("DDO-1".equals(holder) && HEAD.equals(account.get("line")) ? "" : "/" + holder));
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
        // Follow-up 2 (v): the data set's set-up (payees, budget) is tenant administration, done with the token the
        // live runbook already gives the seed and the preflight (SANGAM_ADMIN_TOKEN)
        env.put("SANGAM_ADMIN_TOKEN", PresentationSeedAgainstServerTest.token("rajendra.sharma",
                List.of("sangam-tenant-admin", "sangam-user")));
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
