// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.bpm.engine.task.Task;
import org.sangam.packs.core.MoneyOutApproval;
import org.sangam.packs.sim.LoggingAuditSink;
import org.sangam.packs.port.PackRuntime;
import org.sangam.packs.spi.PackDeployer;
import org.sangam.packs.spi.PackRegistry;

/**
 * DEC-5 and the integrator ruling of 2026-11-18 on the shipped processes: the DMN gives the exact figure, op EXACT
 * (MoneyCalculationDelegate) uses it only when it is a whole number of minor units (paise) and otherwise holds the case
 * (MONEY_OUT_HELD, rounding rule not configured) for the reviewer, who enters one of the two whole amounts next to the
 * exact value; anything else is held again; the case then goes on to the same scrutiny and sanction. Nothing is
 * rounded by the platform. One test per process (contract amendment, pension sanction), plus the whole-figure path.
 */
class ExactMoneyHoldProcessTest {

    static ProcessEngine engine;
    static int seq;

    @BeforeAll
    static void setUp() {
        PackRuntime.reset();
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-exact-money-hold;DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        cfg.setHistory("full");
        PackTestEngines.configure(cfg);
        engine = cfg.buildProcessEngine();
        new PackDeployer(engine, PackRegistry.discover()).deployAll(PackTestTenant.ID);
    }

    @AfterAll
    static void tearDown() {
        engine.close();
        engine = null;
        PackRuntime.reset();
    }

    static ProcessInstance start(String key, Map<String, Object> vars) {
        Map<String, Object> v = AllPacksSmokeTest.bag();
        v.putAll(vars);
        return engine.getRuntimeService().createProcessInstanceByKey(key).businessKey("EXACT/" + (++seq))
                .setVariables(v).processDefinitionTenantId(PackTestTenant.ID).execute();
    }

    static Task task(ProcessInstance pi) {
        List<Task> open = engine.getTaskService().createTaskQuery().processInstanceBusinessKeyLike(pi.getBusinessKey() + "%")
                .list();
        assertEquals(1, open.size(), "one open task: " + open.stream().map(Task::getTaskDefinitionKey).toList());
        return open.get(0);
    }

    static Object var(ProcessInstance pi, String name) {
        return engine.getRuntimeService().getVariable(pi.getId(), name);
    }

    static long held(ProcessInstance pi) {
        return ((LoggingAuditSink) PackRuntime.audit()).timeline(pi.getBusinessKey()).stream()
                .filter(e -> MoneyOutApproval.HELD.equals(e.type())).count();
    }

    /** Holds at `enterKey`, refuses a non-adjacent amount, accepts `payable`; returns the next open task. */
    static Task heldThenEntered(ProcessInstance pi, String target, String enterKey, String exact, long wrong,
                                long payable) {
        Task t = task(pi);
        assertEquals(enterKey, t.getTaskDefinitionKey(), target + " held for the reviewer");
        assertEquals(Boolean.TRUE, var(pi, target + "Held"));
        assertEquals(exact, var(pi, target + "Exact"), "the exact value is kept, never rounded");
        assertNull(var(pi, target), target + " is not set while held");
        long before = held(pi);
        assertTrue(before > 0, "MONEY_OUT_HELD is audited");
        engine.getTaskService().complete(t.getId(), Map.of(target + "Entered", wrong));
        assertEquals(enterKey, task(pi).getTaskDefinitionKey(), "an amount not next to the exact value is held again");
        assertTrue(held(pi) > before, "the second hold is audited too");
        engine.getTaskService().complete(task(pi).getId(), Map.of(target + "Entered", payable));
        assertEquals(Boolean.FALSE, var(pi, target + "Held"));
        assertEquals(payable, ((Number) var(pi, target)).longValue(), "the reviewer's whole amount is used");
        return task(pi);
    }

    @Test
    void contractAmendmentHoldsFractionalLiquidatedDamagesForTheReviewer() {
        // 3 days x 0.05 % of 1,000,000.01 rupees = 150000.0015 paise
        ProcessInstance pi = start("sangam-contract-amendment", Map.of("amendmentType", "VARIATION",
                "amendmentAmount", 1000000L, "delayDays", 3, "contractValue", 100000001L, "escalationIndex", 0,
                "justification", "delay"));
        Task next = heldThenEntered(pi, "ldAmount", "ca_exLd_enter", "150000.0015", 149999L, 150000L);
        assertEquals(0L, ((Number) var(pi, "escalationAmount")).longValue(), "a whole escalation passes unheld");
        assertEquals(1000000L, ((Number) var(pi, "amount")).longValue(), "amount for the same sanction");
        assertTrue(!next.getTaskDefinitionKey().endsWith("_enter"), "on to scrutiny: " + next.getTaskDefinitionKey());
    }

    @Test
    void pensionSanctionHoldsFractionalPensionAndFamilyPensionForTheReviewer() {
        // 50 % of 50,000.01 rupees = 2500000.5 paise; family pension 30 % = 1500000.3 paise; gratuity is whole
        ProcessInstance pi = start("sangam-pension-sanction", Map.of("qualifyingService", 20, "lastPay", 5000001L,
                "averageEmoluments", 4000000L));
        heldThenEntered(pi, "pensionAmount", "ps_exPension_enter", "2500000.5", 2499999L, 2500001L);
        Task next = heldThenEntered(pi, "familyPension", "ps_exFamily_enter", "1500000.3", 1500002L, 1500000L);
        assertEquals(50000010L, ((Number) var(pi, "retirementGratuity")).longValue(), "whole gratuity passes unheld");
        assertEquals(2500001L, ((Number) var(pi, "amount")).longValue(), "amount for the power check");
        assertTrue(!next.getTaskDefinitionKey().endsWith("_enter"), "on to scrutiny: " + next.getTaskDefinitionKey());
    }

    @Test
    void wholeFiguresAreNeverHeld() {
        ProcessInstance pi = start("sangam-pension-sanction", Map.of("qualifyingService", 20, "lastPay", 5000000L,
                "averageEmoluments", 4000000L));
        assertEquals(Boolean.FALSE, var(pi, "pensionAmountHeld"));
        assertEquals(2500000L, ((Number) var(pi, "pensionAmount")).longValue());
        assertEquals(1500000L, ((Number) var(pi, "familyPension")).longValue());
        assertEquals(0, held(pi));
        assertNotNull(task(pi));
        assertTrue(!task(pi).getTaskDefinitionKey().endsWith("_enter"));
    }
}
