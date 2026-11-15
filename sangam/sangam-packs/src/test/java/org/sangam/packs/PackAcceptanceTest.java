// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;

import org.sangam.packs.port.PackAuditEvent;
import org.sangam.packs.port.PackRuntime;
import org.sangam.packs.sim.LoggingAuditSink;
import org.sangam.packs.spi.PackDeployer;
import org.sangam.packs.spi.PackRegistry;

/**
 * SL-32: every acceptance scenario a pack ships ({@code pack.json} {@code acceptance[]}) is executed. The scenario
 * starts the pack's process with its {@code startVariables}; every task is answered from configuration by
 * {@link FormAnswers}, with the scenario's own answers ({@code run.answers}: task key → variables, applied to the
 * first {@code times} completions of that task) where the scenario steers the case, and its {@code run.steps}
 * ({@link AcceptanceSteps}: advance the scenario clock, complete one occurrence, correlate a message, set variables,
 * each when its activity is waiting; every step must have been applied); then its machine-checkable outcome
 * is asserted: {@code expectState} (the case state), and {@code run.expect} ({@code tasks}: the completed tasks contain
 * this sequence in order; {@code audit} / {@code notAudit}: audit event types present / absent; {@code ended}: the case
 * ended or is still waiting; {@code refusedAt}: completing this task is refused with this message code). A scenario
 * with no checkable outcome fails the build unless it says why it cannot be driven here ({@code run.manual}); the
 * list of those is printed and kept small (SECTOR-CONFORMANCE, SL-32). The generator writes all of this
 * (tools/packgen {@code Pack.accept}), so a pack cannot ship an acceptance block that no test runs.
 */
class PackAcceptanceTest {

    /** Decision 138: a fraction in scenario data becomes a BigDecimal case variable, never a Double. */
    private static final ObjectMapper JSON = com.fasterxml.jackson.databind.json.JsonMapper.builder()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    static ProcessEngine engine;
    static PackDriver driver;

    @BeforeAll
    static void setUp() {
        PackRuntime.reset();
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-acceptance;DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        cfg.setHistory("full");
        PackTestEngines.configure(cfg); // decisions 138 and FEEL (sangam-feel): as SangamProcessEnginePlugin does
        engine = cfg.buildProcessEngine();
        new PackDeployer(engine, PackRegistry.discover()).deployAll(PackTestTenant.ID);
        driver = new PackDriver(engine).withWorkers(); // external tasks too: the simulator stands in for the worker
    }

    @AfterAll
    static void tearDown() {
        engine.close();
    }

    record Scenario(String pack, List<String> main, JsonNode node) {
        String id() {
            return node.path("id").asText();
        }

        JsonNode run() {
            return node.path("run");
        }
    }

