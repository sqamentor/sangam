// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.engine.task.Task;

import org.sangam.packs.port.ConnectorRequest;
import org.sangam.packs.port.ConnectorResult;
import org.sangam.packs.port.PackAuditEvent;
import org.sangam.packs.port.PackRuntime;
import org.sangam.packs.port.VerifiedPayees;
import org.sangam.packs.sim.DemoSandbox;
import org.sangam.packs.sim.InMemoryLedgerStore;
import org.sangam.packs.sim.LoggingAuditSink;
import org.sangam.packs.sim.SimulatorConnectorGateway;
import org.sangam.packs.spi.PackDeployer;
import org.sangam.packs.spi.PackRegistry;

/**
 * Register E-26 (owner 2026-11-21: the bill journey is shown on the demo's sandbox tenant). The seven cases of the
 * presentation data set that end in a bill (BILL-0001..0005, GPF-0001, ADV-0001), with the data set's own variables.
 *
 * <p>Study, {@link #theConnectorsEachBillCaseCalls}: every pack connector id each case calls on its way to its
 * target, in the order of the first call, read from the simulator's journal with every connector simulated.
 *
 * <p>The sandbox tests run in production mode against {@link AsDeployed}: an installed gateway that answers exactly
 * the ids bound for GOI.FIN on the server as deployed and refuses every other id with CONNECTOR_NOT_CONFIGURED, as the
 * server's gateway does without the simulator fallback. Before the fix every case stopped at an incident: nothing
 * answered {@code ifms.duplicateBill}, and the rejection order that followed could not be issued ({@code docs.issue}).
 */
class SandboxBillJourneyTest {

    static final Path DATA = Path.of("").toAbsolutePath().getParent()
            .resolve("deploy/presentation/data/ifms-jaipur-fy2026-27.json");
    /** The cases of the data set that end in a bill. */
    static final List<String> CASES = List.of("BILL-0001", "BILL-0002", "BILL-0003", "BILL-0004", "BILL-0005",
            "GPF-0001", "ADV-0001");
    /**
     * The pack connector ids bound to an integration connector for GOI.FIN on the server as deployed (line
     * {@code DEMO-SEQUENCE | connectors} of DemoLoaderSequenceAgainstServerTest; CI run 44846993120).
     */
    static final Set<String> BOUND = Set.of("config.classification", "config.documentType", "config.profile",
            "config.series", "esign.sign", "ledger.allocate", "ledger.available", "ledger.consume",
            "ledger.distribute", "ledger.encumber", "ledger.release", "ledger.releaseHolds", "notify.email",
            "notify.sms", "registry.party");
    /** The ids the sandbox answers beyond W-5's payment rails and statement: the list of register E-26. */
    static final Set<String> SANDBOX_CHECKS = Set.of("ifms.duplicateBill", "docs.issue");
    static final String NOT_CONFIGURED = "CONNECTOR_NOT_CONFIGURED";

    static ProcessEngine engine;
    static PackDriver driver;
    static JsonNode set;
    LoggingAuditSink audit;
    AsDeployed deployed;

    /** The installed gateway of the server as deployed: bound ids answer, every other id is not configured. */
    static final class AsDeployed extends SimulatorConnectorGateway {
        /** {@code <businessKey>|<connector>} of every request refused as not configured, in order. */
        final List<String> refused = java.util.Collections.synchronizedList(new ArrayList<>());
        /** {@code <businessKey>|<connector>} of every request a bound connector answered, in order. */
        final List<String> answered = java.util.Collections.synchronizedList(new ArrayList<>());

        private ConnectorResult refusal(String bk, String connector) {
            refused.add(bk + "|" + connector);
            return ConnectorResult.failed(NOT_CONFIGURED, "no integration connector bound to " + connector);
        }

        @Override
        public ConnectorResult query(ConnectorRequest r) {
            if (!BOUND.contains(r.connector())) {
                return refusal(r.businessKey(), r.connector());
            }
            answered.add(r.businessKey() + "|" + r.connector());
            return super.query(r);
        }

        @Override
        public ConnectorResult submit(ConnectorRequest r) {
            if (!BOUND.contains(r.connector())) {
                return refusal(r.businessKey(), r.connector());
            }
            answered.add(r.businessKey() + "|" + r.connector());
            return super.submit(r);
        }

