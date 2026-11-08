// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.modelimport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.sangam.ports.PortsH2Engine;
import org.sangam.ports.modelimport.FlowableModelImporter.Classification;
import org.sangam.ports.modelimport.FlowableModelImporter.Result;

/** FC-05 Flowable model import: attribute mapping, classification, deployability of the output. */
class FlowableModelImporterTest {

    private static final String MODEL = """
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="t">
              <process id="parent" isExecutable="true">
                <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="u"/>
                <userTask id="u" flowable:assignee="ravi" flowable:candidateGroups="clerks" flowable:async="true"/>
                <sequenceFlow id="f2" sourceRef="u" targetRef="call"/>
                <callActivity id="call" calledElement="child" flowable:calledElementType="key"/>
                <sequenceFlow id="f3" sourceRef="call" targetRef="e"/><endEvent id="e"/>
              </process>
              <process id="child" isExecutable="true"><startEvent id="cs"/><sequenceFlow id="cf" sourceRef="cs" targetRef="ce"/><endEvent id="ce"/></process>
            </definitions>""";

    private static boolean has(Result r, Classification c, String construct) {
        return r.findings().stream().anyMatch(f -> f.classification() == c && f.construct().equals(construct));
    }

    @Test
    void mapsAttributesAndTheOutputDeploysAndRuns() {
        Result r = FlowableModelImporter.convert(MODEL.getBytes(StandardCharsets.UTF_8));
        String out = new String(r.converted(), StandardCharsets.UTF_8);
        assertTrue(r.importable(), r.findings().toString());
        assertTrue(has(r, Classification.MAPPED, "flowable:assignee"));
        assertTrue(has(r, Classification.MAPPED, "flowable:async"));
        assertTrue(has(r, Classification.MAPPED, "flowable:calledElementType=key"));
        assertTrue(out.contains("operaton:assignee=\"ravi\""), out);
        assertTrue(out.contains("operaton:asyncBefore=\"true\""), out);
        assertFalse(out.contains("calledElementBinding"), "Flowable's key|id is not a version binding");
        assertFalse(out.contains("flowable:assignee"), out);

        try (PortsH2Engine h2 = PortsH2Engine.start(null)) {
            h2.engine.getRepositoryService().createDeployment().addString("imported.bpmn", out).deploy();
            String pi = h2.engine.getRuntimeService().startProcessInstanceByKey("parent").getId();
            h2.executeDueJobs();
            var task = h2.engine.getTaskService().createTaskQuery().processInstanceId(pi).singleResult();
            assertTrue("ravi".equals(task.getAssignee()));
            h2.engine.getTaskService().complete(task.getId());
            assertTrue(h2.engine.getHistoryService().createHistoricProcessInstanceQuery().processDefinitionKey("child").finished().count() == 1,
                    "call activity by key reached the child");
        }
    }

    @Test
    void classifiesUnsupportedConstructs() {
        String m = MODEL.replace("flowable:calledElementType=\"key\"", "flowable:calledElementType=\"id\"")
                .replace("<endEvent id=\"e\"/>", "<scriptTask id=\"sc\"/><endEvent id=\"e\"/>");
        Result r = FlowableModelImporter.convert(m.getBytes(StandardCharsets.UTF_8));
        assertFalse(r.importable());
        assertTrue(has(r, Classification.UNSUPPORTED, "flowable:calledElementType=id"));
        assertTrue(has(r, Classification.UNSUPPORTED, "scriptTask"));
    }
}
