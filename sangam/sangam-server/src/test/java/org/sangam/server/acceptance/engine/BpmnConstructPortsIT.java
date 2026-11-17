// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.engine;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * R10-bpmn constructs on the real server and database (chunk B-engine: PostgreSQL, Oracle, SQL Server, MySQL):
 * standard loop (P-6), data store references and type imports as documentation (P-7), descriptive gateways without
 * conditions. Before R10-bpmn each of these models was refused at deployment.
 */
class BpmnConstructPortsIT extends EngineScenarioSupport {

    private static final String LOOP = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" targetNamespace="t">
              <import importType="http://www.w3.org/2001/XMLSchema" location="types.xsd" namespace="urn:t"/>
              <dataStore id="store"/>
              <process id="r10-loop" isExecutable="true">
                <startEvent id="s"/>
                <sequenceFlow id="f1" sourceRef="s" targetRef="r10_work"/>
                <userTask id="r10_work">
                  <standardLoopCharacteristics testBefore="false" loopMaximum="5">
                    <loopCondition xsi:type="tFormalExpression">${again}</loopCondition>
                  </standardLoopCharacteristics>
                </userTask>
                <sequenceFlow id="f2" sourceRef="r10_work" targetRef="r10_probe"/>
                <userTask id="r10_probe"/>
                <sequenceFlow id="f3" sourceRef="r10_probe" targetRef="e"/>
                <endEvent id="e"/>
                <dataStoreReference id="dsr" dataStoreRef="store"/>
              </process>
              <process id="r10-descriptive" isExecutable="false">
                <exclusiveGateway id="g"/>
                <sequenceFlow id="d1" sourceRef="g" targetRef="t1"/>
                <sequenceFlow id="d2" sourceRef="g" targetRef="t2"/>
                <task id="t1"/><task id="t2"/>
              </process>
            </definitions>
            """;

    @Test
    void standardLoopRepeatsWhileTheConditionHolds() {
        scenario("R10-standard-loop", ctx -> {
            adapter.deploy(SCENARIO_TENANT, "r10-loop-" + RUN, Map.of("r10-loop.bpmn", LOOP.getBytes(StandardCharsets.UTF_8)));
            String pid = adapter.startProcess(SCENARIO_TENANT, "r10-loop", key("r10-loop"), Map.of());
            complete(ctx, pid, "r10_work", null, Map.of("again", true));
            complete(ctx, pid, "r10_work", null, Map.of("again", true));
            ctx.check("third iteration open (do-while kept looping)", tasks(pid, "r10_work").size() == 1);
            complete(ctx, pid, "r10_work", null, Map.of("again", false));
            ctx.check("loop left after the condition turned false; active=" + active(pid), tasks(pid, "r10_probe").size() == 1);
            ctx.check("three iterations in history", historicActivityIds(pid).stream().filter("r10_work"::equals).count() == 3);
        });
    }
}
