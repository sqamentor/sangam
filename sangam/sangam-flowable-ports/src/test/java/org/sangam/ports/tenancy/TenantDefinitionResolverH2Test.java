// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngineException;
import org.sangam.ports.PortsH2Engine;

/** FC-33 callee tenant resolution: own, then shared, then the default tenant, each fallback checked by R19 access. */
class TenantDefinitionResolverH2Test {

    private static String model(String key, String name) {
        return """
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="t">
                  <process id="%s" name="%s" isExecutable="true"><startEvent id="s"/></process>
                </definitions>""".formatted(key, name);
    }

    private static final String CASE = """
            <definitions xmlns="http://www.omg.org/spec/CMMN/20151109/MODEL" targetNamespace="t">
              <case id="file"><casePlanModel id="plan"><planItem id="pi1" definitionRef="t1"/><humanTask id="t1"/></casePlanModel></case>
            </definitions>""";

    private PortsH2Engine h2;

    @BeforeEach
    void setUp() {
        h2 = PortsH2Engine.start(null);
        deploy(null, "child", "shared");
        deploy("acc", "child", "acc own");
        deploy("base", "child", "base");
        deploy("base", "onlyBase", "base only");
        deploy(null, "onlyShared", "shared only");
        h2.engine.getRepositoryService().createDeployment().addString("file.cmmn", CASE).deploy();
    }

    @AfterEach
    void tearDown() {
        h2.close();
    }

    private void deploy(String tenant, String key, String name) {
        var b = h2.engine.getRepositoryService().createDeployment().addString(key + ".bpmn", model(key, name));
        if (tenant != null) {
            b.tenantId(tenant);
        }
        b.deploy();
    }

    @Test
    void theTenantsOwnDefinitionWinsThenTheSharedOne() {
        TenantDefinitionResolver r = new TenantDefinitionResolver(h2.engine, null, TenantDefinitionResolver.TenantAccess.SHARED_ONLY);
        assertEquals("acc own", r.processDefinition("child", "acc").getName());
        assertEquals("acc", r.callee("child", "acc"));
        assertEquals("shared", r.processDefinition("child", "ops").getName(), "ops has none: shared fallback");
        assertNull(r.callee("child", "ops"));
        assertEquals("shared only", r.processDefinition("onlyShared", "acc").getName());
        assertEquals("shared", r.processDefinition("child", null).getName(), "no tenant: shared");
    }

    @Test
    void anotherTenantsDefinitionIsNeverUsedWithoutTheDefaultTenantAndAccess() {
        TenantDefinitionResolver noDefault = new TenantDefinitionResolver(h2.engine, null, TenantDefinitionResolver.TenantAccess.SHARED_ONLY);
        ProcessEngineException e = assertThrows(ProcessEngineException.class, () -> noDefault.processDefinition("onlyBase", "acc"));
        assertTrue(e.getMessage().contains("onlyBase"));

        TenantDefinitionResolver sharedOnly = new TenantDefinitionResolver(h2.engine, "base", TenantDefinitionResolver.TenantAccess.SHARED_ONLY);
        assertThrows(ProcessEngineException.class, () -> sharedOnly.processDefinition("onlyBase", "acc"),
                "R19: the default tenant is a fallback only where the access hook allows it");

        TenantDefinitionResolver withDefault = new TenantDefinitionResolver(h2.engine, "base",
                (caller, def) -> def == null || def.equals(caller) || "base".equals(def));
        assertEquals("base only", withDefault.processDefinition("onlyBase", "acc").getName());
        assertEquals("base", withDefault.callee("onlyBase", "acc"));
        assertEquals("acc own", withDefault.processDefinition("child", "acc").getName(), "own still first");
    }

    @Test
    void theAccessHookCanForbidTheSharedFallback() {
        TenantDefinitionResolver ownOnly = new TenantDefinitionResolver(h2.engine, null, (caller, def) -> def != null && def.equals(caller));
        assertEquals("acc own", ownOnly.processDefinition("child", "acc").getName());
        assertThrows(ProcessEngineException.class, () -> ownOnly.processDefinition("child", "ops"));
        assertTrue(ownOnly.caseDefinition("file", "acc").isEmpty());
    }

    @Test
    void caseDefinitionsResolveTheSameWay() {
        TenantDefinitionResolver r = new TenantDefinitionResolver(h2.engine, null, TenantDefinitionResolver.TenantAccess.SHARED_ONLY);
        assertEquals("file", r.caseDefinition("file", "acc").orElseThrow().getKey());
        assertNull(r.caseDefinition("file", "acc").orElseThrow().getTenantId());
        assertTrue(r.caseDefinition("missing", "acc").isEmpty());
    }
}
