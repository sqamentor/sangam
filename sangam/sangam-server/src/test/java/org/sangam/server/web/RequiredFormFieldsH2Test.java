// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * SL-77 (found through AT-3): completing a task through {@code POST /api/v1/tasks/{id}/complete} enforces the required
 * fields of the task's Sangam form ({@code operaton:formKey="sangam:<key>"}, the {@code *.form.json} deployed with the
 * process, as a pack deploys it). Before, any task completed with its required fields empty. h2 profile, real HTTP.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
@DirtiesContext
class RequiredFormFieldsH2Test {

    static final String DEV = "Bearer sangam-dev-token-change-me";
    static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    ProcessEngine engine;

    @LocalServerPort
    int port;

    static String bpmn(String key, String formKey) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" xmlns:operaton=\"http://operaton.org/schema/1.0/bpmn\""
                + " targetNamespace=\"urn:sangam:test\"><process id=\"" + key + "\" isExecutable=\"true\" operaton:historyTimeToLive=\"30\">"
                + "<startEvent id=\"s\"/><sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"t\"/>"
                + "<userTask id=\"t\" name=\"Triage\" operaton:assignee=\"dev-admin\" operaton:formKey=\"" + formKey + "\"/>"
                + "<sequenceFlow id=\"f2\" sourceRef=\"t\" targetRef=\"e\"/><endEvent id=\"e\"/></process></definitions>";
    }

    static String form(String key) {
        return "{\"key\":\"" + key + "\",\"fields\":["
                + "{\"id\":\"triageLevel\",\"type\":\"select\",\"required\":true,\"label\":{\"en\":\"Triage level\"}},"
                + "{\"id\":\"clinicalNotes\",\"type\":\"text\",\"required\":false},"
                + "{\"id\":\"wardType\",\"type\":\"select\",\"required\":true}],"
                + "\"decision\":{\"variable\":\"triageDecision\",\"required\":true,\"options\":[{\"value\":\"admit\"}]}}";
    }

    /** Deploys the process with its form (tenant dev) and starts it; returns the open task id. */
    String startTask(String formKey, String formResourceKey) {
        String key = "sl77" + Long.toString(System.nanoTime(), 36);
        engine.getRepositoryService().createDeployment().tenantId("dev").name(key)
                .addString(key + ".bpmn", bpmn(key, formKey))
                .addString("packs/sl77/forms/triage.form.json", form(formResourceKey)).deploy();
        String pi = engine.getRuntimeService().createProcessInstanceByKey(key).processDefinitionTenantId("dev")
                .setVariable("wardType", "GENERAL").execute().getId();
        return engine.getTaskService().createTaskQuery().processInstanceId(pi).singleResult().getId();
    }

    HttpResponse<String> complete(String taskId, String variablesJson) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/tasks/" + taskId + "/complete"))
                .timeout(Duration.ofSeconds(60)).header("Authorization", DEV).header("Accept", "application/json")
                .header("Content-Type", "application/json").header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString("{\"variables\":" + variablesJson + "}")).build();
        return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void missingRequiredFieldsAre422NamingEveryFieldAndTheTaskStaysOpen() throws Exception {
        String task = startTask("sangam:sl77/triage", "sl77/triage");
        HttpResponse<String> r = complete(task, "{\"clinicalNotes\":\"stable\",\"triageLevel\":\"  \"}");
        assertEquals(422, r.statusCode(), r.body());
        assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("application/problem+json"), r.body());
        JsonNode p = JSON.readTree(r.body());
        assertEquals("REQUIRED_FIELDS_MISSING", p.path("code").asText(), r.body());
        // wardType is already a process variable, so only the two empty ones are named
        assertEquals("[\"triageLevel\",\"triageDecision\"]", p.path("fields").toString(), r.body());
        assertTrue(p.path("detail").asText().contains("triageLevel, triageDecision"), r.body());
        assertEquals(task, engine.getTaskService().createTaskQuery().taskId(task).singleResult().getId(),
                "the refused completion left the task open");
    }

    @Test
    void completesOnceEveryRequiredFieldHasAValue() throws Exception {
        String task = startTask("sangam:sl77/triage:1", "sl77/triage");
        HttpResponse<String> r = complete(task, "{\"triageLevel\":\"YELLOW\",\"triageDecision\":\"admit\"}");
        assertEquals(200, r.statusCode(), r.body());
        assertNull(engine.getTaskService().createTaskQuery().taskId(task).singleResult());
    }

    @Test
    void aTaskWithoutASangamFormIsUnchanged() throws Exception {
        String task = startTask("embedded:deployment:forms/x.html", "sl77/triage");
        HttpResponse<String> r = complete(task, "{}");
        assertEquals(200, r.statusCode(), r.body());
    }
}
