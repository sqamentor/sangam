// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.enginerest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * SL-43 / SL-45 on {@code /engine-rest}: Sangam refusals answer exactly like {@code /api} instead of Operaton's 500.
 * <ul>
 *   <li>a start over the tenant's {@code startsPerSecond} quota (thrown as is by {@code TenantQuotaEnginePlugin}):
 *       429 {@code TENANT_QUOTA_EXCEEDED} with {@code Retry-After};</li>
 *   <li>a variable over {@code maxVariableBytes} (checked before the flush): 413, same code, no Retry-After, and the
 *       value is not stored;</li>
 *   <li>a task completion that reaches a call activity naming another tenant (Operaton wraps the engine exception in
 *       its {@code RestException}): 403 {@code TENANT_ISOLATION_VIOLATION}.</li>
 * </ul>
 * Before the fix all three answered 500 with Operaton's own error body. h2 profile, dev token (tenant {@code dev}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "sangam.security.dev-token.groups=sangam-engine-admin",
        "sangam.quotas.defaults.startsPerSecond=0",
        "sangam.quotas.defaults.maxVariableBytes=1024"})
@ActiveProfiles("h2")
@DirtiesContext
class EngineRestTenantRefusalTest {

    static final String TOKEN = "sangam-dev-token-change-me";
    static final String TENANT = "dev";
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final ObjectMapper JSON = new ObjectMapper();

    static final String BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="t">
              <process id="sl43refusal" isExecutable="true" operaton:historyTimeToLive="30">
                <startEvent id="s"/>
                <sequenceFlow id="f1" sourceRef="s" targetRef="wait"/>
                <userTask id="wait"/>
                <sequenceFlow id="f2" sourceRef="wait" targetRef="call"/>
                <callActivity id="call" calledElement="sl43refusal" operaton:calledElementTenantId="other-tenant"/>
                <sequenceFlow id="f3" sourceRef="call" targetRef="e"/>
                <endEvent id="e"/>
              </process>
            </definitions>
            """;

    @LocalServerPort
    int port;

    @Autowired
    ProcessEngine engine;

    HttpResponse<String> call(String method, String path, String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + TOKEN).header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** Deployed and started through the Java API without a bound tenant identity, so no quota is counted here. */
    ProcessInstance waitingInstance() {
        if (engine.getRepositoryService().createProcessDefinitionQuery().processDefinitionKey("sl43refusal").count() == 0) {
            engine.getRepositoryService().createDeployment().tenantId(TENANT).name("sl43refusal")
                    .addString("sl43refusal.bpmn", BPMN).deploy();
        }
        return engine.getRuntimeService().createProcessInstanceByKey("sl43refusal").processDefinitionTenantId(TENANT)
                .execute();
    }

    static JsonNode problem(HttpResponse<String> r, int status) throws Exception {
        assertEquals(status, r.statusCode(), r.body());
        assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("application/problem+json"),
                "problem media type: " + r.headers().map());
        return JSON.readTree(r.body());
    }

    @Test
    void startOverTheRateQuotaIs429WithRetryAfter() throws Exception {
        waitingInstance();
        HttpResponse<String> r = call("POST", "/engine-rest/process-definition/key/sl43refusal/tenant-id/" + TENANT
                + "/start", "{}");
        JsonNode p = problem(r, 429);
        assertEquals("TENANT_QUOTA_EXCEEDED", p.path("code").asText(), r.body());
        assertEquals("startsPerSecond", p.path("limit").asText(), r.body());
        assertEquals(TENANT, p.path("tenant").asText(), r.body());
        int retryAfter = Integer.parseInt(r.headers().firstValue("Retry-After").orElseThrow());
        assertTrue(retryAfter >= 1, "Retry-After " + retryAfter);
    }

    @Test
    void variableOverTheSizeQuotaIs413WithoutRetryAfterAndIsNotStored() throws Exception {
        ProcessInstance pi = waitingInstance();
        String big = "x".repeat(2_000);
        HttpResponse<String> r = call("PUT", "/engine-rest/process-instance/" + pi.getId() + "/variables/big",
                JSON.writeValueAsString(Map.of("value", big, "type", "String")));
        JsonNode p = problem(r, 413);
        assertEquals("TENANT_QUOTA_EXCEEDED", p.path("code").asText(), r.body());
        assertEquals("maxVariableBytes", p.path("limit").asText(), r.body());
        assertFalse(r.headers().firstValue("Retry-After").isPresent(), "a size limit has no Retry-After");
        assertNull(engine.getRuntimeService().getVariable(pi.getId(), "big"), "the oversized value was rolled back");
    }

    @Test
    void crossTenantCallActivityIs403WithAStableCode() throws Exception {
        ProcessInstance pi = waitingInstance();
        String task = engine.getTaskService().createTaskQuery().processInstanceId(pi.getId()).singleResult().getId();
        HttpResponse<String> r = call("POST", "/engine-rest/task/" + task + "/complete", "{}");
        JsonNode p = problem(r, 403);
        assertEquals("TENANT_ISOLATION_VIOLATION", p.path("code").asText(), r.body());
        assertEquals(TENANT, p.path("tenant").asText(), r.body());
        assertEquals("other-tenant", p.path("targetTenant").asText(), r.body());
        assertEquals(1, engine.getTaskService().createTaskQuery().taskId(task).count(), "the completion rolled back");
    }

    @Test
    void otherEngineErrorsKeepOperatonsMapping() throws Exception {
        HttpResponse<String> r = call("GET", "/engine-rest/process-instance/does-not-exist", "");
        assertEquals(404, r.statusCode(), r.body());
        assertTrue(r.body().contains("\"type\""), "Operaton's own error body: " + r.body());
    }
}
