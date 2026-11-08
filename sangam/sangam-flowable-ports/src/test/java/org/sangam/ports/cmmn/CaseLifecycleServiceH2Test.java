// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.cmmn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.runtime.CaseInstance;
import org.sangam.ports.PortsH2Engine;

/** FC-59 case reactivation and FC-19 cut-over: linked new cases, variables carried, tenant boundaries kept. */
class CaseLifecycleServiceH2Test {

    private static String caseModel(String taskName) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/CMMN/20151109/MODEL" targetNamespace="t" id="d">
                  <case id="claim">
                    <casePlanModel id="plan">
                      <planItem id="PI_work" definitionRef="work"/>
                      <humanTask id="work" name="%s"/>
                    </casePlanModel>
                  </case>
                </definitions>
                """.formatted(taskName);
    }

    private PortsH2Engine h2;
    private CaseLifecycleService svc;

    @BeforeEach
    void setUp() {
        h2 = PortsH2Engine.start(null);
        deploy("v1", "t1");
        deploy("other-tenant", "t2");
        svc = new CaseLifecycleService(h2.engine, h2.plugin.links());
    }

    @AfterEach
    void tearDown() {
        h2.close();
    }

    private String deploy(String name, String tenant) {
        h2.engine.getRepositoryService().createDeployment().addString("claim.cmmn", caseModel(name)).tenantId(tenant).deploy();
        return h2.engine.getRepositoryService().createCaseDefinitionQuery().caseDefinitionKey("claim").tenantIdIn(tenant).latestVersion()
                .singleResult().getId();
    }

    private CaseInstance create(String tenant, Map<String, Object> vars) {
        return h2.engine.getCaseService().withCaseDefinitionByKey("claim").caseDefinitionTenantId(tenant).businessKey("BK-1")
                .setVariables(vars).create();
    }

    private long links(String tenant, String type, String oldCase, String newCase) {
        return h2.plugin.links().outgoing(tenant, "case", oldCase).stream()
                .filter(l -> type.equals(l.linkType()) && newCase.equals(l.targetId())).count();
    }

    @Test
    void reactivationStartsALinkedCaseWithTheHistoricVariables() {
        CaseInstance old = create("t1", Map.of("amount", 120, "secret", "s", "note", "n"));
        h2.engine.getCaseService().terminateCaseExecution(old.getId());

        String neu = svc.reactivate(old.getId(), null, Set.of("secret"), Map.of("note", "reopened"), "u1");
        assertNotEquals(old.getId(), neu);
        CaseInstance ci = h2.engine.getCaseService().createCaseInstanceQuery().caseInstanceId(neu).singleResult();
        assertEquals("BK-1", ci.getBusinessKey());
        assertEquals("t1", ci.getTenantId());
        Map<String, Object> vars = h2.engine.getCaseService().getVariables(neu);
        assertEquals(120, vars.get("amount"));
        assertEquals("reopened", vars.get("note"));
        assertFalse(vars.containsKey("secret"), "excluded variable not carried");
        assertEquals(1, links("t1", "reactivation", old.getId(), neu));
        assertEquals(0, links("t2", "reactivation", old.getId(), neu), "link is in the case's tenant");
    }

    @Test
    void reactivationRefusesActiveOrUnknownCases() {
        CaseInstance running = create("t1", Map.of());
        assertThrows(ProcessEngineException.class, () -> svc.reactivate(running.getId(), null, null, null, "u1"));
        assertThrows(ProcessEngineException.class, () -> svc.reactivate("missing", null, null, null, "u1"));
    }

    /** Regression: a target definition of another tenant moved the case's data into that tenant. */
    @Test
    void reactivationAndCutOverStayInTheCasesTenant() {
        String foreign = h2.engine.getRepositoryService().createCaseDefinitionQuery().caseDefinitionKey("claim").tenantIdIn("t2")
                .singleResult().getId();
        CaseInstance old = create("t1", Map.of("amount", 1));
        CaseInstance running = create("t1", Map.of("amount", 2));
        h2.engine.getCaseService().terminateCaseExecution(old.getId());
        long before = h2.engine.getCaseService().createCaseInstanceQuery().tenantIdIn("t2").count();

        assertThrows(ProcessEngineException.class, () -> svc.reactivate(old.getId(), foreign, null, null, "u1"));
        assertThrows(ProcessEngineException.class, () -> svc.cutOver(running.getId(), foreign, "u1"));

        assertEquals(before, h2.engine.getCaseService().createCaseInstanceQuery().tenantIdIn("t2").count(), "nothing created in t2");
        assertTrue(h2.engine.getCaseService().createCaseInstanceQuery().caseInstanceId(running.getId()).singleResult().isActive(),
                "running case untouched");
    }

    @Test
    void cutOverMovesARunningCaseToTheLatestDefinition() {
        CaseInstance old = create("t1", Map.of("amount", 7));
        assertThrows(ProcessEngineException.class, () -> svc.cutOver(old.getId(), null, "u1"), "already on the latest");
        String v2 = deploy("v2", "t1");

        String neu = svc.cutOver(old.getId(), null, "u1");
        CaseInstance ci = h2.engine.getCaseService().createCaseInstanceQuery().caseInstanceId(neu).singleResult();
        assertEquals(v2, ci.getCaseDefinitionId());
        assertEquals("BK-1", ci.getBusinessKey());
        assertEquals(7, h2.engine.getCaseService().getVariable(neu, "amount"));
        assertEquals("v2", h2.engine.getTaskService().createTaskQuery().caseInstanceId(neu).singleResult().getName());
        CaseInstance oldNow = h2.engine.getCaseService().createCaseInstanceQuery().caseInstanceId(old.getId()).singleResult();
        assertTrue(oldNow == null || oldNow.isTerminated(), "old case terminated");
        assertEquals(1, links("t1", "cut-over", old.getId(), neu));
        assertThrows(ProcessEngineException.class, () -> svc.cutOver("missing", null, "u1"));
    }
}
