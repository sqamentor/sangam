// SPDX-License-Identifier: Apache-2.0
package org.sangam.spike.dmn;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.AuthorizationException;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.ProcessEngineConfiguration;
import org.operaton.bpm.engine.authorization.Authorization;
import org.operaton.bpm.engine.authorization.Permissions;
import org.operaton.bpm.engine.authorization.Resources;
import org.operaton.bpm.engine.history.HistoricDecisionInstance;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.engine.repository.DecisionDefinition;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.bpm.engine.variable.Variables;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Option A proof inside a real Operaton process engine (H2): a model with BKM, context, invocation and relation is
 * deployed by Operaton (DecisionDefinition rows, tenant), evaluated by Drools, and recorded by Operaton's own history
 * producer (HistoricDecisionInstance + output). Command-level authorization still applies. The first test is the
 * fail-first baseline: the same model on plain Operaton.
 */
class HybridProcessEngineTest {

    private ProcessEngine engine;

    private ProcessEngine start(boolean hybrid, boolean authorization) {
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=1000");
        cfg.setProcessEngineName("spike-" + UUID.randomUUID());
        cfg.setHistory(ProcessEngineConfiguration.HISTORY_FULL);
        cfg.setEnforceHistoryTimeToLive(false);
        cfg.setAuthorizationEnabled(authorization);
        cfg.setJobExecutorActivate(false);
        if (hybrid) {
            cfg.setDmnEngineConfiguration(new HybridDmnEngineConfiguration(HybridDmnEngineConfiguration.Routing.BOXED_ONLY));
        }
        engine = cfg.buildProcessEngine();
        return engine;
    }

    @AfterEach
    void close() {
        if (engine != null) {
            engine.close();
            engine = null;
        }
    }

    private void deploy(String tenant) {
        engine.getRepositoryService().createDeployment().tenantId(tenant)
                .addClasspathResource("boxed.dmn").addClasspathResource("charge.bpmn").deploy();
    }

