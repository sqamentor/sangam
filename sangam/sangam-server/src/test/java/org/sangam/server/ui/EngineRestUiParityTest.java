// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * SL-37, OPERATON-UI-PARITY: the exact {@code /engine-rest} (and {@code /api/v1/ops}) calls the Sangam UI makes for the
 * parity rows, on a running server (h2 profile, dev token in the engine-admin group, tenant {@code dev}). Row ids
 * (P01…) match {@code sangam-ui/src/modules/operaton-parity.test.tsx} and the register's UI-parity table. Where a UI
 * fix depends on an engine contract (date format, resource type on authorization update, duplicate global
 * authorization) the contract itself is asserted, so the UI fix is grounded on observed server behaviour.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"sangam.engine.allow-multiple-engines-per-jvm=true",
        "sangam.security.dev-token.groups=sangam-engine-admin,sangam-admin,sangam-operator",
        // No job executor: batches, timers and jobs stay put while the UI calls act on them (no race with execution).
        "operaton.bpm.job-execution.enabled=false"})
@ActiveProfiles("h2")
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EngineRestUiParityTest {

    static final String TOKEN = "sangam-dev-token-change-me";
    static final String TENANT = "dev";
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final ObjectMapper JSON = new ObjectMapper();
    /** Operaton REST date format (JacksonConfigurator.DEFAULT_DATE_FORMAT). */
    static final DateTimeFormatter ENGINE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

    static final String BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:operaton="http://operaton.org/schema/1.0/bpmn"
                         id="uip-defs" targetNamespace="http://sangam.org/test/uiparity">
              <process id="uip" name="UI parity" isExecutable="true" operaton:historyTimeToLive="30">
                <startEvent id="start"/>
                <sequenceFlow id="f1" sourceRef="start" targetRef="fork"/>
                <parallelGateway id="fork"/>
                <sequenceFlow id="f2" sourceRef="fork" targetRef="review"/>
                <sequenceFlow id="f3" sourceRef="fork" targetRef="wait"/>
                <sequenceFlow id="f4" sourceRef="fork" targetRef="pay"/>
                <userTask id="review" name="Review" operaton:candidateGroups="clerks"/>
                <intermediateCatchEvent id="wait"><timerEventDefinition><timeDuration>P10D</timeDuration></timerEventDefinition></intermediateCatchEvent>
                <serviceTask id="pay" name="Pay" operaton:type="external" operaton:topic="uip-pay"/>
                <sequenceFlow id="f5" sourceRef="review" targetRef="join"/>
                <sequenceFlow id="f6" sourceRef="wait" targetRef="join"/>
                <sequenceFlow id="f7" sourceRef="pay" targetRef="join"/>
                <parallelGateway id="join"/>
                <sequenceFlow id="f8" sourceRef="join" targetRef="end"/>
                <endEvent id="end"/>
              </process>
            </definitions>
            """;

    static final String DMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" xmlns:operaton="http://operaton.org/schema/1.0/dmn"
                         id="uip-drd" name="UI parity DRD" namespace="http://sangam.org/test/uiparity">
              <decision id="uipElig" name="Eligibility" operaton:historyTimeToLive="30">
                <decisionTable id="t1">
                  <input id="i1"><inputExpression id="e1" typeRef="integer"><text>age</text></inputExpression></input>
                  <output id="o1" name="ok" typeRef="boolean"/>
                  <rule id="r1"><inputEntry id="ie1"><text>&gt;= 18</text></inputEntry><outputEntry id="oe1"><text>true</text></outputEntry></rule>
                </decisionTable>
              </decision>
              <decision id="uipAge" name="Age band" operaton:historyTimeToLive="30">
                <decisionTable id="t2">
                  <input id="i2"><inputExpression id="e2" typeRef="integer"><text>age</text></inputExpression></input>
                  <output id="o2" name="band" typeRef="string"/>
                  <rule id="r2"><inputEntry id="ie2"><text>&gt;= 0</text></inputEntry><outputEntry id="oe2"><text>"any"</text></outputEntry></rule>
                </decisionTable>
              </decision>
            </definitions>
            """;

    @LocalServerPort
    int port;

    String deploymentId;
    String definitionId;

    // ------------------------------------------------------------------ HTTP helpers

    HttpResponse<String> call(String method, String path, Object body) throws Exception {
        return call(method, path, body, "application/json");
    }

    HttpResponse<String> call(String method, String path, Object body, String accept) throws Exception {
        String json = body == null ? null : body instanceof String s ? s : JSON.writeValueAsString(body);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + TOKEN).header("Accept", accept)
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        if (json != null) {
            b.header("Content-Type", "application/json");
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<byte[]> multipart(String path, Map<String, Object> parts) throws Exception {
        String boundary = "sg" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Map.Entry<String, Object> p : parts.entrySet()) {
            out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            if (p.getValue() instanceof byte[] bytes) {
                out.write(("Content-Disposition: form-data; name=\"" + p.getKey() + "\"; filename=\"" + p.getKey() + ".bin\"\r\n"
                        + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(bytes);
            } else if (p.getValue() instanceof String[] file) {
                out.write(("Content-Disposition: form-data; name=\"" + p.getKey() + "\"; filename=\"" + file[0] + "\"\r\n"
                        + "Content-Type: application/xml\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(file[1].getBytes(StandardCharsets.UTF_8));
            } else {
                out.write(("Content-Disposition: form-data; name=\"" + p.getKey() + "\"\r\n\r\n" + p.getValue())
                        .getBytes(StandardCharsets.UTF_8));
            }
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest r = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + TOKEN).header("Accept", "application/json")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build();
        return HTTP.send(r, HttpResponse.BodyHandlers.ofByteArray());
    }

    JsonNode ok(String method, String path, Object body) throws Exception {
        HttpResponse<String> r = call(method, path, body);
        assertTrue(r.statusCode() / 100 == 2, method + " " + path + " -> " + r.statusCode() + ": " + r.body());
        return r.body() == null || r.body().isBlank() ? JSON.nullNode() : JSON.readTree(r.body());
    }

    static String q(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    static String engineDate(ZonedDateTime t) {
        return ENGINE.format(t.withZoneSameInstant(ZoneOffset.UTC));
    }

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    String start(String businessKey) throws Exception {
        return ok("POST", "/engine-rest/process-definition/" + definitionId + "/start",
                map("businessKey", businessKey, "variables", map("amount", map("value", 5, "type", "Long")))).get("id").asText();
    }

    String reviewExecution(String pi) throws Exception {
        return ok("GET", "/engine-rest/execution?processInstanceId=" + pi + "&activityId=review", null).get(0).get("id").asText();
    }

    @BeforeAll
    void deploy() throws Exception {
        HttpResponse<byte[]> d = multipart("/engine-rest/deployment/create", map("deployment-name", "uip", "tenant-id", TENANT,
                "deploy-changed-only", "false", "uip.bpmn", new String[] {"uip.bpmn", BPMN}, "uip.dmn", new String[] {"uip.dmn", DMN}));
        String body = new String(d.body(), StandardCharsets.UTF_8);
        assertEquals(200, d.statusCode(), body);
        JsonNode dep = JSON.readTree(body);
        deploymentId = dep.get("id").asText();
        definitionId = dep.get("deployedProcessDefinitions").fieldNames().next();
    }

    // ------------------------------------------------------------------ P00: engine date format (UI root fix)

    @Test
    void p00EngineReadsTheUiDateFormatInBodiesAndQueries() throws Exception {
        ZonedDateTime when = ZonedDateTime.now(ZoneOffset.UTC).plusYears(1);
        String body = "{\"suspended\":true,\"includeProcessInstances\":false,\"executionDate\":\"%s\"}";
        assertEquals(204, call("PUT", "/engine-rest/process-definition/" + definitionId + "/suspended",
                body.formatted(engineDate(when))).statusCode());
        assertEquals(200, call("GET", "/engine-rest/task?dueBefore=" + q(engineDate(when)), null).statusCode());
    }

    // ------------------------------------------------------------------ process definition

    @Test
    void p01TtlClearAndRemovalTimeBatch() throws Exception {
        ok("PUT", "/engine-rest/process-definition/" + definitionId + "/history-time-to-live", "{\"historyTimeToLive\":null}");
        assertTrue(ok("GET", "/engine-rest/process-definition/" + definitionId, null).get("historyTimeToLive").isNull());
        ok("PUT", "/engine-rest/process-definition/" + definitionId + "/history-time-to-live", map("historyTimeToLive", 30));
        JsonNode batch = ok("POST", "/engine-rest/history/process-instance/set-removal-time",
                map("historicProcessInstanceQuery", map("processDefinitionId", definitionId), "calculatedRemovalTime", true, "hierarchical", true));
        assertFalse(batch.get("id").asText().isBlank(), batch.toString());
    }

    @Test
    void p03JobDefinitionRetriesAndP02Priority() throws Exception {
        start("p03");
        JsonNode jd = ok("GET", "/engine-rest/job-definition?processDefinitionId=" + q(definitionId), null).get(0);
        ok("PUT", "/engine-rest/job-definition/" + jd.get("id").asText() + "/retries", map("retries", 3));
        ok("PUT", "/engine-rest/job-definition/" + jd.get("id").asText() + "/jobPriority", map("priority", 7, "includeJobs", false));
        assertEquals(7, ok("GET", "/engine-rest/job-definition/" + jd.get("id").asText(), null).get("overridingJobPriority").asInt());
    }

    // ------------------------------------------------------------------ process instance

    @Test
    void p04CancelWithSkipFlags() throws Exception {
        String pi = start("p04");
        assertEquals(204, call("DELETE", "/engine-rest/process-instance/" + pi + "?skipCustomListeners=true&skipIoMappings=true", null).statusCode());
        assertEquals(404, call("GET", "/engine-rest/process-instance/" + pi, null).statusCode());
    }

    @Test
    void p05ModificationAsBatch() throws Exception {
        String pi = start("p05");
        JsonNode b = ok("POST", "/engine-rest/modification/executeAsync", map("processDefinitionId", definitionId,
                "processInstanceIds", new String[] {pi}, "instructions", new Object[] {map("type", "startBeforeActivity", "activityId", "review")},
                "skipCustomListeners", false, "skipIoMappings", false));
        assertEquals("instance-modification", b.get("type").asText());
    }

    @Test
    void p06ToP12Variables() throws Exception {
        String pi = start("p06");
        ok("PUT", "/engine-rest/process-instance/" + pi + "/variables/note", map("type", "Short", "value", 4));
        JsonNode found = ok("GET", "/engine-rest/variable-instance?deserializeValues=false&processInstanceIdIn=" + pi + "&variableNameLike=" + q("no%"), null);
        assertEquals(1, found.size(), found.toString());
        assertEquals("Short", found.get(0).get("type").asText());
        ok("POST", "/engine-rest/execution/" + pi + "/localVariables", map("modifications", map("x", map("value", 1, "type", "Integer"))));
        ok("DELETE", "/engine-rest/process-instance/" + pi + "/variables/note", null);
        assertEquals(0, ok("GET", "/engine-rest/variable-instance?processInstanceIdIn=" + pi + "&variableName=note", null).size());
        byte[] data = {1, 2, 3, 4, 5};
        HttpResponse<byte[]> up = multipart("/engine-rest/process-instance/" + pi + "/variables/scan/data", map("data", data, "valueType", "Bytes"));
        assertEquals(204, up.statusCode(), new String(up.body(), StandardCharsets.UTF_8));
        String vid = ok("GET", "/engine-rest/variable-instance?processInstanceIdIn=" + pi + "&variableName=scan", null).get(0).get("id").asText();
        HttpResponse<byte[]> down = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/engine-rest/variable-instance/" + vid + "/data"))
                .header("Authorization", "Bearer " + TOKEN).header("Accept", "*/*").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, down.statusCode());
        assertArrayEquals(data, down.body());
    }

    @Test
    void p15IncidentsOfOneInstance() throws Exception {
        String pi = start("p15a");
        String other = start("p15b");
        ok("POST", "/engine-rest/execution/" + reviewExecution(pi) + "/create-incident", map("incidentType", "sangamUiParity", "message", "for " + pi));
        ok("POST", "/engine-rest/execution/" + reviewExecution(other) + "/create-incident", map("incidentType", "sangamUiParity", "message", "for " + other));
        JsonNode list = ok("GET", "/api/v1/ops/incidents?processInstanceId=" + pi, null);
        JsonNode items = list.isArray() ? list : list.get("items");
        assertEquals(1, items.size(), list.toString());
        assertTrue(items.get(0).toString().contains(pi), items.toString());
    }

    @Test
    void p16ToP20Jobs() throws Exception {
        String pi = start("p16");
        JsonNode jobs = ok("GET", "/engine-rest/job?processInstanceId=" + pi, null);
        assertEquals(1, jobs.size(), jobs.toString());
        String job = jobs.get(0).get("id").asText();
        ok("PUT", "/engine-rest/job/" + job + "/suspended", map("suspended", true));
        assertTrue(ok("GET", "/engine-rest/job/" + job, null).get("suspended").asBoolean());
        ok("PUT", "/engine-rest/job/" + job + "/suspended", map("suspended", false));
        String due = engineDate(ZonedDateTime.now(ZoneOffset.UTC).plusDays(40));
        ok("PUT", "/engine-rest/job/" + job + "/duedate", map("duedate", due, "cascade", true));
        ok("POST", "/engine-rest/job/" + job + "/duedate/recalculate?creationDateBased=true", null);
        ok("PUT", "/engine-rest/job/" + job + "/retries", map("retries", 2));
        assertEquals(2, ok("GET", "/engine-rest/job/" + job, null).get("retries").asInt());
    }

    @Test
    void p21UserTasksAndP43DatesAndP48Attachments() throws Exception {
        String pi = start("p21");
        JsonNode tasks = ok("GET", "/engine-rest/task?processInstanceId=" + pi, null);
        assertEquals(1, tasks.size());
        String tid = tasks.get(0).get("id").asText();
        JsonNode task = ok("GET", "/engine-rest/task/" + tid, null);
        String due = engineDate(ZonedDateTime.now(ZoneOffset.UTC).plusDays(3));
        ((com.fasterxml.jackson.databind.node.ObjectNode) task).put("due", due);
        ok("PUT", "/engine-rest/task/" + tid, task);
        assertFalse(ok("GET", "/engine-rest/task/" + tid, null).get("due").isNull());
        HttpResponse<byte[]> at = multipart("/engine-rest/task/" + tid + "/attachment/create", map("attachment-name", "Order", "url", "https://gov.example/order.pdf"));
        assertEquals(200, at.statusCode(), new String(at.body(), StandardCharsets.UTF_8));
        String aid = JSON.readTree(at.body()).get("id").asText();
        ok("DELETE", "/engine-rest/task/" + tid + "/attachment/" + aid, null);
        assertEquals(0, ok("GET", "/engine-rest/task/" + tid + "/attachment", null).size());
    }

    @Test
    void p22P23ExternalTaskErrorDetailsAndUnlock() throws Exception {
        String pi = start("p22");
        String xid = ok("GET", "/engine-rest/external-task?processInstanceId=" + pi, null).get(0).get("id").asText();
        Map<String, Object> fetch = map("workerId", "w1", "maxTasks", 10, "topics", new Object[] {map("topicName", "uip-pay", "lockDuration", 60000, "businessKey", "p22")});
        JsonNode locked = ok("POST", "/engine-rest/external-task/fetchAndLock", fetch);
        assertTrue(locked.toString().contains(xid), "w1 locks the task: " + locked + " task " + ok("GET", "/engine-rest/external-task/" + xid, null));
        ok("POST", "/engine-rest/external-task/" + xid + "/failure", map("workerId", "w1", "errorMessage", "short", "errorDetails", "full worker trace", "retries", 1, "retryTimeout", 0));
        HttpResponse<String> det = call("GET", "/engine-rest/external-task/" + xid + "/errorDetails", null, "*/*");
        assertEquals(200, det.statusCode(), det.body());
        assertEquals("full worker trace", det.body());
        ok("POST", "/engine-rest/external-task/fetchAndLock", fetch);
        ok("POST", "/engine-rest/external-task/" + xid + "/unlock", null);
        assertTrue(ok("GET", "/engine-rest/external-task/" + xid, null).get("workerId").isNull());
    }

    @Test
    void p24OperationLogAnnotation() throws Exception {
        String pi = start("p24");
        ok("PUT", "/engine-rest/process-instance/" + pi + "/suspended", map("suspended", true));
        JsonNode log = ok("GET", "/engine-rest/history/user-operation?processInstanceId=" + pi, null);
        assertTrue(log.size() > 0, "suspension is logged for the authenticated caller");
        String op = log.get(0).get("operationId").asText();
        ok("PUT", "/engine-rest/history/user-operation/" + op + "/set-annotation", map("annotation", "approved by board"));
        assertEquals("approved by board", ok("GET", "/engine-rest/history/user-operation?operationId=" + op, null).get(0).get("annotation").asText());
        ok("PUT", "/engine-rest/history/user-operation/" + op + "/clear-annotation", null);
        assertTrue(ok("GET", "/engine-rest/history/user-operation?operationId=" + op, null).get(0).get("annotation").isNull());
    }

    // ------------------------------------------------------------------ decisions, batches, deployments, dashboards

    @Test
    void p25DecisionTtlAndP26Drd() throws Exception {
        JsonNode dd = ok("GET", "/engine-rest/decision-definition?key=uipElig&latestVersion=true", null).get(0);
        ok("PUT", "/engine-rest/decision-definition/" + dd.get("id").asText() + "/history-time-to-live", map("historyTimeToLive", 90));
        assertEquals(90, ok("GET", "/engine-rest/decision-definition/" + dd.get("id").asText(), null).get("historyTimeToLive").asInt());
        ok("PUT", "/engine-rest/decision-definition/" + dd.get("id").asText() + "/history-time-to-live", "{\"historyTimeToLive\":null}");
        String drd = dd.get("decisionRequirementsDefinitionId").asText();
        assertTrue(ok("GET", "/engine-rest/decision-requirements-definition/" + drd + "/xml", null).get("dmnXml").asText().contains("uipElig"));
    }

    @Test
    void p27ToP31Batches() throws Exception {
        String pi = start("p27");
        String id = ok("POST", "/engine-rest/modification/executeAsync", map("processDefinitionId", definitionId, "processInstanceIds", new String[] {pi},
                "instructions", new Object[] {map("type", "startBeforeActivity", "activityId", "review")})).get("id").asText();
        JsonNode stat = ok("GET", "/engine-rest/batch/statistics?batchId=" + id + "&type=instance-modification", null);
        assertEquals(1, stat.size(), stat.toString());
        String bjd = stat.get(0).get("batchJobDefinitionId").asText();
        ok("PUT", "/engine-rest/job-definition/" + bjd + "/retries", map("retries", 1));
        ok("GET", "/engine-rest/job?jobDefinitionId=" + bjd + "&noRetriesLeft=true", null);
        ok("PUT", "/engine-rest/batch/" + id + "/suspended", map("suspended", true));
        ok("DELETE", "/engine-rest/batch/" + id + "?cascade=true", null);
        assertEquals(0, ok("GET", "/engine-rest/history/batch?batchId=" + id, null).size(), "cascade removes the history too");
    }

    @Test
    void p32P33DeploymentCountAndResources() throws Exception {
        start("p32");
        assertTrue(ok("GET", "/engine-rest/process-instance/count?deploymentId=" + deploymentId, null).get("count").asInt() >= 1);
        JsonNode res = ok("GET", "/engine-rest/deployment/" + deploymentId + "/resources", null);
        String rid = null;
        for (JsonNode r : res) {
            if ("uip.bpmn".equals(r.get("name").asText())) {
                rid = r.get("id").asText();
            }
        }
        HttpResponse<String> data = call("GET", "/engine-rest/deployment/" + deploymentId + "/resources/" + rid + "/data", null, "*/*");
        assertEquals(200, data.statusCode());
        assertTrue(data.body().contains("id=\"uip\""), data.body());
    }

    @Test
    void p34TaskDashboardQueries() throws Exception {
        start("p34");
        assertTrue(ok("GET", "/engine-rest/task/count?unassigned=true", null).get("count").asInt() >= 1);
        assertTrue(ok("GET", "/engine-rest/task/report/candidate-group-count", null).toString().contains("clerks"));
        assertTrue(ok("GET", "/engine-rest/task?candidateGroup=clerks&maxResults=200", null).size() >= 1);
    }

    // ------------------------------------------------------------------ tasklist

    @Test
    void p35ToP39FiltersCardVariablesAndGlobalRead() throws Exception {
        start("p35");
        String fid = ok("POST", "/engine-rest/filter/create", map("resourceType", "Task", "name", "Clerks", "query", map("candidateGroup", "clerks"),
                "properties", map("priority", 3, "refresh", true, "variables", new Object[] {map("name", "amount", "label", "Amount")}))).get("id").asText();
        HttpResponse<String> hal = call("GET", "/engine-rest/filter/" + fid + "/list?maxResults=100", null, "application/hal+json");
        assertEquals(200, hal.statusCode(), hal.body());
        JsonNode task = JSON.readTree(hal.body()).get("_embedded").get("task").get(0);
        assertTrue(task.get("_embedded").get("variable").toString().contains("\"amount\""), task.toString());
        Map<String, Object> global = map("type", 0, "resourceType", 5, "resourceId", fid, "userId", "*", "permissions", new String[] {"READ"});
        String aid = ok("POST", "/engine-rest/authorization/create", global).get("id").asText();
        assertEquals(1, ok("GET", "/engine-rest/authorization?type=0&resourceType=5&resourceId=" + fid, null).size());
        assertNotEquals(200, call("POST", "/engine-rest/authorization/create", global).statusCode(),
                "a second global read for the same filter is refused (why the editor must not re-create it)");
        ok("DELETE", "/engine-rest/authorization/" + aid, null);
        assertEquals(0, ok("GET", "/engine-rest/authorization?type=0&resourceType=5&resourceId=" + fid, null).size());
    }

    @Test
    void p40SortByProcessVariable() throws Exception {
        start("p40");
        JsonNode r = ok("POST", "/engine-rest/task?maxResults=200", map("candidateGroup", "clerks",
                "sorting", new Object[] {map("sortBy", "processVariable", "sortOrder", "desc", "parameters", map("variable", "amount", "type", "Long"))}));
        assertTrue(r.size() >= 1);
    }

    @Test
    void p47StandaloneTaskWithTenant() throws Exception {
        String id = "st-" + UUID.randomUUID();
        ok("POST", "/engine-rest/task/create", map("id", id, "name", "Call citizen", "tenantId", TENANT));
        assertEquals(TENANT, ok("GET", "/engine-rest/task/" + id, null).get("tenantId").asText());
    }

    // ------------------------------------------------------------------ admin, account

    @Test
    void p49AuthorizationUpdateNeedsTheResourceType() throws Exception {
        String aid = ok("POST", "/engine-rest/authorization/create", map("type", 1, "resourceType", 6, "resourceId", "uip", "userId", "asha",
                "permissions", new String[] {"READ"})).get("id").asText();
        HttpResponse<String> without = call("PUT", "/engine-rest/authorization/" + aid, map("resourceId", "uip", "userId", "asha", "permissions", new String[] {"READ", "UPDATE"}));
        assertNotEquals(204, without.statusCode(), "without resourceType the engine cannot convert permission names: " + without.body());
        ok("PUT", "/engine-rest/authorization/" + aid, map("resourceType", "6", "resourceId", "uip", "userId", "asha", "permissions", new String[] {"READ", "UPDATE"}));
        assertTrue(ok("GET", "/engine-rest/authorization/" + aid, null).get("permissions").toString().contains("UPDATE"));
    }

    @Test
    void p51ToP54And57IdentityMemberships() throws Exception {
        String u = "u" + UUID.randomUUID().toString().substring(0, 8);
        ok("POST", "/engine-rest/user/create", map("profile", map("id", u, "firstName", "Asha", "lastName", "K"), "credentials", map("password", "Sangam#Parity2026")));
        ok("PUT", "/engine-rest/user/" + u + "/profile", map("id", u, "firstName", "Asha", "lastName", "Kumari", "email", "asha@example.in"));
        assertEquals("Kumari", ok("GET", "/engine-rest/user/" + u + "/profile", null).get("lastName").asText());
        ok("POST", "/engine-rest/group/create", map("id", "g" + u, "name", "Clerks " + u, "type", "WORKFLOW"));
        ok("PUT", "/engine-rest/group/g" + u + "/members/" + u, null);
        ok("POST", "/engine-rest/tenant/create", map("id", "t" + u, "name", "Old"));
        ok("PUT", "/engine-rest/tenant/t" + u + "/user-members/" + u, null);
        assertEquals(1, ok("GET", "/engine-rest/tenant?userMember=" + u, null).size());
        ok("DELETE", "/engine-rest/tenant/t" + u + "/user-members/" + u, null);
        ok("PUT", "/engine-rest/tenant/t" + u + "/group-members/g" + u, null);
        ok("DELETE", "/engine-rest/tenant/t" + u + "/group-members/g" + u, null);
        assertEquals(0, ok("GET", "/engine-rest/tenant?groupMember=g" + u, null).size());
        ok("PUT", "/engine-rest/tenant/t" + u, map("id", "t" + u, "name", "New name"));
        assertEquals("New name", ok("GET", "/engine-rest/tenant/t" + u, null).get("name").asText());
    }

    @Test
    void p56MetricsChartQuery() throws Exception {
        assertTrue(ok("GET", "/engine-rest/metrics?name=activity-instance-start&interval=3600&aggregateByReporter=true&maxResults=168", null).isArray());
    }
}
