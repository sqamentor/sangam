// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.task.Task;
import org.sangam.server.acceptance.engine.EngineScenarioSupport;

/**
 * Decision 148 (FIO D-27) on the lane's real database (Oracle and SQL Server lanes NOT_RUN until their matrix runs):
 * the event-scope snapshot execution and its copied variables persist through the dialect, and each compensation
 * handler of a parallel multi-instance activity sees the instance it compensates.
 */
class MultiInstanceCompensationIT extends EngineScenarioSupport {

    private static final String MODEL = """
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="d27">
              <process id="d27" isExecutable="true">
                <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="hold"/>
                <userTask id="hold">
                  <multiInstanceLoopCharacteristics isSequential="false" operaton:collection="${bidders}"
                      operaton:elementVariable="bidder"/>
                </userTask>
                <boundaryEvent id="holdComp" attachedToRef="hold"><compensateEventDefinition/></boundaryEvent>
                <userTask id="refund" isForCompensation="true"/>
                <sequenceFlow id="f2" sourceRef="hold" targetRef="decide"/><userTask id="decide"/>
                <sequenceFlow id="f3" sourceRef="decide" targetRef="comp"/>
                <intermediateThrowEvent id="comp"><compensateEventDefinition/></intermediateThrowEvent>
                <sequenceFlow id="f4" sourceRef="comp" targetRef="e"/><endEvent id="e"/>
                <!-- BPMN 2.0 XSD (tProcess): every flow element precedes the artifacts, so the association is last -->
                <association id="a1" associationDirection="One" sourceRef="holdComp" targetRef="refund"/>
              </process>
            </definitions>
            """;

    private List<Task> open(String pid, String key) {
        return engine.getTaskService().createTaskQuery().processInstanceId(pid).taskDefinitionKey(key).list();
    }

    @Test
    void eachHandlerSeesItsInstanceOnThisDatabase() {
        scenario("D148-mi-compensation-" + AcceptanceDatabase.name(), ctx -> {
            adapter.deploy(SCENARIO_TENANT, "d27-" + RUN, Map.of("d27.bpmn", MODEL.getBytes(StandardCharsets.UTF_8)));
            String pid = adapter.startProcess(SCENARIO_TENANT, "d27", key("d27"),
                    Map.of("bidders", new ArrayList<>(List.of("B1", "B2", "B3"))));
            for (int i = 0; i < 3; i++) {
                Task t = open(pid, "hold").getFirst();
                Object bidder = engine.getRuntimeService().getVariableLocal(t.getExecutionId(), "bidder");
                engine.getRuntimeService().setVariableLocal(t.getExecutionId(), "receipt", "EMD-" + bidder);
                engine.getTaskService().complete(t.getId());
            }
            engine.getTaskService().complete(open(pid, "decide").getFirst().getId());
            Map<Object, Object> seen = new TreeMap<>((x, y) -> String.valueOf(x).compareTo(String.valueOf(y)));
            for (Task t : open(pid, "refund")) {
                Map<String, Object> v = engine.getTaskService().getVariables(t.getId());
                seen.put(v.get("loopCounter"), v.get("bidder") + "/" + v.get("receipt"));
            }
            ctx.detail("handlers", seen);
            ctx.check("three handlers, each with its own loopCounter, bidder and receipt",
                    seen.equals(Map.of(0, "B1/EMD-B1", 1, "B2/EMD-B2", 2, "B3/EMD-B3")));
            for (Task t : open(pid, "refund")) {
                engine.getTaskService().complete(t.getId());
            }
            ctx.check("instance ended after one handler run per instance", ended(pid)
                    && engine.getHistoryService().createHistoricTaskInstanceQuery().processInstanceId(pid)
                    .taskDefinitionKey("refund").count() == 3);
        });
    }
}