    @Test
    void plainOperatonCannotExecuteTheBoxedModel() {
        start(false, false);
        // Operaton accepts the deployment (it parses the DRD) but has no logic for context / relation decisions
        Exception e = assertThrows(Exception.class, () -> {
            deploy("t1");
            engine.getDecisionService().evaluateDecisionByKey("charge").decisionDefinitionTenantId("t1")
                    .variables(Map.of("Amount", new BigDecimal("1000"))).evaluate();
        });
        System.out.println("[plain Operaton] " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }

    @Test
    void hybridEvaluatesBoxedDecisionsWithOperatonHistoryAndTenancy() {
        start(true, false);
        deploy("t1");
        List<DecisionDefinition> defs = engine.getRepositoryService().createDecisionDefinitionQuery()
                .tenantIdIn("t1").list();
        assertEquals(3, defs.size(), "Operaton owns the decision definitions");
        assertTrue(defs.stream().allMatch(d -> "t1".equals(d.getTenantId())));

        var charge = engine.getDecisionService().evaluateDecisionByKey("charge").decisionDefinitionTenantId("t1")
                .variables(Map.of("Amount", new BigDecimal("1000"))).evaluate();
        assertEquals(0, new BigDecimal("30").compareTo((BigDecimal) charge.getSingleResult().getSingleEntry()));

        var bands = engine.getDecisionService().evaluateDecisionByKey("bands").decisionDefinitionTenantId("t1")
                .evaluate();
        List<?> rows = (List<?>) bands.getSingleResult().getSingleEntry();
        assertEquals(2, rows.size());
        assertEquals("high", ((Map<?, ?>) rows.get(1)).get("name"));

        HistoricDecisionInstance h = engine.getHistoryService().createHistoricDecisionInstanceQuery()
                .decisionDefinitionKey("charge").includeOutputs().singleResult();
        assertNotNull(h, "Operaton's history producer recorded the Drools evaluation");
        assertEquals("t1", h.getTenantId());
        assertEquals(1, h.getOutputs().size());
        assertEquals("Charge", h.getOutputs().get(0).getVariableName());
        assertEquals(0, new BigDecimal("30").compareTo((BigDecimal) h.getOutputs().get(0).getValue()));

        // a tenant that does not own the decision cannot see it
        assertThrows(Exception.class, () -> engine.getDecisionService().evaluateDecisionByKey("charge")
                .decisionDefinitionTenantId("t2").variables(Map.of("Amount", BigDecimal.ONE)).evaluate());
    }

    @Test
    void businessRuleTaskRunsADroolsDecisionWithRequiredDecision() {
        start(true, false);
        deploy("t1");
        ProcessInstance pi = engine.getRuntimeService().createProcessInstanceByKey("chargeProcess")
                .processDefinitionTenantId("t1").setVariables(Variables.putValue("Amount", new BigDecimal("500")))
                .execute();
        Object doubled = engine.getRuntimeService().getVariable(pi.getId(), "doubled");
        assertEquals(0, new BigDecimal("40").compareTo((BigDecimal) doubled)); // (500*0.02+10)*2
        List<HistoricDecisionInstance> hist = engine.getHistoryService().createHistoricDecisionInstanceQuery()
                .processInstanceId(pi.getId()).list();
        // root decision plus its required decision, both tied to the process instance, as for Operaton DRG evaluation
        assertEquals(2, hist.size());
        assertTrue(hist.stream().allMatch(x -> "t1".equals(x.getTenantId()) && "chargeProcess".equals(x.getProcessDefinitionKey())));
    }

    @Test
    void strictFailureRollsBackTheCommand() {
        start(true, false);
        deploy("t1");
        // "x" is not a number: the spec yields null; Sangam's strict policy (SG-34) fails the evaluation
        assertThrows(Exception.class, () -> engine.getDecisionService().evaluateDecisionByKey("charge")
                .decisionDefinitionTenantId("t1").variables(Map.of("Amount", "x")).evaluate());
        assertEquals(0, engine.getHistoryService().createHistoricDecisionInstanceQuery().count());
    }

    @Test
    void authorizationStillGuardsEvaluation() {
        start(true, true);
        Authorization grant = engine.getAuthorizationService().createNewAuthorization(Authorization.AUTH_TYPE_GRANT);
        grant.setUserId("admin");
        grant.setResource(Resources.DEPLOYMENT);
        grant.setResourceId("*");
        grant.addPermission(Permissions.ALL);
        engine.getAuthorizationService().saveAuthorization(grant); // no authenticated user: setup is not checked
        engine.getIdentityService().setAuthentication("admin", List.of());
        deploy("t1");
        engine.getIdentityService().clearAuthentication();

        engine.getIdentityService().setAuthentication("clerk", List.of(), List.of("t1"));
        assertThrows(AuthorizationException.class, () -> engine.getDecisionService().evaluateDecisionByKey("charge")
                .decisionDefinitionTenantId("t1").variables(Map.of("Amount", BigDecimal.TEN)).evaluate());
        engine.getIdentityService().clearAuthentication();

        Authorization allow = engine.getAuthorizationService().createNewAuthorization(Authorization.AUTH_TYPE_GRANT);
        allow.setUserId("clerk");
        allow.setResource(Resources.DECISION_DEFINITION);
        allow.setResourceId("charge");
        allow.addPermission(Permissions.CREATE_INSTANCE);
        engine.getAuthorizationService().saveAuthorization(allow);
        engine.getIdentityService().setAuthentication("clerk", List.of(), List.of("t1"));
        var r = engine.getDecisionService().evaluateDecisionByKey("charge").decisionDefinitionTenantId("t1")
                .variables(Map.of("Amount", BigDecimal.TEN)).evaluate();
        assertEquals(0, new BigDecimal("10.2").compareTo((BigDecimal) r.getSingleResult().getSingleEntry()));
        engine.getIdentityService().clearAuthentication();
    }
}
