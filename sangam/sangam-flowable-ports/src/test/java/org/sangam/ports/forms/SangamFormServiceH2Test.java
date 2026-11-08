// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.forms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.task.Task;
import org.sangam.core.layer.Dialect;
import org.sangam.ports.PortsH2Engine;
import org.sangam.ports.support.EngineTx;

/** FC-39 form engine on a real engine: versioned repository, tenant isolation, start and task forms, outcomes, drafts. */
class SangamFormServiceH2Test {

    private static final String FORM_V1 = """
            {"key":"review","fields":[
              {"id":"amount","type":"integer","required":true,"min":1},
              {"id":"remark","type":"text","scope":"local","variable":"taskRemark"},
              {"id":"ref","type":"text","readOnly":true}
            ],"outcomes":[{"id":"approve","name":"Approve"},{"id":"reject","name":"Reject"}]}""";

    private static final String FORM_V2 = FORM_V1.replace("\"min\":1", "\"min\":10");

    private static final String START = """
            {"key":"start","fields":[{"id":"applicant","type":"text","required":true,"variable":"applicantName"}]}""";

    private static String bpmn(String formKey) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:operaton="http://operaton.org/schema/1.0/bpmn"
                             targetNamespace="sangam-test">
                  <process id="formProc" isExecutable="true" operaton:historyTimeToLive="1">
                    <startEvent id="s" operaton:formKey="sangam:start"/>
                    <sequenceFlow id="f1" sourceRef="s" targetRef="review"/>
                    <userTask id="review" operaton:formKey="%s"/>
                    <sequenceFlow id="f2" sourceRef="review" targetRef="wait"/>
                    <userTask id="wait"/>
                    <sequenceFlow id="f3" sourceRef="wait" targetRef="e"/>
                    <endEvent id="e"/>
                  </process>
                </definitions>""".formatted(formKey);
    }

    private PortsH2Engine h2;
    private SangamFormService forms;

    @BeforeEach
    void setUp() {
        h2 = PortsH2Engine.start(null);
        forms = new SangamFormService(h2.engine, Dialect.POSTGRESQL, EngineTx.transactions(h2.engine));
    }

    @AfterEach
    void tearDown() {
        h2.close();
    }

    private String deployProcess(String tenant, String formKey) {
        var b = h2.engine.getRepositoryService().createDeployment().addString("p.bpmn", bpmn(formKey));
        if (tenant != null) {
            b.tenantId(tenant);
        }
        b.deploy();
        var q = h2.engine.getRepositoryService().createProcessDefinitionQuery().processDefinitionKey("formProc").latestVersion();
        return (tenant == null ? q.withoutTenantId() : q.tenantIdIn(tenant)).singleResult().getId();
    }

    private Task reviewTask(String pi) {
        return h2.engine.getTaskService().createTaskQuery().processInstanceId(pi).taskDefinitionKey("review").singleResult();
    }

    @Test
    void deployIsIdempotentPerContentAndVersionedPerTenant() {
        assertEquals(1, forms.deploy("t1", "review", FORM_V1, "app-1").version());
        assertEquals(1, forms.deploy("t1", "review", FORM_V1, "app-2").version(), "same content, same version");
        assertEquals(2, forms.deploy("t1", "review", FORM_V2, null).version());
        assertEquals(2, forms.form("t1", "review", null).orElseThrow().version());
        assertEquals(1.0, forms.form("t1", "review", 1).orElseThrow().definition().field("amount").min());
        assertTrue(forms.form("t2", "review", null).isEmpty(), "tenant isolation");
        assertTrue(forms.form("t1", "review", 9).isEmpty());
        assertEquals(1, forms.deploy("t2", "review", FORM_V2, null).version(), "versions are per tenant");
        assertEquals(1, forms.deploy(null, "review", FORM_V1, null).version(), "shared (no tenant) forms are their own space");
        assertNotEquals(forms.form("t1", "review", 1).orElseThrow().sha256(), forms.form("t1", "review", 2).orElseThrow().sha256());
    }

    @Test
    void startFormValidatesThenStartsWithMappedVariables() {
        forms.deploy("t1", "start", START, null);
        forms.deploy("t1", "review", FORM_V1, null);
        String pd = deployProcess("t1", "sangam:review");
        assertEquals("start", forms.getStartForm(pd).key());
        var bad = forms.submitStartForm(pd, "bk-1", Map.of());
        assertFalse(bad.getKey().valid());
        assertEquals("", bad.getValue());
        assertEquals(0, h2.engine.getRuntimeService().createProcessInstanceQuery().count(), "invalid start form starts nothing");
        var ok = forms.submitStartForm(pd, "bk-1", Map.of("applicant", "Ann", "junk", 1));
        assertTrue(ok.getKey().valid());
        assertEquals("Ann", h2.engine.getRuntimeService().getVariable(ok.getValue(), "applicantName"));
        assertNull(h2.engine.getRuntimeService().getVariable(ok.getValue(), "junk"), "unknown fields are not assigned");
    }

    @Test
    void taskFormRequiresOutcomeAndWritesLocalAndProcessVariables() {
        forms.deploy("t1", "start", START, null);
        forms.deploy("t1", "review", FORM_V1, null);
        String pd = deployProcess("t1", "sangam:review");
        String pi = forms.submitStartForm(pd, null, Map.of("applicant", "Ann")).getValue();
        Task task = reviewTask(pi);
        h2.engine.getRuntimeService().setVariable(pi, "amount", 5L);

        var rendered = forms.getTaskForm(task.getId());
        assertEquals(1, rendered.form().version());
        assertEquals(5L, rendered.values().get("amount"));

        var noOutcome = forms.submitTaskForm(task.getId(), Map.of("amount", "7"), null);
        assertFalse(noOutcome.valid());
        assertTrue(noOutcome.errors().containsKey("_outcome"));
        assertFalse(forms.submitTaskForm(task.getId(), Map.of("amount", "7"), "maybe").valid());
        assertFalse(forms.submitTaskForm(task.getId(), Map.of("amount", "0"), "approve").valid());
        assertFalse(forms.submitTaskForm(task.getId(), Map.of("amount", "7", "ref", "x"), "approve").valid(), "read-only field");
        assertEquals(1, h2.engine.getTaskService().createTaskQuery().taskId(task.getId()).count(), "still open after invalid submits");

        forms.saveTaskDraft(task.getId(), Map.of("amount", "3", "ref", "ignored", "other", 1));
        String draft = (String) h2.engine.getTaskService().getVariableLocal(task.getId(), "_sangamFormDraft");
        assertTrue(draft.contains("\"amount\":\"3\"") && !draft.contains("ref") && !draft.contains("other"), draft);

        var ok = forms.submitTaskForm(task.getId(), Map.of("amount", "7", "remark", "fine"), "approve");
        assertTrue(ok.valid(), ok.errors().toString());
        assertEquals(0, h2.engine.getTaskService().createTaskQuery().taskId(task.getId()).count(), "completed");
        assertEquals(7L, h2.engine.getRuntimeService().getVariable(pi, "amount"));
        assertEquals("approve", h2.engine.getRuntimeService().getVariable(pi, "review" + SangamFormService.OUTCOME_VARIABLE_SUFFIX));
        assertNull(h2.engine.getRuntimeService().getVariable(pi, "taskRemark"), "local field did not leak to the process scope");
        var hist = h2.engine.getHistoryService().createHistoricVariableInstanceQuery().variableName("taskRemark").singleResult();
        assertEquals("fine", hist.getValue());
        assertEquals(task.getId(), hist.getTaskId());
    }

    @Test
    void pinnedVersionAndTenantScopedResolution() {
        forms.deploy("t1", "start", START, null);
        forms.deploy("t1", "review", FORM_V1, null);
        forms.deploy("t1", "review", FORM_V2, null);
        forms.deploy("t2", "start", START, null);
        String pd = deployProcess("t1", "sangam:review:1");
        String pi = forms.submitStartForm(pd, null, Map.of("applicant", "Ann")).getValue();
        assertEquals(1, forms.getTaskForm(reviewTask(pi).getId()).form().version(), "pinned v1 although v2 is latest");

        String pd2 = deployProcess("t2", "sangam:review");
        String pi2 = forms.submitStartForm(pd2, null, Map.of("applicant", "Bob")).getValue();
        String t2task = reviewTask(pi2).getId();
        var ex = assertThrows(ProcessEngineException.class, () -> forms.getTaskForm(t2task));
        assertTrue(ex.getMessage().contains("not deployed"), "t1's form is not visible to t2's task");
    }

    @Test
    void nonSangamFormKeysAndUnknownTasksAreRejected() {
        forms.deploy(null, "start", START, null);
        String pd = deployProcess(null, "embedded:app:forms/x.html");
        String pi = forms.submitStartForm(pd, null, Map.of("applicant", "Ann")).getValue();
        String id = reviewTask(pi).getId();
        assertThrows(ProcessEngineException.class, () -> forms.getTaskForm(id));
        assertThrows(ProcessEngineException.class, () -> forms.submitTaskForm(id, Map.of(), null));
        assertThrows(ProcessEngineException.class, () -> forms.getTaskForm("no-such-task"));
    }
}
