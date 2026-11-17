// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.history.HistoricVariableInstance;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.engine.variable.Variables;
import org.sangam.packs.port.PackRuntime;
import org.sangam.packs.spi.PackDeployer;
import org.sangam.packs.spi.PackRegistry;

/**
 * P3 grant application, the optional form field {@code landHolding}: the decision
 * {@code p3-field-verification-required} reads it, and strict FEEL (ADR-D-26) refuses an undefined variable and a null
 * input that no rule tests. An application that does not state its land holding therefore carries it as null (pack
 * default, never an invented 0), and the decision has an explicit rule for it: no visit on the ground of the holding;
 * the other grounds (self-uploaded documents, a holding above 2 hectares) are unchanged. Evaluated on the shipped
 * models with the production FEEL set-up.
 */
class P3LandHoldingNotStatedTest {

    static final String KEY = "sangam-p3-grant-application";
    static final String DECISION = "p3-field-verification-required";
    static ProcessEngine engine;
    static PackDriver driver;
    static int seq;

    @BeforeAll
    static void setUp() {
        PackRuntime.reset();
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-p3-landholding;DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        cfg.setHistory("full");
        PackTestEngines.configure(cfg); // decisions 138 and FEEL (sangam-feel): as SangamProcessEnginePlugin does
        engine = cfg.buildProcessEngine();
        new PackDeployer(engine, PackRegistry.discover()).deployAll(PackTestTenant.ID);
        driver = new PackDriver(engine);
    }

    @AfterAll
    static void tearDown() {
        engine.close();
        // R8-heap2: a test class stays loaded for the whole run; its statics must not keep a closed engine
        engine = null;
        driver = null;
        PackRuntime.reset();
    }

    /** The P3 acceptance application (gov1.py), block A1, with {@code landHolding} as given or not stated (null). */
    static String start(BigDecimal landHolding) {
        String bk = "P3-LAND/" + (++seq);
        Map<String, Object> v = AllPacksSmokeTest.bag();
        v.putAll(Map.of("applicantName", "Sita Devi", "applicantRef", "mobile:98XXXXXX10", "schemeId", "HOUSING-GRANT",
                "beneficiaryId", "VAULT-P3-LAND-" + seq, "bankAccount", "3210987654", "ifsc", "PUNB0123400", "income",
                120000, "age", 41, "socialCategory", "SC"));
        v.putAll(Map.of("district", "A", "officeId", "BLOCK-A1", "actingSeat", "DEO-A1", "initiator", "p3.deo-a1",
                "instalmentInterval", "PT1S"));
        v.remove("landHolding");
        if (landHolding != null) {
            v.put("landHolding", landHolding);
        }
        engine.getRuntimeService().createProcessInstanceByKey(KEY).businessKey(bk).setVariables(v)
                .processDefinitionTenantId(PackTestTenant.ID).execute();
        return bk;
    }

    /** Runs the case through document scrutiny (accepted), after which the field verification decision is evaluated. */
    static List<String> throughScrutiny(String bk) {
        FormAnswers form = new FormAnswers(engine);
        return driver.run(bk, t -> t.getTaskDefinitionKey().equals("sc_scrutiny") ? form.answer(t) : null, 80);
    }

    static HistoricVariableInstance variable(String bk, String name) {
        String pi = engine.getHistoryService().createHistoricProcessInstanceQuery().processInstanceBusinessKey(bk)
                .processDefinitionKey(KEY).singleResult().getId();
        return engine.getHistoryService().createHistoricVariableInstanceQuery().processInstanceId(pi).variableName(name)
                .singleResult();
    }

    @Test
    void anApplicationThatDoesNotStateItsLandHoldingIsDecidedNotRefused() {
        String bk = start(null);
        HistoricVariableInstance land = variable(bk, "landHolding");
        assertTrue(land != null, "the pack default defines landHolding, so the decision input is never undefined");
        assertNull(land.getValue(), "not stated stays null: no holding is invented");

        List<String> done = throughScrutiny(bk);
        assertEquals(List.of("sc_scrutiny"), done.subList(0, Math.min(1, done.size())), "scrutiny completed: " + done);
        long failed = engine.getManagementService().createJobQuery().withException().count();
        for (var p : engine.getRuntimeService().createProcessInstanceQuery().processInstanceBusinessKeyLike(bk + "%").list()) {
            failed += engine.getRuntimeService().createIncidentQuery().processInstanceId(p.getId()).count();
        }
        assertEquals(0, failed, "no failed step");
        HistoricVariableInstance visit = variable(bk, "fieldVerification");
        assertTrue(visit != null, "p3_fieldRule decided: " + done);
        assertEquals(Boolean.FALSE, visit.getValue(), "no visit on the ground of a holding that is not stated");
    }

    @Test
    void aStatedHoldingIsDecidedAsBefore() {
        String small = start(new BigDecimal("0.5"));
        throughScrutiny(small);
        assertEquals(Boolean.FALSE, variable(small, "fieldVerification").getValue());
        String large = start(new BigDecimal("2.5"));
        throughScrutiny(large);
        assertEquals(Boolean.TRUE, variable(large, "fieldVerification").getValue(), "larger holdings are visited");
        assertEquals(0, new BigDecimal("2.5").compareTo(new BigDecimal(variable(large, "landHolding").getValue()
                .toString())), "the stated holding is kept exactly");
    }

    /** The decision itself, with the production FEEL set-up: each ground, and the holding that is not stated. */
    @Test
    void theDecisionHasAnExplicitRuleForAHoldingThatIsNotStated() {
        Object[][] cases = {
                {null, false, false}, {null, true, true}, {new BigDecimal("2"), false, false},
                {new BigDecimal("2.01"), false, true}, {new BigDecimal("0"), true, true}};
        for (Object[] c : cases) {
            Object result = engine.getDecisionService().evaluateDecisionTableByKey(DECISION)
                    .decisionDefinitionTenantId(PackTestTenant.ID)
                    .variables(Variables.createVariables().putValue("landHolding", c[0])
                            .putValue("documentsSelfUploaded", c[1])).evaluate().getSingleResult()
                    .getEntry("fieldVerification");
            assertEquals(c[2], result, "landHolding " + c[0] + ", self-uploaded " + c[1]);
        }
    }
}
