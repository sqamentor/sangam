// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.variables;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.task.Task;
import org.sangam.core.layer.Jdbc;
import org.sangam.ports.PortsH2Engine;
import org.sangam.ports.history.EngineEventBus;
import org.sangam.ports.support.EngineTx;
import org.sangam.ports.support.Json;

/** FC-21 multi-instance aggregation and FC-05 variable listeners. */
class VariablesPortsH2Test {

    private static String miModel(String id, boolean sequential, String extraAttrs, boolean multiInstance) {
        return """
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:sangam="http://sangam.org/schema/1.0/bpmn" targetNamespace="t">
                  <process id="%s" isExecutable="true">
                    <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="review"/>
                    <userTask id="review" %s>%s</userTask>
                    <sequenceFlow id="f2" sourceRef="review" targetRef="after"/>
                    <userTask id="after"/><sequenceFlow id="f3" sourceRef="after" targetRef="e"/><endEvent id="e"/>
                  </process>
                </definitions>""".formatted(id, extraAttrs, multiInstance
                ? "<multiInstanceLoopCharacteristics isSequential=\"" + sequential + "\"><loopCardinality>3</loopCardinality></multiInstanceLoopCharacteristics>"
                : "");
    }

    private static final String AGG = "sangam:aggregateTo=\"reviews\" sangam:aggregateVariables=\"decision, comment\"";

    private PortsH2Engine h2;

    @BeforeEach
    void setUp() {
        h2 = PortsH2Engine.start(null);
    }

    @AfterEach
    void tearDown() {
        h2.close();
    }

    private List<Task> reviews(String pid) {
        return h2.engine.getTaskService().createTaskQuery().processInstanceId(pid).taskDefinitionKey("review").list();
    }

    private void completeWith(Task t, int loop) {
        h2.engine.getRuntimeService().setVariableLocal(t.getExecutionId(), "decision", "d" + loop);
        h2.engine.getRuntimeService().setVariableLocal(t.getExecutionId(), "comment", "c" + loop);
        h2.engine.getTaskService().complete(t.getId());
    }

    private long aggregateRows() {
        return EngineTx.execute(h2.engine, cc -> Jdbc.one(EngineTx.connection(), "SELECT COUNT(*) FROM MI_AGGREGATE", rs -> rs.getLong(1)).orElse(0L));
    }

    private List<Map<String, Object>> aggregated(String pid) {
        Object v = h2.engine.getRuntimeService().getVariable(pid, "reviews");
        List<Map<String, Object>> out = new ArrayList<>();
        Json.tree((String) v).forEach(n -> out.add(Json.map(n)));
        return out;
    }

    @Test
    void parallelItemsAreAssembledInLoopOrderAndRowsCleanedUp() {
        h2.engine.getRepositoryService().createDeployment().addString("mi.bpmn", miModel("mi", false, AGG, true)).deploy();
        String pid = h2.engine.getRuntimeService().startProcessInstanceByKey("mi").getId();
        String other = h2.engine.getRuntimeService().startProcessInstanceByKey("mi").getId();
        List<Task> tasks = new ArrayList<>(reviews(pid));
        assertEquals(3, tasks.size());
        // complete in reverse loop order
        tasks.sort((a, b) -> Integer.compare(loop(b), loop(a)));
        for (Task t : tasks) {
            completeWith(t, loop(t));
        }
        completeWith(reviews(other).get(0), 7);
        List<Map<String, Object>> agg = aggregated(pid);
        assertEquals(3, agg.size());
        for (int i = 0; i < 3; i++) {
            assertEquals(i, ((Number) agg.get(i).get("loopCounter")).intValue());
            assertEquals("d" + i, agg.get(i).get("decision"));
            assertEquals("c" + i, agg.get(i).get("comment"));
        }
        assertEquals(1, aggregateRows(), "only the other instance's pending item row is left");
        assertTrue(h2.engine.getRuntimeService().getVariable(other, "reviews") == null, "the other instance is not assembled yet");
    }

    @Test
    void sequentialItemsAreAggregatedToo() {
        h2.engine.getRepositoryService().createDeployment().addString("mi.bpmn", miModel("seq", true, AGG, true)).deploy();
        String pid = h2.engine.getRuntimeService().startProcessInstanceByKey("seq").getId();
        for (int i = 0; i < 3; i++) {
            Task t = reviews(pid).get(0);
            completeWith(t, loop(t));
        }
        List<Map<String, Object>> agg = aggregated(pid);
        assertEquals(List.of("d0", "d1", "d2"), agg.stream().map(m -> m.get("decision")).toList());
        assertEquals(0, aggregateRows());
    }

    @Test
    void invalidAggregationMarkupIsRefusedAtDeployment() {
        assertThrows(RuntimeException.class, () -> h2.engine.getRepositoryService().createDeployment()
                .addString("bad.bpmn", miModel("bad1", false, AGG, false)).deploy(), "not multi-instance");
        assertThrows(RuntimeException.class, () -> h2.engine.getRepositoryService().createDeployment()
                .addString("bad.bpmn", miModel("bad2", false, "sangam:aggregateTo=\"x\"", true)).deploy(), "no variables named");
        assertEquals(0, h2.engine.getRepositoryService().createProcessDefinitionQuery().count());
    }

    private int loop(Task t) {
        return (Integer) h2.engine.getRuntimeService().getVariable(t.getExecutionId(), "loopCounter");
    }

    @Test
    void variableListenersFilterByDefinitionNameAndEvent() throws Exception {
        h2.engine.getRepositoryService().createDeployment().addString("a.bpmn", miModel("a", false, "", false))
                .addString("b.bpmn", miModel("b", false, "", false)).deploy();
        VariableListenerRegistry reg = new VariableListenerRegistry(h2.plugin.eventBus());
        List<String> all = new CopyOnWriteArrayList<>();
        List<String> updatesOnly = new CopyOnWriteArrayList<>();
        AutoCloseable r1 = reg.register("a", "amount", Set.of(), e -> all.add(e.type() + ":" + e.data().get("processDefinitionKey")));
        AutoCloseable r2 = reg.register(null, "amount", Set.of("update"), e -> updatesOnly.add((String) e.data().get("processDefinitionKey")));

        String pa = h2.engine.getRuntimeService().startProcessInstanceByKey("a", Map.of("amount", 1, "other", 1)).getId();
        String pb = h2.engine.getRuntimeService().startProcessInstanceByKey("b", Map.of("amount", 1)).getId();
        h2.engine.getRuntimeService().setVariable(pa, "amount", 2);
        h2.engine.getRuntimeService().setVariable(pa, "other", 2);
        h2.engine.getRuntimeService().setVariable(pb, "amount", 2);
        h2.engine.getRuntimeService().removeVariable(pa, "amount");

        assertEquals(List.of("variable.create:a", "variable.update:a", "variable.delete:a"), all);
        assertEquals(List.of("a", "b"), updatesOnly);

        r1.close();
        r2.close();
        h2.engine.getRuntimeService().setVariable(pa, "amount", 3);
        assertEquals(3, all.size(), "unregistered");

        // a failing callback fails the engine transaction (callbacks run inside it)
        AutoCloseable r3 = reg.register("a", "amount", Set.of(), e -> {
            throw new IllegalStateException("veto");
        });
        assertThrows(RuntimeException.class, () -> h2.engine.getRuntimeService().setVariable(pa, "amount", 4));
        assertEquals(3, h2.engine.getRuntimeService().getVariable(pa, "amount"));
        r3.close();
        EngineEventBus bus = h2.plugin.eventBus();
        assertTrue(bus != null);
    }
}
