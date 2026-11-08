// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.simulation;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** ADR-D-25: the simulation reports the same routing-policy problems the engine rejects at deployment. */
class TokenWalkRoutingTest {

    private static String model(String f2Priority) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                             xmlns:sangam="http://sangam.org/schema/1.0/bpmn" targetNamespace="t">
                  <process id="p" isExecutable="true">
                    <startEvent id="s"/>
                    <sequenceFlow id="f0" sourceRef="s" targetRef="gw"/>
                    <exclusiveGateway id="gw" sangam:routingPolicy="priority"/>
                    <sequenceFlow id="f1" sourceRef="gw" targetRef="e1" sangam:priority="1"><conditionExpression xsi:type="tFormalExpression">${x}</conditionExpression></sequenceFlow>
                    <sequenceFlow id="f2" sourceRef="gw" targetRef="e2" %s><conditionExpression xsi:type="tFormalExpression">${y}</conditionExpression></sequenceFlow>
                    <endEvent id="e1"/><endEvent id="e2"/>
                  </process>
                </definitions>
                """.formatted(f2Priority);
    }

    @Test
    void incompletePriorityPolicyIsWarned() {
        var r = TokenWalk.walk(model(""), null, Map.of("gw", List.of("f1")), 50);
        assertTrue(r.warnings().stream().anyMatch(w -> w.startsWith("ADR-D-25") && w.contains("f2")), r.warnings().toString());
    }

    @Test
    void completePriorityPolicyHasNoWarning() {
        var r = TokenWalk.walk(model("sangam:priority=\"2\""), null, Map.of("gw", List.of("f1")), 50);
        assertTrue(r.warnings().stream().noneMatch(w -> w.startsWith("ADR-D-25")), r.warnings().toString());
    }
}
