// SPDX-License-Identifier: Apache-2.0
package org.sangam.host.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.sangam.rest.port.ApiProblem;
import org.w3c.dom.Document;

/** M-144/M-150 kit mechanics that need no host: refusal matching, the fixed models, the refusal label. Not run. */
class KitMechanicsTest {

    @Test
    void theRefusalMarkerIsFoundInCausesAndSuppressedExceptions() {
        RuntimeException root = new IllegalStateException("sangam-security is not active, so jobs run without identity");
        RuntimeException wrapped = new RuntimeException("context failed", new RuntimeException("bean failed", root));
        assertTrue(HostFailClosedKit.chainContains(wrapped, "sangam-security is not active"));
        RuntimeException other = new RuntimeException("missing key-encryption key");
        assertFalse(HostFailClosedKit.chainContains(other, "sangam-security is not active"));
        other.addSuppressed(root);
        assertTrue(HostFailClosedKit.chainContains(other, "sangam-security is not active"));
    }

    @Test
    void theModelsAreWellFormedXmlWithTheirKeys() throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document dmn = f.newDocumentBuilder().parse(new ByteArrayInputStream(ConformanceModels.feeDecision()));
        assertEquals(ConformanceModels.FEE_DECISION,
                dmn.getElementsByTagNameNS("*", "decision").item(0).getAttributes().getNamedItem("id").getNodeValue());
        assertEquals(3, dmn.getElementsByTagNameNS("*", "rule").getLength());
        Document bpmn = f.newDocumentBuilder().parse(new ByteArrayInputStream(ConformanceModels.feeProcess()));
        assertEquals(ConformanceModels.FEE_PROCESS,
                bpmn.getElementsByTagNameNS("*", "process").item(0).getAttributes().getNamedItem("id").getNodeValue());
        assertEquals(1, bpmn.getElementsByTagNameNS("*", "businessRuleTask").getLength());
        assertEquals(ConformanceModels.REVIEW_TASK,
                bpmn.getElementsByTagNameNS("*", "userTask").item(0).getAttributes().getNamedItem("id").getNodeValue());
    }

    @Test
    void aRefusalIsLabelledByItsStatusAndAnAllowedCallSaysSo() {
        assertEquals("refused-404", EquivalenceScenarios.refusal(() -> {
            throw ApiProblem.notFound("process instance", "x");
        }));
        assertEquals("allowed", EquivalenceScenarios.refusal(() -> { }));
    }

    @Test
    void theScenarioCatalogueCoversTheKitAreas() {
        List<String> areas = EquivalenceScenarios.all().stream().map(EquivalenceScenario::area).toList();
        for (String area : List.of("money", "authorization", "audit", "duplicate prevention")) {
            assertTrue(areas.stream().anyMatch(a -> a.contains(area)), area);
        }
    }
}
