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

/** FC-46 pre-deploy validator: built-in rules, custom rules, deployer refusal. */
class SangamModelValidatorTest {

    private static final String HEAD = "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
            + "xmlns:flowable=\"http://flowable.org/bpmn\" targetNamespace=\"t\"><process id=\"p\" isExecutable=\"true\">";
    private static final String TAIL = "</process></definitions>";

    private static List<Finding> check(SangamModelValidator v, String body) {
        return v.validate("m.bpmn", (HEAD + body + TAIL).getBytes(StandardCharsets.UTF_8));
    }

    private static Set<String> codes(List<Finding> f, Severity s) {
        return f.stream().filter(x -> x.severity() == s).map(Finding::code).collect(Collectors.toSet());
    }

    @Test
    void reportsUnsupportedConstructs() {
        SangamModelValidator v = new SangamModelValidator(new SangamModelValidator.Options(false));
        List<Finding> f = check(v, "<startEvent id=\"s\"><messageEventDefinition/><signalEventDefinition/></startEvent>"
                + "<complexGateway id=\"cg\"/><adHocSubProcess id=\"ah\"/>"
                + "<userTask id=\"u\" flowable:assignee=\"x\"/>");
        assertEquals(Set.of("D-52", "D-09"), codes(f, Severity.ERROR));
        assertEquals(2, f.stream().filter(x -> "D-52".equals(x.code())).count(), "complex gateway + multiple event");
        assertEquals(Set.of("IMPORT"), codes(f, Severity.WARNING));

        SangamModelValidator withAdHoc = new SangamModelValidator(new SangamModelValidator.Options(true));
        assertTrue(codes(check(withAdHoc, "<adHocSubProcess id=\"ah\"/>"), Severity.ERROR).isEmpty());
    }

    @Test
    void rejectsDoctypeAndIgnoresOtherResources() {
        SangamModelValidator v = new SangamModelValidator(new SangamModelValidator.Options(true));
        byte[] evil = ("<?xml version=\"1.0\"?><!DOCTYPE d [<!ENTITY x \"y\">]>" + HEAD + TAIL).getBytes(StandardCharsets.UTF_8);
        assertEquals("D-51", v.validate("m.bpmn", evil).get(0).code());
        assertTrue(v.validate("notes.txt", evil).isEmpty());
        List<Finding> broken = v.validate("broken.bpmn", "<definitions".getBytes(StandardCharsets.UTF_8));
        assertEquals(1, broken.size());
        assertEquals(Severity.ERROR, broken.get(0).severity(), "unparsable model is an error, not an exception");
    }

    @Test
    void customRuleAndDeployerRefusal() {
        SangamModelValidator v = new SangamModelValidator(new SangamModelValidator.Options(true))
                .addRule((res, doc, out) -> {
                    if (doc.getElementsByTagNameNS("*", "userTask").getLength() == 0) {
                        out.add(new Finding(Severity.ERROR, "PROFILE", res, null, "needs a human step"));
                    }
                });
        assertEquals(Set.of("PROFILE"), codes(check(v, "<startEvent id=\"s\"/>"), Severity.ERROR));

        try (PortsH2Engine h2 = PortsH2Engine.start(null)) {
            ProcessEngineException e = assertThrows(ProcessEngineException.class, () -> h2.engine.getRepositoryService()
                    .createDeployment().addString("bad.bpmn", HEAD + "<startEvent id=\"s\"/><complexGateway id=\"cg\"/>" + TAIL).deploy());
            assertTrue(e.getMessage().contains("[D-52] bad.bpmn#cg"), e.getMessage());
            assertEquals(0, h2.engine.getRepositoryService().createDeploymentQuery().count(), "nothing stored");
        }
    }
}
