// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.cmmn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.operaton.bpm.model.bpmn.Bpmn;
import org.operaton.bpm.model.bpmn.BpmnModelInstance;
import org.operaton.bpm.model.bpmn.instance.BusinessRuleTask;
import org.operaton.bpm.model.bpmn.instance.CallActivity;
import org.operaton.bpm.model.bpmn.instance.FlowNode;
import org.operaton.bpm.model.bpmn.instance.ParallelGateway;
import org.operaton.bpm.model.bpmn.instance.Process;
import org.operaton.bpm.model.bpmn.instance.SequenceFlow;
import org.operaton.bpm.model.bpmn.instance.SubProcess;
import org.operaton.bpm.model.bpmn.instance.UserTask;
import org.sangam.modeller.cmmn.CmmnConversion.Diagnostic;
import org.sangam.modeller.cmmn.CmmnConversion.Severity;
import org.sangam.modeller.cmmn.CmmnConversion.StateMapping;
import org.sangam.modeller.cmmn.CmmnConversion.Status;

/**
 * M-127 / T43: the supported and the rejected CMMN conversion corpus (R45). A supported model becomes a schema-valid,
 * executable-shaped BPMN draft whose activities keep the plan item ids; every unsupported construct is an ERROR and
 * gives no BPMN.
 */
class CmmnToBpmnConverterTest {

    static final String HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><definitions xmlns=\"http://www.omg.org/spec/CMMN/20151109/MODEL\""
            + " xmlns:camunda=\"http://camunda.org/schema/1.0/cmmn\" id=\"defs\" targetNamespace=\"http://example/cmmn\">"
            + "<case id=\"loan\" name=\"Loan\" camunda:historyTimeToLive=\"P30D\"><casePlanModel id=\"plan\" name=\"Plan\" autoComplete=\"true\">";
    static final String TAIL = "</casePlanModel></case></definitions>";

    static byte[] cmmn(String body) {
        return (HEAD + body + TAIL).getBytes(StandardCharsets.UTF_8);
    }

    static String onComplete(String sentry, String... sources) {
        StringBuilder b = new StringBuilder("<sentry id=\"" + sentry + "\">");
        int n = 0;
        for (String s : sources) {
            b.append("<planItemOnPart id=\"").append(sentry).append("_op").append(++n).append("\" sourceRef=\"").append(s)
                    .append("\"><standardEvent>complete</standardEvent></planItemOnPart>");
        }
        return b.append("</sentry>").toString();
    }

    /** check and score in parallel; decide after both; then the approval stage (call + milestone) and a done milestone. */
    static final String SUPPORTED =
            "<planItem id=\"check\" definitionRef=\"checkDef\"/>"
            + "<planItem id=\"score\" definitionRef=\"scoreDef\"/>"
            + "<planItem id=\"decide\" definitionRef=\"decideDef\"><entryCriterion id=\"ec1\" sentryRef=\"s1\"/></planItem>"
            + "<planItem id=\"approval\" definitionRef=\"approvalDef\"><entryCriterion id=\"ec2\" sentryRef=\"s2\"/></planItem>"
            + onComplete("s1", "check", "score") + onComplete("s2", "decide")
            + "<humanTask id=\"checkDef\" name=\"Check documents\" camunda:candidateGroups=\"clerks\" camunda:formKey=\"embedded:check\">"
            + "<extensionElements><camunda:taskListener event=\"create\" class=\"org.example.Notify\"/></extensionElements></humanTask>"
            + "<decisionTask id=\"scoreDef\" name=\"Score\" decisionRef=\"score\" camunda:resultVariable=\"score\""
            + " camunda:mapDecisionResult=\"singleEntry\"/>"
            + "<humanTask id=\"decideDef\" name=\"Decide\" camunda:assignee=\"${officer}\"/>"
            + "<stage id=\"approvalDef\" name=\"Approval\" autoComplete=\"true\">"
            + "<planItem id=\"sign\" definitionRef=\"signDef\"/>"
            + "<planItem id=\"signed\" definitionRef=\"signedDef\"><entryCriterion id=\"ec3\" sentryRef=\"s3\"/></planItem>"
            + onComplete("s3", "sign")
            + "<processTask id=\"signDef\" name=\"Sign\" processRef=\"esign\" camunda:processBinding=\"latest\">"
            + "<extensionElements><camunda:in variables=\"all\"/><camunda:out source=\"signature\" target=\"signature\"/>"
            + "</extensionElements></processTask>"
            + "<milestone id=\"signedDef\" name=\"Signed\"/></stage>";

