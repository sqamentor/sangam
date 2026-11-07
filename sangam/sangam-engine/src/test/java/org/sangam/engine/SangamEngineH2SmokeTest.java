// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.sangam.core.api.StaleLeaseException;
import org.sangam.core.engine.SangamEngineAdapter;

/** Fast in-memory smoke test of the wiring: schema, plugin, L2, lease fencing, operationId, long strings. */
class SangamEngineH2SmokeTest {

    private static final String BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="t">
              <process id="pay" isExecutable="true">
                <startEvent id="s"/>
                <sequenceFlow id="f1" sourceRef="s" targetRef="ext"/>
                <serviceTask id="ext" operaton:type="external" operaton:topic="payment"/>
                <sequenceFlow id="f2" sourceRef="ext" targetRef="ut"/>
                <userTask id="ut"/>
                <sequenceFlow id="f3" sourceRef="ut" targetRef="e"/>
                <endEvent id="e"/>
              </process>
            </definitions>
            """;

    private static final String DMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="d" name="d" namespace="t">
              <decision id="approve" name="approve">
                <decisionTable id="dt">
                  <input id="i1"><inputExpression id="ie1" typeRef="integer"><text>amount</text></inputExpression></input>
                  <output id="o1" name="ok" typeRef="boolean"/>
                  <rule id="r1"><inputEntry id="e1"><text>&lt; 100</text></inputEntry><outputEntry id="x1"><text>true</text></outputEntry></rule>
                  <rule id="r2"><inputEntry id="e2"><text>&gt;= 100</text></inputEntry><outputEntry id="x2"><text>false</text></outputEntry></rule>
                </decisionTable>
              </decision>
            </definitions>
            """;

    @Test
    void endToEndOnH2() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:smoke;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamEngineSettings settings = new SangamEngineSettings();
        settings.setAllowMultipleEnginesPerJvm(true);
        try (OperatonEngineAdapter a = SangamEngineFactory.build(ds, settings, "h2")) {
            a.deploy("t1", "d", Map.of("pay.bpmn", BPMN.getBytes(StandardCharsets.UTF_8)));
            String big = "x".repeat(5000);
            String pi = a.startProcess("t1", "pay", "BILL-1", Map.of("note", big, "items", List.of(1, 2)));
            List<SangamEngineAdapter.LeasedWork> w = a.fetchAndLockWork("w1", "payment", 5, Duration.ofMinutes(1));
            assertEquals(1, w.size());
            assertNotNull(w.get(0).operationId());
            assertEquals(big, w.get(0).work().variables().get("note"));
            long v = w.get(0).work().lockVersion();
            assertThrows(StaleLeaseException.class, () -> a.completeExternal(w.get(0).work().id(), "w1", v + 1, Map.of()));
            a.completeExternal(w.get(0).work().id(), "w1", v, Map.of());
            var tasks = a.queryTasks(new SangamEngineAdapter.TaskQuery("t1", null, null, pi, null, 0, 10));
            assertEquals(1, tasks.size());
            a.completeTask(tasks.get(0).id(), Map.of());
            assertTrue(a.getInstance(pi).ended());
            // R10: model versions are bound to the running FEEL engine at deploy; decisions pass the run-time check
            a.deploy("t1", "dmn", Map.of("d.dmn", DMN.getBytes(StandardCharsets.UTF_8)));
            Object out = a.processEngine().getDecisionService().evaluateDecisionTableByKey("approve")
                    .decisionDefinitionTenantId("t1").variables(Map.of("amount", 10)).evaluate().getSingleResult()
                    .getSingleEntry();
            assertEquals(Boolean.TRUE, out);
            long bound = a.inEngineTransaction(c -> org.sangam.core.layer.Jdbc.one(c,
                    "SELECT COUNT(*) FROM SANGAM_FEEL_BINDING", rs -> rs.getLong(1)).orElse(0L));
            assertTrue(bound >= 2, "process and decision definitions bound to the FEEL engine");
            assertThrows(RuntimeException.class, () -> a.deploy("t1", "x",
                    Map.of("x.bpmn", "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e \"e\">]><x/>".getBytes())));
        }
    }
}