    static List<Scenario> scenarios() throws IOException {
        List<Scenario> out = new ArrayList<>();
        ClassLoader cl = PackAcceptanceTest.class.getClassLoader();
        try (InputStream idx = cl.getResourceAsStream("META-INF/sangam/packs.idx")) {
            for (String line : new String(idx.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String path = line.trim();
                if (path.isEmpty() || path.startsWith("#")) {
                    continue;
                }
                JsonNode m;
                try (InputStream in = cl.getResourceAsStream(path)) {
                    m = JSON.readTree(in);
                }
                List<String> main = new ArrayList<>();
                m.path("mainProcesses").forEach(n -> main.add(n.asText()));
                for (JsonNode a : m.path("acceptance")) {
                    out.add(new Scenario(m.path("id").asText(), main, a));
                }
            }
        }
        return out;
    }

    /**
     * {@code expect.merit} (GP-26 / GP-28 / GP-47): the merit-list outputs of the case ({@code MeritListDelegate},
     * {@code WaitlistDelegate}): {@code selected} (the {@code selectedKeys} in order), {@code waitlist} (keys in
     * order), {@code scores} (key to the score the merit list used), {@code allotted} (key to the category post),
     * {@code promoted} (the {@code promotedKey}). Keys are the cases the scenario started ({@code <scenario>/<key>}),
     * written without the scenario prefix.
     */
    static void assertMerit(JsonNode merit, String bk, String report) {
        if (merit.isMissingNode()) {
            return;
        }
        String p = bk + "/";
        if (merit.has("selected")) {
            List<String> want = new ArrayList<>();
            merit.path("selected").forEach(n -> want.add(p + n.asText()));
            Object got = driver.var(bk, "selectedKeys");
            assertEquals(want, got == null || got.toString().isBlank() ? List.of()
                    : List.of(got.toString().split(",")), report + ": selectedKeys");
        }
        Map<String, JsonNode> rows = new java.util.LinkedHashMap<>();
        jsonOf(driver.var(bk, "meritList")).forEach(r -> rows.put(r.path("key").asText(), r));
        if (merit.has("waitlist")) {
            List<String> want = new ArrayList<>();
            merit.path("waitlist").forEach(n -> want.add(p + n.asText()));
            List<String> got = new ArrayList<>();
            jsonOf(driver.var(bk, "waitlist")).forEach(r -> got.add(r.path("key").asText()));
            assertEquals(want, got, report + ": waitlist");
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = merit.path("scores").fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode row = rows.get(p + e.getKey());
            assertTrue(row != null, report + ": " + e.getKey() + " is not on the merit list");
            assertEquals(0, e.getValue().decimalValue().compareTo(row.path("score").decimalValue()),
                    report + ": score of " + e.getKey() + " was " + row.path("score"));
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = merit.path("allotted").fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode row = rows.get(p + e.getKey());
            assertTrue(row != null, report + ": " + e.getKey() + " is not on the merit list");
            assertEquals(e.getValue().asText(), row.path("allotted").asText(), report + ": post of " + e.getKey());
        }
        if (merit.hasNonNull("promoted")) {
            assertEquals(p + merit.path("promoted").asText(), String.valueOf(driver.var(bk, "promotedKey")),
                    report + ": promoted from the waitlist");
        }
    }

    private static JsonNode jsonOf(Object v) {
        if (v == null) {
            return JSON.createArrayNode();
        }
        try {
            return JSON.readTree(v instanceof byte[] b ? new String(b, StandardCharsets.UTF_8) : v.toString());
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** A pack's first main process ({@code pack.json mainProcesses}), for a {@code run.steps} start of that pack. */
    static String mainOf(String packId) {
        ClassLoader cl = PackAcceptanceTest.class.getClassLoader();
        try (InputStream idx = cl.getResourceAsStream("META-INF/sangam/packs.idx")) {
            for (String line : new String(idx.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String path = line.trim();
                if (path.isEmpty() || path.startsWith("#")) {
                    continue;
                }
                try (InputStream in = cl.getResourceAsStream(path)) {
                    JsonNode m = JSON.readTree(in);
                    if (packId.equals(m.path("id").asText())) {
                        return m.path("mainProcesses").path(0).asText();
                    }
                }
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        throw new IllegalArgumentException("no pack " + packId);
    }

    static boolean checkable(Scenario s) {
        return !s.run().hasNonNull("manual") && (s.node().hasNonNull("expectState") || s.run().has("expect"));
    }

    @Test
    void everyScenarioIsCheckableOrSaysWhyNot() throws IOException {
        List<String> unchecked = new ArrayList<>();
        List<String> manual = new ArrayList<>();
        for (Scenario s : scenarios()) {
            if (checkable(s)) {
                continue;
            }
            if (s.run().hasNonNull("manual") && !s.run().path("manual").asText().isBlank()) {
                manual.add(s.pack() + "/" + s.id() + ": " + s.run().path("manual").asText());
            } else {
                unchecked.add(s.pack() + "/" + s.id());
            }
        }
        System.out.println("[acceptance] not driven here (" + manual.size() + "):\n  " + String.join("\n  ", manual));
        assertTrue(unchecked.isEmpty(), "acceptance scenarios with no checkable outcome and no reason: " + unchecked);
    }

    @TestFactory
    Stream<DynamicTest> everyCheckableScenarioPasses() throws IOException {
        return scenarios().stream().filter(PackAcceptanceTest::checkable)
                .map(s -> DynamicTest.dynamicTest(s.pack() + "/" + s.id(), () -> execute(s)));
    }

    static void execute(Scenario s) {
        JsonNode run = s.run();
        String key = run.path("process").asText(s.main().get(0));
        String bk = "ACC/" + s.pack() + "/" + s.id();
        // each scenario is its own "given": no simulator, ledger, batch or audit state from another one (seed seats stay)
        // ... and its own clock: run.steps advance the engine and the pack clock together, reset after the scenario
        driver.resetClock();
        PackRuntime.install(PackRuntime.ports().withGateway(new org.sangam.packs.sim.SimulatorConnectorGateway())
                .withAudit(new LoggingAuditSink()).withLedgers(new org.sangam.packs.sim.InMemoryLedgerStore())
                .withBatches(new org.sangam.packs.sim.InMemoryBatchLedger()).withClock(PackDriver.clock()));
        try {
            drive(s, run, key, bk);
        } finally {
            driver.resetClock();
        }
    }

    private static void drive(Scenario s, JsonNode run, String key, String bk) {
        String stopAt = run.path("stopAt").asText(null);
        Map<String, Object> vars = AllPacksSmokeTest.bag();
        vars.putAll(JSON.convertValue(s.node().path("startVariables"), Map.class));
        engine.getRuntimeService().createProcessInstanceByKey(key).businessKey(bk).setVariables(vars)
                .processDefinitionTenantId(PackTestTenant.ID).execute();
        FormAnswers base = new FormAnswers(engine);
        Map<String, Integer> seen = new HashMap<>();
        JsonNode answers = run.path("answers");
        JsonNode expect = run.path("expect");
        String refusedAt = expect.path("refusedAt").path("task").asText(null);
        AcceptanceSteps steps = new AcceptanceSteps(engine, driver, bk, run.path("steps"), PackAcceptanceTest::mainOf);
        List<String> done = new ArrayList<>();
        List<String> progress = new ArrayList<>(); // the tasks completed so far, as of the current driver turn
        String refusal = null;
        try {
            done.addAll(driver.run(bk, t -> {
                String tk = t.getTaskDefinitionKey();
                if (tk.equals(stopAt)) {
                    return null;
                }
                PackDriver.Answer a = base.answer(t);
                int n = seen.merge(tk, 1, Integer::sum);
                JsonNode o = answers.path(tk);
                if (!o.isMissingNode() && n <= o.path("times").asInt(Integer.MAX_VALUE)) {
                    Iterator<Map.Entry<String, JsonNode>> it = o.path("vars").fields();
                    while (it.hasNext()) {
                        Map.Entry<String, JsonNode> e = it.next();
                        a.vars().put(e.getKey(), JSON.convertValue(e.getValue(), Object.class));
                    }
                    if (o.hasNonNull("person")) {
                        a = new PackDriver.Answer(o.path("person").asText(), a.seat(), a.vars());
                    }
                }
                return steps.answer(t, a, progress); // a run.steps "complete" is the most specific answer
            }, 120, d -> {
                progress.clear();
                progress.addAll(d);
                return steps.beforeTurn(d);
            }));
        } catch (RuntimeException e) {
            if (refusedAt == null) {
                throw e;
            }
            refusal = String.valueOf(e.getMessage());
            done.addAll(progress); // the completions before the refused one
        }
        assertTrue(steps.pending().isEmpty(), s.pack() + "/" + s.id() + ": run.steps never reached " + steps.pending()
                + " (done " + (done.isEmpty() ? progress : done) + ")");
        List<PackAuditEvent> audit = ((LoggingAuditSink) PackRuntime.audit()).timeline(bk);
        Set<String> types = new LinkedHashSet<>();
        audit.forEach(e -> types.add(e.type()));
        String report = s.pack() + "/" + s.id() + " (" + key + "): done " + done + ", state "
                + driver.var(bk, "caseState") + ", audit " + types;
        if (refusedAt != null) {
            assertTrue(refusal != null, report + ": completing " + refusedAt + " was not refused");
            assertTrue(refusal.contains(expect.path("refusedAt").path("code").asText()), report + ": " + refusal);
        }
        if (s.node().hasNonNull("expectState")) {
            assertEquals(s.node().path("expectState").asText(), String.valueOf(driver.var(bk, "caseState")), report);
        }
        if (expect.hasNonNull("openTask")) {
            assertTrue(engine.getTaskService().createTaskQuery().processInstanceBusinessKeyLike(bk + "%")
                    .taskDefinitionKey(expect.path("openTask").asText()).count() > 0, report + ": task not open");
        }
        if (expect.has("ended")) {
            assertEquals(expect.path("ended").asBoolean(), driver.ended(bk), report + ": ended");
        }
        int at = 0;
        for (JsonNode t : expect.path("tasks")) {
            int i = done.subList(at, done.size()).indexOf(t.asText());
            if (i < 0) {
                fail(report + ": task " + t.asText() + " not completed in order");
            }
            at += i + 1;
        }
        for (JsonNode a : expect.path("audit")) {
            assertTrue(types.contains(a.asText()), report + ": no " + a.asText());
        }
        for (JsonNode a : expect.path("notAudit")) {
            assertFalse(types.contains(a.asText()), report + ": unexpected " + a.asText());
        }
        // batch rounds (GP-09 / GP-49): the item keys of the child cases started in each round, read from their
        // business keys <batch>:<round>:<item> (BatchDelegate FAN_OUT; the batch id is the case's business key)
        for (Iterator<Map.Entry<String, JsonNode>> it = expect.path("batchRounds").fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            String prefix = bk + ":" + e.getKey() + ":";
            Set<String> items = new java.util.TreeSet<>();
            engine.getHistoryService().createHistoricProcessInstanceQuery().processInstanceBusinessKeyLike(prefix + "%")
                    .list().forEach(p -> items.add(p.getBusinessKey().substring(prefix.length())));
            Set<String> want = new java.util.TreeSet<>();
            e.getValue().forEach(n -> want.add(n.asText()));
            assertEquals(want, items, report + ": items of batch round " + e.getKey());
        }
        assertMerit(expect.path("merit"), bk, report);
        // case variables at the end (the root process instance), compared as text so 1 == 1L
        for (Iterator<Map.Entry<String, JsonNode>> it = expect.path("vars").fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            assertEquals(String.valueOf(JSON.convertValue(e.getValue(), Object.class)),
                    String.valueOf(driver.var(bk, e.getKey())), report + ": variable " + e.getKey());
        }
        if (!expect.path("incident").asBoolean(false)) {
            Set<String> mine = new java.util.HashSet<>();
            engine.getHistoryService().createHistoricProcessInstanceQuery().processInstanceBusinessKeyLike(bk + "%")
                    .list().forEach(p -> mine.add(p.getId()));
            assertEquals(0, engine.getRuntimeService().createIncidentQuery().list().stream()
                    .filter(i -> mine.contains(i.getProcessInstanceId())).count(), report + ": incidents");
        }
    }
}
