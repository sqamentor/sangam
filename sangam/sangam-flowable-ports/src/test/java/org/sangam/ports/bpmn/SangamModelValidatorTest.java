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

    /**
     * R10-bpmn: a data store (reference) is documentation only (profile NOT_EXECUTABLE), so it is accepted with a
     * warning; it used to be refused, which refused MIWG B.1.0, B.2.0, C.4.0, C.5.0 and C.10.0. The standard loop is
     * accepted once P-6 is installed.
     */
    @Test
    void dataStoresAreDocumentationAndTheStandardLoopNeedsP6() {
        SangamModelValidator v = new SangamModelValidator(new SangamModelValidator.Options(true, true));
        List<Finding> f = check(v, "<dataStoreReference id=\"ds\"/><userTask id=\"u\"><standardLoopCharacteristics loopMaximum=\"2\"/></userTask>");
        assertTrue(codes(f, Severity.ERROR).isEmpty(), f.toString());
        assertEquals(Set.of("DOC-ONLY"), codes(f, Severity.WARNING));

        SangamModelValidator noP6 = new SangamModelValidator(new SangamModelValidator.Options(true));
        assertEquals(Set.of("D-52"), codes(check(noP6, "<userTask id=\"u\"><standardLoopCharacteristics loopMaximum=\"2\"/></userTask>"),
                Severity.ERROR));

        try (PortsH2Engine h2 = PortsH2Engine.start(null)) {
            String xml = HEAD.replace("<process ", "<dataStore id=\"store\"/><process ")
                    + "<startEvent id=\"s\"/><dataStoreReference id=\"ds\" dataStoreRef=\"store\"/>" + TAIL;
            h2.engine.getRepositoryService().createDeployment().addString("ds.bpmn", xml).deploy();
            assertEquals(1, h2.engine.getRepositoryService().createProcessDefinitionQuery().processDefinitionKey("p").count());
        }
    }

    @Test
    void optionsFromSettingsCarryEveryInstalledPatch() {
        org.sangam.ports.PortsSettings settings = new org.sangam.ports.PortsSettings();
        assertEquals(new SangamModelValidator.Options(true, true), SangamModelValidator.Options.of(settings));
        settings.setStandardLoop(false);
        assertEquals(new SangamModelValidator.Options(true, false), SangamModelValidator.Options.of(settings));
        settings.setAdHocSubProcess(false);
        settings.setStandardLoop(true);
        assertEquals(new SangamModelValidator.Options(false, true), SangamModelValidator.Options.of(settings));
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

    /**
     * E-46 s2.5 (WF-5): an extension the engine would drop silently is reported as EXT (namespace, local name and
     * element), never an error by itself; a rejected deployment's report names it next to the error.
     */
    @Test
    void unknownExtensionsAreNamedAndListedInARejection() {
        SangamModelValidator v = new SangamModelValidator(new SangamModelValidator.Options(true));
        String ext = "<userTask id=\"CHECK\" xmlns:acme=\"urn:acme:unknown\" acme:routingHint=\"fast\" "
                + "xmlns:operaton=\"http://operaton.org/schema/1.0/bpmn\" operaton:candidateGroups=\"g\">"
                + "<extensionElements><acme:unknownThing level=\"3\"/></extensionElements></userTask>";
        List<Finding> f = check(v, ext);
        assertTrue(codes(f, Severity.ERROR).isEmpty(), "unknown extensions alone never reject: " + f);
        List<String> msgs = f.stream().filter(x -> "EXT".equals(x.code())).map(x -> x.elementId() + " " + x.message()).toList();
        assertEquals(2, msgs.size(), msgs.toString());
        assertTrue(msgs.get(0).contains("CHECK") && msgs.get(0).contains("{urn:acme:unknown}routingHint"), msgs.toString());
        assertTrue(msgs.get(1).contains("{urn:acme:unknown}unknownThing"), msgs.toString());

        try (PortsH2Engine h2 = PortsH2Engine.start(null)) {
            h2.engine.getRepositoryService().createDeployment().addString("ok.bpmn", HEAD + "<startEvent id=\"s\"/>"
                    + ext.replace("CHECK", "u") + TAIL).deploy();
            ProcessEngineException e = assertThrows(ProcessEngineException.class, () -> h2.engine.getRepositoryService()
                    .createDeployment().addString("bad.bpmn", HEAD + "<complexGateway id=\"cg\"/>" + ext + TAIL).deploy());
            assertTrue(e.getMessage().contains("[D-52] bad.bpmn#cg"), e.getMessage());
            assertTrue(e.getMessage().contains("[EXT] bad.bpmn#CHECK") && e.getMessage().contains("routingHint")
                    && e.getMessage().contains("unknownThing"), e.getMessage());
        }
    }
}
