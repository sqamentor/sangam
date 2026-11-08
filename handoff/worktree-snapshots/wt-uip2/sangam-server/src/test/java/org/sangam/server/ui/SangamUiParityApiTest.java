// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.runtime.ActivityInstance;
import org.sangam.rest.adapter.operaton.OperatonHistoryAdapter;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.HistoryPort;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.ProcessPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.TaskPort;
import org.sangam.rest.support.RestProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * UI-PARITY-2 API additions (integrator-approved additive contract), on a running server (h2):
 * <ul>
 *   <li>P45 {@code GET /tasks?variableNames=}: list rows carry the named variables, only for tasks the caller already
 *       sees (visibility and tenant negatives) and only those names;</li>
 *   <li>P13 {@code GET /process-instances/{id}/variables?activityInstanceId=} and {@code activityInstanceId} on the
 *       history lists (variables, activity instances): one activity-instance scope, tenant-checked;</li>
 *   <li>P14 {@code GET /history/activity-instances?state=running|completed|canceled}, on the server's read-model port
 *       and on the Operaton history adapter (the "completed" native query included);</li>
 *   <li>filters a list cannot apply are refused ({@code 400 FILTER_UNSUPPORTED}), never silently ignored.</li>
 * </ul>
 * Instances: A (tenant dev, running: {@code review} running with a local variable, {@code check} canceled), B (dev,
 * ended) and X (tenant other).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"sangam.engine.allow-multiple-engines-per-jvm=true",
        "sangam.security.dev-token.groups=sangam-admin,sangam-operator",
        "operaton.bpm.job-execution.enabled=false"})
