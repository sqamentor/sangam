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
 *
 * <p>SL-45 (PostgreSQL {@code CrossTenantIsolationIT}, rerun 4): a deployment naming another tenant, or none, was
 * created (200) because Operaton has no tenant check for deployments; it is now 403 {@code TENANT_ISOLATION_VIOLATION}
 * and nothing is deployed. An async batch was created without a tenant, so its jobs ran without any tenant check on the
 * other tenant's instance; it now belongs to the caller's tenant (its jobs run, and are checked, as that tenant).
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

    HttpResponse<String> deploy(String tenantId) throws Exception {
        String boundary = "sl45" + java.util.UUID.randomUUID().toString().replace("-", "");
        StringBuilder b = new StringBuilder();
        b.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"deployment-name\"\r\n\r\n")
                .append("sl45-refusal\r\n");
        if (tenantId != null) {
            b.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"tenant-id\"\r\n\r\n")
                    .append(tenantId).append("\r\n");
        }
        b.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"data\"; filename=\"x.bpmn\"\r\n")
                .append("Content-Type: application/xml\r\n\r\n").append(BPMN.replace("sl43refusal", "sl45refusal").strip())
                .append("\r\n--").append(boundary).append("--\r\n");
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/engine-rest/deployment/create"))
                .header("Authorization", "Bearer " + TOKEN).header("Accept", "application/json")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(b.toString(), StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void deploymentIntoAnotherTenantOrNoneIs403AndDeploysNothing() throws Exception {
        for (String target : new String[] {"other-tenant", null}) {
            HttpResponse<String> r = deploy(target);
            JsonNode p = problem(r, 403);
            assertEquals("TENANT_ISOLATION_VIOLATION", p.path("code").asText(), r.body());
            assertEquals(TENANT, p.path("tenant").asText(), r.body());
            JsonNode targetTenant = p.path("targetTenant");
            assertEquals(target, targetTenant.isMissingNode() || targetTenant.isNull() ? null : targetTenant.asText(),
                    r.body());
            assertTrue(p.path("detail").asText().startsWith("SL-13 tenant isolation"), r.body());
        }
        assertEquals(0, engine.getRepositoryService().createProcessDefinitionQuery().processDefinitionKey("sl45refusal")
                .count(), "nothing was deployed, neither into the other tenant nor shared");
        HttpResponse<String> own = deploy(TENANT);
        assertEquals(200, own.statusCode(), own.body());
        assertEquals(TENANT, JSON.readTree(own.body()).path("tenantId").asText(), own.body());
    }

    /**
     * The batch (here over the caller's own instance, so its job simply runs) belongs to the caller's tenant; that its
     * jobs then refuse another tenant's instance is proven on the engine ({@code TenantScopedCreationH2Test}) and on
     * PostgreSQL ({@code CrossTenantIsolationIT}).
     */
    @Test
    void anAsyncBatchBelongsToTheCallersTenant() throws Exception {
        ProcessInstance pi = waitingInstance();
        HttpResponse<String> r = call("POST", "/engine-rest/process-instance/delete",
                JSON.writeValueAsString(Map.of("processInstanceIds", java.util.List.of(pi.getId()), "deleteReason", "SL-45")));
        assertEquals(200, r.statusCode(), r.body());
        JsonNode batch = JSON.readTree(r.body());
        assertEquals(TENANT, batch.path("tenantId").asText(), "the batch is the caller's: " + r.body());
        assertEquals(TENANT, engine.getManagementService().createJobDefinitionQuery()
                .jobDefinitionId(batch.path("batchJobDefinitionId").asText()).singleResult().getTenantId(),
                "its jobs inherit the tenant and run with its authentication");
    }

    @Test
    void otherEngineErrorsKeepOperatonsMapping() throws Exception {
        HttpResponse<String> r = call("GET", "/engine-rest/process-instance/does-not-exist", "");
        assertEquals(404, r.statusCode(), r.body());
        assertTrue(r.body().contains("\"type\""), "Operaton's own error body: " + r.body());
    }
}
