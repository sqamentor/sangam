// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.bpmn;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sangam.ports.PortsH2Engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * R10-bpmn: the published standards profile (deploy/evidence/standards-profile.json, generated from
 * sangam-ui/scripts/compat-rules.json) is checked against behaviour, so a row cannot go stale. Every BPMN row whose rule
 * is not enforced by sangam-engine's parse listener has a probe model here; the probe is deployed on an engine with the
 * ports plugin (parse factory P-1/P-6/P-7 and the pre-deploy validator) and must deploy when the profile says
 * Supported / Supported with rules / Ported / Not executable, and must be refused when it says Rejected / Unsupported.
 * Rows enforced by sangam-engine (source "sangam-engine SangamBpmnParseListener") are checked by
 * sangam-engine StandardsProfileEngineGuardTest. A BPMN row without a probe in either guard fails.
 */
class StandardsProfileGuardTest {

    static final Set<String> DEPLOYS = Set.of("SUPPORTED", "SUPPORTED_WITH_RULES", "PORTED", "NOT_EXECUTABLE");
    static final String ENGINE_SOURCE = "sangam-engine SangamBpmnParseListener";
    /** Rows the engine guard owns; listed so a new row cannot fall between the two guards. */
    static final Set<String> ENGINE_ROWS = Set.of("BusinessRuleTask", "CallActivity", "MultiInstanceLoopCharacteristics",
            "ScriptTask", "TimerEventDefinition");

    private static final String E = "<endEvent id=\"e\"/>";

