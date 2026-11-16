// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.engine.variable.VariableMap;
import org.operaton.bpm.engine.variable.Variables;
import org.sangam.core.power.LegacyPowerContracts;
import org.sangam.core.power.PowerDecision;
import org.sangam.packs.port.PackRuntime;
import org.sangam.packs.spi.PackDeployer;
import org.sangam.packs.spi.PackRegistry;

/**
 * Decision 391 on the shipped {@code sangam-core-powers} with the production FEEL set-up: the leave pack's competent
 * sanctioning role CONTROLLING holds the power for the leave case type only. Its sanction (lv_sanction) is non-monetary
 * with {@code ruleCaseType} LEAVE (packs/leave/pack.json), so the table is consulted with {@code caseType = "LEAVE"}
 * ({@code PowerLimitGuardListener}, decision 222) and the row maps to a zero ceiling, which confers a power with no
 * money value. For every other case type CONTROLLING still falls to the catch-all and is
 * refused; the other roles are unchanged.
 */
class CorePowersControllingLeaveTest {

    static final String DECISION = "sangam-core-powers";
    static ProcessEngine engine;

    @BeforeAll
    static void setUp() {
        PackRuntime.reset();
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-core-powers-391;DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        PackTestEngines.configure(cfg);
        engine = cfg.buildProcessEngine();
        new PackDeployer(engine, PackRegistry.discover()).deployAll(PackTestTenant.ID);
    }

    @AfterAll
    static void tearDown() {
        engine.close();
        PackRuntime.reset();
    }

    /** The table's single row for the inputs the guard listener passes (seatRole, amount, caseType when declared). */
    static Map<String, Object> row(String seatRole, String caseType) {
        VariableMap in = Variables.createVariables().putValue("seatRole", seatRole).putValue("amount", BigDecimal.ZERO);
        // as Classification.ruleInputs builds them: the case type is present only when the manifest declares one; with
        // none the input is absent (an explicit null is refused by the strict null policy, ADR-D-26, before and after 391)
        if (caseType != null) {
            in.putValue("caseType", caseType);
        }
        return new HashMap<>(engine.getDecisionService().evaluateDecisionTableByKey(DECISION)
                .decisionDefinitionTenantId(PackTestTenant.ID).variables(in).evaluate().getSingleResult().getEntryMap());
    }

    static PowerDecision mapped(Map<String, Object> row) {
        return LegacyPowerContracts.listenerRow(DECISION, "test", row, "INR").decision();
    }

    @Test
    void controllingHoldsTheLeaveSanctionWithNoMoneyPower() {
        Map<String, Object> r = row("CONTROLLING", "LEAVE");
        assertEquals(Boolean.TRUE, r.get("allowed"), String.valueOf(r));
        assertEquals(0, BigDecimal.ZERO.compareTo(new BigDecimal(String.valueOf(r.get("powerLimit")))),
                "no money power: " + r);
        PowerDecision.Limited d = assertInstanceOf(PowerDecision.Limited.class, mapped(r));
        assertEquals(0, BigDecimal.ZERO.compareTo(d.maxAmount()), "a zero ceiling");
    }

    @Test
    void controllingIsRefusedForEveryOtherCaseType() {
        for (String other : Arrays.asList("GENERAL", "GRANT", "BILL", "PENSION", "leave")) {
            Map<String, Object> r = row("CONTROLLING", other);
            assertEquals(Boolean.FALSE, r.get("allowed"), "case type " + other + ": " + r);
            assertInstanceOf(PowerDecision.Deny.class, mapped(r), "case type " + other);
        }
    }

    @Test
    void theOtherRolesAreUnchanged() {
        assertEquals(Boolean.TRUE, row("HOD", "LEAVE").get("allowed"), "HOD keeps its power on every case type");
        assertEquals(0, new BigDecimal("1000000000").compareTo(new BigDecimal(String.valueOf(
                row("HOD", "GENERAL").get("powerLimit")))));
        assertEquals(Boolean.FALSE, row("CLERK", "LEAVE").get("allowed"), "a role without a row is still refused");
    }
}
