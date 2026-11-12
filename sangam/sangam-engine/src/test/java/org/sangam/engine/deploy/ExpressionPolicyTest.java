// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.deploy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.operaton.bpm.impl.juel.AstDot;
import org.operaton.bpm.impl.juel.AstString;
import org.operaton.bpm.impl.juel.Builder;

/**
 * SL-160 / TM-10 part B (owner decisions 2026-11-12) on Operaton's own JUEL parse tree: what the shipped models use is
 * allowed; every escape class is refused with its code (context members, unapproved beans by dot, bracket or method,
 * methods outside the reviewed list, reflective members, EL functions, hidden receivers behind parentheses or a choice,
 * computed keys and method names, syntax errors); non-juel and external scripts are refused, juel scripts are checked.
 */
class ExpressionPolicyTest {

    private static final Set<String> BEANS = ModelCodeAllowList.SANGAM_BEANS;

    private static List<String> codes(String expr) {
        return ExpressionPolicy.check(expr, BEANS).stream().map(ExpressionPolicy.Refusal::code).toList();
    }

    private static void allowed(String expr) {
        assertEquals(List.of(), ExpressionPolicy.check(expr, BEANS), expr);
    }

    private static void refused(String code, String expr) {
        List<String> c = codes(expr);
        assertTrue(c.contains(code), expr + " -> " + ExpressionPolicy.check(expr, BEANS));
    }

    @Test
    void whatTheShippedModelsUseIsAllowed() {
        allowed("${execution.processBusinessKey}");
        allowed("#{execution.processBusinessKey}");
        allowed("${execution.setVariable('total', quantity * unitPrice)}");
        allowed("${execution.setVariable('ref', 'GST-'.concat(gstin))}");
        allowed("${execution.setVariable('k', employeeId.concat('-').concat(leaveType))}");
        allowed("${execution.setVariable('bk', execution.processBusinessKey)}");
        allowed("${committeeSeats.split(',')[loopCounter]}");
        allowed("${slaAction == 'ESCALATE' && amount > 100000}");
        allowed("${empty initiatorSeat ? 'pool' : initiatorSeat}");
        allowed("${moveSteps.prepare(execution)}");
        allowed("Pay ${amount} to ${payee}");
        allowed("${execution.getVariable('x')}");
        allowed("${decisionResult.getSingleResult().get('route')}");
        allowed("${amount.substring(0, 2)}");
        allowed("${members.split(',').length}");
        allowed("${items.size() > 0 && items.get(0) == 'a'}");
    }

    @Test
    void contextMembersReachingTheEngineOrTheModelAreRefused() {
        for (String e : List.of("${execution.processEngineServices.runtimeService}", "${execution.getProcessEngine()}",
                "${execution.processEngine}", "${execution.bpmnModelInstance}", "${execution.getBpmnModelElementInstance()}",
                "${execution.parent.setVariable('x', 1)}", "${execution.superExecution}", "${execution.processInstance}",
                "${task.execution}", "${caseExecution.processEngineServices}", "${externalTask.getProcessEngine()}",
                "${execution['processEngineServices']}", "${(execution).processEngine}",
                "${(c ? execution : x).processEngineServices}")) {
            refused(ExpressionPolicy.MEMBER, e);
        }
    }

    @Test
    void unapprovedBeansAreRefusedInEveryForm() {
        refused(ExpressionPolicy.BEAN, "${unapprovedBean.x}");
        refused(ExpressionPolicy.BEAN, "${unapprovedBean['x']}");
        refused(ExpressionPolicy.BEAN, "${processEngine.runtimeService}");
        refused(ExpressionPolicy.METHOD, "${unapprovedBean.run()}");
        refused(ExpressionPolicy.BEAN, "${decisionResult.singleResult}"); // a name's property: use the method form
        refused(ExpressionPolicy.BEAN, "${moveSteps.deleteEverything()}");
        assertEquals(List.of(), ExpressionPolicy.check("${deptBean.run()}", Set.of("deptBean")), "tenant-approved bean");
        assertEquals(List.of(), ExpressionPolicy.check("${deptBean.run()}", Set.of("deptBean.run")), "approved member");
        assertFalse(ExpressionPolicy.check("${deptBean.other()}", Set.of("deptBean.run")).isEmpty(), "other member");
    }

    @Test
    void methodsOutsideTheReviewedListAndReflectionAreRefused() {
        refused(ExpressionPolicy.METHOD, "${name.replaceAll('a', 'b')}");
        refused(ExpressionPolicy.METHOD, "${list.clear()}");
        refused(ExpressionPolicy.METHOD, "${'x'.concat(a).intern()}");
        refused(ExpressionPolicy.MEMBER, "${x.getClass().forName('java.lang.Runtime')}");
        refused(ExpressionPolicy.MEMBER, "${''.class.forName('java.lang.Runtime')}");
        refused(ExpressionPolicy.MEMBER, "${x.split(',')[k].class}");
        refused(ExpressionPolicy.MEMBER, "${x.concat('')['class']}");
        refused(ExpressionPolicy.MEMBER, "${sangamOnboardingStep.getClass()}");
        refused(ExpressionPolicy.MEMBER, "${x.concat('').classLoader}");
        refused(ExpressionPolicy.MEMBER, "${execution.variables[k]}");
        refused(ExpressionPolicy.METHOD, "${x.concat('')['concat']('y')}");
    }

