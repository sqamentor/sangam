// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.dmn.engine.DmnDecisionRuleResult;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.engine.variable.Variables;
import org.sangam.packs.port.PackRuntime;
import org.sangam.packs.spi.PackDeployer;
import org.sangam.packs.spi.PackRegistry;

/**
 * Owner DEC-5 and the integrator ruling of 2026-11-18: the three shipped models that rounded money with floor() /
 * ceiling() (contract-ld, contract-price-escalation, pension-computation) give the EXACT figure. Whether it is payable
 * (a whole number of minor units of the currency, ISO 4217) is decided by the Java money step, which holds the case
 * for a reviewer-entered amount when it is not, until a jurisdiction rounding rule (SANGAM-RJ-RULES (a)) is configured.
 * Evaluated on the shipped models with the production FEEL set-up; amounts in paise (SD-398).
 */
class DmnExactMoneyOutputTest {

    static ProcessEngine engine;

    @BeforeAll
    static void setUp() {
        PackRuntime.reset();
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-dmn-exact-money;DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
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

    static DmnDecisionRuleResult eval(String key, Map<String, Object> vars) {
        return engine.getDecisionService().evaluateDecisionTableByKey(key).decisionDefinitionTenantId(PackTestTenant.ID)
                .variables(Variables.fromMap(vars)).evaluate().getFirstResult();
    }

    static void exact(String expected, Object actual, String what) {
        assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(String.valueOf(actual))),
                what + ": expected the exact " + expected + ", got " + actual);
    }

    @Test
    void liquidatedDamagesAreExact() {
        // 3 days x 0.05 % of 1,000,000.01 rupees = 150000.0015 paise: not rounded down in DMN
        exact("150000.0015", eval("contract-ld", Map.of("delayDays", 3, "contractValue", 100000001L))
                .getEntry("ldAmount"), "ldAmount");
    }

    @Test
    void priceEscalationIsExact() {
        // (6 - 5) % on 85 % of 1,000,000.01 rupees = 850000.0085 paise
        exact("850000.0085", eval("contract-price-escalation", Map.of("escalationIndex", 6, "amendmentAmount",
                100000001L, "contractValue", 100000001L)).getEntry("escalationAmount"), "escalationAmount");
    }

    @Test
    void pensionFiguresAreExact() {
        DmnDecisionRuleResult r = eval("pension-computation", Map.of("qualifyingService", 20, "lastPay", 5000001L,
                "averageEmoluments", 4000000L));
        exact("2500000.5", r.getEntry("pensionAmount"), "pensionAmount (50 % of 50,000.01)");
        exact("50000010", r.getEntry("retirementGratuity"), "retirementGratuity (1/4 x 40 half-years)");
        exact("1500000.3", r.getEntry("familyPension"), "familyPension (30 %)");
        DmnDecisionRuleResult g = eval("pension-computation", Map.of("qualifyingService", 9, "lastPay", 3,
                "averageEmoluments", 3L));
        exact("27", g.getEntry("retirementGratuity"), "service gratuity: 3 x 0.5 x 18 half-years");
        exact("900000", g.getEntry("familyPension"), "family pension minimum 9,000 rupees");
        DmnDecisionRuleResult h = eval("pension-computation", Map.of("qualifyingService", new BigDecimal("9.75"),
                "lastPay", 5L, "averageEmoluments", 5L));
        exact("47.5", h.getEntry("retirementGratuity"), "5 x 0.5 x floor(19.5) = 19 half-years: the count is floored, "
                + "the money is exact");
    }
}