    @Test
    void supportedCaseBecomesAValidBpmnDraftWithTheSameIds() {
        CmmnConversion c = CmmnToBpmnConverter.convert("loan.cmmn", cmmn(SUPPORTED));
        assertEquals(Status.REVIEW_REQUIRED, c.status(), c.diagnostics().toString());
        assertTrue(c.converted());
        assertTrue(c.errors().isEmpty());
        assertNotNull(c.bpmn());

        BpmnModelInstance m = Bpmn.readModelFromStream(new ByteArrayInputStream(c.bpmn()));
        Bpmn.validateModel(m); // BPMN 2.0 schema

        Process p = m.getModelElementById("loan");
        assertTrue(p.isExecutable());
        assertEquals("P30D", p.getOperatonHistoryTimeToLiveString());

        UserTask check = m.getModelElementById("check");
        assertEquals("Check documents", check.getName());
        assertEquals("clerks", check.getOperatonCandidateGroups());
        assertEquals("embedded:check", check.getOperatonFormKey());
        assertEquals(1, check.getExtensionElements().getElementsQuery().count(), "task listener carried over");
        UserTask decide = m.getModelElementById("decide");
        assertEquals("${officer}", decide.getOperatonAssignee());
        BusinessRuleTask score = m.getModelElementById("score");
        assertEquals("score", score.getOperatonDecisionRef());
        assertEquals("score", score.getOperatonResultVariable());
        assertEquals("singleEntry", score.getOperatonMapDecisionResult());
        SubProcess approval = m.getModelElementById("approval");
        CallActivity sign = m.getModelElementById("sign");
        assertEquals("esign", sign.getCalledElement());
        assertEquals("latest", sign.getOperatonCalledElementBinding());
        assertEquals(approval.getId(), ((SubProcess) sign.getParentElement()).getId());
        assertNotNull(m.getModelElementById("signed"));

        // decide waits for both check and score: one parallel join in front of it
        FlowNode decideNode = m.getModelElementById("decide");
        assertEquals(1, decideNode.getIncoming().size());
        FlowNode join = decideNode.getIncoming().iterator().next().getSource();
        assertTrue(join instanceof ParallelGateway);
        assertEquals(Set.of("check", "score"), sources(join.getIncoming()));
        // the plan starts check and score in parallel
        assertEquals(Set.of("check", "score"), targets(m.<FlowNode>getModelElementById("plan_start").getOutgoing()
                .iterator().next().getTarget().getOutgoing()));
        // sign -> signed inside the stage
        assertEquals(Set.of("signed"), targets(m.<FlowNode>getModelElementById("sign").getOutgoing()));

        Map<String, StateMapping> rows = c.mapping().stream().collect(Collectors.toMap(StateMapping::planItemId, r -> r));
        assertEquals(Set.of("check", "score", "decide", "approval", "sign", "signed"), rows.keySet());
        assertEquals("userTask", rows.get("check").bpmnType());
        assertTrue(rows.get("check").waitState());
        assertFalse(rows.get("score").waitState());
        assertFalse(rows.get("signed").waitState());
        assertEquals("loan", rows.get("sign").processId());
        assertTrue(c.diagnostics().stream().anyMatch(d -> d.code().equals("BPMN-NO-DIAGRAM")));
    }

    static Set<String> sources(Collection<SequenceFlow> flows) {
        return flows.stream().map(f -> f.getSource().getId()).collect(Collectors.toSet());
    }

    static Set<String> targets(Collection<SequenceFlow> flows) {
        return flows.stream().map(f -> f.getTarget().getId()).collect(Collectors.toSet());
    }

