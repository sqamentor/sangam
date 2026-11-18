// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.bpmn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngineException;
import org.sangam.ports.PortsH2Engine;
import org.sangam.ports.bpmn.SangamModelValidator.Finding;
import org.sangam.ports.bpmn.SangamModelValidator.Severity;

/**
 * Owner ruling F3 (R10-bpmn): Sangam does not evaluate XPath. A model that declares XPath, as the definitions'
 * {@code expressionLanguage} or as the {@code language} of a condition or other formal expression, is refused at deploy
 * with code XPATH. Before this rule the declaration was ignored and every expression was read as JUEL (MIWG C.1.0 ran
 * with XPath declared). Applies to descriptive and executable processes alike: the declaration covers the whole file.
 */
class XPathRefusedTest {

    private static final String XPATH = "http://www.w3.org/1999/XPath";

    private static String model(String definitionsLanguage, String conditionLanguage, String executable) {
        String lang = definitionsLanguage == null ? "" : " expressionLanguage=\"" + definitionsLanguage + "\"";
        String cond = conditionLanguage == null ? "" : " language=\"" + conditionLanguage + "\"";
        return """
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" targetNamespace="t"%s>
                  <process id="p" isExecutable="%s">
                    <startEvent id="s"/>
                    <sequenceFlow id="f0" sourceRef="s" targetRef="x"/>
                    <exclusiveGateway id="x" default="f2"/>
                    <sequenceFlow id="f1" sourceRef="x" targetRef="a">
                      <conditionExpression xsi:type="tFormalExpression"%s>${ok}</conditionExpression>
                    </sequenceFlow>
                    <sequenceFlow id="f2" sourceRef="x" targetRef="a"/>
                    <userTask id="a"/>
                  </process>
                </definitions>
                """.formatted(lang, executable, cond);
    }

    private static Set<String> errors(String xml) {
        List<Finding> f = new SangamModelValidator(new SangamModelValidator.Options(true, true))
                .validate("m.bpmn", xml.getBytes(StandardCharsets.UTF_8));
        return f.stream().filter(x -> x.severity() == Severity.ERROR).map(x -> x.code() + "#" + x.elementId())
                .collect(Collectors.toSet());
    }

    @Test
    void xpathDeclaredOnTheDefinitionsIsRefused() {
        assertEquals(Set.of("XPATH#null"), errors(model(XPATH, null, "true")));
        assertEquals(Set.of("XPATH#null"), errors(model(XPATH, null, "false")), "descriptive too: the file declares XPath");
        assertEquals(Set.of("XPATH#null"), errors(model("HTTP://WWW.W3.ORG/1999/XPATH", null, "true")), "URI case");
    }

    @Test
    void xpathDeclaredOnAConditionIsRefused() {
        assertEquals(Set.of("XPATH#f1"), errors(model(null, XPATH, "true")));
        assertEquals(Set.of("XPATH#f1"), errors(model(null, XPATH, "false")));
    }

    @Test
    void juelAndNoDeclarationStayAccepted() {
        assertEquals(Set.of(), errors(model(null, null, "true")));
        assertEquals(Set.of(), errors(model("http://www.operaton.org/juel", "juel", "true")));
    }

    @Test
    void theDeploymentIsRefusedNotReadAsJuel() {
        try (PortsH2Engine h2 = PortsH2Engine.start(null)) {
            ProcessEngineException e = assertThrows(ProcessEngineException.class, () -> h2.engine.getRepositoryService()
                    .createDeployment().addString("x.bpmn", model(XPATH, null, "true")).deploy());
            assertTrue(e.getMessage().contains("[XPATH] x.bpmn"), e.getMessage());
            assertTrue(e.getMessage().contains("XPath"), e.getMessage());
            assertEquals(0, h2.engine.getRepositoryService().createProcessDefinitionQuery().count());
            assertThrows(ProcessEngineException.class, () -> h2.engine.getRepositoryService()
                    .createDeployment().addString("y.bpmn", model(null, XPATH, "true")).deploy());
            h2.engine.getRepositoryService().createDeployment().addString("z.bpmn", model(null, null, "true")).deploy();
            assertEquals(1, h2.engine.getRepositoryService().createProcessDefinitionQuery().count());
        }
    }
}
