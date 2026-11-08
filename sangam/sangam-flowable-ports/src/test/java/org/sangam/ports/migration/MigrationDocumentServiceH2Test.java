// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.task.Task;
import org.sangam.ports.PortsH2Engine;

/** FC-18 JSON migration documents on a real engine: parse, dry run, 1:1, N:1, 1:N, variables, hooks, per-instance failure. */
class MigrationDocumentServiceH2Test {

    /** v1: a -> b -> end (two user tasks in sequence) plus a parallel "check" task. */
    private static final String V1 = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:operaton="http://operaton.org/schema/1.0/bpmn"
                         targetNamespace="sangam-test">
              <process id="mig" isExecutable="true" operaton:historyTimeToLive="1">
                <startEvent id="s"/>
                <sequenceFlow id="f0" sourceRef="s" targetRef="fork"/>
                <parallelGateway id="fork"/>
                <sequenceFlow id="f1" sourceRef="fork" targetRef="review"/>
                <sequenceFlow id="f2" sourceRef="fork" targetRef="check"/>
                <userTask id="review"/>
                <userTask id="check"/>
                <sequenceFlow id="f3" sourceRef="review" targetRef="e1"/>
                <sequenceFlow id="f4" sourceRef="check" targetRef="e2"/>
                <endEvent id="e1"/>
                <endEvent id="e2"/>
              </process>
            </definitions>""";

    /** v2: review renamed to review2, check split into checkA + checkB (both reached from the fork). */
    private static final String V2 = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:operaton="http://operaton.org/schema/1.0/bpmn"
                         targetNamespace="sangam-test">
              <process id="mig" isExecutable="true" operaton:historyTimeToLive="1">
                <startEvent id="s"/>
                <sequenceFlow id="f0" sourceRef="s" targetRef="fork"/>
                <parallelGateway id="fork"/>
                <sequenceFlow id="f1" sourceRef="fork" targetRef="review2"/>
                <sequenceFlow id="f2" sourceRef="fork" targetRef="checkA"/>
                <sequenceFlow id="f5" sourceRef="fork" targetRef="checkB"/>
                <userTask id="review2"/>
                <userTask id="checkA"/>
                <userTask id="checkB"/>
                <sequenceFlow id="f3" sourceRef="review2" targetRef="e1"/>
                <sequenceFlow id="f4" sourceRef="checkA" targetRef="e2"/>
                <sequenceFlow id="f6" sourceRef="checkB" targetRef="e3"/>
                <endEvent id="e1"/>
                <endEvent id="e2"/>
                <endEvent id="e3"/>
              </process>
            </definitions>""";

    private static final String DOC = """
            {"source":{"key":"mig","version":1},"target":{"key":"mig","version":2},
             "mappings":[{"from":["review"],"to":["review2"]},{"from":["check"],"to":["checkA","checkB"]}],
             "variables":{"migratedFrom":"v1"},"preHook":"pre","postHook":"post"}""";

    private PortsH2Engine h2;
    private final List<String> calls = new ArrayList<>();
    private MigrationDocumentService svc;

    @BeforeEach
    void setUp() {
        h2 = PortsH2Engine.start(null);
        h2.engine.getRepositoryService().createDeployment().addString("v1.bpmn", V1).deploy();
        h2.engine.getRepositoryService().createDeployment().addString("v2.bpmn", V2).deploy();
        svc = new MigrationDocumentService(h2.engine, name -> switch (name) {
            case "pre" -> (e, pi, d) -> calls.add("pre:" + pi);
            case "post" -> (e, pi, d) -> calls.add("post:" + pi + ":" + e.getRuntimeService().getVariable(pi, "migratedFrom"));
            case "boom" -> (e, pi, d) -> {
                throw new IllegalStateException("hook failed for " + pi);
            };
            default -> null;
        });
    }

    @AfterEach
    void tearDown() {
        h2.close();
    }

    private String start() {
        String v1 = h2.engine.getRepositoryService().createProcessDefinitionQuery().processDefinitionKey("mig")
                .processDefinitionVersion(1).singleResult().getId();
        return h2.engine.getRuntimeService().startProcessInstanceById(v1).getId();
    }

    private String targetId() {
        return h2.engine.getRepositoryService().createProcessDefinitionQuery().processDefinitionKey("mig")
                .processDefinitionVersion(2).singleResult().getId();
    }

    private List<String> activeTasks(String pi) {
        return h2.engine.getTaskService().createTaskQuery().processInstanceId(pi).list().stream()
                .map(Task::getTaskDefinitionKey).sorted().collect(Collectors.toList());
    }

    @Test
    void parseAppliesDefaultsAndKeepsAllFields() {
        MigrationDocument d = svc.parse(DOC);
        assertEquals("mig", d.source().key());
        assertEquals(2, d.target().version());
        assertEquals(2, d.mappings().size());
        assertEquals(List.of("checkA", "checkB"), d.mappings().get(1).to());
        assertEquals(Map.of("migratedFrom", "v1"), d.variables());
        assertTrue(d.processInstanceIds().isEmpty());
        MigrationDocument empty = svc.parse("{\"source\":{\"id\":\"x\"},\"target\":{\"id\":\"y\"},\"unknown\":1}");
        assertTrue(empty.mappings().isEmpty() && empty.variables().isEmpty() && empty.processInstanceIds().isEmpty());
    }

