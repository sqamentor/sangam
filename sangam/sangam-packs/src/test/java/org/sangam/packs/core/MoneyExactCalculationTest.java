// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.bpm.engine.task.Task;

import org.sangam.packs.port.PackRuntime;

/**
 * Integrator rulings A and C (owner rules E1-E3, C4): op {@code EXACT} never rounds. A whole minor-unit result is
 * paid as computed; a result with more decimals than the minor unit is held for the reviewer, whose amount is
 * accepted only when it is one of the two whole minor-unit amounts next to the exact value; a missing input is held.
 * The admission refund is fee x refund % less the processing fee, never below 0 and never above the fee. Expected
 * values are worked out by hand in minor units (paise).
 */
class MoneyExactCalculationTest {

    static ProcessEngine engine;

    private static final String NS = "xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:operaton=\"http://operaton.org/schema/1.0/bpmn\""
            + " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" targetNamespace=\"t\"";

    private static String f(String n, String v) {
        return "<operaton:field name=\"" + n + "\"><operaton:string>" + v + "</operaton:string></operaton:field>";
    }

    @BeforeAll
    static void setUp() {
        PackRuntime.reset();
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-money-exact;DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        cfg.setHistory("full");
        org.sangam.packs.PackTestEngines.configure(cfg);
        engine = cfg.buildProcessEngine();
        // the admission withdrawal shape: calc -> held? -> reviewer enters -> calc again -> done
        String p = "<definitions " + NS + "><process id=\"exact\" isExecutable=\"true\""
                + " operaton:historyTimeToLive=\"30\"><startEvent id=\"s\"/>"
                + "<serviceTask id=\"c\" operaton:class=\"" + MoneyCalculationDelegate.class.getName()
                + "\"><extensionElements>" + f("result", "refundAmount") + f("op", "EXACT")
                + f("formula", "feeAmount * refundPct / 100 - processingFee") + f("min", "0") + f("max", "feeAmount")
                + "</extensionElements></serviceTask>"
                + "<exclusiveGateway id=\"g\" default=\"g_ok\"/>"
                + "<userTask id=\"enter\"/><userTask id=\"done\"/>"
                + "<sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"c\"/>"
                + "<sequenceFlow id=\"f2\" sourceRef=\"c\" targetRef=\"g\"/>"
                + "<sequenceFlow id=\"g_ok\" sourceRef=\"g\" targetRef=\"done\"/>"
                + "<sequenceFlow id=\"g_held\" sourceRef=\"g\" targetRef=\"enter\"><conditionExpression"
                + " xsi:type=\"tFormalExpression\">${refundAmountHeld == true}</conditionExpression></sequenceFlow>"
                + "<sequenceFlow id=\"f3\" sourceRef=\"enter\" targetRef=\"c\"/>"
                + "<endEvent id=\"e\"/><sequenceFlow id=\"f4\" sourceRef=\"done\" targetRef=\"e\"/>"
                + "</process></definitions>";
        engine.getRepositoryService().createDeployment().tenantId("t-exact").addString("exact.bpmn", p).deploy();
    }

    @AfterAll
    static void tearDown() {
        engine.close();
        engine = null;
        PackRuntime.reset();
    }

    private static ProcessInstance start(Object fee, Object pct, Object processingFee) {
        Map<String, Object> v = new HashMap<>();
        v.put("feeAmount", fee);
        v.put("refundPct", pct);
        if (processingFee != null) {
            v.put("processingFee", processingFee);
        }
        return engine.getRuntimeService().createProcessInstanceByKey("exact").processDefinitionTenantId("t-exact").setVariables(v).execute();
    }

    private static Object var(ProcessInstance pi, String name) {
        return engine.getRuntimeService().getVariable(pi.getId(), name);
    }

    private static Task task(ProcessInstance pi) {
        return engine.getTaskService().createTaskQuery().processInstanceId(pi.getId()).singleResult();
    }