        Set<String> of(List<String> calls, String bk) {
            Set<String> out = new LinkedHashSet<>();
            synchronized (calls) {
                calls.stream().filter(c -> c.startsWith(bk + "|")).forEach(c -> out.add(c.substring(bk.length() + 1)));
            }
            return out;
        }
    }

    @BeforeAll
    static void setUp() throws Exception {
        set = new ObjectMapper().readTree(DATA.toFile());
        PackRuntime.reset();
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-sandbox-bill;DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        cfg.setHistory("full");
        PackTestEngines.configure(cfg);
        engine = cfg.buildProcessEngine();
        new PackDeployer(engine, PackRegistry.discover()).deployAll(PackTestTenant.ID);
        driver = new PackDriver(engine).withWorkers();
    }

    @AfterAll
    static void tearDown() {
        engine.close();
        engine = null;
        driver = null;
        set = null;
        PackRuntime.reset();
        DemoSandbox.install(null);
    }

    @AfterEach
    void reset() {
        DemoSandbox.install(null);
        VerifiedPayees.install(null);
        PackRuntime.reset();
        driver.resetClock();
    }

    static JsonNode caseOf(String id) {
        for (JsonNode c : set.path("cases")) {
            if (id.equals(c.path("id").asText())) {
                return c;
            }
        }
        throw new IllegalArgumentException("no case " + id);
    }

    /**
     * The case's variables as the data set gives them, typed as the forms declare them. {@code directives}: the
     * simulator directives to carry. A hosted server is sent none by the loader (load_presentation_data.py posts the
     * data set's variables only); here the directives of the BOUND connectors stand in for the answers of the
     * integration connectors bound on the server (ledger, party registry, document types, classification).
     */
    static Map<String, Object> variables(JsonNode c, Map<String, String> directives) {
        Map<String, Object> v = new HashMap<>();
        JsonNode types = set.path("types");
        for (Iterator<Map.Entry<String, JsonNode>> it = c.path("variables").fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode x = e.getValue();
            v.put(e.getKey(), switch (types.path(e.getKey()).asText("")) {
                case "decimal" -> new BigDecimal(x.asText());
                case "date" -> Date.from(LocalDate.parse(x.asText()).atStartOfDay(ZoneOffset.UTC).toInstant());
                default -> x.isIntegralNumber() ? (Object) x.asLong() : x.asText();
            });
        }
        String by = c.path("startedBy").asText();
        v.put("initiator", by);
        JsonNode seat = set.path("people").path(by).path("seat");
        if (!seat.isNull() && !seat.isMissingNode()) {
            v.put("actingSeat", seat.asText());
        }
        v.putAll(directives);
        return v;
    }

    /** The answers of the connectors that are bound on the server, as the data set's H2 section gives them. */
    static Map<String, String> boundAnswers() {
        Map<String, String> d = new LinkedHashMap<>();
        d.put("sim_ledger_available", set.path("h2Simulator").path("fundsAvailable").asText());
        d.put("sim_registry_party", set.path("h2Simulator").path("registryParty").asText());
        set.path("h2Simulator").path("systems").fields().forEachRemaining(e -> {
            if (e.getKey().startsWith("sim_config_")) {
                d.put(e.getKey(), e.getValue().asText());
            }
        });
        return d;
    }

    /** Every directive of the H2 load test (PresentationDataSetH2Test): all connectors simulated. */
    static Map<String, String> h2Answers(JsonNode c) {
        Map<String, String> d = boundAnswers();
        set.path("h2Simulator").path("systems").fields().forEachRemaining(e -> d.put(e.getKey(), e.getValue().asText()));
        c.path("h2Directives").fields().forEachRemaining(e -> d.put(e.getKey(), e.getValue().asText()));
        return d;
    }

    /** Starts the case under {@code bk} and drives it to its stop task or until nothing moves; the tasks done. */
    static List<String> run(JsonNode c, String bk, Map<String, String> directives) {
        String stopAt = c.path("stopAt").asText(null);
        engine.getRuntimeService().createProcessInstanceByKey(c.path("process").asText()).businessKey(bk)
                .setVariables(variables(c, directives)).processDefinitionTenantId(PackTestTenant.ID).execute();
        FormAnswers answers = new FormAnswers(engine);
        List<String> done = new ArrayList<>();
        for (int round = 0; round < 10; round++) {
            try {
                done.addAll(driver.run(bk, (Task t) -> t.getTaskDefinitionKey().equals(stopAt) ? null
                        : answers.answer(t), 150));
                break;
            } catch (IllegalStateException failedJob) {
                // a job that fails stays as the incident of a server once its retries are used up: the case stands
                // where the job started, and the jobs of other cases go on
                done.add("FAILED JOB: " + failedJob.getMessage());
                engine.getManagementService().createJobQuery().withException().list()
                        .forEach(j -> engine.getManagementService().setJobRetries(j.getId(), 0));
            }
        }
        return done;
    }