    /** construct -> process body (the process is executable; DI is not needed). */
    static Map<String, String> probes() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("StartEvent", "<startEvent id=\"s\"/><sequenceFlow id=\"f\" sourceRef=\"s\" targetRef=\"e\"/>" + E);
        p.put("EndEvent", p.get("StartEvent"));
        p.put("Task", flow("<task id=\"x\"/>"));
        p.put("UserTask", flow("<userTask id=\"x\"/>"));
        p.put("ManualTask", flow("<manualTask id=\"x\"/>"));
        p.put("ReceiveTask", flow("<receiveTask id=\"x\" messageRef=\"msg\"/>"));
        p.put("SendTask", flow("<sendTask id=\"x\" operaton:type=\"external\" operaton:topic=\"t\"/>"));
        p.put("ServiceTask", flow("<serviceTask id=\"x\" operaton:type=\"external\" operaton:topic=\"t\"/>"));
        p.put("SubProcess", flow("<subProcess id=\"x\"><startEvent id=\"ss\"/><sequenceFlow id=\"sf\" sourceRef=\"ss\" targetRef=\"se\"/><endEvent id=\"se\"/></subProcess>"));
        p.put("SubProcess.triggeredByEvent", "<startEvent id=\"s\"/><sequenceFlow id=\"f\" sourceRef=\"s\" targetRef=\"u\"/><userTask id=\"u\"/>"
                + "<sequenceFlow id=\"f2\" sourceRef=\"u\" targetRef=\"e\"/>" + E
                + "<subProcess id=\"esp\" triggeredByEvent=\"true\"><startEvent id=\"es\"><signalEventDefinition signalRef=\"sig\"/></startEvent>"
                + "<sequenceFlow id=\"ef\" sourceRef=\"es\" targetRef=\"ee\"/><endEvent id=\"ee\"/></subProcess>");
        p.put("Transaction", flow("<transaction id=\"x\"><startEvent id=\"ts\"/><sequenceFlow id=\"tf\" sourceRef=\"ts\" targetRef=\"te\"/><endEvent id=\"te\"/></transaction>"));
        p.put("AdHocSubProcess", flow("<adHocSubProcess id=\"x\"><userTask id=\"a\"/></adHocSubProcess>"));
        p.put("ExclusiveGateway", "<startEvent id=\"s\"/><sequenceFlow id=\"f\" sourceRef=\"s\" targetRef=\"g\"/><exclusiveGateway id=\"g\" default=\"f2\"/>"
                + "<sequenceFlow id=\"f1\" sourceRef=\"g\" targetRef=\"e\"><conditionExpression>${true}</conditionExpression></sequenceFlow>"
                + "<sequenceFlow id=\"f2\" sourceRef=\"g\" targetRef=\"e\"/>" + E);
        p.put("ParallelGateway", gateway("parallelGateway"));
        p.put("InclusiveGateway", gateway("inclusiveGateway"));
        p.put("ComplexGateway", gateway("complexGateway"));
        p.put("EventBasedGateway", "<startEvent id=\"s\"/><sequenceFlow id=\"f\" sourceRef=\"s\" targetRef=\"g\"/><eventBasedGateway id=\"g\"/>"
                + "<sequenceFlow id=\"f1\" sourceRef=\"g\" targetRef=\"c1\"/><intermediateCatchEvent id=\"c1\"><signalEventDefinition signalRef=\"sig\"/></intermediateCatchEvent>"
                + "<sequenceFlow id=\"f2\" sourceRef=\"g\" targetRef=\"c2\"/><intermediateCatchEvent id=\"c2\"><messageEventDefinition messageRef=\"msg\"/></intermediateCatchEvent>"
                + "<sequenceFlow id=\"f3\" sourceRef=\"c1\" targetRef=\"e\"/><sequenceFlow id=\"f4\" sourceRef=\"c2\" targetRef=\"e\"/>" + E);
        p.put("IntermediateCatchEvent", flow("<intermediateCatchEvent id=\"x\"><signalEventDefinition signalRef=\"sig\"/></intermediateCatchEvent>"));
        p.put("IntermediateThrowEvent", flow("<intermediateThrowEvent id=\"x\"/>"));
        p.put("SignalEventDefinition", flow("<intermediateThrowEvent id=\"x\"><signalEventDefinition signalRef=\"sig\"/></intermediateThrowEvent>"));
        p.put("MessageEventDefinition", flow("<intermediateCatchEvent id=\"x\"><messageEventDefinition messageRef=\"msg\"/></intermediateCatchEvent>"));
        p.put("ConditionalEventDefinition", flow("<intermediateCatchEvent id=\"x\"><conditionalEventDefinition><condition>${go}</condition></conditionalEventDefinition></intermediateCatchEvent>"));
        p.put("LinkEventDefinition", "<startEvent id=\"s\"/><sequenceFlow id=\"f\" sourceRef=\"s\" targetRef=\"lt\"/>"
                + "<intermediateThrowEvent id=\"lt\"><linkEventDefinition name=\"L\"/></intermediateThrowEvent>"
                + "<intermediateCatchEvent id=\"lc\"><linkEventDefinition name=\"L\"/></intermediateCatchEvent>"
                + "<sequenceFlow id=\"f2\" sourceRef=\"lc\" targetRef=\"e\"/>" + E);
        p.put("TerminateEventDefinition", "<startEvent id=\"s\"/><sequenceFlow id=\"f\" sourceRef=\"s\" targetRef=\"e\"/><endEvent id=\"e\"><terminateEventDefinition/></endEvent>");
        p.put("ErrorEventDefinition", boundary("<errorEventDefinition errorRef=\"err\"/>"));
        p.put("EscalationEventDefinition", boundary("<escalationEventDefinition escalationRef=\"esc\"/>"));
        p.put("BoundaryEvent", boundary("<signalEventDefinition signalRef=\"sig\"/>"));
        p.put("CancelEventDefinition", "<startEvent id=\"s\"/><sequenceFlow id=\"f\" sourceRef=\"s\" targetRef=\"tx\"/>"
                + "<transaction id=\"tx\"><startEvent id=\"ts\"/><sequenceFlow id=\"tf\" sourceRef=\"ts\" targetRef=\"te\"/><endEvent id=\"te\"><cancelEventDefinition/></endEvent></transaction>"
                + "<boundaryEvent id=\"b\" attachedToRef=\"tx\"><cancelEventDefinition/></boundaryEvent>"
                + "<sequenceFlow id=\"bf\" sourceRef=\"b\" targetRef=\"e\"/><sequenceFlow id=\"f2\" sourceRef=\"tx\" targetRef=\"e\"/>" + E);
        p.put("CompensateEventDefinition", "<startEvent id=\"s\"/><sequenceFlow id=\"f\" sourceRef=\"s\" targetRef=\"x\"/><task id=\"x\"/>"
                + "<boundaryEvent id=\"b\" attachedToRef=\"x\"><compensateEventDefinition/></boundaryEvent>"
                + "<task id=\"undo\" isForCompensation=\"true\"/>"
                + "<sequenceFlow id=\"f2\" sourceRef=\"x\" targetRef=\"c\"/><intermediateThrowEvent id=\"c\"><compensateEventDefinition/></intermediateThrowEvent>"
                + "<sequenceFlow id=\"f3\" sourceRef=\"c\" targetRef=\"e\"/>" + E
                + "<association associationDirection=\"One\" sourceRef=\"b\" targetRef=\"undo\"/>");   // artifacts after flow elements (XSD)
        p.put("MultipleEventDefinition", flow("<intermediateCatchEvent id=\"x\"><signalEventDefinition signalRef=\"sig\"/><messageEventDefinition messageRef=\"msg\"/></intermediateCatchEvent>"));
        p.put("FormalExpression.XPath", "<startEvent id=\"s\"/><sequenceFlow id=\"f\" sourceRef=\"s\" targetRef=\"g\"/>"
                + "<exclusiveGateway id=\"g\" default=\"f2\"/><sequenceFlow id=\"f1\" sourceRef=\"g\" targetRef=\"e\">"
                + "<conditionExpression language=\"http://www.w3.org/1999/XPath\">${true}</conditionExpression></sequenceFlow>"
                + "<sequenceFlow id=\"f2\" sourceRef=\"g\" targetRef=\"e\"/>" + E);   // F3
        p.put("StandardLoopCharacteristics", flow("<userTask id=\"x\"><standardLoopCharacteristics loopMaximum=\"2\"/></userTask>"));
        p.put("DataObjectReference", flow("<task id=\"x\"/>") + "<dataObject id=\"do\"/><dataObjectReference id=\"dor\" dataObjectRef=\"do\"/>");
        p.put("DataStoreReference", flow("<task id=\"x\"/>") + "<dataStoreReference id=\"dsr\" dataStoreRef=\"store\"/>");
        p.put("Group", flow("<task id=\"x\"/>") + "<group id=\"grp\" categoryValueRef=\"cv\"/>");
        p.put("TextAnnotation", flow("<task id=\"x\"/>") + "<textAnnotation id=\"ta\"><text>note</text></textAnnotation>");
        p.put("Participant", null);   // collaboration: built separately in xml()
        return p;
    }

    private static String flow(String node) {
        return "<startEvent id=\"s\"/><sequenceFlow id=\"f\" sourceRef=\"s\" targetRef=\"x\"/>" + node
                + "<sequenceFlow id=\"f2\" sourceRef=\"x\" targetRef=\"e\"/>" + E;
    }

    private static String gateway(String tag) {
        return "<startEvent id=\"s\"/><sequenceFlow id=\"f\" sourceRef=\"s\" targetRef=\"g\"/><" + tag + " id=\"g\"/>"
                + "<sequenceFlow id=\"f1\" sourceRef=\"g\" targetRef=\"j\"/><sequenceFlow id=\"f2\" sourceRef=\"g\" targetRef=\"j\"/>"
                + "<parallelGateway id=\"j\"/><sequenceFlow id=\"f3\" sourceRef=\"j\" targetRef=\"e\"/>" + E;
    }

    private static String boundary(String def) {
        return "<startEvent id=\"s\"/><sequenceFlow id=\"f\" sourceRef=\"s\" targetRef=\"x\"/><userTask id=\"x\"/>"
                + "<boundaryEvent id=\"b\" attachedToRef=\"x\">" + def + "</boundaryEvent>"
                + "<sequenceFlow id=\"bf\" sourceRef=\"b\" targetRef=\"e\"/><sequenceFlow id=\"f2\" sourceRef=\"x\" targetRef=\"e\"/>" + E;
    }

    static String xml(String construct, String body) {
        String head = "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
                + "xmlns:operaton=\"http://operaton.org/schema/1.0/bpmn\" targetNamespace=\"t\">"
                + "<signal id=\"sig\" name=\"sig\"/><message id=\"msg\" name=\"msg\"/><error id=\"err\" errorCode=\"E1\"/>"
                + "<escalation id=\"esc\" escalationCode=\"X1\"/><dataStore id=\"store\"/>"
                + "<category id=\"cat\"><categoryValue id=\"cv\" value=\"v\"/></category>";
        if (body == null) {   // Participant: an executable pool next to a descriptive one
            return head + "<collaboration id=\"c\"><participant id=\"pa\" processRef=\"p\"/><participant id=\"pb\" processRef=\"q\"/>"
                    + "<messageFlow id=\"mf\" sourceRef=\"pa\" targetRef=\"pb\"/></collaboration>"
                    + "<process id=\"p\" isExecutable=\"true\">" + flow("<task id=\"x\"/>") + "</process>"
                    + "<process id=\"q\" isExecutable=\"false\"><task id=\"y\"/></process></definitions>";
        }
        return head + "<process id=\"p\" isExecutable=\"true\">" + body + "</process></definitions>";
    }

    static JsonNode profile() throws Exception {
        return new ObjectMapper().readTree(new File("../deploy/evidence/standards-profile.json"));
    }

    @Test
    void everyBpmnRowBehavesAsThePublishedProfileSays() throws Exception {
        Map<String, String> probes = probes();
        List<String> wrong = new ArrayList<>();
        try (PortsH2Engine h2 = PortsH2Engine.start(null)) {
            int n = 0;
            for (JsonNode row : profile().get("constructs")) {
                if (!"BPMN".equals(row.get("notation").asText())) {
                    continue;
                }
                String construct = row.get("construct").asText();
                String status = row.get("status").asText();
                if (row.get("source").asText().contains(ENGINE_SOURCE)) {
                    if (!ENGINE_ROWS.contains(construct)) {
                        wrong.add(construct + ": enforced by sangam-engine but not in ENGINE_ROWS (add a probe there)");
                    }
                    continue;
                }
                if (!probes.containsKey(construct)) {
                    wrong.add(construct + ": no probe model (add one to StandardsProfileGuardTest)");
                    continue;
                }
                String error = null;
                try {
                    h2.engine.getRepositoryService().createDeployment().name("probe-" + construct)
                            .addString("probe" + (n++) + ".bpmn", xml(construct, probes.get(construct))).deploy();
                } catch (RuntimeException e) {
                    error = String.valueOf(e.getMessage()).replaceAll("\\s+", " ");
                }
                boolean deployed = error == null;
                if (deployed != DEPLOYS.contains(status)) {
                    wrong.add(construct + ": profile says " + status + " but the probe " + (deployed ? "deployed" : "was refused: " + error));
                }
            }
        }
        assertTrue(wrong.isEmpty(), "standards-profile.json disagrees with the engine (fix compat-rules.json or the code, "
                + "then run gen-compat.mjs and gen_standards_profile.py):\n  " + String.join("\n  ", wrong));
    }
}
