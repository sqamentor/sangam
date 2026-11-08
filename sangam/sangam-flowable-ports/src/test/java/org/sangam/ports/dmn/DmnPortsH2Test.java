// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.dmn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.dmn.engine.DmnDecisionResult;
import org.operaton.bpm.engine.ProcessEngineException;
import org.sangam.ports.PortsH2Engine;

/** FC-53 decision services and FC-54 PRIORITY / OUTPUT ORDER hit policies, per tenant and per version. */
class DmnPortsH2Test {

    private static final String DMN_NS = "https://www.omg.org/spec/DMN/20191111/MODEL/";

    private static String priorityTable(String hitPolicy, String outputValues) {
        return """
                <definitions xmlns="%s" id="riskDefs" name="Risk" namespace="t">
                  <decision id="risk" name="Risk">
                    <decisionTable id="dt" hitPolicy="%s">
                      <input id="i1"><inputExpression id="ie1" typeRef="integer"><text>amount</text></inputExpression></input>
                      <output id="o1" name="level" typeRef="string"><outputValues id="ov"><text>%s</text></outputValues></output>
                      <rule id="r1"><inputEntry id="e1"><text>&gt; 100</text></inputEntry><outputEntry id="x1"><text>"medium"</text></outputEntry></rule>
                      <rule id="r2"><inputEntry id="e2"><text>&gt; 1000</text></inputEntry><outputEntry id="x2"><text>"high"</text></outputEntry></rule>
                      <rule id="r3"><inputEntry id="e3"><text></text></inputEntry><outputEntry id="x3"><text>"low"</text></outputEntry></rule>
                    </decisionTable>
                  </decision>
                </definitions>""".formatted(DMN_NS, hitPolicy, outputValues);
    }

    private static String serviceModel(String largeApprover) {
        return """
                <definitions xmlns="%s" id="svcDefs" name="Svc" namespace="t">
                  <inputData id="amount" name="amount"/>
                  <decision id="base" name="Base">
                    <decisionTable id="dtb">
                      <input id="bi"><inputExpression id="bie" typeRef="integer"><text>amount</text></inputExpression></input>
                      <output id="bo" name="band" typeRef="string"/>
                      <rule id="br1"><inputEntry id="be1"><text>&lt; 100</text></inputEntry><outputEntry id="bx1"><text>"small"</text></outputEntry></rule>
                      <rule id="br2"><inputEntry id="be2"><text>&gt;= 100</text></inputEntry><outputEntry id="bx2"><text>"large"</text></outputEntry></rule>
                    </decisionTable>
                  </decision>
                  <decision id="approval" name="Approval">
                    <informationRequirement id="ir1"><requiredDecision href="#base"/></informationRequirement>
                    <decisionTable id="dta">
                      <input id="ai"><inputExpression id="aie" typeRef="string"><text>band</text></inputExpression></input>
                      <output id="ao" name="approver" typeRef="string"/>
                      <rule id="ar1"><inputEntry id="ae1"><text>"small"</text></inputEntry><outputEntry id="ax1"><text>"clerk"</text></outputEntry></rule>
                      <rule id="ar2"><inputEntry id="ae2"><text>"large"</text></inputEntry><outputEntry id="ax2"><text>"%s"</text></outputEntry></rule>
                    </decisionTable>
                  </decision>
                  <decisionService id="approve" name="Approve">
                    <outputDecision href="#approval"/>
                    <encapsulatedDecision href="#base"/>
                    <inputData href="#amount"/>
                  </decisionService>
                </definitions>""".formatted(DMN_NS, largeApprover);
    }

    private PortsH2Engine h2;

    @BeforeEach
    void setUp() {
        h2 = PortsH2Engine.start(null);
    }

    @AfterEach
    void tearDown() {
        h2.close();
    }

    private void deploy(String name, String xml, String tenant) {
        var d = h2.engine.getRepositoryService().createDeployment().addString(name, xml);
        if (tenant != null) {
            d.tenantId(tenant);
        }
        d.deploy();
    }