    @Test
    void functionsAndSyntaxErrorsAreRefused() {
        refused(ExpressionPolicy.FUNCTION, "${now()}");
        refused(ExpressionPolicy.FUNCTION, "${fn:exec('rm')}");
        refused(ExpressionPolicy.SYNTAX, "${a.}");
    }

    /** Pins the two JUEL details the policy reads (operaton-juel 2.2.0): AstDot.toString and AstString.eval. */
    @Test
    void juelTreeDetailsArePinned() {
        AstDot dot = (AstDot) ((org.operaton.bpm.impl.juel.AstEval) new Builder().build("${a.b}").getRoot()).getChild(0);
        assertEquals("b", ExpressionPolicy.dotName(dot));
        assertEquals(". b", dot.toString());
        AstString s = new AstString("v");
        assertEquals("v", s.eval(null, null));
    }

    private static byte[] model(String body) {
        return ("""
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:operaton="http://operaton.org/schema/1.0/bpmn"
                             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" targetNamespace="t">
                  <process id="p" isExecutable="true">
                """ + body + "</process></definitions>").getBytes(StandardCharsets.UTF_8);
    }

    private static List<String> modelCodes(String body) {
        return ModelCodeAllowList.check("m.bpmn", model(body), ModelCodeAllowList.SANGAM_CLASSES, BEANS).stream()
                .map(ModelCodeAllowList.Finding::code).toList();
    }

    @Test
    void scriptsMustBeInlineJuelAndPassTheExpressionRules() {
        assertEquals(List.of(ModelCodeAllowList.SCRIPT), modelCodes("<task id=\"t\"><extensionElements>"
                + "<operaton:executionListener event=\"start\"><operaton:script scriptFormat=\"groovy\">x()</operaton:script>"
                + "</operaton:executionListener></extensionElements></task>"));
        assertEquals(List.of(ModelCodeAllowList.SCRIPT), modelCodes("<task id=\"t\"><extensionElements>"
                + "<operaton:inputOutput><operaton:inputParameter name=\"a\"><operaton:script scriptFormat=\"javascript\">1"
                + "</operaton:script></operaton:inputParameter></operaton:inputOutput></extensionElements></task>"));
        assertEquals(List.of(ModelCodeAllowList.SCRIPT), modelCodes("<startEvent id=\"s\"/><endEvent id=\"e\"/>"
                + "<sequenceFlow id=\"f\" sourceRef=\"s\" targetRef=\"e\"><conditionExpression xsi:type=\"tFormalExpression\" "
                + "language=\"groovy\">true</conditionExpression></sequenceFlow>"));
        assertEquals(List.of(ModelCodeAllowList.SCRIPT), modelCodes("<task id=\"t\"><extensionElements>"
                + "<operaton:executionListener event=\"start\"><operaton:script scriptFormat=\"juel\" resource=\"x.juel\"/>"
                + "</operaton:executionListener></extensionElements></task>"));
        assertEquals(List.of(), modelCodes("<startEvent id=\"s\"/><endEvent id=\"e\"/>"
                + "<sequenceFlow id=\"f\" sourceRef=\"s\" targetRef=\"e\"><conditionExpression xsi:type=\"tFormalExpression\" "
                + "language=\"juel\">${outcome == 'APPROVE'}</conditionExpression></sequenceFlow>"), "juel conditions (E46)");
        assertEquals(List.of(ExpressionPolicy.MEMBER), modelCodes("<task id=\"t\"><extensionElements>"
                + "<operaton:executionListener event=\"start\"><operaton:script scriptFormat=\"juel\">"
                + "${execution.processEngine}</operaton:script></operaton:executionListener></extensionElements></task>"),
                "a juel script passes the expression rules");
        assertEquals(List.of(ExpressionPolicy.MEMBER), modelCodes("<startEvent id=\"s\"/><endEvent id=\"e\"/>"
                + "<sequenceFlow id=\"f\" sourceRef=\"s\" targetRef=\"e\"><conditionExpression xsi:type=\"tFormalExpression\" "
                + "language=\"juel\">${execution.processEngine != null}</conditionExpression></sequenceFlow>"));
    }

    @Test
    void expressionsAreCheckedWhereverTheModelEvaluatesThem() {
        assertEquals(List.of(ExpressionPolicy.BEAN), modelCodes("<userTask id=\"u\" operaton:assignee=\"${seatBean.lookup}\"/>"));
        assertEquals(List.of(ExpressionPolicy.METHOD), modelCodes("<serviceTask id=\"s\" operaton:expression=\"${evil.run()}\"/>"));
        assertEquals(List.of(ExpressionPolicy.MEMBER), modelCodes("<task id=\"t\"><extensionElements><operaton:inputOutput>"
                + "<operaton:inputParameter name=\"a\">${execution.processEngineServices}</operaton:inputParameter>"
                + "</operaton:inputOutput></extensionElements></task>"));
        assertEquals(List.of(), modelCodes("<task id=\"t\"><documentation>Use ${anything.goes()} in prose</documentation></task>"),
                "documentation is prose");
    }
}
