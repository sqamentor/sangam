// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.TaskPort;
import org.sangam.rest.web.TaskController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * SL-37: {@code GET /api/v1/tasks/{id}/form} serves the task's deployed embedded form to the people who may read the
 * task, so the task UI no longer needs {@code /engine-rest} (403 for task users). The check is the task-read rule:
 * tenant first, then assignee / candidate user / candidate group (seat) / supervisor. Not visible means 404 (the task
 * API's convention, which does not reveal that the task exists); no task permission at all means 403.
 * h2 profile, no Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
@DirtiesContext
class TaskFormEndpointTest {

    static final String TENANT = "dev";
    static final String FORM = "<form><label for=\"amt\">Amount</label>"
            + "<input id=\"amt\" cam-variable-name=\"amount\" cam-variable-type=\"Long\" required></form>";

    @Autowired
    ProcessEngine engine;

    @Autowired
    TaskController controller;

    @LocalServerPort
    int port;

    String assignedTask;
    String candidateTask;
    String otherTenantTask;

    static String bpmn(String key, String assignee, String candidateUsers, String candidateGroups) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" xmlns:operaton=\"http://operaton.org/schema/1.0/bpmn\""
                + " targetNamespace=\"urn:sangam:test\"><process id=\"" + key + "\" isExecutable=\"true\">"
                + "<startEvent id=\"s\"/><sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"t\"/>"
                + "<userTask id=\"t\" name=\"Approve\" operaton:formKey=\"embedded:deployment:forms/approve.html\""
                + (assignee == null ? "" : " operaton:assignee=\"" + assignee + "\"")
                + (candidateUsers == null ? "" : " operaton:candidateUsers=\"" + candidateUsers + "\"")
                + (candidateGroups == null ? "" : " operaton:candidateGroups=\"" + candidateGroups + "\"")
                + "/><sequenceFlow id=\"f2\" sourceRef=\"t\" targetRef=\"e\"/><endEvent id=\"e\"/></process></definitions>";
    }

    String startTask(String tenant, String key, String assignee, String users, String groups) {
        engine.getRepositoryService().createDeployment().tenantId(tenant).name(key)
                .addString(key + ".bpmn", bpmn(key, assignee, users, groups))
                .addString("forms/approve.html", FORM).deploy();
        String pi = engine.getRuntimeService().createProcessInstanceByKey(key).processDefinitionTenantId(tenant)
                .execute().getId();
        return engine.getTaskService().createTaskQuery().processInstanceId(pi).singleResult().getId();
    }

    @BeforeEach
    void tasks() {
        String n = Long.toString(System.nanoTime(), 36);
        assignedTask = startTask(TENANT, "formAssigned" + n, "alice", null, null);
        candidateTask = startTask(TENANT, "formCandidate" + n, null, "carol", "clerks");
        otherTenantTask = startTask("other-tenant", "formOther" + n, "alice", null, null);
    }

    static RequestContext who(String tenant, String person, String... groups) {
        return new RequestContext(tenant, person, Set.of(groups), null, null, null, null, null, "TEST");
    }

    static void assertForm(TaskPort.TaskForm f, String taskId) {
        assertEquals(taskId, f.taskId());
        assertEquals("EMBEDDED", f.kind());
        assertEquals("embedded:deployment:forms/approve.html", f.formKey());
        assertEquals("text/html", f.contentType());
        assertEquals(FORM, f.content());
    }

    @Test
    void assigneeGetsTheDeployedForm() {
        assertForm(controller.form(who(TENANT, "alice", "sangam-user"), assignedTask), assignedTask);
    }

    @Test
    void candidateUserAndCandidateGroupMemberGetTheForm() {
        assertForm(controller.form(who(TENANT, "carol", "sangam-user"), candidateTask), candidateTask);
        assertForm(controller.form(who(TENANT, "dave", "sangam-user", "clerks"), candidateTask), candidateTask);
    }

    @Test
    void supervisorOfTheTenantGetsTheForm() {
        assertForm(controller.form(who(TENANT, "sup", "sangam-operator"), assignedTask), assignedTask);
    }

    @Test
    void otherUserOfTheSameTenantIsRefused() {
        ApiProblem notInvolved = assertThrows(ApiProblem.class,
                () -> controller.form(who(TENANT, "eve", "sangam-user"), assignedTask));
        assertEquals(404, notInvolved.status(), "not involved: hidden like GET /tasks/{id}");
        ApiProblem notCandidate = assertThrows(ApiProblem.class,
                () -> controller.form(who(TENANT, "eve", "sangam-user", "other-group"), candidateTask));
        assertEquals(404, notCandidate.status());
        ApiProblem noTaskPermission = assertThrows(ApiProblem.class,
                () -> controller.form(who(TENANT, "alice"), assignedTask));
        assertEquals(403, noTaskPermission.status(), "no task.read: 403 before any object is touched");
    }

    @Test
    void otherTenantIsNotFoundEvenForTheAssigneeAndASupervisor() {
        ApiProblem a = assertThrows(ApiProblem.class,
                () -> controller.form(who(TENANT, "alice", "sangam-user"), otherTenantTask));
        assertEquals(404, a.status());
        ApiProblem s = assertThrows(ApiProblem.class,
                () -> controller.form(who(TENANT, "sup", "sangam-operator", "sangam-admin"), otherTenantTask));
        assertEquals(404, s.status());
    }

    @Test
    void sameRuleAsTaskRead() {
        // the form is readable exactly when the task is (no wider, no narrower)
        RequestContext eve = who(TENANT, "eve", "sangam-user");
        assertEquals(404, assertThrows(ApiProblem.class, () -> controller.get(eve, assignedTask)).status());
        RequestContext alice = who(TENANT, "alice", "sangam-user");
        assertEquals(assignedTask, controller.get(alice, assignedTask).task().id());
    }

    @Test
    void servedOverHttpUnderApiV1() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/tasks/" + assignedTask + "/form"))
                .header("Authorization", "Bearer sangam-dev-token-change-me").GET().build();
        HttpResponse<String> r = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.body().contains("\"kind\":\"EMBEDDED\"") && r.body().contains("cam-variable-name"), r.body());
        HttpRequest anon = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/tasks/" + assignedTask + "/form")).GET().build();
        assertEquals(401, HttpClient.newHttpClient().send(anon, HttpResponse.BodyHandlers.ofString()).statusCode());
    }
}
