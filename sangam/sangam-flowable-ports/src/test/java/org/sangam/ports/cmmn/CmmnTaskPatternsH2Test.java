// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.cmmn;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.externaltask.LockedExternalTask;
import org.sangam.ports.PortsH2Engine;

/** FC-11 / FC-58: CMMN process tasks marked with sangam:taskType run through generated one-step BPMN helpers. */
class CmmnTaskPatternsH2Test {

    private static String caseXml(String taskAttrs, String taskBody) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/CMMN/20151109/MODEL" xmlns:sangam="http://sangam.org/schema/1.0/bpmn"
                             targetNamespace="t" id="d">
                  <case id="kycCase">
                    <casePlanModel id="plan">
                      <planItem id="PI_pt" definitionRef="pt"/>
                      <planItem id="PI_review" definitionRef="review"/>
                      <processTask id="pt" %s>%s</processTask>
                      <humanTask id="review"/>
                    </casePlanModel>
                  </case>
                </definitions>
                """.formatted(taskAttrs, taskBody);
    }

    private static final String EXTERNAL = "sangam:taskType=\"external-worker\" sangam:topic=\"kyc\"";

    private PortsH2Engine h2;

    @BeforeEach
    void setUp() {
        h2 = PortsH2Engine.start(null);
    }

    @AfterEach
    void tearDown() {
        h2.close();
    }

    private static Map<String, byte[]> resources(String cmmn) {
        Map<String, byte[]> r = new LinkedHashMap<>();
        r.put("kyc.cmmn", cmmn.getBytes(StandardCharsets.UTF_8));
        r.put("notes.txt", "plain".getBytes(StandardCharsets.UTF_8));
        return r;
    }

    private void deploy(Map<String, byte[]> resources) {
        var d = h2.engine.getRepositoryService().createDeployment();
        resources.forEach((n, b) -> d.addInputStream(n, new ByteArrayInputStream(b)));
        d.deploy();
    }

    private void runExternalWorkerCase(String body) {
        Map<String, byte[]> out = CmmnTaskPatterns.enrich(resources(caseXml(EXTERNAL, body)));
        assertTrue(out.containsKey("cmmn-pt.bpmn"), out.keySet().toString());
        deploy(out);
        String c = h2.engine.getCaseService().createCaseInstanceByKey("kycCase", Map.of("customer", "C-9")).getId();

        List<LockedExternalTask> locked = h2.engine.getExternalTaskService().fetchAndLock(1, "worker").topic("kyc", 60_000).execute();
        assertEquals(1, locked.size(), "the case's process task is served by the normal external worker API");
        assertEquals("C-9", locked.get(0).getVariables().get("customer"), "case variables passed in");
        h2.engine.getExternalTaskService().complete(locked.get(0).getId(), "worker", Map.of("kycOk", true));

        assertEquals(true, h2.engine.getCaseService().getVariable(c, "kycOk"), "worker result passed back to the case");
        assertEquals(0, h2.engine.getCaseService().createCaseExecutionQuery().caseInstanceId(c).activityId("PI_pt").active().count(),
                "process task completed");
    }

    @Test
    void externalWorkerProcessTaskRoundTrips() {
        runExternalWorkerCase("");
    }

    /** Regression: extensionElements was inserted before documentation, breaking CMMN element order (deployment failed). */
    @Test
    void enrichKeepsSchemaOrderWhenTheTaskHasDocumentation() {
        runExternalWorkerCase("<documentation>Runs the KYC check</documentation>");
    }

    @Test
    void untouchedResourcesStayByteIdenticalAndHelpersAreGeneratedPerType() {
        String plain = caseXml("processRef=\"existing\"", "");
        Map<String, byte[]> in = resources(plain);
        Map<String, byte[]> out = CmmnTaskPatterns.enrich(in);
        assertEquals(in.keySet(), out.keySet());
        assertArrayEquals(in.get("kyc.cmmn"), out.get("kyc.cmmn"));

        Map<String, byte[]> svc = CmmnTaskPatterns.enrich(resources(caseXml(
                "processRef=\"myHelper\" sangam:taskType=\"service\" sangam:delegateExpression=\"${kycBean}\"", "")));
        String helper = new String(svc.get("myHelper.bpmn"), StandardCharsets.UTF_8);
        assertTrue(helper.contains("${kycBean}"), "explicit processRef kept as helper key");
        String rewritten = new String(svc.get("kyc.cmmn"), StandardCharsets.UTF_8);
        assertTrue(rewritten.contains("variables=\"all\""), rewritten);

        Map<String, byte[]> send = CmmnTaskPatterns.enrich(resources(caseXml(
                "sangam:taskType=\"send-event\" sangam:eventType=\"orderPlaced\" sangam:channels=\"out\"", "")));
        String sendHelper = new String(send.get("cmmn-pt.bpmn"), StandardCharsets.UTF_8);
        assertTrue(sendHelper.contains("orderPlaced") && sendHelper.contains("SendEventDelegate"), sendHelper);

        assertEquals("topicX", CmmnTaskPatterns.externalWorkerProcess("k", "topicX").getModelElementById("work").getAttributeValueNs(
                "http://operaton.org/schema/1.0/bpmn", "topic"));
    }

    @Test
    void invalidMarkupIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> CmmnTaskPatterns.enrich(resources(caseXml("sangam:taskType=\"teleport\"", ""))));
        assertThrows(IllegalArgumentException.class, () -> CmmnTaskPatterns.enrich(resources(caseXml("sangam:taskType=\"external-worker\"", ""))));
        assertThrows(IllegalArgumentException.class, () -> CmmnTaskPatterns.enrich(resources(caseXml(
                "sangam:taskType=\"send-event\" sangam:eventType=\"e\"", ""))));
    }
}