    private DmnDecisionResult evaluate(String tenant, int amount) {
        return h2.engine.getDecisionService().evaluateDecisionByKey("risk").decisionDefinitionTenantId(tenant)
                .variables(Map.of("amount", amount)).evaluate();
    }

    private DmnDecisionResult evaluateById(String definitionId, int amount) {
        return h2.engine.getDecisionService().evaluateDecisionById(definitionId).variables(Map.of("amount", amount)).evaluate();
    }

    @Test
    void priorityPicksTheHighestRankedMatchingRule() {
        deploy("risk.dmn", priorityTable("PRIORITY", "\"high\",\"medium\",\"low\""), "t1");
        assertEquals("high", evaluate("t1", 5000).getSingleResult().getSingleEntry());
        assertEquals("medium", evaluate("t1", 500).getSingleResult().getSingleEntry());
        assertEquals("low", evaluate("t1", 5).getSingleResult().getSingleEntry());
    }

    @Test
    void outputOrderSortsAllMatchesByPriority() {
        deploy("risk.dmn", priorityTable("OUTPUT ORDER", "\"high\",\"medium\",\"low\""), "t1");
        List<Object> levels = evaluate("t1", 5000).collectEntries("level");
        assertEquals(List.of("high", "medium", "low"), levels);
    }

    /** Regression: priorities were kept per decision key only, so one tenant's (or version's) order overrode another's. */
    @Test
    void priorityOrderIsPerTenantAndPerVersion() {
        deploy("risk.dmn", priorityTable("PRIORITY", "\"high\",\"medium\",\"low\""), "t1");
        deploy("risk.dmn", priorityTable("PRIORITY", "\"low\",\"medium\",\"high\""), "t2");
        assertEquals("high", evaluate("t1", 5000).getSingleResult().getSingleEntry(), "t1 keeps its own order");
        assertEquals("low", evaluate("t2", 5000).getSingleResult().getSingleEntry());

        String v1 = h2.engine.getRepositoryService().createDecisionDefinitionQuery().decisionDefinitionKey("risk").tenantIdIn("t1")
                .singleResult().getId();
        deploy("risk.dmn", priorityTable("PRIORITY", "\"medium\",\"high\",\"low\""), "t1");
        assertEquals("medium", evaluate("t1", 5000).getSingleResult().getSingleEntry(), "latest version's order");
        assertEquals("high", evaluateById(v1, 5000).getSingleResult().getSingleEntry(), "version 1 keeps its order");
    }

    @Test
    void decisionServiceEvaluatesOutputDecisionsWithTheirRequiredDecisions() {
        deploy("svc.dmn", serviceModel("manager"), null);
        deploy("svc.dmn", serviceModel("director"), "t1");
        DecisionServiceEvaluator ev = new DecisionServiceEvaluator(h2.engine);

        List<DecisionServiceEvaluator.DecisionServiceDefinition> svcs = ev.services("svcDefs", null);
        assertEquals(1, svcs.size());
        assertEquals("approve", svcs.get(0).id());
        assertEquals(List.of("approval"), svcs.get(0).outputDecisions());
        assertEquals(List.of("base"), svcs.get(0).encapsulatedDecisions());
        assertEquals(List.of("amount"), svcs.get(0).inputData());

        assertEquals(Map.of("approval", List.of(Map.of("approver", "clerk"))), ev.evaluate("svcDefs", "approve", null, Map.of("amount", 5)));
        assertEquals(Map.of("approval", List.of(Map.of("approver", "manager"))), ev.evaluate("svcDefs", "approve", null, Map.of("amount", 500)));
        assertEquals(Map.of("approval", List.of(Map.of("approver", "director"))), ev.evaluate("svcDefs", "approve", "t1", Map.of("amount", 500)),
                "tenant's own definition");

        assertThrows(ProcessEngineException.class, () -> ev.evaluate("svcDefs", "nope", null, Map.of("amount", 5)));
        assertThrows(ProcessEngineException.class, () -> ev.services("svcDefs", "t-unknown"));
        assertThrows(ProcessEngineException.class, () -> ev.services("missing", null));
    }
}
