// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.bpmn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngineException;
import org.sangam.ports.PortsH2Engine;

/**
 * R10-bpmn, found by the MIWG harness: (1) the strict-XOR rule (ADR-D-25) refused descriptive models
 * (isExecutable="false"), which are never run; (2) an {@code <import>} of XML Schema / DMN / BPMN types refused the whole
 * deployment (Operaton parseImports, fixed by P-7). Both still hold for executable processes where they matter.
 */
class DescriptiveModelH2Test {

    private static String model(String executable, String imports) {
        return """
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="t">%s
                  <process id="p" isExecutable="%s">
                    <startEvent id="s"/>
                    <sequenceFlow id="f0" sourceRef="s" targetRef="x"/>
                    <exclusiveGateway id="x"/>
                    <sequenceFlow id="f1" sourceRef="x" targetRef="a"/>
                    <sequenceFlow id="f2" sourceRef="x" targetRef="b"/>
                    <task id="a"/><task id="b"/>
                  </process>
                </definitions>
                """.formatted(imports, executable);
    }

    @Test
    void aDescriptiveGatewayWithoutConditionsDeploysAndAnExecutableOneIsStillRefused() {
        try (PortsH2Engine h2 = PortsH2Engine.start(null)) {
            h2.engine.getRepositoryService().createDeployment().addString("d.bpmn", model("false", "")).deploy();
            assertEquals(0, h2.engine.getRepositoryService().createProcessDefinitionQuery().count());
            ProcessEngineException e = assertThrows(ProcessEngineException.class, () -> h2.engine.getRepositoryService()
                    .createDeployment().addString("e.bpmn", model("true", "")).deploy());
            assertTrue(e.getMessage().contains("[ADR-D-25] e.bpmn#x"), e.getMessage());
        }
    }

    /** P-8 (two collaborations, MIWG C.4.0) and P-9 (a DI edge without bpmnElement, MIWG C.7.0). */
    @Test
    void twoCollaborationsAndADrawingOnlyEdgeDeploy() {
        String xml = """
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
                             xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
                             xmlns:di="http://www.omg.org/spec/DD/20100524/DI" targetNamespace="t">
                  <collaboration id="c1"><participant id="pa" name="Pool A" processRef="p"/></collaboration>
                  <collaboration id="c2"><participant id="pb" name="Pool B" processRef="q"/></collaboration>
                  <process id="p" isExecutable="true"><startEvent id="s"/><sequenceFlow id="f" sourceRef="s" targetRef="e"/><endEvent id="e"/></process>
                  <process id="q" isExecutable="false"><task id="t"/></process>
                  <bpmndi:BPMNDiagram id="d"><bpmndi:BPMNPlane id="pl" bpmnElement="c1">
                    <bpmndi:BPMNEdge id="drawing"><di:waypoint x="0" y="0"/><di:waypoint x="10" y="10"/></bpmndi:BPMNEdge>
                  </bpmndi:BPMNPlane></bpmndi:BPMNDiagram>
                </definitions>
                """;
        try (PortsH2Engine h2 = PortsH2Engine.start(null)) {
            h2.engine.getRepositoryService().createDeployment().addString("two.bpmn", xml).deploy();
            var pd = h2.engine.getRepositoryService().createProcessDefinitionQuery().processDefinitionKey("p").singleResult();
            assertEquals("p", pd.getKey());
            assertEquals(1, h2.engine.getRepositoryService().createProcessDefinitionQuery().count(), "only the executable pool");
        }
    }

    @Test
    void anImportOfTypesIsDocumentationAndDoesNotRefuseTheDeployment() {
        String imports = "<import importType=\"http://www.w3.org/2001/XMLSchema\" location=\"types.xsd\" namespace=\"urn:t\"/>"
                + "<import importType=\"https://www.omg.org/spec/DMN/20191111/MODEL/\" location=\"d.dmn\" namespace=\"urn:d\"/>";
        try (PortsH2Engine h2 = PortsH2Engine.start(null)) {
            h2.engine.getRepositoryService().createDeployment().addString("i.bpmn", model("false", imports)).deploy();
            assertEquals(1, h2.engine.getRepositoryService().createDeploymentQuery().count());
        }
        SangamModelValidator v = new SangamModelValidator(new SangamModelValidator.Options(true, true));
        assertTrue(v.validate("i.bpmn", model("false", imports).getBytes(java.nio.charset.StandardCharsets.UTF_8)).stream()
                .anyMatch(f -> "DOC-ONLY".equals(f.code()) && f.message().contains("XMLSchema")));
    }
}