    static List<String> open(String bk) {
        return engine.getTaskService().createTaskQuery().processInstanceBusinessKey(bk).list().stream()
                .map(Task::getTaskDefinitionKey).toList();
    }

    /** Whether the case stands at the target the data set declares; {@code done} are the tasks completed. */
    static boolean atTarget(JsonNode c, String bk, List<String> done) {
        Object state = driver.var(bk, "caseState");
        return switch (c.path("target").asText()) {
            case "COMPLETED" -> driver.ended(bk) && "CLOSED".equals(String.valueOf(state));
            case "PAYMENT_HELD" -> !driver.ended(bk) && done.contains("pc_pao") && open(bk).isEmpty()
                    && !"CLOSED".equals(String.valueOf(state));
            case "RUNNING" -> !driver.ended(bk) && open(bk).contains(c.path("stopAt").asText());
            default -> false;
        };
    }

    private void production() {
        audit = new LoggingAuditSink();
        deployed = new AsDeployed();
        driver.resetClock();
        PackRuntime.install(PackRuntime.ports().withGateway(deployed).withAudit(audit)
                .withLedgers(new InMemoryLedgerStore()).withBatches(new org.sangam.packs.sim.InMemoryBatchLedger())
                .withClock(PackDriver.clock()).withProduction(true));
    }

