// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.engine.task.Task;
import org.sangam.packs.port.PackRuntime;
import org.sangam.packs.sim.InMemoryLedgerStore;
import org.sangam.packs.sim.LoggingAuditSink;
import org.sangam.packs.sim.SimulatorConnectorGateway;
import org.sangam.packs.spi.PackDeployer;
import org.sangam.packs.spi.PackRegistry;

/**
 * The presentation data set (deploy/presentation/data, DEMO-DATA lane 2026-11-19) loads cleanly on H2 with the shipped
 * packs: every case starts from its process key with the data set's own variables (no filler values), each task is
 * answered from its form, and each case reaches the state the data set declares: COMPLETED (closed, sandbox payment),
 * PAYMENT_HELD (passed by the PAO, the payment request held at the rail, nothing closed) or RUNNING (waits at the
 * declared task). No case leaves an incident. Connectors are the simulators: nothing pays anywhere.
 */
class PresentationDataSetH2Test {

    static final Path DATA = Path.of("").toAbsolutePath().getParent()
            .resolve("deploy/presentation/data/ifms-jaipur-fy2026-27.json");

    static ProcessEngine engine;
    static PackDriver driver;
    static JsonNode set;

    @BeforeAll
    static void setUp() throws Exception {
        set = new ObjectMapper().readTree(DATA.toFile());
        PackRuntime.reset();
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-presentation-data;DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        cfg.setHistory("full");
        PackTestEngines.configure(cfg);
        engine = cfg.buildProcessEngine();
        new PackDeployer(engine, PackRegistry.discover()).deployAll(PackTestTenant.ID);
        driver = new PackDriver(engine).withWorkers();
        PackRuntime.install(PackRuntime.ports().withGateway(new SimulatorConnectorGateway())
                .withAudit(new LoggingAuditSink()).withLedgers(new InMemoryLedgerStore())
                .withBatches(new org.sangam.packs.sim.InMemoryBatchLedger()).withClock(PackDriver.clock()));
    }

    @AfterAll
    static void tearDown() {
        engine.close();
        engine = null;
        driver = null;
        PackRuntime.reset();
    }

    /** The data set's variables, typed as the forms declare them (money: integer paise; decimals; dates). */
    static Map<String, Object> variables(JsonNode c) {
        Map<String, Object> v = new HashMap<>();
        JsonNode types = set.path("types");
        for (Iterator<Map.Entry<String, JsonNode>> it = c.path("variables").fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            String type = types.path(e.getKey()).asText("");
            JsonNode x = e.getValue();
            v.put(e.getKey(), switch (type) {
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
        v.put("sim_ledger_available", set.path("h2Simulator").path("fundsAvailable").asText());
        v.put("sim_registry_party", set.path("h2Simulator").path("registryParty").asText());
        set.path("h2Simulator").path("systems").fields().forEachRemaining(e -> v.put(e.getKey(), e.getValue().asText()));
        c.path("h2Directives").fields().forEachRemaining(e -> v.put(e.getKey(), e.getValue().asText()));
        return v;
    }

    static List<String> open(String bk) {
        return engine.getTaskService().createTaskQuery().processInstanceBusinessKeyLike(bk + "%").list().stream()
                .map(Task::getTaskDefinitionKey).toList();
    }

    static List<String> incidents(String bk) {
        List<String> mine = new ArrayList<>();
        engine.getHistoryService().createHistoricProcessInstanceQuery().processInstanceBusinessKeyLike(bk + "%").list()
                .forEach(p -> mine.add(p.getId()));
        return engine.getRuntimeService().createIncidentQuery().list().stream()
                .filter(i -> mine.contains(i.getProcessInstanceId()))
                .map(i -> i.getActivityId() + ": " + i.getIncidentMessage()).toList();
    }

    /** Why a case stands where it stands: its reason, finding and rule variables (all its process instances). */
    static Map<String, Object> facts(String bk) {
        Map<String, Object> f = new java.util.TreeMap<>();
        engine.getHistoryService().createHistoricProcessInstanceQuery().processInstanceBusinessKeyLike(bk + "%").list()
                .forEach(p -> engine.getHistoryService().createHistoricVariableInstanceQuery()
                        .processInstanceId(p.getId()).list().forEach(v -> {
                            if (v.getName().matches("(?i).*(reason|finding|rule|valid|verified|outcome).*")) {
                                f.put(v.getName(), v.getValue());
                            }
                        }));
        return f;
    }

    static List<String> payments(String bk) {
        return ((SimulatorConnectorGateway) PackRuntime.gateway()).journal().stream()
                .filter(r -> bk.equals(r.businessKey()) && r.connector().contains("payment"))
                .map(r -> r.connector() + "." + r.operation()).toList();
    }

    @Test
    void everyCaseOfTheDataSetReachesItsDeclaredState() {
        List<String> failures = new ArrayList<>();
        for (JsonNode c : set.path("cases")) {
            String bk = c.path("businessKey").asText();
            String target = c.path("target").asText();
            String stopAt = c.path("stopAt").asText(null);
            engine.getRuntimeService().createProcessInstanceByKey(c.path("process").asText()).businessKey(bk)
                    .setVariables(variables(c)).processDefinitionTenantId(PackTestTenant.ID).execute();
            FormAnswers answers = new FormAnswers(engine);
            List<String> done = driver.run(bk, (Task t) -> t.getTaskDefinitionKey().equals(stopAt) ? null
                    : answers.answer(t), 150);
            Object state = driver.var(bk, "caseState");
            String r = c.path("id").asText() + " " + bk + ": target " + target + ", done " + done + ", open " + open(bk)
                    + ", state " + state + ", payments " + payments(bk) + ", incidents " + incidents(bk)
                    + ", facts " + facts(bk);
            System.out.println("[presentation-data] " + r);
            boolean ok = incidents(bk).isEmpty() && switch (target) {
                case "COMPLETED" -> driver.ended(bk) && "CLOSED".equals(String.valueOf(state));
                case "PAYMENT_HELD" -> !driver.ended(bk) && done.contains("pc_pao") && !payments(bk).isEmpty()
                        && !"CLOSED".equals(String.valueOf(state));
                case "RUNNING" -> !driver.ended(bk) && open(bk).contains(stopAt);
                // the case waits for time or an outside answer (UC due date, SLA, statutory time limit), no task open
                case "WAITING" -> !driver.ended(bk) && c.path("expectState").asText().equals(String.valueOf(state));
                default -> false;
            };
            if (!ok) {
                failures.add(r);
            }
        }
        assertEquals(List.of(), failures);
    }

    @Test
    void theDataSetCoversTheBillJourneyAndGpf() {
        List<String> seen = new ArrayList<>();
        set.path("cases").forEach(c -> seen.add(c.path("flow").asText() + ":" + c.path("target").asText()));
        assertTrue(seen.contains("bill:COMPLETED") && seen.contains("bill:PAYMENT_HELD") && seen.contains("bill:RUNNING"),
                "bill journey: " + seen);
        assertTrue(seen.contains("gpf:COMPLETED") && seen.contains("gpf:RUNNING"), "GPF: " + seen);
        assertFalse(seen.isEmpty());
    }
}
