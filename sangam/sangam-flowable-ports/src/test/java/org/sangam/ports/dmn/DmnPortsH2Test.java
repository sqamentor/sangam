// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.dmn;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.dmn.engine.DmnDecisionResult;
import org.sangam.ports.PortsH2Engine;

/** FC-54 PRIORITY / OUTPUT ORDER hit policies, per tenant and per version. (FC-53 decision services: removed, owner decision D3 2026-11-18.) */
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
}