    /** The connector ids of the case's audit events that say the sandbox simulated the answer. */
    private Set<String> simulated(String bk) {
        Set<String> out = new TreeSet<>();
        for (PackAuditEvent e : audit.timeline(bk)) {
            if (Boolean.TRUE.equals(e.details().get("simulated")) && e.details().get("connector") != null) {
                out.add(String.valueOf(e.details().get("connector")));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ study (point 1 of the brief)

    /** The distinct connector ids of each case, in the order of their first call, with every connector simulated. */
    static final Map<String, List<String>> CALLED = new LinkedHashMap<>();

    static {
        List<String> intake = List.of("config.profile", "config.documentType", "config.classification",
                "ifms.duplicateBill", "registry.party", "ledger.available", "ledger.encumber", "config.series");
        List<String> paid = new ArrayList<>(intake);
        paid.addAll(List.of("pfms.payment", "bank.statement", "ledger.consume", "docs.issue", "notify.sms",
                "notify.email", "notify.inapp"));
        CALLED.put("BILL-0001", paid);
        CALLED.put("BILL-0002", paid);
        List<String> held = new ArrayList<>(intake);
        held.add("pfms.payment");
        CALLED.put("BILL-0003", held);
        CALLED.put("BILL-0004", intake);
        CALLED.put("BILL-0005", intake);
        CALLED.put("GPF-0001", paid);
        List<String> advance = new ArrayList<>(paid);
        advance.add("ledger.allocate");
        CALLED.put("ADV-0001", advance);
    }

    @Test
    void theConnectorsEachBillCaseCalls() {
        SimulatorConnectorGateway all = new SimulatorConnectorGateway();
        PackRuntime.install(PackRuntime.ports().withGateway(all).withAudit(new LoggingAuditSink())
                .withLedgers(new InMemoryLedgerStore()).withBatches(new org.sangam.packs.sim.InMemoryBatchLedger())
                .withClock(PackDriver.clock()));
        Map<String, List<String>> seen = new LinkedHashMap<>();
        List<String> notAtTarget = new ArrayList<>();
        for (String id : CASES) {
            JsonNode c = caseOf(id);
            String bk = c.path("businessKey").asText() + "/study";
            List<String> done = run(c, bk, h2Answers(c));
            Set<String> ids = new LinkedHashSet<>();
            all.journal().stream().filter(r -> bk.equals(r.businessKey())).forEach(r -> ids.add(r.connector()));
            seen.put(id, new ArrayList<>(ids));
            System.out.println("[sandbox-bill] study " + id + " (" + c.path("target").asText() + "): tasks " + done
                    + ", connectors " + ids);
            if (!atTarget(c, bk, done)) {
                notAtTarget.add(id + " open " + open(bk) + " state " + driver.var(bk, "caseState"));
            }
        }
        assertEquals(List.of(), notAtTarget, "every case reaches its target with every connector simulated");
        assertEquals(CALLED, seen);
        // of everything these cases call, only the listed ids and one notification channel have nobody on the server
        Set<String> nobody = new TreeSet<>();
        seen.values().forEach(nobody::addAll);
        nobody.removeAll(BOUND);
        nobody.removeAll(DemoSandbox.PAYMENT_CONNECTORS);
        nobody.removeAll(DemoSandbox.STATEMENT_CONNECTORS);
        assertEquals(new TreeSet<>(Set.of("docs.issue", "ifms.duplicateBill", "notify.inapp")), nobody);
    }

    // ------------------------------------------------------------------ the sandbox tenant

    @Test
    void onTheSandboxTenantEachBillCaseReachesItsTargetWithTheListedChecksSimulated() {
        DemoSandbox.install(List.of(PackTestTenant.ID));
        production();
        List<String> wrong = new ArrayList<>();
        for (String id : CASES) {
            JsonNode c = caseOf(id);
            String bk = c.path("businessKey").asText() + "/sandbox";
            // BILL-0003 is held at the rail by a directive of its own (the sandbox pays unless it is told otherwise)
            Map<String, String> directives = boundAnswers();
            c.path("h2Directives").fields().forEachRemaining(e -> directives.put(e.getKey(), e.getValue().asText()));
            List<String> done = run(c, bk, directives);
            Set<String> sim = simulated(bk);
            Set<String> refused = deployed.of(deployed.refused, bk);
            System.out.println("[sandbox-bill] sandbox " + id + " (" + c.path("target").asText() + "): tasks " + done
                    + ", bound " + deployed.of(deployed.answered, bk) + ", simulated " + sim + ", refused " + refused
                    + ", open " + open(bk) + ", state " + driver.var(bk, "caseState"));
            if (!atTarget(c, bk, done)) {
                wrong.add(id + ": not at target " + c.path("target").asText() + ", tasks " + done + ", open " + open(bk)
                        + ", state " + driver.var(bk, "caseState"));
            }
            // exactly the listed ids (and W-5's) are simulated, each said so in the audit trail
            Set<String> expected = new TreeSet<>(CALLED.get(id));
            expected.removeAll(BOUND);
            expected.remove("notify.inapp");
            if (!sim.equals(expected)) {
                wrong.add(id + ": simulated per audit " + sim + ", expected " + expected);
            }
            // the in-app notification has no connector on the server; a notification never stops a case
            Set<String> mayBeRefused = CALLED.get(id).contains("notify.inapp") ? Set.of("notify.inapp") : Set.of();
            if (!refused.equals(mayBeRefused)) {
                wrong.add(id + ": refused by the installed gateway " + refused + ", expected " + mayBeRefused);
            }
            for (PackAuditEvent e : audit.timeline(bk)) {
                if (!Boolean.TRUE.equals(e.details().get(DemoSandbox.AUDIT_KEY))) {
                    wrong.add(id + ": audit event without sandbox=true: " + e);
                }
            }
        }
        assertEquals(List.of(), wrong);
    }

    @Test
    void theDuplicateCheckOfASandboxTenantIsAnsweredMarkedAndAudited() {
        DemoSandbox.install(List.of(PackTestTenant.ID));
        production();
        JsonNode c = caseOf("BILL-0005");
        String bk = c.path("businessKey").asText() + "/dup";
        run(c, bk, boundAnswers());
        assertEquals("COMPLETED", driver.var(bk, "dupStatus"));
        assertEquals(Boolean.FALSE, driver.var(bk, "dupDuplicate"), "the sandbox's answer: no duplicate found");
        assertEquals(Boolean.TRUE, driver.var(bk, "dupSandbox"), "the answer is marked as the sandbox's");
        List<PackAuditEvent> checks = audit.timeline(bk).stream()
                .filter(e -> "ifms.duplicateBill".equals(e.details().get("connector"))).toList();
        assertEquals(1, checks.size(), "one audit record of the simulated check: " + audit.timeline(bk));
        PackAuditEvent e = checks.get(0);
        assertEquals("EXT_OP_ACKNOWLEDGED", e.type());
        assertEquals(true, e.details().get(DemoSandbox.AUDIT_KEY));
        assertEquals(true, e.details().get("simulated"));
        assertEquals("check", e.details().get("operation"));
        assertFalse(deployed.of(deployed.refused, bk).contains("ifms.duplicateBill"),
                "the installed gateway is not asked");
    }

    @Test
    void anUnlistedConnectorOfASandboxTenantIsStillNotConfigured() {
        DemoSandbox.install(List.of(PackTestTenant.ID));
        production();
        for (String id : List.of("rail.ekuber", "rail.agencyBank", "rail.stateBank", "rail.cheque", "rail.loc",
                "rail.fcy", "paygov.fee", "grantee.registry", "dsc.sign", "referral.send", "referral.reply",
                "notify.inapp", "ifms.budget", "docs.render", "ifms.anything")) {
            ConnectorRequest r = new ConnectorRequest(PackTestTenant.ID, id, "call", "op-" + id, "BK/unlisted", "pi-1",
                    Map.of(), "h-" + id);
            assertEquals(NOT_CONFIGURED, PackRuntime.gateway().query(r).reasonCode(), id + " query");
            assertEquals(NOT_CONFIGURED, PackRuntime.gateway().submit(r).reasonCode(), id + " submit");
            assertNull(PackRuntime.gateway().query(r).data().get(DemoSandbox.AUDIT_KEY), id);
        }
        assertEquals(15, deployed.of(deployed.refused, "BK/unlisted").size(), "every one reached the installed gateway");
    }

    @Test
    void theListIsExactlyTheTwoChecksEachWithItsShippedAnswerAndNeitherMovesMoney() {
        assertEquals(SANDBOX_CHECKS, DemoSandbox.CHECK_CONNECTORS);
        assertEquals(Map.of("ifms.duplicateBill", "duplicate=false", "docs.issue", ""), DemoSandbox.checkAnswers());
        for (String id : DemoSandbox.CHECK_CONNECTORS) {
            assertEquals(java.util.Optional.of(org.sangam.packs.spi.MoneyMovement.NONE),
                    org.sangam.packs.spi.ConnectorMoneyMovements.declared(id), id + " is declared as moving no money");
            assertFalse(DemoSandbox.PAYMENT_CONNECTORS.contains(id) || DemoSandbox.STATEMENT_CONNECTORS.contains(id), id);
        }
        DemoSandbox.install(Set.of("t-demo"));
        assertTrue(DemoSandbox.simulates("t-demo", "ifms.duplicateBill"));
        assertTrue(DemoSandbox.simulates("t-demo", "docs.issue"));
        assertTrue(DemoSandbox.simulates("t-demo", "pfms.payment"), "W-5 is unchanged");
        assertFalse(DemoSandbox.simulates("t-demo", "rail.ekuber"));
        assertFalse(DemoSandbox.simulates("t-demo", "ledger.consume"));
        assertFalse(DemoSandbox.simulates("t-real", "ifms.duplicateBill"));
        assertFalse(DemoSandbox.simulates(null, "ifms.duplicateBill"));
    }

    @Test
    void theCheckSimulatorRefusesEveryOtherTenantAndEveryOtherConnectorWhenCalledDirectly() {
        DemoSandbox.install(Set.of("t-demo"));
        for (String id : DemoSandbox.CHECK_CONNECTORS) {
            ConnectorRequest other = new ConnectorRequest("t-real", id, "check", "op-d-" + id, "BK/d", "pi-d",
                    Map.of("payee", "X"), "h-d-" + id);
            assertEquals(DemoSandbox.REFUSED, DemoSandbox.CHECKS.query(other).reasonCode(), id);
            assertEquals(DemoSandbox.REFUSED, DemoSandbox.CHECKS.submit(other).reasonCode(), id);
            assertEquals(DemoSandbox.REFUSED, DemoSandbox.CHECKS.lookup("t-real", id, "op-d-" + id).reasonCode(), id);
            assertEquals(DemoSandbox.REFUSED, DemoSandbox.CHECKS.cancel("t-real", id, "op-d-" + id, "x").reasonCode(), id);
            assertTrue(org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                    () -> DemoSandbox.CHECKS.openReconciliation(new org.sangam.packs.port.ReconciliationRequest("t-real",
                            id, "op-d-" + id, "BK/d", "pi-d", "UNKNOWN_OUTCOME", Map.of()))).getMessage()
                    .startsWith(DemoSandbox.REFUSED));
        }
        // the listed tenant is answered, and the answer says it is the sandbox's
        ConnectorResult dup = DemoSandbox.CHECKS.query(new ConnectorRequest("t-demo", "ifms.duplicateBill", "check", null,
                "BK/e", "pi-e", Map.of("payee", "X"), "h-e"));
        assertEquals(org.sangam.packs.port.OperationStatus.COMPLETED, dup.status());
        assertEquals(false, dup.data().get("duplicate"));
        assertEquals(true, dup.data().get(DemoSandbox.AUDIT_KEY));
        ConnectorRequest doc = new ConnectorRequest("t-demo", "docs.issue", "payment-advice", "op-e-doc", "BK/e", "pi-e",
                Map.of("template", "payment-advice"), "h-e-doc");
        ConnectorResult first = DemoSandbox.CHECKS.submit(doc);
        assertEquals(org.sangam.packs.port.OperationStatus.COMPLETED, first.status());
        assertTrue(String.valueOf(first.data().get("documentNo")).startsWith("DOC/"), first.data().toString());
        assertEquals(true, first.data().get(DemoSandbox.AUDIT_KEY));
        assertEquals(first.data().get("documentNo"), DemoSandbox.CHECKS.submit(doc).data().get("documentNo"),
                "a repeat of the same operation gets the same number");
        // a connector that is not on the list is never answered by the check simulator, whoever asks
        for (String id : List.of("pfms.payment", "ifms.payment", "bank.transfer", "bank.statement", "rail.ekuber",
                "ledger.consume", "ifms.budget", "docs.render", "paygov.fee")) {
            ConnectorRequest r = new ConnectorRequest("t-demo", id, "call", "op-f-" + id, "BK/f", "pi-f", Map.of(),
                    "h-f-" + id);
            assertEquals(DemoSandbox.NOT_LISTED, DemoSandbox.CHECKS.query(r).reasonCode(), id);
            assertEquals(DemoSandbox.NOT_LISTED, DemoSandbox.CHECKS.submit(r).reasonCode(), id);
            assertEquals(DemoSandbox.NOT_LISTED, DemoSandbox.CHECKS.lookup("t-demo", id, "op-f-" + id).reasonCode(), id);
            assertEquals(DemoSandbox.NOT_LISTED, DemoSandbox.CHECKS.cancel("t-demo", id, "op-f-" + id, "x").reasonCode(), id);
        }
    }

    @Test
    void aListedConnectorOfAnotherTenantIsStillNotConfiguredAndItsBillStops() {
        DemoSandbox.install(List.of("t-some-other-tenant"));
        production();
        for (String id : SANDBOX_CHECKS) {
            ConnectorRequest r = new ConnectorRequest(PackTestTenant.ID, id, "check", "op-x-" + id, "BK/other", "pi-2",
                    Map.of("payee", "X"), "h-x-" + id);
            assertEquals(NOT_CONFIGURED, PackRuntime.gateway().query(r).reasonCode(), id + " query");
            assertEquals(NOT_CONFIGURED, PackRuntime.gateway().submit(r).reasonCode(), id + " submit");
        }
        assertEquals(SANDBOX_CHECKS, deployed.of(deployed.refused, "BK/other"));
        JsonNode c = caseOf("BILL-0001");
        String bk = c.path("businessKey").asText() + "/other-tenant";
        List<String> done = run(c, bk, boundAnswers());
        assertFalse(driver.ended(bk), "the bill of a tenant that is not a sandbox tenant does not get through");
        assertEquals("FAILED", driver.var(bk, "dupStatus"));
        assertEquals(NOT_CONFIGURED, driver.var(bk, "dupReasonCode"));
        assertTrue(done.toString().contains("could not be issued: " + NOT_CONFIGURED), "stops as before: " + done);
        assertTrue(deployed.of(deployed.refused, bk).containsAll(SANDBOX_CHECKS), "both reached the installed gateway");
        audit.timeline(bk).forEach(e -> {
            assertNull(e.details().get(DemoSandbox.AUDIT_KEY), e.toString());
            assertNull(e.details().get("simulated"), e.toString());
        });
    }
}
