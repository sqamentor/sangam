// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.fwt.parsing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.sangam.ports.modelimport.FlowableModelImporter;
import org.sangam.ports.modelimport.FlowableModelImporter.Classification;
import org.sangam.ports.modelimport.FlowableModelImporter.Finding;
import org.sangam.ports.modelimport.FlowableModelImporter.Result;
import org.sangam.ports.support.SecureXml;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** FW-27 / FW-44 importer scenarios (FLOWABLE-WEAKNESS-AUDIT §5). */
class FwtModelImporterTest {

    static final String OP = FlowableModelImporter.OPERATON_NS;
    static final String BPMN = "http://www.omg.org/spec/BPMN/20100524/MODEL";

    static String model(String vendorNs, String body) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><definitions xmlns=\"" + BPMN + "\" xmlns:v=\"" + vendorNs
                + "\" targetNamespace=\"t\"><process id=\"p\" isExecutable=\"true\">" + body + "</process></definitions>";
    }

    static Result convert(String xml) {
        return FlowableModelImporter.convert(xml.getBytes(StandardCharsets.UTF_8));
    }

    static Element byId(Result r, String id) {
        Document d = SecureXml.parse(r.converted());
        var all = d.getElementsByTagNameNS("*", "*");
        for (int i = 0; i < all.getLength(); i++) {
            Element e = (Element) all.item(i);
            if (id.equals(e.getAttribute("id"))) {
                return e;
            }
        }
        throw new AssertionError("no element " + id);
    }

    static List<Finding> of(Result r, String construct) {
        return r.findings().stream().filter(f -> f.construct().equals(construct)).toList();
    }

    // FWT-020
    @Test
    void fwt020_activitiNamespaceMappedLikeFlowable() {
        String body = "<userTask id=\"ut\" v:assignee=\"kermit\" v:candidateGroups=\"g1\" v:formKey=\"f1\"/>"
                + "<serviceTask id=\"st\" v:async=\"true\" v:class=\"a.B\"/>";
        Result fl = convert(model(FlowableModelImporter.FLOWABLE_NS, body));
        Result ac = convert(model(FlowableModelImporter.ACTIVITI_NS, body));
        for (Result r : List.of(fl, ac)) {
            Element ut = byId(r, "ut");
            assertEquals("kermit", ut.getAttributeNS(OP, "assignee"));
            assertEquals("g1", ut.getAttributeNS(OP, "candidateGroups"));
            assertEquals("f1", ut.getAttributeNS(OP, "formKey"));
            Element st = byId(r, "st");
            assertEquals("true", st.getAttributeNS(OP, "asyncBefore"));
            assertEquals("a.B", st.getAttributeNS(OP, "class"));
            assertEquals("", ut.getAttributeNS(FlowableModelImporter.ACTIVITI_NS, "assignee"));
            assertTrue(r.importable(), String.valueOf(r.findings()));
        }
        assertEquals(fl.findings().stream().map(f -> f.classification() + f.construct()).sorted().toList(),
                ac.findings().stream().map(f -> f.classification() + f.construct()).sorted().toList(), "same findings");
    }

    // FWT-021
    @Test
    void fwt021_dmnServiceTaskWithKeyBecomesBusinessRuleTaskWithDeploymentBinding_withoutKeyUnsupported() {
        Result with = convert(model(FlowableModelImporter.FLOWABLE_NS,
                "<serviceTask id=\"d\" v:type=\"dmn\"><extensionElements>"
                        + "<v:field name=\"decisionTableReferenceKey\"><v:string>approve</v:string></v:field>"
                        + "</extensionElements></serviceTask>"));
        Element d = byId(with, "d");
        assertEquals("businessRuleTask", d.getLocalName());
        assertEquals(BPMN, d.getNamespaceURI());
        assertEquals("approve", d.getAttributeNS(OP, "decisionRef"));
        assertEquals("deployment", d.getAttributeNS(OP, "decisionRefBinding"));
        assertEquals(Classification.MAPPED, of(with, "flowable:type=dmn").get(0).classification());
        assertTrue(with.importable(), String.valueOf(with.findings()));

        Result without = convert(model(FlowableModelImporter.FLOWABLE_NS, "<serviceTask id=\"d\" v:type=\"dmn\"/>"));
        assertEquals(Classification.UNSUPPORTED, of(without, "flowable:type=dmn").get(0).classification());
        assertFalse(without.importable());
    }

    // FWT-022
    @Test
    void fwt022_scriptTaskIsUnsupportedD46() {
        Result r = convert(model(FlowableModelImporter.FLOWABLE_NS,
                "<scriptTask id=\"sc\" scriptFormat=\"groovy\"><script>1+1</script></scriptTask>"));
        Finding f = of(r, "scriptTask").get(0);
        assertEquals(Classification.UNSUPPORTED, f.classification());
        assertEquals("D-46", f.reference());
        assertEquals("sc", f.elementId());
        assertFalse(r.importable());
    }

    // FWT-057 (importer part; the direct deploy part is FwtScriptTaskDeployH2Test in sangam-engine)
    @Test
    void fwt057_shellTypeAndScriptTaskBothUnsupported() {
        Result r = convert(model(FlowableModelImporter.FLOWABLE_NS,
                "<serviceTask id=\"sh\" v:type=\"shell\"/><scriptTask id=\"sc\" scriptFormat=\"js\"><script>x</script></scriptTask>"));
        assertEquals(Classification.UNSUPPORTED, of(r, "flowable:type=shell").get(0).classification());
        assertEquals(Classification.UNSUPPORTED, of(r, "scriptTask").get(0).classification());
        assertFalse(r.importable());
    }
}
