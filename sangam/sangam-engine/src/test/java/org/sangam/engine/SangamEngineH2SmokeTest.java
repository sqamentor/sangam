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
            assertThrows(RuntimeException.class, () -> a.deploy("t1", "x",
                    Map.of("x.bpmn", "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e \"e\">]><x/>".getBytes())));
        }
    }
}