    @Test
    void wholeMinorUnitResultIsPaidAsComputed() {
        // 50000.00 rupees = 5000000 paise x 90 / 100 = 4500000, less processing fee 100000 = 4400000
        ProcessInstance pi = start(5000000L, 90L, 100000L);
        assertEquals(4400000L, ((Number) var(pi, "refundAmount")).longValue());
        assertEquals(Boolean.FALSE, var(pi, "refundAmountHeld"));
        assertEquals("done", task(pi).getTaskDefinitionKey());
    }

    @Test
    void fractionalResultIsHeldForTheReviewerNeverRounded() {
        // 10001 x 50 / 100 = 5000.5 paise, less 0 = 5000.5: not whole paise -> held, nothing in refundAmount
        ProcessInstance pi = start(10001L, 50L, 0L);
        assertNull(var(pi, "refundAmount"));
        assertEquals(Boolean.TRUE, var(pi, "refundAmountHeld"));
        assertEquals("5000.5", var(pi, "refundAmountExact"));
        assertTrue(String.valueOf(var(pi, "refundAmountHeldReason")).contains("minor unit"));
        assertEquals("enter", task(pi).getTaskDefinitionKey());
        // an amount not next to the exact value is held again
        engine.getTaskService().complete(task(pi).getId(), Map.of("refundAmountEntered", 4000L));
        assertNull(var(pi, "refundAmount"));
        assertEquals("enter", task(pi).getTaskDefinitionKey());
        // the reviewer's choice 5000 (one of 5000 / 5001) is accepted
        engine.getTaskService().complete(task(pi).getId(), Map.of("refundAmountEntered", 5000L));
        assertEquals(5000L, ((Number) var(pi, "refundAmount")).longValue());
        assertEquals(Boolean.FALSE, var(pi, "refundAmountHeld"));
        assertEquals("done", task(pi).getTaskDefinitionKey());
    }

    @Test
    void refundNeverBelowZeroNorAboveTheFee() {
        // 1000 x 10 / 100 = 100, less 500 = -400 -> 0
        ProcessInstance low = start(1000L, 10L, 500L);
        assertEquals(0L, ((Number) var(low, "refundAmount")).longValue());
        // 1000 x 120 / 100 = 1200, less 0 = 1200 -> capped at the fee 1000
        ProcessInstance high = start(1000L, 120L, 0L);
        assertEquals(1000L, ((Number) var(high, "refundAmount")).longValue());
    }

    @Test
    void missingProcessingFeeIsHeldNotDefaulted() {
        ProcessEngineException e = assertThrows(ProcessEngineException.class, () -> start(5000000L, 90L, null));
        assertTrue(e.getMessage().startsWith(MoneyOutApproval.HELD), e.getMessage());
        assertTrue(e.getMessage().contains("processingFee"), e.getMessage());
    }

    @Test
    void roundInsideAnExactFormulaIsRefused() {
        DecimalFormula.parse("round(a * b)");
        String p = "<definitions " + NS + "><process id=\"exactRound\" isExecutable=\"true\""
                + " operaton:historyTimeToLive=\"30\"><startEvent id=\"s\"/><serviceTask id=\"c\" operaton:class=\""
                + MoneyCalculationDelegate.class.getName() + "\"><extensionElements>" + f("result", "x")
                + f("op", "EXACT") + f("formula", "round(a * b)") + "</extensionElements></serviceTask>"
                + "<endEvent id=\"e\"/><sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"c\"/>"
                + "<sequenceFlow id=\"f2\" sourceRef=\"c\" targetRef=\"e\"/></process></definitions>";
        engine.getRepositoryService().createDeployment().tenantId("t-exact").addString("exactRound.bpmn", p).deploy();
        ProcessEngineException e = assertThrows(ProcessEngineException.class, () -> engine.getRuntimeService()
                .startProcessInstanceByKey("exactRound", Map.of("a", 1L, "b", 2L)));
        assertTrue(e.getMessage().startsWith("CALCULATION_INVALID"), e.getMessage());
    }
}