    @Test
    void dryRunCountsWithoutChangingAnything() {
        String pi = start();
        start();
        var report = svc.dryRun(svc.parse(DOC));
        assertTrue(report.valid(), report.errors().toString());
        assertEquals(targetId(), report.targetDefinitionId());
        assertEquals(2, report.instances());
        assertEquals(2L, report.instancesPerSourceActivity().get("review"));
        assertEquals(2L, report.instancesPerSourceActivity().get("check"));
        assertEquals(List.of("check", "review"), activeTasks(pi), "dry run did not migrate");
        assertTrue(calls.isEmpty(), "dry run runs no hooks");
    }

    @Test
    void dryRunReportsAnInvalidPlanInsteadOfThrowing() {
        start();
        String unknown = """
                {"source":{"key":"mig","version":1},"target":{"key":"mig","version":2},
                 "mappings":[{"from":["review"],"to":["noSuchActivity"]}]}""";
        var r = svc.dryRun(svc.parse(unknown));
        assertFalse(r.valid(), "Operaton's plan validator rejects an unknown target activity");
        assertFalse(r.errors().isEmpty());
        String nm = """
                {"source":{"key":"mig","version":1},"target":{"key":"mig","version":2},
                 "mappings":[{"from":["review","check"],"to":["checkA","checkB"]}]}""";
        var r2 = svc.dryRun(svc.parse(nm));
        assertFalse(r2.valid(), "an N:M mapping is reported as invalid by the dry run");
        assertFalse(r2.errors().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> svc.execute(svc.parse(nm)), "execute still refuses it");
    }

    @Test
    void executeMigratesOneToOneAndOneToManyWithVariablesAndHooks() {
        String pi = start();
        var results = svc.execute(svc.parse(DOC));
        assertEquals(1, results.size());
        assertTrue(results.get(0).migrated(), String.valueOf(results.get(0).error()));
        assertEquals(targetId(), h2.engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(pi).singleResult()
                .getProcessDefinitionId());
        assertEquals(List.of("checkA", "checkB", "review2"), activeTasks(pi), "1:N started the extra target");
        assertEquals("v1", h2.engine.getRuntimeService().getVariable(pi, "migratedFrom"));
        assertEquals(List.of("pre:" + pi, "post:" + pi + ":v1"), calls);
    }

    @Test
    void manyToOneMapsEverySourceActivity() {
        String pi = start();
        String doc = """
                {"source":{"key":"mig","version":1},"target":{"key":"mig","version":2},
                 "mappings":[{"from":["review","check"],"to":["checkA"]}]}""";
        assertTrue(svc.execute(svc.parse(doc)).get(0).migrated());
        assertEquals(List.of("checkA", "checkA"), activeTasks(pi));
    }

    @Test
    void oneFailingInstanceDoesNotBlockTheOthersAndRollsBackItself() {
        String good = start();
        String bad = start();
        MigrationDocumentService failing = new MigrationDocumentService(h2.engine,
                name -> (e, pi, d) -> {
                    if (pi.equals(bad)) {
                        throw new IllegalStateException("hook failed for " + pi);
                    }
                });
        String doc = DOC.replace("\"postHook\":\"post\"", "\"postHook\":\"any\"").replace("\"preHook\":\"pre\",", "");
        var results = failing.execute(failing.parse(doc));
        var byId = results.stream().collect(Collectors.toMap(MigrationDocumentService.Result::processInstanceId, r -> r));
        assertTrue(byId.get(good).migrated());
        assertFalse(byId.get(bad).migrated());
        assertTrue(byId.get(bad).error().contains("hook failed"));
        assertEquals(List.of("check", "review"), activeTasks(bad), "failed instance rolled back to the source state");
        assertEquals(null, h2.engine.getRuntimeService().getVariable(bad, "migratedFrom"));
    }

    @Test
    void explicitInstanceListUnknownHookAndMissingDefinition() {
        String a = start();
        String b = start();
        String doc = DOC.replace("\"preHook\":\"pre\"", "\"preHook\":\"nobody\"")
                .replace("\"postHook\":\"post\"", "\"processInstanceIds\":[\"" + a + "\"]");
        var res = svc.execute(svc.parse(doc));
        assertEquals(1, res.size(), "only the listed instance");
        assertFalse(res.get(0).migrated());
        assertTrue(res.get(0).error().contains("not registered"));
        assertEquals(List.of("check", "review"), activeTasks(b));

        assertThrows(ProcessEngineException.class, () -> svc.dryRun(svc.parse(DOC.replace("\"version\":2", "\"version\":9"))));
        assertThrows(IllegalArgumentException.class, () -> svc.dryRun(svc.parse("{\"target\":{\"key\":\"mig\"}}")));
    }

    @Test
    void batchHandlerMigratesOneReferencedInstance() throws Exception {
        String pi = start();
        String doc = DOC.replace(",\"preHook\":\"pre\",\"postHook\":\"post\"", "");
        assertEquals("migrated", svc.asBatchHandler().execute("t1", pi, Map.of("document", doc)));
        assertEquals(List.of("checkA", "checkB", "review2"), activeTasks(pi));
    }
}
