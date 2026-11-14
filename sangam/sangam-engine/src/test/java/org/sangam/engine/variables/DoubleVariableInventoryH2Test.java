// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.variables;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.SangamEngineFactory;
import org.sangam.engine.SangamEngineSettings;

/** Decision 138: the read-only inventory lists Double variables per definition and name, runtime and history. */
class DoubleVariableInventoryH2Test {

    private static final String MODEL = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="t">
              <process id="legacy" isExecutable="true">
                <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="work"/>
                <userTask id="work"/><sequenceFlow id="f2" sourceRef="work" targetRef="e"/><endEvent id="e"/>
              </process>
            </definitions>
            """;

    @Test
    void listsDoubleVariablesAndNothingElseWithoutChangingThem() throws Exception {
        SangamEngineSettings s = new SangamEngineSettings();
        s.setSystemAuditScope("system:platform"); // decision 144: required at engine start
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true");
        s.setApplyEnvironment(false);
        s.setJobExecutorActivate(false);
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:d138inv" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        try (OperatonEngineAdapter a = SangamEngineFactory.build(ds, s, "h2")) {
            a.deploy("t1", "inv-" + UUID.randomUUID(), Map.of("legacy.bpmn", MODEL.getBytes(StandardCharsets.UTF_8)));
            String p1 = a.startProcess("t1", "legacy", null, Map.of("rate", 0.07d, "amount", new BigDecimal("10.00"),
                    "count", 3));
            a.startProcess("t1", "legacy", null, Map.of("rate", 0.08d));
            try (Connection c = ds.getConnection()) {
                List<DoubleVariableInventory.Entry> found = DoubleVariableInventory.scan(c, "");
                assertEquals(List.of(
                        new DoubleVariableInventory.Entry(DoubleVariableInventory.Source.RUNTIME, "t1", "legacy", "rate",
                                2),
                        new DoubleVariableInventory.Entry(DoubleVariableInventory.Source.HISTORY, "t1", "legacy", "rate",
                                2)), found);
            }
            assertEquals(0.07d, a.processEngine().getRuntimeService().getVariable(p1, "rate"), "unchanged");
            try (Connection c = ds.getConnection()) {
                assertThrows(IllegalArgumentException.class, () -> DoubleVariableInventory.scan(c, "x; DROP TABLE y"));
                assertTrue(DoubleVariableInventory.scan(c, null).size() == 2);
            }
        }
    }
}