    @Test
    void warningsKeepTheConversionButNeedReview() {
        String body = "<planItem id=\"a\" definitionRef=\"aDef\"><itemControl><requiredRule/></itemControl></planItem>"
                + "<planItem id=\"b\" definitionRef=\"bDef\"/>"
                + "<task id=\"aDef\" name=\"Paper work\"/><humanTask id=\"bDef\" camunda:candidateStarterGroups=\"x\"/>";
        String noTtl = (HEAD.replace(" camunda:historyTimeToLive=\"P30D\"", "").replace("autoComplete=\"true\"", "")
                + body + TAIL);
        CmmnConversion c = CmmnToBpmnConverter.convert("w.cmmn", noTtl.getBytes(StandardCharsets.UTF_8));
        assertEquals(Status.REVIEW_REQUIRED, c.status(), c.diagnostics().toString());
        Set<String> codes = codes(c.diagnostics(), Severity.WARNING);
        assertTrue(codes.containsAll(Set.of("CMMN-REQUIRED-RULE", "CMMN-STAGE-COMPLETION", "CMMN-BLOCKING-TASK",
                "CMMN-ATTRIBUTE-DROPPED", "CMMN-NO-HISTORY-TTL")), codes.toString());
        BpmnModelInstance m = Bpmn.readModelFromStream(new ByteArrayInputStream(c.bpmn()));
        Bpmn.validateModel(m);
        assertTrue(m.getModelElementById("a") instanceof UserTask, "blocking plain task -> user task");
        // two parallel items end through one join
        assertTrue(m.getModelElementById("plan_done") instanceof ParallelGateway);
    }

