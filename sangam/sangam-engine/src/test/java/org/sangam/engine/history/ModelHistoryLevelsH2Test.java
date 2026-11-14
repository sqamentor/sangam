// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.HistoryService;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.SangamEngineFactory;
import org.sangam.engine.SangamEngineSettings;

/**
 * M-057 (owner decision 88): {@code sangam:historyLevel} in a model is honoured only with the opt-in
 * {@code historyModelOverrides}; operator settings still win; a bad declaration is refused at deploy.
 */
class ModelHistoryLevelsH2Test {

    private static String model(String key, String processLevel, String taskLevel) {
        String p = processLevel == null ? "" : props(processLevel);
        String t = taskLevel == null ? "" : props(taskLevel);
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="t">
                  <process id="%s" isExecutable="true">%s
                    <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="review"/>
                    <userTask id="review">%s</userTask>
                    <sequenceFlow id="f2" sourceRef="review" targetRef="e"/><endEvent id="e"/>
                  </process>
                </definitions>
                """.formatted(key, p, t);
    }

    private static String props(String level) {
        return "<extensionElements><operaton:properties><operaton:property name=\"sangam:historyLevel\" value=\""
                + level + "\"/></operaton:properties></extensionElements>";
    }

    private static OperatonEngineAdapter engine(boolean overrides, Map<String, String> byKey) {
        SangamEngineSettings s = new SangamEngineSettings();
        s.setSystemAuditScope("system:platform"); // decision 144: required at engine start
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true");
        s.setApplyEnvironment(false);
        s.setHistory("full");
        s.setHistoryModelOverrides(overrides);
        s.setHistoryLevelByProcessKey(byKey);
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:m057" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        return SangamEngineFactory.build(ds, s, "h2");
    }

    private static long taskHistory(OperatonEngineAdapter a, String pid) {
        HistoryService h = a.processEngine().getHistoryService();
        return h.createHistoricTaskInstanceQuery().processInstanceId(pid).count();
    }

    private static String run(OperatonEngineAdapter a, String key, String xml) {
        a.deploy("t1", key + "-" + UUID.randomUUID(), Map.of(key + ".bpmn", xml.getBytes(StandardCharsets.UTF_8)));
        return a.startProcess("t1", key, "B-" + UUID.randomUUID(), Map.of());
    }

    @Test
    void offByDefaultTheDeclarationIsIgnored() {
        try (OperatonEngineAdapter a = engine(false, Map.of())) {
            String pid = run(a, "plain", model("plain", "activity", null));
            assertEquals(1, taskHistory(a, pid), "full history stays: the model cannot lower it when the option is off");
        }
    }

    @Test
    void onTheModelLevelAppliesPerDefinitionAndPerActivity() {
        try (OperatonEngineAdapter a = engine(true, Map.of())) {
            String low = run(a, "low", model("low", "activity", null));
            assertEquals(0, taskHistory(a, low), "process-level declaration: no task history at activity level");
            String mixed = run(a, "mixed", model("mixed", "activity", "full"));
            assertEquals(1, taskHistory(a, mixed), "the activity declaration is more specific than the process one");
        }
    }

    @Test
    void operatorSettingsWinOverTheModel() {
        try (OperatonEngineAdapter a = engine(true, Map.of("low", "full"))) {
            String pid = run(a, "low", model("low", "activity", null));
            assertEquals(1, taskHistory(a, pid));
        }
    }

    @Test
    void externalModeWithoutAnEventSinkIsRefusedAtStartup() {
        SangamEngineSettings s = new SangamEngineSettings();
        s.setSystemAuditScope("system:platform"); // decision 144: required at engine start
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true");
        s.setApplyEnvironment(false);
        s.setHistoryExternal(true);
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:m057x" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        RuntimeException e = assertThrows(RuntimeException.class, () -> SangamEngineFactory.build(ds, s, "h2").close());
        String m = String.valueOf(e.getMessage()) + (e.getCause() == null ? "" : " " + e.getCause().getMessage());
        assertTrue(m.contains("M-057"), m);
    }

    @Test
    void anUnknownLevelIsRefusedAtDeploy() {
        try (OperatonEngineAdapter a = engine(true, Map.of())) {
            RuntimeException e = assertThrows(RuntimeException.class, () -> run(a, "bad", model("bad", "verbose", null)));
            String m = e.getMessage() + (e.getCause() == null ? "" : " " + e.getCause().getMessage());
            assertTrue(m.contains("M-057"), m);
        }
    }
}