@ActiveProfiles("h2")
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SangamUiParityApiTest {

    static final String TOKEN = "sangam-dev-token-change-me";
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final ObjectMapper JSON = new ObjectMapper();

    static final String BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:operaton="http://operaton.org/schema/1.0/bpmn"
                         id="uip2-defs" targetNamespace="http://sangam.org/test/uip2">
              <process id="uip2" name="UI parity 2" isExecutable="true" operaton:historyTimeToLive="30">
                <startEvent id="start"/>
                <sequenceFlow id="f1" sourceRef="start" targetRef="fork"/>
                <parallelGateway id="fork"/>
                <sequenceFlow id="f2" sourceRef="fork" targetRef="review"/>
                <sequenceFlow id="f3" sourceRef="fork" targetRef="check"/>
                <userTask id="review" name="Review" operaton:candidateGroups="clerks">
                  <extensionElements><operaton:inputOutput>
                    <operaton:inputParameter name="note">local to review</operaton:inputParameter>
                  </operaton:inputOutput></extensionElements>
                </userTask>
                <userTask id="check" name="Check" operaton:candidateGroups="clerks"/>
                <sequenceFlow id="f4" sourceRef="review" targetRef="join"/>
                <sequenceFlow id="f5" sourceRef="check" targetRef="join"/>
                <parallelGateway id="join"/>
                <sequenceFlow id="f6" sourceRef="join" targetRef="end"/>
                <endEvent id="end"/>
              </process>
            </definitions>
            """;

    @LocalServerPort
    int port;

    @Autowired
    ProcessEngine engine;
    @Autowired
    TaskPort tasks;
    @Autowired
    ProcessPort processes;
    @Autowired
    HistoryPort history;
    @Autowired
    RestProperties props;

    String piA;
    String reviewA;
    String checkA;
    String piB;
    String reviewB;
    String piX;

    static RequestContext ctx(String tenant, String user, String... groups) {
        return new RequestContext(tenant, user, Set.of(groups), null, null, null, null, null, "TEST");
    }

    final RequestContext admin = ctx("dev", "demo", "sangam-admin");
    final RequestContext clerk = ctx("dev", "clerk1", "clerks");
    final RequestContext outsider = ctx("dev", "bob", "gardeners");
    final RequestContext otherTenant = ctx("other", "olga", "sangam-admin", "clerks");

    // ------------------------------------------------------------------ setup

    String start(String tenant, String businessKey) {
        String defId = engine.getRepositoryService().createProcessDefinitionQuery().processDefinitionKey("uip2")
                .tenantIdIn(tenant).latestVersion().singleResult().getId();
        return engine.getRuntimeService().startProcessInstanceById(defId, businessKey,
                Map.of("amount", 5, "district", "Jaipur")).getId();
    }

    static String child(ActivityInstance tree, String activityId) {
        for (ActivityInstance c : tree.getChildActivityInstances()) {
            if (activityId.equals(c.getActivityId())) {
                return c.getId();
            }
        }
        throw new AssertionError("no " + activityId + " in " + tree);
    }

    @BeforeAll
    void setUp() {
        for (String tenant : List.of("dev", "other")) {
            engine.getRepositoryService().createDeployment().name("uip2").tenantId(tenant)
                    .addString("uip2.bpmn", BPMN).deploy();
        }
        piA = start("dev", "UIP2-A");
        ActivityInstance treeA = engine.getRuntimeService().getActivityInstance(piA);
        reviewA = child(treeA, "review");
        checkA = child(treeA, "check");
        engine.getRuntimeService().createProcessInstanceModification(piA).cancelActivityInstance(checkA).execute();

        piB = start("dev", "UIP2-B");
        reviewB = child(engine.getRuntimeService().getActivityInstance(piB), "review");
        engine.getTaskService().createTaskQuery().processInstanceId(piB).list()
                .forEach(t -> engine.getTaskService().complete(t.getId()));
        assertNull(engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(piB).singleResult());

        piX = start("other", "UIP2-X");
    }

    // ------------------------------------------------------------------ HTTP helpers

    HttpResponse<String> get(String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + TOKEN).header("Accept", "application/json").GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    JsonNode ok(String path) throws Exception {
        HttpResponse<String> r = get(path);
        assertEquals(200, r.statusCode(), path + " -> " + r.body());
        return JSON.readTree(r.body());
    }

    static String q(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    static Set<String> fields(JsonNode n) {
        Set<String> out = new TreeSet<>();
        n.fieldNames().forEachRemaining(out::add);
        return out;
    }

    static Set<String> col(JsonNode list, String field) {
        Set<String> out = new TreeSet<>();
        list.forEach(x -> out.add(x.path(field).asText()));
        return out;
    }

    static final Page.PageRequest PAGE = new Page.PageRequest(0, 50);

    static HistoryPort.HistoryFilter f(String pi, String activityInstanceId, String state) {
        return new HistoryPort.HistoryFilter(null, pi, null, null, null, null, null, null, null, null, activityInstanceId, state);
    }

    /**
     * Runs a history-port call with the request's identity bound as the tenant's system identity. The bridge then
     * skips the VI.4 READ_HISTORY predicate (system sees its tenant); that predicate's SQL does not run on H2 today
     * ({@code BITAND(g.perms, ?)}: H2 cannot type the bare parameter, "Unknown data type ?12"), reported as a separate
     * finding. Tenant scoping is still asserted.
     */
    static <T> T asSystem(String tenant, java.util.function.Supplier<T> work) {
        return org.sangam.security.tenant.TenantContext.runAs(org.sangam.core.security.SecurityIdentity.system(tenant, "uip2-test"), work);
    }

    Set<String> acts(String bound, RequestContext c, String pi, String activityInstanceId, String state) {
        return asSystem(bound, () -> history.activityInstances(c, f(pi, activityInstanceId, state), PAGE).items().stream()
                .map(HistoryPort.HistoricActivityInstance::activityId).collect(Collectors.toCollection(TreeSet::new)));
    }

    /** The history read model is projected after commit: poll until it holds the expected rows. */
    static <T> T eventually(java.util.function.Supplier<T> read, java.util.function.Predicate<T> done) throws Exception {
        T last = null;
        for (int i = 0; i < 100; i++) {
            last = read.get();
            if (done.test(last)) {
                return last;
            }
            Thread.sleep(150);
        }
        throw new AssertionError("never matched; last: " + last);
    }

    static int status(Runnable r) {
        ApiProblem p = assertThrows(ApiProblem.class, r::run);
        return p.status();
    }

    // ------------------------------------------------------------------ P45 task-card variables

    TaskPort.TaskFilter byInstance(String pi, List<String> names) {
        return new TaskPort.TaskFilter(null, null, null, null, null, pi, null, null, null, null, null, null, null, names);
    }

    @Test
    void p45ListedTasksCarryOnlyTheNamedVariables() {
        List<TaskPort.Task> rows = tasks.list(clerk, byInstance(piA, List.of("amount", "missing")), new Page.PageRequest(0, 50)).items();
        assertEquals(1, rows.size(), "review is open, check was canceled");
        assertEquals(Map.of("amount", 5), rows.get(0).variables(), "only the named, set variables");
        assertNull(tasks.list(clerk, byInstance(piA, List.of()), new Page.PageRequest(0, 50)).items().get(0).variables(),
                "no variables unless asked for");
        // comma-separated names are split like repeated parameters
        assertEquals(Set.of("amount", "district"), tasks.list(clerk, byInstance(piA, List.of("amount,district")),
                new Page.PageRequest(0, 50)).items().get(0).variables().keySet());
    }

    @Test
    void p45VariablesNeverWidenVisibility() {
        assertTrue(tasks.list(outsider, byInstance(piA, List.of("amount")), new Page.PageRequest(0, 50)).items().isEmpty(),
                "not a candidate: the task, and so its variables, stay invisible");
        assertTrue(tasks.list(otherTenant, byInstance(piA, List.of("amount")), new Page.PageRequest(0, 50)).items().isEmpty(),
                "other tenant");
        List<TaskPort.Task> own = tasks.list(otherTenant, byInstance(piX, List.of("amount")), new Page.PageRequest(0, 50)).items();
        assertEquals(2, own.size(), "X: review and check, both open");
        assertTrue(own.stream().allMatch(t -> "other".equals(t.tenantId())));
        List<String> tooMany = IntStream.range(0, TaskPort.MAX_CARD_VARIABLES + 1).mapToObj(i -> "v" + i).toList();
        assertEquals(400, status(() -> byInstance(piA, tooMany)));
    }

    @Test
    void p45OverHttp() throws Exception {
        JsonNode rows = ok("/api/v1/tasks?processInstanceId=" + piA + "&variableNames=amount&variableNames=district");
        assertEquals(1, rows.size());
        assertEquals(Set.of("amount", "district"), fields(rows.get(0).get("variables")));
        assertEquals(5, rows.get(0).get("variables").get("amount").asInt());
        assertEquals("Jaipur", rows.get(0).get("variables").get("district").asText());
        assertEquals(0, ok("/api/v1/tasks?processInstanceId=" + piX + "&variableNames=amount").size(), "other tenant");
        JsonNode plain = ok("/api/v1/tasks?processInstanceId=" + piA).get(0).path("variables");
        assertTrue(plain.isNull() || plain.isMissingNode(), "no variables unless asked for: " + plain);
    }

    // ------------------------------------------------------------------ P13 process variables by activity instance

    @Test
    void p13RuntimeVariablesOfOneActivityInstanceScope() {
        assertEquals(Set.of("note"), processes.variables(admin, piA, reviewA).keySet(), "review's local variable only");
        Set<String> root = processes.variables(admin, piA, piA).keySet();
        assertTrue(root.containsAll(Set.of("amount", "district")) && !root.contains("note"), "the root scope: " + root);
        assertTrue(processes.variables(admin, piA, reviewB).isEmpty(), "an activity instance of another instance");
        assertEquals(processes.variables(admin, piA), processes.variables(admin, piA, null), "no filter: unchanged");
        assertEquals(404, status(() -> processes.variables(otherTenant, piA, reviewA)), "tenant check");
    }

    @Test
    void p13HistoricVariablesOfOneActivityInstanceScope() throws Exception {
        assertEquals(Set.of("note"), processes.variables(admin, piB, reviewB).keySet(), "ended instance: from history");
        assertEquals(404, status(() -> processes.variables(otherTenant, piB, reviewB)));
        assertEquals(Set.of("note"), fields(ok("/api/v1/process-instances/" + piA + "/variables?activityInstanceId=" + q(reviewA))));
        assertEquals(404, get("/api/v1/process-instances/" + piX + "/variables?activityInstanceId=x").statusCode());
        // the server's history port (read-model bridge); bound as the tenant's system identity, see the class note.
        // Its instance check reads the projected read model, so wait until A is projected.
        eventually(() -> acts("dev", admin, piA, null, "running"), s -> !s.isEmpty());
        List<HistoryPort.HistoricVariable> hv = asSystem("dev", () -> history.variables(admin, f(piA, reviewA, null), PAGE).items());
        assertEquals(Set.of("note"), hv.stream().map(HistoryPort.HistoricVariable::name).collect(Collectors.toSet()));
        assertEquals(Set.of(reviewA), hv.stream().map(HistoryPort.HistoricVariable::activityInstanceId).collect(Collectors.toSet()));
        assertTrue(asSystem("dev", () -> history.variables(admin, f(piA, null, null), PAGE).items()).stream()
                .map(HistoryPort.HistoricVariable::name).collect(Collectors.toSet()).containsAll(Set.of("amount", "district", "note")));
        assertEquals(404, status(() -> asSystem("other", () -> history.variables(otherTenant, f(piA, reviewA, null), PAGE))),
                "other tenant: the instance is not visible (no existence leak)");
    }

    // ------------------------------------------------------------------ P14 activity-instance state filter

    @Test
    void p14StateFilterOnTheServerHistoryPort() throws Exception {
        assertEquals(Set.of("check"), eventually(() -> acts("dev", admin, piA, null, "canceled"), s -> !s.isEmpty()));
        assertEquals(Set.of("review"), eventually(() -> acts("dev", admin, piA, null, "running"), s -> !s.isEmpty()));
        assertEquals(Set.of("fork", "start"), eventually(() -> acts("dev", admin, piA, null, "completed"), s -> s.size() >= 2));
        assertEquals(Set.of("review"), acts("dev", admin, piA, reviewA, null), "one activity instance");
        assertTrue(acts("other", otherTenant, piA, null, "running").isEmpty(), "other tenant");
        assertTrue(eventually(() -> acts("other", otherTenant, piX, null, "running"), s -> !s.isEmpty()).contains("review"));
        assertTrue(acts("dev", otherTenant, piA, null, "running").isEmpty(), "identity of another tenant sees nothing");
        assertEquals(400, status(() -> acts("dev", admin, piA, null, "paused")));
        assertEquals(400, get("/api/v1/history/activity-instances?processInstanceId=" + piA + "&state=paused").statusCode());
    }

    @Test
    void p14StateFilterOnTheOperatonHistoryAdapter() {
        OperatonHistoryAdapter a = new OperatonHistoryAdapter(engine.getHistoryService(), engine.getRepositoryService(),
                engine.getManagementService(), props);
        java.util.function.BiFunction<RequestContext, String, Set<String>> by = (c, state) ->
                a.activityInstances(c, new HistoryPort.HistoryFilter(null, piA, null, null, null, null, null, null, null,
                        null, null, state), new Page.PageRequest(0, 50)).items().stream()
                        .map(HistoryPort.HistoricActivityInstance::activityId).collect(Collectors.toCollection(TreeSet::new));
        assertEquals(Set.of("check"), by.apply(admin, "canceled"));
        assertEquals(Set.of("review"), by.apply(admin, "RUNNING"));
        assertEquals(Set.of("fork", "start"), by.apply(admin, "completed"), "ended and not canceled (native query)");
        assertEquals(Set.of("check", "fork", "review", "start"), by.apply(admin, null));
        assertTrue(by.apply(otherTenant, "completed").isEmpty(), "the native query keeps the tenant");
        assertTrue(by.apply(otherTenant, "canceled").isEmpty());
        Page<HistoryPort.HistoricActivityInstance> page = a.activityInstances(admin, new HistoryPort.HistoryFilter(null,
                piA, null, null, null, null, null, null, null, null, null, "completed"), new Page.PageRequest(0, 1));
        assertEquals(1, page.items().size());
        assertEquals(2, page.total(), "count of the completed filter");
        assertEquals(List.of(reviewA), a.activityInstances(admin, new HistoryPort.HistoryFilter(null, piA, null, null,
                null, null, null, null, null, null, reviewA, null), new Page.PageRequest(0, 50)).items().stream()
                .map(HistoryPort.HistoricActivityInstance::id).toList());
        assertEquals(400, status(() -> a.activityInstances(admin, new HistoryPort.HistoryFilter(null, piA, null, null,
                null, null, null, null, null, null, null, "paused"), new Page.PageRequest(0, 50))));
        Set<String> vars = new TreeSet<>();
        a.variables(admin, new HistoryPort.HistoryFilter(null, piA, null, null, null, null, null, null, null, null,
                reviewA, null), new Page.PageRequest(0, 50)).items().forEach(v -> vars.add(v.name()));
        assertEquals(Set.of("note"), vars);
    }

    // ------------------------------------------------------------------ unsupported filters are refused

    @Test
    void filtersAListCannotApplyAreRefused() throws Exception {
        List<String> refused = new ArrayList<>();
        for (String path : List.of("/api/v1/history/incidents?activityInstanceId=x", "/api/v1/history/job-log?activityInstanceId=x",
                "/api/v1/history/operation-log?activityInstanceId=x", "/api/v1/history/tasks?activityInstanceId=x",
                "/api/v1/history/process-instances?activityInstanceId=x", "/api/v1/history/process-instances?state=running",
                "/api/v1/history/variables?processInstanceId=" + piA + "&state=running")) {
            HttpResponse<String> r = get(path);
            assertEquals(400, r.statusCode(), path + " -> " + r.body());
            assertTrue(r.body().contains("FILTER_UNSUPPORTED"), r.body());
            refused.add(path);
        }
        assertFalse(refused.isEmpty());
    }
}
