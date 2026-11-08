// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.bpmn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.ports.modelimport.FlowableModelImporter;

/** ADR-D-25 in the model tools: importer reports native first-match, keeps Sangam routing attributes; validator rejects. */
class XorRoutingModelToolsTest {

    private static String model(String gwAttrs, String f1Attrs, String f2Attrs) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                             xmlns:flowable="http://flowable.org/bpmn"
                             xmlns:sangam="http://sangam.org/schema/1.0/bpmn" targetNamespace="t">
                  <process id="p" isExecutable="true">
                    <startEvent id="s"/>
                    <sequenceFlow id="f0" sourceRef="s" targetRef="gw"/>
                    <exclusiveGateway id="gw" default="fd" %s/>
                    <sequenceFlow id="f1" sourceRef="gw" targetRef="a" %s><conditionExpression xsi:type="tFormalExpression">${amount &gt;= 100000}</conditionExpression></sequenceFlow>
                    <sequenceFlow id="f2" sourceRef="gw" targetRef="b" %s><conditionExpression xsi:type="tFormalExpression">${amount &gt; 99999}</conditionExpression></sequenceFlow>
                    <sequenceFlow id="fd" sourceRef="gw" targetRef="c"/>
                    <userTask id="a"/><userTask id="b"/><userTask id="c"/>
                  </process>
                </definitions>
                """.formatted(gwAttrs, f1Attrs, f2Attrs);
    }

    @Test
    void importerReportsNativeFirstMatchExplicitly() {
        var r = FlowableModelImporter.convert(model("", "", "").getBytes(StandardCharsets.UTF_8));
        List<FlowableModelImporter.Finding> w = r.findings().stream()
                .filter(f -> f.classification() == FlowableModelImporter.Classification.WARNING
                        && "ADR-D-25".equals(f.reference()) && "gw".equals(f.elementId())).toList();
        assertEquals(1, w.size(), String.valueOf(r.findings()));
        assertTrue(w.get(0).note().contains("first true") && w.get(0).note().contains("AMBIGUOUS_ROUTE"));
    }

    @Test
    void importerPreservesAnExplicitPriorityPolicy() {
        var r = FlowableModelImporter.convert(model("sangam:routingPolicy=\"priority\"", "sangam:priority=\"2\"",
                "sangam:priority=\"1\"").getBytes(StandardCharsets.UTF_8));
        assertTrue(r.findings().stream().noneMatch(f -> "ADR-D-25".equals(f.reference())), String.valueOf(r.findings()));
        String out = new String(r.converted(), StandardCharsets.UTF_8);
        assertTrue(out.contains("routingPolicy=\"priority\""), out);
        assertTrue(out.matches("(?s).*id=\"f1\"[^>]*priority=\"2\".*") && out.matches("(?s).*id=\"f2\"[^>]*priority=\"1\".*"), out);
    }

    @Test
    void validatorRejectsDuplicatePrioritiesAndAcceptsStrictModels() {
        SangamModelValidator v = new SangamModelValidator(new SangamModelValidator.Options(false));
        var bad = v.validate(Map.of("m.bpmn", model("sangam:routingPolicy=\"priority\"", "sangam:priority=\"1\"",
                "sangam:priority=\"1\"").getBytes(StandardCharsets.UTF_8)));
        assertTrue(bad.stream().anyMatch(f -> f.severity() == SangamModelValidator.Severity.ERROR
                && "ADR-D-25".equals(f.code()) && f.message().contains("duplicate")), String.valueOf(bad));
        var strict = v.validate(Map.of("m.bpmn", model("", "", "").getBytes(StandardCharsets.UTF_8)));
        assertTrue(strict.stream().noneMatch(f -> "ADR-D-25".equals(f.code())), String.valueOf(strict));
    }
}