    static Set<String> codes(List<Diagnostic> d, Severity s) {
        return d.stream().filter(x -> x.severity() == s).map(Diagnostic::code).collect(Collectors.toSet());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "CMMN-UNSUPPORTED-CASETASK|<planItem id='a' definitionRef='d'/><caseTask id='d' caseRef='other'/>",
            "CMMN-UNSUPPORTED-TIMEREVENTLISTENER|<planItem id='a' definitionRef='d'/><timerEventListener id='d'/>",
            "CMMN-UNSUPPORTED-USEREVENTLISTENER|<planItem id='a' definitionRef='d'/><userEventListener id='d'/>",
            "CMMN-UNSUPPORTED-REPETITION|<planItem id='a' definitionRef='d'><itemControl><repetitionRule/></itemControl></planItem><humanTask id='d'/>",
            "CMMN-UNSUPPORTED-MANUAL-ACTIVATION|<planItem id='a' definitionRef='d'/><humanTask id='d'><defaultControl><manualActivationRule/></defaultControl></humanTask>",
            "CMMN-UNSUPPORTED-EXIT|<planItem id='a' definitionRef='d'><exitCriterion id='x' sentryRef='s'/></planItem><humanTask id='d'/><sentry id='s'/>",
            "CMMN-UNSUPPORTED-DISCRETIONARY|<planItem id='a' definitionRef='d'/><humanTask id='d'><planningTable id='pt'/></humanTask>",
            "CMMN-UNSUPPORTED-NON-BLOCKING|<planItem id='a' definitionRef='d'/><humanTask id='d' isBlocking='false'/>",
            "CMMN-UNSUPPORTED-EXTENSION|<planItem id='a' definitionRef='d'/><humanTask id='d'><extensionElements><camunda:caseExecutionListener event='create' class='x'/></extensionElements></humanTask>",
            "CMMN-UNSUPPORTED-IF-PART|<planItem id='a' definitionRef='d'/><planItem id='b' definitionRef='d2'><entryCriterion id='e' sentryRef='s'/></planItem><sentry id='s'><planItemOnPart id='o' sourceRef='a'><standardEvent>complete</standardEvent></planItemOnPart><ifPart id='i'><condition>${x}</condition></ifPart></sentry><humanTask id='d'/><humanTask id='d2'/>",
            "CMMN-UNSUPPORTED-OR-ENTRY|<planItem id='a' definitionRef='d'/><planItem id='c' definitionRef='d'/><planItem id='b' definitionRef='d2'><entryCriterion id='e1' sentryRef='s1'/><entryCriterion id='e2' sentryRef='s2'/></planItem><sentry id='s1'><planItemOnPart id='o1' sourceRef='a'><standardEvent>complete</standardEvent></planItemOnPart></sentry><sentry id='s2'><planItemOnPart id='o2' sourceRef='c'><standardEvent>complete</standardEvent></planItemOnPart></sentry><humanTask id='d'/><humanTask id='d2'/>",
            "CMMN-UNSUPPORTED-EVENT|<planItem id='a' definitionRef='d'/><planItem id='b' definitionRef='d2'><entryCriterion id='e' sentryRef='s'/></planItem><sentry id='s'><planItemOnPart id='o' sourceRef='a'><standardEvent>terminate</standardEvent></planItemOnPart></sentry><humanTask id='d'/><humanTask id='d2'/>",
            "CMMN-UNSUPPORTED-CROSS-STAGE|<planItem id='b' definitionRef='d2'><entryCriterion id='e' sentryRef='s'/></planItem><sentry id='s'><planItemOnPart id='o' sourceRef='elsewhere'><standardEvent>complete</standardEvent></planItemOnPart></sentry><humanTask id='d2'/>",
            "CMMN-SENTRY-CYCLE|<planItem id='a' definitionRef='d'><entryCriterion id='e1' sentryRef='s1'/></planItem><planItem id='b' definitionRef='d'><entryCriterion id='e2' sentryRef='s2'/></planItem><sentry id='s1'><planItemOnPart id='o1' sourceRef='b'><standardEvent>complete</standardEvent></planItemOnPart></sentry><sentry id='s2'><planItemOnPart id='o2' sourceRef='a'><standardEvent>complete</standardEvent></planItemOnPart></sentry><humanTask id='d'/>",
            "CMMN-DANGLING-REF|<planItem id='a' definitionRef='missing'/>",
            "CMMN-UNSUPPORTED-CASE-FILE|<planItem id='a' definitionRef='d'/><humanTask id='d'><inputs id='in' bindingRef='cfi'/></humanTask>",
    })
    void unsupportedConstructsGiveAnErrorAndNoBpmn(String code, String body) {
        CmmnConversion c = CmmnToBpmnConverter.convert("x.cmmn", cmmn(body.replace('\'', '"')));
        assertEquals(Status.MANUAL, c.status(), c.diagnostics().toString());
        assertNull(c.bpmn());
        assertTrue(c.mapping().isEmpty());
        assertTrue(codes(c.diagnostics(), Severity.ERROR).contains(code), c.diagnostics().toString());
        assertEquals("MANUAL", c.toJson().get("status"));
    }

    @Test
    void otherVersionsAndUnreadableInputAreManual() {
        CmmnConversion old = CmmnToBpmnConverter.convert("old.cmmn", ("<definitions xmlns=\"http://www.omg.org/spec/"
                + "CMMN/20131201/MODEL\" id=\"d\"/>").getBytes(StandardCharsets.UTF_8));
        assertEquals("CMMN-VERSION", old.errors().get(0).code());
        CmmnConversion broken = CmmnToBpmnConverter.convert("x.cmmn", "<definitions".getBytes(StandardCharsets.UTF_8));
        assertEquals("CMMN-UNREADABLE", broken.errors().get(0).code());
        CmmnConversion doctype = CmmnToBpmnConverter.convert("x.cmmn", ("<!DOCTYPE d [<!ENTITY e SYSTEM \"file:///etc/x\">]>"
                + "<d>&e;</d>").getBytes(StandardCharsets.UTF_8));
        assertEquals("CMMN-UNREADABLE", doctype.errors().get(0).code());
        CmmnConversion empty = CmmnToBpmnConverter.convert("x.cmmn", ("<definitions xmlns=\"" + CmmnToBpmnConverter.CMMN11_NS
                + "\" id=\"d\"/>").getBytes(StandardCharsets.UTF_8));
        assertEquals("CMMN-NO-CASE", empty.errors().get(0).code());
    }

    @Test
    void generatedIdsNeverClashWithModelIds() {
        String body = "<planItem id=\"a\" definitionRef=\"d\"/><planItem id=\"b\" definitionRef=\"d2\"/>"
                + "<humanTask id=\"d\"/><humanTask id=\"plan_start\"/><humanTask id=\"d2\"/>";
        // "plan_start" is taken by a definition id, so the start event gets another id
        CmmnConversion c = CmmnToBpmnConverter.convert("x.cmmn", cmmn(body.replace("definitionRef=\"d2\"",
                "definitionRef=\"plan_start\"")));
        assertEquals(Status.REVIEW_REQUIRED, c.status(), c.diagnostics().toString());
        BpmnModelInstance m = Bpmn.readModelFromStream(new ByteArrayInputStream(c.bpmn()));
        Bpmn.validateModel(m);
        assertNotNull(m.getModelElementById("plan_start_2"));
    }
}
