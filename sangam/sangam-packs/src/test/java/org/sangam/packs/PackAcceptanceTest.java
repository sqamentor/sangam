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
 * first {@code times} completions of that task) where the scenario steers the case; then its machine-checkable outcome
 * is asserted: {@code expectState} (the case state), and {@code run.expect} ({@code tasks}: the completed tasks contain
 * this sequence in order; {@code audit} / {@code notAudit}: audit event types present / absent; {@code ended}: the case
 * ended or is still waiting; {@code refusedAt}: completing this task is refused with this message code). A scenario
 * with no checkable outcome fails the build unless it says why it cannot be driven here ({@code run.manual}); the
 * list of those is printed and kept small (SECTOR-CONFORMANCE, SL-32). The generator writes all of this
 * (tools/packgen {@code Pack.accept}), so a pack cannot ship an acceptance block that no test runs.
 */
class PackAcceptanceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
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
        engine = cfg.buildProcessEngine();
        new PackDeployer(engine, PackRegistry.discover()).deployAll(null);
        driver = new PackDriver(engine);
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
        PackRuntime.install(PackRuntime.ports().withGateway(new org.sangam.packs.sim.SimulatorConnectorGateway())
                .withAudit(new LoggingAuditSink()).withLedgers(new org.sangam.packs.sim.InMemoryLedgerStore())
                .withBatches(new org.sangam.packs.sim.InMemoryBatchLedger()));
        String stopAt = run.path("stopAt").asText(null);
        Map<String, Object> vars = AllPacksSmokeTest.bag();
        vars.putAll(JSON.convertValue(s.node().path("startVariables"), Map.class));
        engine.getRuntimeService().createProcessInstanceByKey(key).businessKey(bk).setVariables(vars)
                .processDefinitionWithoutTenantId().execute();
        FormAnswers base = new FormAnswers(engine);
        Map<String, Integer> seen = new HashMap<>();
        JsonNode answers = run.path("answers");
        JsonNode expect = run.path("expect");
        String refusedAt = expect.path("refusedAt").path("task").asText(null);
        List<String> done = new ArrayList<>();
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
                return a;
            }, 120));
        } catch (RuntimeException e) {
            if (refusedAt == null) {
                throw e;
            }
            refusal = String.valueOf(e.getMessage());
        }
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
        if (!expect.path("incident").asBoolean(false)) {
            Set<String> mine = new java.util.HashSet<>();
            engine.getHistoryService().createHistoricProcessInstanceQuery().processInstanceBusinessKeyLike(bk + "%")
                    .list().forEach(p -> mine.add(p.getId()));
            assertEquals(0, engine.getRuntimeService().createIncidentQuery().list().stream()
                    .filter(i -> mine.contains(i.getProcessInstanceId())).count(), report + ": incidents");
        }
    }
}
