// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.repository.ProcessDefinition;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.sangam.ports.PortsH2Engine;
import org.sangam.ports.links.InstanceLinkService;

/** FC-35 change tenant: definition copy (idempotent) and instance cut-over with link and product-row rewrite. */
class ChangeTenantServiceH2Test {

    private static final String MODEL = """
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="t">
              <process id="claim" isExecutable="true">
                <startEvent id="s"/><sequenceFlow id="f0" sourceRef="s" targetRef="fork"/>
                <parallelGateway id="fork"/>
                <sequenceFlow id="f1" sourceRef="fork" targetRef="review"/><userTask id="review"/>
                <sequenceFlow id="f2" sourceRef="fork" targetRef="check"/><userTask id="check"/>
                <sequenceFlow id="f3" sourceRef="review" targetRef="join"/><sequenceFlow id="f4" sourceRef="check" targetRef="join"/>
                <parallelGateway id="join"/><sequenceFlow id="f5" sourceRef="join" targetRef="e"/><endEvent id="e"/>
              </process>
              <process id="parent" isExecutable="true">
                <startEvent id="ps"/><sequenceFlow id="p1" sourceRef="ps" targetRef="call"/>
                <callActivity id="call" calledElement="claim"/>
                <sequenceFlow id="p2" sourceRef="call" targetRef="pe"/><endEvent id="pe"/>
              </process>
            </definitions>""";

    private static final String OTHER = MODEL.replace("<userTask id=\"check\"/>", "<userTask id=\"check\" name=\"tenant b's own\"/>");

    record Rewrite(String oldId, String newId, String from, String to) {
    }

    private PortsH2Engine h2;
    private InstanceLinkService links;
    private final List<Rewrite> rewrites = new CopyOnWriteArrayList<>();
    private ChangeTenantService svc;

    @BeforeEach
    void setUp() {
        h2 = PortsH2Engine.start(null);
        links = h2.plugin.links();
        svc = new ChangeTenantService(h2.engine, links, List.of((c, o, n, from, to) -> {
            assertTrue(c != null, "rewriter gets the engine transaction's connection");
            rewrites.add(new Rewrite(o, n, from, to));
        }));
        h2.engine.getRepositoryService().createDeployment().tenantId("a").addString("claim.bpmn", MODEL).deploy();
    }

    @AfterEach
    void tearDown() {
        h2.close();
    }

    private ProcessDefinition def(String tenant) {
        return h2.engine.getRepositoryService().createProcessDefinitionQuery().processDefinitionKey("claim").tenantIdIn(tenant)
                .latestVersion().singleResult();
    }

    @Test
    void copyDefinitionIsIdempotentAndRefusesAMissingTarget() {
        String src = def("a").getId();
        String copy = svc.copyDefinition(src, "b");
        assertEquals("b", h2.engine.getRepositoryService().getProcessDefinition(copy).getTenantId());
        assertEquals(copy, svc.copyDefinition(src, "b"), "duplicate filtering: no second version");
        assertEquals(1, h2.engine.getRepositoryService().createProcessDefinitionQuery().processDefinitionKey("claim").tenantIdIn("b").count());
        assertEquals(src, svc.copyDefinition(src, "a"), "same tenant: the definition itself");
        for (String bad : new String[]{null, "", "  ", "null"}) {
            assertThrows(IllegalArgumentException.class, () -> svc.copyDefinition(src, bad), "FW-46: '" + bad + "'");
        }
    }

    /**
     * The copy must be the source model even when the target tenant has a newer version of the same key of its own:
     * a re-copy is filtered as a duplicate, and "latest version in the target tenant" would then be the tenant's own
     * (different) model, so the cut-over would start the instance in the wrong model.
     */
    @Test
    void copyDefinitionReturnsTheCopyNotTheTargetTenantsOwnNewerVersion() throws Exception {
        String src = def("a").getId();
        String first = svc.copyDefinition(src, "b");
        h2.engine.getRepositoryService().createDeployment().tenantId("b").name("b-own").addString("claim.bpmn", OTHER).deploy();
        String own = def("b").getId();
        assertNotEquals(first, own);
        String again = svc.copyDefinition(src, "b");
        assertEquals(first, again, "the copied model, not tenant b's own latest version");
        assertNull(h2.engine.getRepositoryService().getBpmnModelInstance(again).getModelElementById("check")
                .getAttributeValue("name"));
    }

