package org.sangam.spike.operaton;

import java.nio.charset.StandardCharsets;

/** Tiny inline BPMN/DMN/CMMN models for the adapter tests. */
final class Models {

    private Models() {
    }

    private static final String BPMN_HEAD = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/bpmn"
                         targetNamespace="http://sangam.example/spike">
            """;

    static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** start -> user task (candidate group) -> external task "pay" -> end. */
    static String userThenPay(String key, String candidateGroup) {
        return BPMN_HEAD + """
                  <process id="%s" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="review"/>
                    <userTask id="review" name="Review" operaton:candidateGroups="%s"/>
                    <sequenceFlow id="f2" sourceRef="review" targetRef="pay"/>
                    <serviceTask id="pay" name="Pay" operaton:type="external" operaton:topic="pay"/>
                    <sequenceFlow id="f3" sourceRef="pay" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(key, candidateGroup);
    }

    /** start -> external task "pay" (error boundary "PAY_REJECTED" -> user task "handleRejection") -> end. */
    /** start, one external task on {@code topic}, end. */
    static String singleExternalTask(String key, String topic) {
        return BPMN_HEAD + """
                  <process id="%s" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="work"/>
                    <serviceTask id="work" name="Work" operaton:type="external" operaton:topic="%s"/>
                    <sequenceFlow id="f2" sourceRef="work" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(key, topic);
    }

    static String payWithErrorBoundary(String key) {
        return BPMN_HEAD + """
                  <error id="payRejected" errorCode="PAY_REJECTED"/>
                  <process id="%s" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="pay"/>
                    <serviceTask id="pay" name="Pay" operaton:type="external" operaton:topic="pay"/>
                    <boundaryEvent id="onReject" attachedToRef="pay">
                      <errorEventDefinition errorRef="payRejected"/>
                    </boundaryEvent>
                    <sequenceFlow id="f2" sourceRef="pay" targetRef="end"/>
                    <endEvent id="end"/>
                    <sequenceFlow id="f3" sourceRef="onReject" targetRef="handleRejection"/>
                    <userTask id="handleRejection" name="Handle rejection"/>
                    <sequenceFlow id="f4" sourceRef="handleRejection" targetRef="end2"/>
                    <endEvent id="end2"/>
                  </process>
                </definitions>
                """.formatted(key);
    }

    /** start -> user task "approve" -> end (used for Q1). */
    static String singleUserTask(String key) {
        return BPMN_HEAD + """
                  <process id="%s" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="approve"/>
                    <userTask id="approve" name="Approve"/>
                    <sequenceFlow id="f2" sourceRef="approve" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(key);
    }

    /** start -> timer catch (duration) -> user task "afterTimer" -> end. */
    static String timer(String key, String isoDuration) {
        return BPMN_HEAD + """
                  <process id="%s" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="wait"/>
                    <intermediateCatchEvent id="wait">
                      <timerEventDefinition><timeDuration>%s</timeDuration></timerEventDefinition>
                    </intermediateCatchEvent>
                    <sequenceFlow id="f2" sourceRef="wait" targetRef="afterTimer"/>
                    <userTask id="afterTimer" name="After timer"/>
                    <sequenceFlow id="f3" sourceRef="afterTimer" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(key, isoDuration);
    }

    /** start -> business rule task (DMN decision) -> user task -> end. */
    static String businessRule(String key, String decisionKey) {
        return BPMN_HEAD + """
                  <process id="%s" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="decide"/>
                    <businessRuleTask id="decide" operaton:decisionRef="%s"
                                      operaton:resultVariable="band" operaton:mapDecisionResult="singleEntry"/>
                    <sequenceFlow id="f2" sourceRef="decide" targetRef="approve"/>
                    <userTask id="approve" name="Approve"/>
                    <sequenceFlow id="f3" sourceRef="approve" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(key, decisionKey);
    }

    /** DMN 1.3 decision table: amount -> approver band (FEEL unary tests). */
    static String approverBandDmn(String decisionKey) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/"
                             id="defs" name="Delegation" namespace="http://sangam.example/dmn">
                  <decision id="%s" name="Approver band">
                    <decisionTable id="table" hitPolicy="UNIQUE">
                      <input id="in1" label="Amount">
                        <inputExpression id="ie1" typeRef="integer"><text>amount</text></inputExpression>
                      </input>
                      <output id="out1" name="band" typeRef="string"/>
                      <rule id="r1">
                        <inputEntry id="r1i"><text>&lt; 10000</text></inputEntry>
                        <outputEntry id="r1o"><text>"A"</text></outputEntry>
                      </rule>
                      <rule id="r2">
                        <inputEntry id="r2i"><text>[10000..100000]</text></inputEntry>
                        <outputEntry id="r2o"><text>"B"</text></outputEntry>
                      </rule>
                      <rule id="r3">
                        <inputEntry id="r3i"><text>&gt; 100000</text></inputEntry>
                        <outputEntry id="r3o"><text>"C"</text></outputEntry>
                      </rule>
                    </decisionTable>
                  </decision>
                </definitions>
                """.formatted(decisionKey);
    }

    /** CMMN 1.1 case with one human task; the case plan model auto-completes. */
    static String smokeCase(String caseKey) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/CMMN/20151109/MODEL"
                             xmlns:operaton="http://operaton.org/schema/1.0/cmmn"
                             id="cdefs" targetNamespace="http://sangam.example/cmmn">
                  <case id="%s" name="Smoke case">
                    <casePlanModel id="plan" autoComplete="true">
                      <planItem id="PI_scrutiny" definitionRef="scrutiny"/>
                      <humanTask id="scrutiny" name="Scrutiny"/>
                    </casePlanModel>
                  </case>
                </definitions>
                """.formatted(caseKey);
    }
}
