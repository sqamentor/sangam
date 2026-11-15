// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.task.Task;
import org.sangam.core.engine.SangamEngineAdapter;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.SangamEngineFactory;
import org.sangam.engine.SangamEngineSettings;
import org.sangam.packs.engine.LocalDateValueType;
import org.sangam.rest.port.ApiProblem;

/**
 * Decision 333 at the REST variable boundary: {@code {"type":"LocalDate","value":"2026-03-31"}} written as a process
 * start, worker complete or task complete variable is stored by the engine as a typed date and read back (instance,
 * task, worker fetch, history) as the same typed value; also inside maps and lists. Invalid text is a validation
 * problem; a date sent as a plain string stays a string. The shared T18 vector ({@code sdks/testing/t18-vectors.json},
 * kind {@code localdate}) round-trips through the engine. The calls are the ones the REST adapters make
 * ({@code TypedVariables.fromJson} into the engine adapter, {@code TypedVariables.toJson} of what it returns); the HTTP
 * layer above them is not part of this test.
 */
class TypedVariablesLocalDateH2Test {

    private static final String MODEL = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="t">
              <process id="t18" isExecutable="true">
                <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="x"/>
                <serviceTask id="x" operaton:type="external" operaton:topic="t18.localdate"/>
                <sequenceFlow id="f2" sourceRef="x" targetRef="ut"/><userTask id="ut"/>
                <sequenceFlow id="f3" sourceRef="ut" targetRef="e"/><endEvent id="e"/>
              </process>
            </definitions>
            """;

    private static Map<String, Object> typed(String type, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("value", value);
        return m;
    }

    private static OperatonEngineAdapter engine() {
        SangamEngineSettings s = new SangamEngineSettings();
        s.setSystemAuditScope("system:platform"); // decision 144: required at engine start
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true");
        s.setApplyEnvironment(false);
        s.setJobExecutorActivate(false);
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:tv333" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        OperatonEngineAdapter a = SangamEngineFactory.build(ds, s, "h2");
        a.deploy("t1", "tv333", Map.of("t18.bpmn", MODEL.getBytes(StandardCharsets.UTF_8)));
        return a;
    }

    @Test
    void aTypedLocalDateIsAJavaLocalDateAndIsWrittenBackTyped() {
        Object in = TypedVariables.fromJsonValue(typed("LocalDate", "2026-03-31"));
        assertEquals(LocalDate.of(2026, 3, 31), in, "a LocalDate, not text (decision 333)");
        assertEquals(LocalDate.of(2026, 3, 31), TypedVariables.fromJsonValue(typed("date", "2026-03-31")),
                "the lower-case alias names the same type");
        assertEquals(typed("LocalDate", "2026-03-31"), TypedVariables.toJsonValue(in));
        assertEquals("2026-03-31", TypedVariables.toJsonValue("2026-03-31"), "a plain string stays a string");
        assertInstanceOf(java.util.Date.class, TypedVariables.fromJsonValue(typed("Date", "2026-03-31T18:30:00Z")),
                "Date is an instant, a different type");
    }

    @Test
    void invalidDateTextIsAValidationProblemNeverCoerced() {
        for (Object bad : List.of("2026-02-30", "2026-3-31", "31-03-2026", "2026-03-31T00:00:00Z", " 2026-03-31",
                "+10000-01-01", 20260331)) {
            ApiProblem p = assertThrows(ApiProblem.class,
                    () -> TypedVariables.fromJson(Map.of("day", typed("LocalDate", bad))), String.valueOf(bad));
            assertEquals(400, p.status());
            assertEquals("VALIDATION_FAILED", p.code());
            assertTrue(p.detail().contains("LOCAL_DATE_INVALID"), p.detail());
        }
    }

    @Test
    void startWorkerAndTaskVariablesRoundTripTypedThroughTheEngine() {
        try (OperatonEngineAdapter a = engine()) {
            Map<String, Object> wireStart = new LinkedHashMap<>();
            wireStart.put("day", typed("LocalDate", "2026-03-31"));
            wireStart.put("oldDay", "2026-03-31");
            Map<String, Object> period = new LinkedHashMap<>();
            period.put("from", typed("LocalDate", "2026-04-01"));
            period.put("to", typed("LocalDate", "2027-03-31"));
            period.put("holidays", List.of(typed("LocalDate", "2026-08-15"), "2026-10-02"));
            wireStart.put("period", period);
            String pid = a.startProcess("t1", "t18", "TV-333", TypedVariables.fromJson(wireStart));

            // worker fetch: the variables a worker receives (EngineAdapterWorkAdapter / OperatonWorkAdapter)
            List<SangamEngineAdapter.LeasedWork> leased = a.fetchAndLockWork("w1", "t18.localdate", 1,
                    Duration.ofMinutes(1));
            assertEquals(1, leased.size());
            Map<String, Object> fetched = TypedVariables.toJson(leased.getFirst().work().variables());
            assertEquals(typed("LocalDate", "2026-03-31"), fetched.get("day"), "the worker sees the typed date");
            assertEquals("2026-03-31", fetched.get("oldDay"), "a string stays a string");
            assertEquals(period, fetched.get("period"), "nested dates stay typed in a map and a list");

            // worker complete
            var w = leased.getFirst().work();
            a.completeExternal(w.id(), "w1", w.lockVersion(),
                    TypedVariables.fromJson(Map.of("out_day", typed("LocalDate", "2026-03-31"))));

            // task read, then task complete
            Task task = a.processEngine().getTaskService().createTaskQuery().processInstanceId(pid).singleResult();
            assertNotNull(task);
            Map<String, Object> onTask = TypedVariables.toJson(a.processEngine().getTaskService().getVariables(task.getId()));
            assertEquals(typed("LocalDate", "2026-03-31"), onTask.get("out_day"), "worker output comes back typed");
            assertEquals(typed("LocalDate", "2026-03-31"), onTask.get("day"));
            a.completeTask(task.getId(), TypedVariables.fromJson(Map.of("decidedOn", typed("LocalDate", "2026-12-31"))));

            // history keeps the type after the instance ended
            var hist = a.processEngine().getHistoryService().createHistoricVariableInstanceQuery()
                    .processInstanceId(pid).variableName("decidedOn").singleResult();
            assertEquals(LocalDateValueType.NAME, hist.getTypeName());
            assertEquals(typed("LocalDate", "2026-12-31"), TypedVariables.toJsonValue(hist.getValue()));
            var oldHist = a.processEngine().getHistoryService().createHistoricVariableInstanceQuery()
                    .processInstanceId(pid).variableName("oldDay").singleResult();
            assertEquals("string", oldHist.getTypeName(), "an old string-stored date still reads as a string");
        }
    }

    @Test
    void theSharedT18LocalDateVectorRoundTripsThroughTheEngine() throws Exception {
        JsonNode vectors = new ObjectMapper().readTree(Files.readString(Path.of("..", "sdks", "testing",
                "t18-vectors.json")));
        List<JsonNode> local = new ArrayList<>();
        vectors.get("vectors").forEach(v -> {
            if ("localdate".equals(v.path("kind").asText())) {
                local.add(v);
            }
        });
        assertTrue(!local.isEmpty(), "the T18 vectors have a localdate vector");
        try (OperatonEngineAdapter a = engine()) {
            for (JsonNode v : local) {
                @SuppressWarnings("unchecked")
                Map<String, Object> wire = new ObjectMapper().convertValue(v.get("wire"), Map.class);
                Object decoded = TypedVariables.fromJsonValue(wire);
                assertEquals(LocalDate.parse(v.get("value").asText()), decoded, v.get("name").asText());
                String pid = a.startProcess("t1", "t18", "T18-" + v.get("name").asText(), Map.of("day", decoded));
                Object back = a.processEngine().getRuntimeService().getVariable(pid, "day");
                assertEquals(wire, TypedVariables.toJsonValue(back), "T18 " + v.get("name").asText());
            }
        }
    }
}