    @Test
    void moveInstanceCutsOverAtTheSameActivitiesWithVariablesBusinessKeyLinkAndRewrite() {
        ProcessInstance old = h2.engine.getRuntimeService().createProcessInstanceByKey("claim").processDefinitionTenantId("a")
                .businessKey("CL-1").setVariables(Map.of("amount", 120L, "owner", "kim")).execute();
        String reviewTask = h2.engine.getTaskService().createTaskQuery().processInstanceId(old.getId()).taskDefinitionKey("review")
                .singleResult().getId();
        h2.engine.getTaskService().complete(reviewTask);    // one branch done: only "check" is active (join waits)

        String moved = svc.moveInstance(old.getId(), "b", "admin");

        assertEquals(0, h2.engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(old.getId()).count(), "old cancelled");
        ProcessInstance pi = h2.engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(moved).singleResult();
        assertEquals("b", pi.getTenantId());
        assertEquals("CL-1", pi.getBusinessKey());
        assertEquals(Map.of("amount", 120L, "owner", "kim"), h2.engine.getRuntimeService().getVariables(moved));
        assertEquals(List.of("check"), h2.engine.getTaskService().createTaskQuery().processInstanceId(moved).list().stream()
                .map(t -> t.getTaskDefinitionKey()).toList(), "restarted at the active leaf only");
        assertEquals(List.of(new Rewrite(old.getId(), moved, "a", "b")), rewrites);
        List<InstanceLinkService.Link> out = links.outgoing("b", "process", old.getId());
        assertEquals(1, out.size());
        assertEquals("change-tenant", out.get(0).linkType());
        assertEquals(moved, out.get(0).targetId());
    }

    @Test
    void aFailingRewriterRollsTheCutOverBack() {
        ChangeTenantService failing = new ChangeTenantService(h2.engine, links, List.of((c, o, n, f, t) -> {
            throw new IllegalStateException("ledger locked");
        }));
        ProcessInstance old = h2.engine.getRuntimeService().createProcessInstanceByKey("claim").processDefinitionTenantId("a").execute();
        assertThrows(IllegalStateException.class, () -> failing.moveInstance(old.getId(), "b", "admin"));
        assertEquals(1, h2.engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(old.getId()).count(), "old kept");
        assertEquals(0, h2.engine.getRuntimeService().createProcessInstanceQuery().tenantIdIn("b").count(), "no half-moved copy");
        assertEquals(0, links.outgoing("b", "process", old.getId()).size());
    }

    @Test
    void onlyExistingRootInstancesCanBeMovedAndTheBatchHandlerMovesByConfig() throws Exception {
        assertThrows(ProcessEngineException.class, () -> svc.moveInstance("nope", "b", "admin"));
        ProcessInstance parent = h2.engine.getRuntimeService().createProcessInstanceByKey("parent").processDefinitionTenantId("a").execute();
        ProcessInstance child = h2.engine.getRuntimeService().createProcessInstanceQuery().superProcessInstanceId(parent.getId()).singleResult();
        ProcessEngineException e = assertThrows(ProcessEngineException.class, () -> svc.moveInstance(child.getId(), "b", "admin"));
        assertTrue(e.getMessage().contains("root instance"));

        ProcessInstance plain = h2.engine.getRuntimeService().createProcessInstanceByKey("claim").processDefinitionTenantId("a").execute();
        String result = svc.asBatchHandler().execute("a", plain.getId(), Map.of("targetTenant", "b", "user", "batch"));
        assertTrue(result.startsWith("moved to "), result);
        assertEquals(1, h2.engine.getRuntimeService().createProcessInstanceQuery().tenantIdIn("b").count());
        ProcessInstance another = h2.engine.getRuntimeService().createProcessInstanceByKey("claim").processDefinitionTenantId("a").execute();
        assertThrows(IllegalArgumentException.class, () -> svc.asBatchHandler().execute("a", another.getId(), Map.of()),
                "FW-46: missing targetTenant is not the tenant \"null\"");
    }
}
