// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.engine.task.Task;
import org.sangam.packs.port.PackRuntime;
import org.sangam.packs.spi.PackDeployer;
import org.sangam.packs.spi.PackRegistry;

/**
 * R7UI-F7-2 (decision 400 (2)): an IFMS bill whose type and classification checks could not run (the tenant's profile
 * does not bind the bill type: documentType.get and classification.validate answer FAILED BINDING_UNRESOLVABLE with no
 * result) is held for a person in the task "Profile binding missing" (SL-115 unconfirmed_hold), never an incident.
 * Once the binding is in place the holder completes the task and the checks run again from their clear step.
 * Before the fix the validity gateway read docValid unguarded and the case stopped with the EL incident
 * "Cannot resolve identifier docValid".
 */
class BillProfileBindingHoldTest {

    static final String BILL = "sangam-ifms-bill";
    static final String HOLD = "bl_bindingHold";

    static ProcessEngine engine;
    static PackDriver driver;

    @BeforeAll
    static void setUp() {
        PackRuntime.reset();
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-f7-2;DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        cfg.setHistory("full");
        PackTestEngines.configure(cfg);
        engine = cfg.buildProcessEngine();
        new PackDeployer(engine, PackRegistry.discover()).deployAll(PackTestTenant.ID);
        driver = new PackDriver(engine);
    }

    @AfterAll
    static void tearDown() {
        engine.close();
    }

    @Test
    void anUnboundBillTypeIsHeldForAPersonAndCheckedAgainOnceBound() {
        String bk = "F7-2/bill/1";
        Map<String, Object> v = AllPacksSmokeTest.bag();
        v.put("amount", 15_000_000L);
        v.put("sim_config_documentType", "FAILED:BINDING_UNRESOLVABLE");
        v.put("sim_config_classification", "FAILED:BINDING_UNRESOLVABLE");
        v.put("sim_ledger_available", "FAILED:LEDGER_DOWN");
        engine.getRuntimeService().createProcessInstanceByKey(BILL).businessKey(bk).setVariables(v)
                .processDefinitionTenantId(PackTestTenant.ID).execute();
        FormAnswers answers = new FormAnswers(engine);
        driver.run(bk, t -> HOLD.equals(t.getTaskDefinitionKey()) ? null : answers.answer(t), 80);

        assertEquals(0, engine.getRuntimeService().createIncidentQuery().count(), "incidents");
        List<Task> open = engine.getTaskService().createTaskQuery().processInstanceBusinessKey(bk).list();
        assertEquals(List.of(HOLD), open.stream().map(Task::getTaskDefinitionKey).toList(), "open tasks");
        assertTrue(open.get(0).getName().startsWith("Profile binding missing"), open.get(0).getName());
        assertFalse(driver.ended(bk), "case ended");

        // the profile administrator binds the bill type; the holder completes the task and the checks run again
        String pi = open.get(0).getProcessInstanceId();
        engine.getRuntimeService().setVariable(pi, "sim_config_documentType", "valid=true");
        engine.getRuntimeService().setVariable(pi, "sim_config_classification", "valid=true");
        driver.run(bk, answers::answer, 80);

        assertEquals(0, engine.getRuntimeService().createIncidentQuery().count(), "incidents after the re-check");
        assertTrue(driver.ended(bk), "case still running");
        String codes = String.valueOf(driver.var(bk, "rejectionReasonCodes"));
        assertFalse(codes.contains("BILL_INVALID"), "validation refused: " + codes);
        // the checks passed, so the next gate decides: the funds check that did not complete (SL-113)
        assertTrue(codes.contains("FUNDS_NOT_CONFIRMED"), codes);
    }
}
