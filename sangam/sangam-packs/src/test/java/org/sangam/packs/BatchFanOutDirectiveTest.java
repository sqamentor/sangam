// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.engine.runtime.ProcessInstance;

import org.sangam.packs.port.PackRuntime;

/**
 * GP-49: BatchDelegate FAN_OUT gives every child the case's simulator directives ({@code sim_*}) next to the item
 * fields and the {@code inherit} list, so a directive selected per item ({@code ... @ :1:ITEM-00002}) reaches the
 * item's own connector call; a case without directives (production) passes none.
 */
class BatchFanOutDirectiveTest {

    private static final String BATCH = "org.sangam.packs.core.BatchDelegate";
    static ProcessEngine engine;

    private static String task(String id, String op, String... fields) {
        StringBuilder f = new StringBuilder("<operaton:field name=\"op\"><operaton:string>" + op
                + "</operaton:string></operaton:field>");
        for (int i = 0; i + 1 < fields.length; i += 2) {
            f.append("<operaton:field name=\"").append(fields[i]).append("\"><operaton:string>").append(fields[i + 1])
                    .append("</operaton:string></operaton:field>");
        }
        return "<serviceTask id=\"" + id + "\" operaton:class=\"" + BATCH + "\"><extensionElements>" + f
                + "</extensionElements></serviceTask>";
    }

    @BeforeAll
    static void setUp() {
        PackRuntime.reset();
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-batch-fanout;DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        cfg.setHistory("full");
        PackTestEngines.configure(cfg); // decisions 138 and FEEL (sangam-feel): as SangamProcessEnginePlugin does
        engine = cfg.buildProcessEngine();
        String ns = "xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" xmlns:operaton=\"http://operaton.org/schema/1.0/bpmn\""
                + " targetNamespace=\"t\"";
        String parent = "<definitions " + ns + "><process id=\"fan-parent\" isExecutable=\"true\">"
                + "<startEvent id=\"s\"/>"
                + task("load", "LOAD", "connector", "dbt.beneficiaryList", "sampleSize", "2", "itemsVar", "items")
                + task("open", "OPEN", "itemsVar", "items")
                + task("fan", "FAN_OUT", "itemsVar", "items", "childProcessKey", "fan-child", "chunkSize", "10",
                "inherit", "schemeId")
                + "<userTask id=\"hold\"/><endEvent id=\"e\"/>"
                + "<sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"load\"/>"
                + "<sequenceFlow id=\"f2\" sourceRef=\"load\" targetRef=\"open\"/>"
                + "<sequenceFlow id=\"f3\" sourceRef=\"open\" targetRef=\"fan\"/>"
                + "<sequenceFlow id=\"f4\" sourceRef=\"fan\" targetRef=\"hold\"/>"
                + "<sequenceFlow id=\"f5\" sourceRef=\"hold\" targetRef=\"e\"/></process></definitions>";
        String child = "<definitions " + ns + "><process id=\"fan-child\" isExecutable=\"true\">"
                + "<startEvent id=\"cs\"/><userTask id=\"ct\"/><endEvent id=\"ce\"/>"
                + "<sequenceFlow id=\"c1\" sourceRef=\"cs\" targetRef=\"ct\"/>"
                + "<sequenceFlow id=\"c2\" sourceRef=\"ct\" targetRef=\"ce\"/></process></definitions>";
        engine.getRepositoryService().createDeployment().tenantId(PackTestTenant.ID)
                .addString("fan-parent.bpmn", parent)
                .addString("fan-child.bpmn", child).deploy();
    }

    @AfterAll
    static void tearDown() {
        engine.close();
        PackRuntime.reset();
    }

    private static Map<String, Object> childVars(String bk) {
        ProcessInstance c = engine.getRuntimeService().createProcessInstanceQuery().processInstanceBusinessKey(bk)
                .singleResult();
        return engine.getRuntimeService().getVariables(c.getId());
    }

    @Test
    void childrenReceiveTheCaseDirectives() {
        engine.getRuntimeService().startProcessInstanceByKey("fan-parent", "FAN/1", Map.of("schemeId", "S-1",
                "sim_pfms_dbt", "FAILED:ACCOUNT_CLOSED @ :1:ITEM-00002", "unrelated", "x"));
        for (String item : List.of("ITEM-00001", "ITEM-00002")) {
            Map<String, Object> v = childVars("FAN/1:1:" + item);
            assertEquals("FAILED:ACCOUNT_CLOSED @ :1:ITEM-00002", v.get("sim_pfms_dbt"), item);
            assertEquals("S-1", v.get("schemeId"), "inherit list still applies");
            assertEquals(item, v.get("itemKey"));
            assertFalse(v.containsKey("unrelated"), "only directives and the inherit list are passed");
        }
    }

    @Test
    void aCaseWithoutDirectivesPassesNone() {
        engine.getRuntimeService().startProcessInstanceByKey("fan-parent", "FAN/2", Map.of("schemeId", "S-2"));
        Map<String, Object> v = childVars("FAN/2:1:ITEM-00001");
        assertFalse(v.keySet().stream().anyMatch(k -> k.startsWith("sim_")), "no simulator variables: " + v.keySet());
    }
}
