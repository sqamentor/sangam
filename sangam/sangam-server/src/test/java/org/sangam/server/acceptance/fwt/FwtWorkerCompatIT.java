// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.fwt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.task.Task;
import org.operaton.bpm.model.bpmn.Bpmn;
import org.sangam.core.api.EngineAdapter;
import org.sangam.ports.adhoc.AdHocSubProcessService;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.WorkPort;
import org.sangam.rest.support.TxSupport;
import org.sangam.server.acceptance.AbstractAcceptanceIT;
import org.sangam.server.web.FlowableWorkerCompatController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;

/**
 * FLOWABLE-WEAKNESS-AUDIT §5 scenarios that need the real server (PostgreSQL, OIDC, the Flowable external-worker
 * compatibility endpoint {@code /api/v1/compat/flowable/external-job-api}): FWT-009, 010, 011, 012, 031 (FW-07, FW-30,
 * audit PR-1), and FWT-014 on PostgreSQL (FW-09).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FwtWorkerCompatIT extends AbstractAcceptanceIT {

    static final String T = "acc";
    static final String RUN = "fwt" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    static final String API = "/api/v1/compat/flowable/external-job-api";
    static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    EngineAdapter engine;
    @Autowired
    ProcessEngine processEngine;
    @Autowired
    WorkPort workPort;
    @Autowired
    PermissionPort permissions;
    @Autowired
    TxSupport txSupport;
    @Autowired
    AuditPort audit;
    @Autowired
    AdHocSubProcessService adHoc;

    @Value("${local.server.port}")
    int port;

    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    Instant clock;

    record Rest(int status, String body) {
        JsonNode json() {
            try {
                return body == null || body.isBlank() ? JSON.nullNode() : JSON.readTree(body);
            } catch (IOException e) {
                throw new IllegalStateException("not JSON (" + status + "): " + body, e);
            }
        }

        String code() {
            return json().path("code").asText(null);
        }
    }

    @BeforeAll
    void setUp() {
        clock = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        engine.setClock(clock);
    }

    @AfterAll
    void tearDown() {
        engine.setClock(null);
    }

    Rest post(String path, Object body) {
        try {
            HttpRequest r = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .timeout(Duration.ofSeconds(60))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + bearer(T, "acc-admin", ADMIN_GROUPS))
                    .POST(HttpRequest.BodyPublishers.ofString(body instanceof String s ? s : JSON.writeValueAsString(body)))
                    .build();
            HttpResponse<String> resp = http.send(r, HttpResponse.BodyHandlers.ofString());
            return new Rest(resp.statusCode(), resp.body());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** One instance waiting in an external task on its own topic, then a user task "after". */
    String start(String scenario, String topic) {
        String key = RUN + "-" + scenario;
        String xml = Bpmn.convertToString(Bpmn.createExecutableProcess(key).operatonHistoryTimeToLive(30)
                .startEvent()
                .serviceTask("work").operatonExternalTask(topic)
                .userTask("after")
                .endEvent()
                .done());
        engine.deploy(T, RUN + "-dep-" + scenario, Map.of(key + ".bpmn", xml.getBytes(StandardCharsets.UTF_8)));
        return engine.startProcess(T, key, RUN + "-bk-" + scenario, Map.of());
    }

    JsonNode acquire(String topic, String worker) {
        Rest r = post(API + "/acquire/jobs", Map.of("topic", topic, "workerId", worker, "lockDuration", "PT60S",
                "numberOfTasks", 1));
        assertEquals(200, r.status(), r.body());
        assertEquals(1, r.json().size(), r.body());
        return r.json().get(0);
    }

    boolean stillWaiting(String pid) {
        EngineAdapter.InstanceView v = engine.getInstance(pid);
        return !v.ended() && v.activeActivityIds().contains("work");
    }

    // FWT-009
    @Test
    void fwt009_completeWithoutLockVersionIsFencingTokenRequired() {
        String topic = RUN + "-t009";
        String pid = start("009", topic);
        JsonNode job = acquire(topic, "w9");
        Rest r = post(API + "/acquire/jobs/" + job.path("id").asText() + "/complete",
                Map.of("workerId", "w9", "variables", List.of(Map.of("name", "x", "type", "string", "value", "v"))));
        assertEquals(409, r.status(), r.body());
        assertEquals("FENCING_TOKEN_REQUIRED", r.code(), r.body());
        assertTrue(stillWaiting(pid), "nothing changes");
        assertFalse(processEngine.getRuntimeService().getVariables(pid).containsKey("x"), "no variable written");
    }

    // FWT-010
    @Test
    void fwt010_staleCallAfterReacquireIsStaleLease() {
        String topic = RUN + "-t010";
        String pid = start("010", topic);
        JsonNode first = acquire(topic, "wA");
        clock = clock.plus(Duration.ofSeconds(61));
        engine.setClock(clock);
        JsonNode second = acquire(topic, "wA");
        assertEquals(first.path("id").asText(), second.path("id").asText());
        assertTrue(second.path("sangamLockVersion").asLong() > first.path("sangamLockVersion").asLong(), "new version");
        Rest stale = post(API + "/acquire/jobs/" + first.path("id").asText() + "/complete", Map.of("workerId", "wA",
                "sangamLockVersion", first.path("sangamLockVersion").asLong(), "variables", List.of()));
        assertEquals(409, stale.status(), stale.body());
        assertEquals("STALE_LEASE", stale.code(), stale.body());
        assertTrue(stillWaiting(pid), "stale call changed nothing");
        Rest fresh = post(API + "/acquire/jobs/" + second.path("id").asText() + "/complete", Map.of("workerId", "wA",
                "sangamLockVersion", second.path("sangamLockVersion").asLong(), "variables", List.of()));
        assertEquals(204, fresh.status(), fresh.body());
        assertFalse(stillWaiting(pid));
    }

    // FWT-011: sangam.compat.flowable.allow-unfenced=true. The controller is built with the server's own beans and
    // the opt-in (a second Spring context only for one property would double the suite's start-up).
    @Test
    void fwt011_allowUnfencedRightAttemptAcceptedWrongAttemptRejected() {
        String topic = RUN + "-t011";
        String pid = start("011", topic);
        JsonNode job = acquire(topic, "w11");
        String id = job.path("id").asText();
        FlowableWorkerCompatController unfenced = new FlowableWorkerCompatController(workPort, permissions, txSupport, audit, true);
        RequestContext ctx = new RequestContext(T, "acc-admin", Set.copyOf(ADMIN_GROUPS), null, null, null, null, null, "OIDC");

        ApiProblem wrong = assertThrows(ApiProblem.class, () -> unfenced.complete(ctx, id,
                "{\"workerId\":\"w11\",\"sangamAttemptId\":\"not-the-attempt\",\"variables\":[]}"));
        assertEquals(409, wrong.status());
        assertEquals("STALE_LEASE", wrong.code());
        assertTrue(stillWaiting(pid), "wrong attempt id changed nothing");

        ResponseEntity<Void> right = unfenced.complete(ctx, id, "{\"workerId\":\"w11\",\"sangamAttemptId\":\""
                + job.path("sangamAttemptId").asText() + "\",\"variables\":[]}");
        assertEquals(204, right.getStatusCode().value());
        assertFalse(stillWaiting(pid), "right attempt id accepted");
    }

    // FWT-012 (REST part; the numberOfTasks clamp is asserted on the translation in FwtInputGuardsTest)
    @Test
    void fwt012_malformedInputIs422InvalidInput() {
        String topic = RUN + "-t012";
        String pid = start("012", topic);
        Rest badLock = post(API + "/acquire/jobs", Map.of("topic", topic, "workerId", "w12", "lockDuration", "abc"));
        assertEquals(422, badLock.status(), badLock.body());
        assertEquals("INVALID_INPUT", badLock.code());
        Rest noWorker = post(API + "/acquire/jobs", Map.of("topic", topic, "lockDuration", "PT60S"));
        assertEquals(422, noWorker.status(), noWorker.body());
        assertEquals("INVALID_INPUT", noWorker.code());
        JsonNode job = acquire(topic, "w12");
        Rest badRetry = post(API + "/acquire/jobs/" + job.path("id").asText() + "/fail", Map.of("workerId", "w12",
                "sangamLockVersion", job.path("sangamLockVersion").asLong(), "retries", 1, "retryTimeout", "soon"));
        assertEquals(422, badRetry.status(), badRetry.body());
        assertEquals("INVALID_INPUT", badRetry.code());
        Rest many = post(API + "/acquire/jobs", Map.of("topic", topic, "workerId", "w12b", "lockDuration", "PT60S",
                "numberOfTasks", 10_000));
        assertEquals(200, many.status(), "numberOfTasks=10000 accepted (clamped), not an error: " + many.body());
        assertTrue(stillWaiting(pid));
    }

    // FWT-031
    @Test
    void fwt031_reservedVariableFromWorkerIs422AndNoSkip() {
        String topic = RUN + "-t031";
        String pid = start("031", topic);
        JsonNode job = acquire(topic, "w31");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workerId", "w31");
        body.put("sangamLockVersion", job.path("sangamLockVersion").asLong());
        body.put("variables", List.of(Map.of("name", "_FLOWABLE_SKIP_EXPRESSION_ENABLED", "type", "boolean", "value", true)));
        Rest r = post(API + "/acquire/jobs/" + job.path("id").asText() + "/complete", body);
        assertEquals(422, r.status(), r.body());
        assertEquals("RESERVED_VARIABLE", r.code(), r.body());
        assertTrue(stillWaiting(pid), "nothing completed");
        assertFalse(processEngine.getRuntimeService().getVariables(pid).containsKey("_FLOWABLE_SKIP_EXPRESSION_ENABLED"));
        // a proper completion afterwards reaches the user task (nothing downstream was skipped)
        body.put("variables", List.of());
        assertEquals(204, post(API + "/acquire/jobs/" + job.path("id").asText() + "/complete", body).status());
        assertEquals(List.of("after"), engine.getInstance(pid).activeActivityIds());
    }

    // FWT-014 on PostgreSQL (the module test FwtAdHocImportTest#fwt014 covers H2)
    @Test
    void fwt014_adHocCompensationHandlerTaskOnPostgres() {
        String key = RUN + "-ah014";
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="t">
                  <process id="%s" isExecutable="true" operaton:historyTimeToLive="30">
                    <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="sub"/>
                    <adHocSubProcess id="sub" ordering="Parallel">
                      <serviceTask id="done" operaton:expression="${true}"/>
                      <boundaryEvent id="db" attachedToRef="done"><compensateEventDefinition/></boundaryEvent>
                      <userTask id="undoDone" isForCompensation="true"/>
                      <association id="da" sourceRef="db" targetRef="undoDone"/>
                    </adHocSubProcess>
                    <sequenceFlow id="f2" sourceRef="sub" targetRef="th"/>
                    <intermediateThrowEvent id="th"><compensateEventDefinition/></intermediateThrowEvent>
                    <sequenceFlow id="f3" sourceRef="th" targetRef="e"/><endEvent id="e"/>
                  </process>
                </definitions>
                """.formatted(key);
        engine.deploy(T, RUN + "-dep-ah014", Map.of(key + ".bpmn", xml.getBytes(StandardCharsets.UTF_8)));
        String pid = engine.startProcess(T, key, RUN + "-bk-ah014", Map.of());
        adHoc.executeActivity(pid, null, "done");
        adHoc.complete(pid, null, false);
        List<String> tasks = processEngine.getTaskService().createTaskQuery().processInstanceId(pid).list().stream()
                .map(Task::getTaskDefinitionKey).toList();
        assertEquals(List.of("undoDone"), tasks, "handler task created, no FK error");
    }
}
