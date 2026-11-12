// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.isolation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.batch.Batch;
import org.sangam.core.api.EngineAdapter;
import org.sangam.server.acceptance.AbstractAcceptanceIT;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * SL-45: tenant A never reads, starts, completes, claims, correlates, queries, exports, receives events of or administers
 * tenant B's objects, on every surface, with real OIDC tokens (SANGAM.md section 10, AC-09, VI.4).
 *
 * <p>Generic, so a new endpoint is covered without editing this test: every handler mapping of the server under
 * {@code /api/**} is called as tenant A's full administrator with tenant B's real ids (harvested from every table that
 * has a tenant column, engine and product alike) in its path variables, query parameters and batch bodies, plainly and
 * with a spoofed tenant header and tenant query parameters. Two oracles:
 * <ol>
 *   <li>No successful response carries data of tenant B that A did not supply itself: no object stamped with B's tenant
 *       id (a {@code tenant}/{@code tenantId}/... member equal to B), and no value of B that A never sent (B's secret
 *       variable, the business key and ids of B's instance A never names, and every other identifier of B). A value A
 *       sent itself (an id in a path, B's tenant id in a spoofed header) may come back: an echo, or one of A's own
 *       objects A named with it (a topic, a number series, its own audit trail), tells A nothing it did not know.
 *       B's identifiers are only values no other owner holds (rerun 4: both tenants' copy of the same process key, the
 *       global group {@code sangam-user} and the bootstrap policy id every tenant gets were reported on A's own
 *       objects).</li>
 *   <li>A content hash of every tenant-B row of every tenant-scoped table is unchanged afterwards, so no read, claim,
 *       completion, correlation, signal, suspension, deletion, deployment, batch, variable write, comment or audit row
 *       reached tenant B.</li>
 * </ol>
 * {@code /engine-rest} is exercised the same way with a tenant-A engine administrator: a call on one object of B, or
 * creating one in B, must be refused; a selector (a key both tenants use, a message or signal name, a business key, a
 * tenant filter) may be served inside tenant A and must leave B unchanged; an async batch naming B's instance is
 * accepted like any id list (Operaton does not look the ids up at creation, a nonexistent id included) and is judged by
 * its effect once it has run.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CrossTenantIsolationIT extends AbstractAcceptanceIT {

    static final String RUN = "i" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    static final String A = RUN + "-ta";
    static final String B = RUN + "-tb";
    static final String KEY = RUN + "-iso";
    static final String CASE_KEY = RUN + "-isocase";
    static final String MSG = RUN + "-msg";
    static final String SIG = RUN + "-sig";
    static final String BK_A = RUN + "-bka";
    static final String BK_B = RUN + "-bkb";
    /** Business key of B's REST-started instance: never named by tenant A, so it must never come back. */
    static final String BK_B_REST = BK_B + "-rest";
    static final String SECRET_B = RUN + "-SECRET-B";
    static final ObjectMapper JSON = new ObjectMapper();
    static final Pattern VAR = Pattern.compile("\\{\\*?([A-Za-z0-9_]+)(?::([^}]*))?}");
    /** A JSON member naming the tenant an object belongs to, with its string or array value. */
    static final Pattern TENANT_MEMBER = Pattern.compile(
            "\"(tenant|tenantId|tenant_id|tenant-id|tenantIds|tenantIdIn)\"\\s*:\\s*(\\[[^\\]]*]|\"[^\"]*\")",
            Pattern.CASE_INSENSITIVE);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Autowired
    DataSource dataSource;
    @Autowired
    EngineAdapter engine;
    @Autowired
    ProcessEngine processEngine;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;
    @Autowired
    org.sangam.security.SangamSecurity security;
    @Autowired
    @Qualifier("sangamProductTransactions")
    org.sangam.core.layer.Transactions productTransactions;
    @Value("${local.server.port}")
    int port;

    String tokenA;
    String engineAdminA;
    String taskA;
    String taskB;
    String piB;
    String piBrest;
    String taskBrest;
    /** Tenant B's ids of every tenant-scoped table, by table: the probes put into paths and batch bodies. */
    final Map<String, Set<String>> idsB = new TreeMap<>();
    /** Every value of tenant B a response must not carry unless tenant A sent it: markers plus all of B's ids. */
    final Set<String> valuesOfB = new LinkedHashSet<>();
    /** Values of {@link #valuesOfB} that tenant A has sent in a request so far (path, query, header or body). */
    final Set<String> sentByA = new HashSet<>();
    /** Never put into a request by tenant A, so they stay unknown to A. */
    final Set<String> reserved = new HashSet<>();
    boolean recording;
    /** Every leak found, as "surface operation -> detail". */
    final List<String> leaks = new ArrayList<>();
    Map<String, Rows> before;

    static String bpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="sl45">
                  <message id="m" name="%s"/><signal id="s" name="%s"/>
                  <process id="%s" isExecutable="true">
                    <startEvent id="start"/><sequenceFlow id="f0" sourceRef="start" targetRef="fork"/>
                    <parallelGateway id="fork"/>
                    <sequenceFlow id="f1" sourceRef="fork" targetRef="review"/>
                    <sequenceFlow id="f2" sourceRef="fork" targetRef="waitMsg"/>
                    <sequenceFlow id="f3" sourceRef="fork" targetRef="waitSig"/>
                    <userTask id="review" name="Review"
                              xmlns:operaton="http://operaton.org/schema/1.0/bpmn" operaton:candidateGroups="sangam-user"/>
                    <intermediateCatchEvent id="waitMsg"><messageEventDefinition messageRef="m"/></intermediateCatchEvent>
                    <intermediateCatchEvent id="waitSig"><signalEventDefinition signalRef="s"/></intermediateCatchEvent>
                    <sequenceFlow id="f4" sourceRef="review" targetRef="e1"/><endEvent id="e1"/>
                    <sequenceFlow id="f5" sourceRef="waitMsg" targetRef="e2"/><endEvent id="e2"/>
                    <sequenceFlow id="f6" sourceRef="waitSig" targetRef="e3"/><endEvent id="e3"/>
                  </process>
                </definitions>
                """.formatted(MSG, SIG, KEY);
    }

    static String cmmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/CMMN/20151109/MODEL" targetNamespace="sl45">
                  <case id="%s"><casePlanModel id="plan" autoComplete="false">
                    <planItem id="pi_h" definitionRef="h"/><humanTask id="h" isBlocking="true"/>
                  </casePlanModel></case>
                </definitions>
                """.formatted(CASE_KEY);
    }

    @BeforeAll
    void seed() throws Exception {
        // both tenants exist and are active, with their administrator grants: otherwise every call is 401
        // TENANT_UNAVAILABLE and the sweep would pass without testing anything
        org.sangam.server.bridge.SecurityBootstrap.run(security, productTransactions, List.of(A, B),
                List.of("sangam-admin", "sangam-engine-admin"), false);
        // the token subjects are linked persons of their tenants, otherwise every call is 401 NOT_LINKED. Linked here,
        // not through sangam.security.jit-provisioning-tenants: a @DynamicPropertySource of this class cannot extend
        // that list, because Spring (DynamicPropertiesContextCustomizerFactory) invokes the superclass's methods after
        // this class's and AbstractAcceptanceIT sets the same property to "acc", so its value wins (PostgreSQL run
        // 2026-11-09 19:15 UTC: the seed failed with 401 NOT_LINKED for tenant B's token)
        for (String[] p : new String[][] {{A, "sl45-a-admin"}, {A, "sl45-a-engine"}, {B, "sl45-b-admin"}}) {
            productTransactions.inTransaction(c -> security.persons().provisionJit(c, p[0], p[1], p[1], null));
        }
        for (String t : List.of(A, B)) {
            engine.deploy(t, KEY, Map.of(KEY + ".bpmn", bpmn().getBytes(StandardCharsets.UTF_8),
                    CASE_KEY + ".cmmn", cmmn().getBytes(StandardCharsets.UTF_8)));
            processEngine.getCaseService().withCaseDefinitionByKey(CASE_KEY).caseDefinitionTenantId(t)
                    .businessKey(t.equals(A) ? BK_A : BK_B).create();
        }
        engine.startProcess(A, KEY, BK_A, Map.of("note", RUN + "-own-a"));
        piB = engine.startProcess(B, KEY, BK_B, Map.of("note", SECRET_B));
        // tenant B's own REST traffic, so its product tables (audit, idempotency, outbox, events) hold rows too
        String tokenB = bearer(B, "sl45-b-admin", ADMIN_GROUPS);
        Reply started = send(tokenB, "POST", "/api/v1/process-instances",
                Map.of("key", KEY, "businessKey", BK_B_REST, "variables", Map.of("note", SECRET_B)), Map.of());
        assertEquals(201, started.status, "tenant B's own REST start works: " + started.body);
        piBrest = JSON.readTree(started.body).path("id").asText();
        // SL-78: both set-up calls must succeed (EventFormController: 201 Created with the stored object), otherwise
        // tenant B has no event rows and the sweep below proves nothing for the event registry
        Reply definition = send(tokenB, "POST", "/api/v1/events/definitions", Map.of("key", RUN + "-evdef",
                "name", "SL-45 event", "startProcessKey", KEY, "businessKeyPointer", "/billId"), Map.of());
        assertEquals(201, definition.status, "tenant B's own event definition is created: " + definition.body);
        assertEquals(RUN + "-evdef", JSON.readTree(definition.body).path("key").asText(), definition.body);
        Reply channel = send(tokenB, "POST", "/api/v1/events/channels", Map.of("key", RUN + "-chan",
                "name", "SL-45 channel", "direction", "INBOUND", "transport", "REST",
                "eventDefinitionKeys", List.of(RUN + "-evdef"), "active", true), Map.of());
        assertEquals(201, channel.status, "tenant B's own event channel is created: " + channel.body);
        assertEquals(RUN + "-chan", JSON.readTree(channel.body).path("key").asText(), channel.body);
        taskA = processEngine.getTaskService().createTaskQuery().tenantIdIn(A).processInstanceBusinessKey(BK_A)
                .singleResult().getId();
        taskB = processEngine.getTaskService().createTaskQuery().processInstanceId(piB).singleResult().getId();
        taskBrest = processEngine.getTaskService().createTaskQuery().processInstanceId(piBrest).singleResult().getId();
        reserved.addAll(List.of(SECRET_B, BK_B_REST, piBrest, taskBrest));
        tokenA = bearer(A, "sl45-a-admin", ADMIN_GROUPS);
        engineAdminA = bearer(A, "sl45-a-engine", List.of("sangam-engine-admin", "sangam-user"));
        harvestIdsOfB();
        before = quiescentSnapshotOfB();
        recording = true; // from here on every request is tenant A's
    }

    // ------------------------------------------------------------------ positive controls (the tokens work)

    @Test
    @Order(1)
    void tenantASeesItsOwnObjects() throws Exception {
        Reply own = send(tokenA, "GET", "/api/v1/tasks", null, Map.of());
        assertEquals(200, own.status, own.body);
        assertTrue(own.body.contains(taskA), "tenant A's task list shows its own task: " + own.body);
        Reply rest = send(engineAdminA, "GET", "/engine-rest/task?processDefinitionKey=" + KEY, null, Map.of());
        assertEquals(200, rest.status, rest.body);
        assertTrue(rest.body.contains(taskA), "tenant A's engine admin sees its own task: " + rest.body);
        assertTrue(idsB.values().stream().mapToInt(Set::size).sum() > 10, "tenant B has harvested ids: " + idsB);
        assertTrue(valuesOfB.containsAll(reserved) && valuesOfB.size() > 20, "tenant B's values: " + valuesOfB);
    }

    // ------------------------------------------------------------------ the Sangam API sweep

    @Test
    @Order(2)
    void everySangamEndpointRefusesTenantBObjects() throws Exception {
        Map<String, String> spoof = Map.of("X-Sangam-Tenant", B, "X-Tenant-Id", B, "X-Tenant", B);
        String spoofQuery = "tenant=" + B + "&tenantId=" + B + "&tenantIdIn=" + B + "&tenant_id=" + B
                + "&businessKey=" + BK_B + "&processInstanceId=" + piB + "&taskId=" + taskB + "&id=" + taskB;
        int calls = 0;
        for (Map.Entry<RequestMappingInfo, ?> e : mappings.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = e.getKey();
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods().isEmpty()
                    ? Set.of(RequestMethod.GET) : info.getMethodsCondition().getMethods();
            boolean stream = info.getProducesCondition().getProducibleMediaTypes().contains(MediaType.TEXT_EVENT_STREAM);
            for (String pattern : info.getPatternValues()) {
                if (!pattern.startsWith("/api/") || pattern.startsWith("/api/v1/admin/cell/")) {
                    continue; // cell routes need the service role CELL_ADMIN, which no tenant holds
                }
                for (RequestMethod m : methods) {
                    for (String path : substitutions(pattern)) {
                        boolean read = m == RequestMethod.GET || m == RequestMethod.HEAD;
                        Object body = read ? null : batchBody();
                        String op = m + " " + pattern;
                        check(op + " (guessed id)", send(tokenA, m.name(), path, body, Map.of(), stream));
                        check(op + " (spoofed tenant header + query)", send(tokenA, m.name(),
                                path + (path.contains("?") ? "&" : "?") + spoofQuery, body, spoof, stream));
                        calls += 2;
                    }
                }
            }
        }
        assertTrue(calls > 200, "the sweep reached the API: " + calls + " calls");
        Reply own = send(tokenA, "GET", "/api/v1/tasks", null, Map.of());
        assertTrue(own.status == 200 && own.body.contains(taskA),
                "tenant A still works after the sweep (no false pass): " + own.status + " " + abbreviate(own.body));
        assertNoLeaks();
    }

    @Test
    @Order(3)
    void correlationAndSignalsStayInTenantA() throws Exception {
        for (Object body : List.of(Map.of("messageName", MSG, "businessKey", BK_B),
                Map.of("messageName", MSG, "processInstanceId", piB), Map.of("messageName", MSG, "all", true))) {
            Reply r = send(tokenA, "POST", "/api/v1/messages", body, Map.of("X-Sangam-Tenant", B));
            if (r.status / 100 == 2 && r.body.contains(piB)) {
                leaks.add("POST /api/v1/messages (correlate) -> B instance " + r.body);
            }
        }
        send(tokenA, "POST", "/api/v1/signals", Map.of("name", SIG), Map.of("X-Sangam-Tenant", B));
        send(tokenA, "POST", "/api/v1/signals", Map.of("name", SIG, "executionId", piB), Map.of());
        assertNoLeaks();
    }

    // ------------------------------------------------------------------ /engine-rest

    @Test
    @Order(4)
    void engineRestIsScopedToTheCallersTenant() throws Exception {
        String[] lists = {"task", "process-instance", "execution", "variable-instance", "process-definition",
                "deployment", "case-instance", "case-execution", "case-definition", "decision-definition",
                "event-subscription", "incident", "job", "batch", "history/process-instance", "history/task",
                "history/activity-instance", "history/variable-instance", "history/detail", "history/case-instance",
                "history/identity-link-log", "history/user-operation", "history/job-log", "history/incident"};
        for (String l : lists) {
            for (String q : List.of("", "?tenantIdIn=" + B, "?processInstanceBusinessKey=" + BK_B)) {
                check("GET /engine-rest/" + l + q, send(engineAdminA, "GET", "/engine-rest/" + l + q, null, Map.of()));
            }
        }
        String defB = processEngine.getRepositoryService().createProcessDefinitionQuery().processDefinitionKey(KEY)
                .tenantIdIn(B).singleResult().getId();
        String depB = processEngine.getRepositoryService().createDeploymentQuery().tenantIdIn(B).list().get(0).getId();
        // one object of tenant B by its id, or creating one in tenant B: refused
        String[][] onB = {
                {"GET", "/engine-rest/task/" + taskB, null},
                {"GET", "/engine-rest/task/" + taskB + "/variables", null},
                {"GET", "/engine-rest/process-instance/" + piB, null},
                {"GET", "/engine-rest/process-instance/" + piB + "/variables", null},
                {"GET", "/engine-rest/history/process-instance/" + piB, null},
                {"GET", "/engine-rest/process-definition/" + defB + "/xml", null},
                {"GET", "/engine-rest/process-definition/" + defB + "/static-called-process-definitions", null},
                {"GET", "/engine-rest/deployment/" + depB + "/resources", null},
                {"POST", "/engine-rest/task/" + taskB + "/claim", "{\"userId\":\"sl45-a-engine\"}"},
                {"POST", "/engine-rest/task/" + taskB + "/complete", "{}"},
                {"POST", "/engine-rest/task/" + taskB + "/comment/create", "{\"message\":\"x\"}"},
                {"PUT", "/engine-rest/process-instance/" + piB + "/variables/note", "{\"value\":\"x\",\"type\":\"String\"}"},
                {"PUT", "/engine-rest/process-instance/" + piB + "/suspended", "{\"suspended\":true}"},
                {"PUT", "/engine-rest/process-instance/suspended",
                        "{\"processDefinitionId\":\"" + defB + "\",\"suspended\":true}"},
                {"PUT", "/engine-rest/process-definition/" + defB + "/suspended", "{\"suspended\":true}"},
                {"POST", "/engine-rest/process-definition/key/" + KEY + "/tenant-id/" + B + "/start", "{}"},
                {"POST", "/engine-rest/process-definition/" + defB + "/start", "{}"},
                {"DELETE", "/engine-rest/deployment/" + depB + "?cascade=true", null},
        };
        Map<String, Rows> last = snapshotOfB();
        for (String[] c : onB) {
            Reply r = send(engineAdminA, c[0], c[1], c[2], Map.of());
            check(c[0] + " " + c[1], r);
            if (r.status / 100 == 2) {
                leaks.add(c[0] + " " + c[1] + " -> " + r.status + " (accepted on tenant B's object) " + abbreviate(r.body));
            }
            last = effectOnB(c[0] + " " + c[1], last);
        }
        // selectors (a key both tenants deployed, a message or signal name, a business key, a tenant filter): served
        // inside tenant A, where they may succeed (rerun 4: the key suspension and the signal broadcast acted on A's
        // own instance); they must carry nothing of B and change nothing of B (checked after every call here, and again
        // by tenantBRowsAreUnchanged)
        String[][] selectors = {
                {"PUT", "/engine-rest/process-instance/suspended",
                        "{\"processDefinitionKey\":\"" + KEY + "\",\"suspended\":true}"},
                {"PUT", "/engine-rest/process-definition/key/" + KEY + "/suspended", "{\"suspended\":true}"},
                {"POST", "/engine-rest/message", "{\"messageName\":\"" + MSG + "\",\"businessKey\":\"" + BK_B + "\"}"},
                {"POST", "/engine-rest/message", "{\"messageName\":\"" + MSG + "\",\"tenantId\":\"" + B
                        + "\",\"all\":true}"},
                {"POST", "/engine-rest/signal", "{\"name\":\"" + SIG + "\"}"},
                {"POST", "/engine-rest/signal", "{\"name\":\"" + SIG + "\",\"tenantId\":\"" + B + "\"}"},
        };
        for (String[] c : selectors) {
            check(c[0] + " " + c[1] + " " + c[2], send(engineAdminA, c[0], c[1], c[2], Map.of()));
            last = effectOnB(c[0] + " " + c[1] + " " + c[2], last);
        }
        // async batches naming B's instance: Operaton accepts the ids of a batch without looking them up (a nonexistent
        // id too) and acts on them in the batch's jobs, so they are judged by their effect once they have run (rerun 4:
        // they ran without any tenant check and suspended B's instance)
        String[][] batches = {
                {"POST", "/engine-rest/process-instance/delete", "{\"processInstanceIds\":[\"" + piB + "\"]}"},
                {"POST", "/engine-rest/process-instance/suspended-async",
                        "{\"processInstanceIds\":[\"" + piB + "\"],\"suspended\":true}"},
        };
        List<String> batchIds = new ArrayList<>();
        for (String[] c : batches) {
            Reply r = send(engineAdminA, c[0], c[1], c[2], Map.of());
            check(c[0] + " " + c[1], r);
            if (r.status / 100 == 2) {
                batchIds.add(JSON.readTree(r.body).path("id").asText());
            }
        }
        settle(batchIds);
        if (processEngine.getRuntimeService().createProcessInstanceQuery().processInstanceId(piB).active().count() != 1) {
            leaks.add("tenant A's /engine-rest batches " + batchIds + " deleted or suspended tenant B's instance " + piB);
        }
        last = effectOnB("tenant A's /engine-rest batches " + batchIds + " once run", last);
        // a deployment into tenant B through the multipart API
        Reply dep = deployMultipart(engineAdminA, B);
        if (dep.status / 100 == 2) {
            leaks.add("POST /engine-rest/deployment/create tenant-id=B -> " + dep.status + " " + abbreviate(dep.body));
        }
        effectOnB("POST /engine-rest/deployment/create tenant-id=B", last);
        assertNoLeaks();
    }

    /** A leak when tenant B's rows differ from {@code baseline} (the effect of {@code op}); the rows now. */
    private Map<String, Rows> effectOnB(String op, Map<String, Rows> baseline) throws SQLException {
        Map<String, Rows> now = snapshotOfB();
        List<String> changed = changes(baseline, now);
        if (!changed.isEmpty()) {
            leaks.add(op + " changed tenant B's rows: " + changed);
        }
        return now;
    }

    /** Every table whose rows of tenant B differ, with the rows removed and added (a changed row is both). */
    private static List<String> changes(Map<String, Rows> was, Map<String, Rows> now) {
        Set<String> tables = new java.util.TreeSet<>(was.keySet());
        tables.addAll(now.keySet());
        List<String> changed = new ArrayList<>();
        for (String t : tables) {
            Rows a = was.getOrDefault(t, Rows.NONE);
            Rows b = now.getOrDefault(t, Rows.NONE);
            if (!a.counts().equals(b.counts())) {
                changed.add(t + ": " + a.size() + " -> " + b.size() + " rows; removed " + a.minus(b) + "; added "
                        + b.minus(a));
            }
        }
        return changed;
    }

    // ------------------------------------------------------------------ oracle 2: tenant B's rows are untouched

    @Test
    @Order(99)
    void tenantBRowsAreUnchanged() throws Exception {
        assertEquals(List.of(), changes(before, snapshotOfB()), "tenant A's calls changed tenant B's rows");
        assertEquals(1, processEngine.getTaskService().createTaskQuery().taskId(taskB).taskUnassigned().active().count(),
                "tenant B's task is still open, unclaimed and active");
    }

    // ------------------------------------------------------------------ support

    /**
     * Oracle 1 on a successful response: an object stamped with B's tenant id, or a value of B that tenant A never sent.
     */
    private void check(String op, Reply r) {
        if (r.status / 100 != 2) {
            return;
        }
        String body = r.body;
        List<String> found = new ArrayList<>();
        Matcher member = TENANT_MEMBER.matcher(body);
        while (member.find()) {
            if (member.group(2).contains("\"" + B + "\"")) {
                found.add("an object of tenant B (" + member.group(1) + ")");
                break;
            }
        }
        for (String v : valuesOfB) {
            if (body.contains(v) && !sentByA.contains(v)) {
                found.add(v.equals(B) ? "B's tenant id" : v.equals(BK_B) || v.equals(BK_B_REST) ? "B's business key " + v
                        : v.equals(SECRET_B) ? "B's secret variable value" : origin(v));
            }
        }
        if (!found.isEmpty()) {
            leaks.add(op + " -> " + r.status + " carries " + found + " " + abbreviate(body));
        }
    }

    private String origin(String v) {
        for (Map.Entry<String, Set<String>> e : idsB.entrySet()) {
            if (e.getValue().contains(v)) {
                return e.getKey() + ":" + v;
            }
        }
        return "B's id " + v;
    }

    private void assertNoLeaks() {
        List<String> copy = List.copyOf(leaks);
        leaks.clear();
        assertEquals(List.of(), copy, "cross-tenant leaks");
    }

    /** Records which of B's values tenant A has now put into a request (it may get them back). */
    private void record(String... parts) {
        if (!recording) {
            return;
        }
        StringBuilder text = new StringBuilder();
        for (String p : parts) {
            if (p != null) {
                text.append(p).append('\n');
                try {
                    text.append(URLDecoder.decode(p, StandardCharsets.UTF_8)).append('\n');
                } catch (IllegalArgumentException notEncoded) {
                    // a body or header that is not URL-encoded is already recorded as is
                }
            }
        }
        String all = text.toString();
        for (String v : valuesOfB) {
            if (all.contains(v)) {
                assertTrue(!reserved.contains(v), "the test never names " + v + " as tenant A: " + abbreviate(all));
                sentByA.add(v);
            }
        }
    }

    /** Waits until tenant A's batches are seeded and none of their element jobs has a retry left. */
    private void settle(List<String> batchIds) throws InterruptedException {
        var mgmt = processEngine.getManagementService();
        long end = System.currentTimeMillis() + 120_000;
        while (true) {
            boolean settled = true;
            for (String id : batchIds) {
                Batch b = mgmt.createBatchQuery().batchId(id).singleResult();
                if (b != null) {
                    settled &= mgmt.createJobQuery().jobDefinitionId(b.getSeedJobDefinitionId()).count() == 0
                            && mgmt.createJobQuery().jobDefinitionId(b.getBatchJobDefinitionId()).withRetriesLeft()
                            .count() == 0;
                }
            }
            if (settled) {
                return;
            }
            if (System.currentTimeMillis() > end) {
                throw new AssertionError("tenant A's batches did not settle in 120 s: " + batchIds);
            }
            Thread.sleep(250);
        }
    }

    /** The pattern with tenant B's ids in its path variables (each id once), or the pattern itself without variables. */
    private List<String> substitutions(String pattern) {
        Matcher m = VAR.matcher(pattern);
        if (!m.find()) {
            return List.of(pattern);
        }
        Set<String> candidates = new LinkedHashSet<>(List.of(taskB, piB, B, BK_B, KEY, CASE_KEY, RUN + "-chan",
                RUN + "-evdef"));
        String[] tokens = pattern.toLowerCase(Locale.ROOT).replaceAll("\\{[^}]*}", "").split("[/\\-_]+");
        for (Map.Entry<String, Set<String>> e : idsB.entrySet()) {
            for (String t : tokens) {
                String stem = t.endsWith("s") ? t.substring(0, t.length() - 1) : t;
                if (stem.length() >= 4 && e.getKey().contains(stem)) {
                    e.getValue().stream().filter(this::probe).limit(2).forEach(candidates::add);
                }
            }
        }
        List<String> out = new ArrayList<>();
        for (String c : candidates) {
            StringBuilder b = new StringBuilder();
            Matcher v = VAR.matcher(pattern);
            while (v.find()) {
                String regex = v.group(2);
                String value = regex != null && !regex.isBlank() && !".+".equals(regex) && !regex.contains("\\")
                        ? regex.split("\\|")[0] : URLEncoder.encode(c, StandardCharsets.UTF_8);
                v.appendReplacement(b, Matcher.quoteReplacement(value));
            }
            v.appendTail(b);
            out.add(b.toString());
        }
        return out;
    }

    /** Whether a harvested id of B may be used as a probe: never one that holds a reserved value of B. */
    private boolean probe(String v) {
        return reserved.stream().noneMatch(v::contains);
    }

    /** A batch/bulk body naming tenant B's objects under the usual id keys. */
    private Map<String, Object> batchBody() {
        List<String> all = new ArrayList<>(List.of(taskB, piB));
        idsB.values().forEach(s -> s.stream().filter(this::probe).limit(1).forEach(all::add));
        Map<String, Object> b = new LinkedHashMap<>();
        for (String k : List.of("ids", "taskIds", "processInstanceIds", "instanceIds", "caseInstanceIds", "objectIds")) {
            b.put(k, all);
        }
        b.put("id", taskB);
        b.put("taskId", taskB);
        b.put("processInstanceId", piB);
        b.put("businessKey", BK_B);
        b.put("tenantId", B);
        b.put("tenant", B);
        b.put("reason", "SL-45 probe");
        return b;
    }

    record Reply(int status, String body) {
    }

    private Reply send(String token, String method, String path, Object body, Map<String, String> headers)
            throws Exception {
        return send(token, method, path, body, headers, false);
    }

    private Reply send(String token, String method, String path, Object body, Map<String, String> headers,
                       boolean stream) throws Exception {
        HttpRequest.Builder b;
        try {
            b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .timeout(Duration.ofSeconds(stream ? 3 : 30))
                    .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                    .header("Idempotency-Key", UUID.randomUUID().toString());
        } catch (IllegalArgumentException e) {
            return new Reply(0, "bad uri " + path);
        }
        headers.forEach(b::header);
        String payload = body == null ? null : body instanceof String s ? s : JSON.writeValueAsString(body);
        record(path, String.join("\n", headers.values()), payload);
        b.method(method, payload == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(payload));
        try {
            if (stream) {
                HttpResponse<InputStream> r = http.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream in = r.body()) {
                    byte[] buf = in.readNBytes(in.available());
                    return new Reply(r.statusCode(), new String(buf, StandardCharsets.UTF_8));
                }
            }
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Reply(r.statusCode(), r.body() == null ? "" : r.body());
        } catch (java.net.http.HttpTimeoutException e) {
            return new Reply(0, "timeout");
        }
    }

    private Reply deployMultipart(String token, String tenant) throws Exception {
        String boundary = "sl45-" + UUID.randomUUID();
        String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"deployment-name\"\r\n\r\nsl45\r\n"
                + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"tenant-id\"\r\n\r\n" + tenant + "\r\n"
                + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"data\"; filename=\"x.bpmn\"\r\n"
                + "Content-Type: application/xml\r\n\r\n" + bpmn().strip() + "\r\n--" + boundary + "--\r\n";
        record(body);
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + "/engine-rest/deployment/create")).header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString());
        return new Reply(r.statusCode(), r.body());
    }

    private static String abbreviate(String s) {
        return s == null ? "" : s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }

    /** Tables with a tenant column, as {table -> tenant column}. */
    private Map<String, String> tenantTables(Connection c) throws SQLException {
        Map<String, String> out = new TreeMap<>();
        // pg_catalog, not information_schema: the latter lists only columns the current role may see, and joining
        // tables to columns by name alone matched same-named tables of other schemas on the search path
        try (PreparedStatement ps = c.prepareStatement("SELECT quote_ident(n.nspname) || '.' || quote_ident(cl.relname),"
                + " quote_ident(a.attname) FROM pg_attribute a JOIN pg_class cl ON cl.oid = a.attrelid"
                + " JOIN pg_namespace n ON n.oid = cl.relnamespace WHERE cl.relkind IN ('r', 'p')"
                + " AND n.nspname = current_schema() AND a.attnum > 0 AND NOT a.attisdropped"
                + " AND lower(a.attname) IN ('tenant_id', 'tenant_id_', 'tenant') ORDER BY 1, 2");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.putIfAbsent(rs.getString(1), rs.getString(2));
            }
        }
        return out;
    }

    /** The id-like columns of a table (quoted identifiers). */
    private static List<String> idColumns(Connection c, String table) throws SQLException {
        List<String> idCols = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT quote_ident(attname) FROM pg_attribute"
                + " WHERE attrelid = CAST(? AS regclass) AND attnum > 0 AND NOT attisdropped"
                + " AND (lower(attname) IN ('id', 'id_', 'key', 'key_', 'code') OR lower(attname) LIKE '%\\_id'"
                + " OR lower(attname) LIKE '%\\_id\\_') AND lower(attname) NOT LIKE 'tenant%'")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    idCols.add(rs.getString(1));
                }
            }
        }
        return idCols;
    }

    /**
     * Tenant B's identifiers: the values of id-like columns in rows of tenant B that no row of another owner (tenant A,
     * the shared tenant {@code NULL}, any other tenant) holds in an id-like column of any tenant-scoped table. A value
     * another owner holds too can never show that a response carries B's data; rerun 4 reported A's own objects for
     * exactly such values (both tenants' copy of the process key {@code KEY} and case key, the global group
     * {@code sangam-user} of every candidate-group link, the bootstrap policy id every tenant gets).
     */
    private void harvestIdsOfB() throws SQLException {
        Map<String, Map<String, Set<String>>> ofB = new TreeMap<>();
        Set<String> all = new LinkedHashSet<>();
        try (Connection c = dataSource.getConnection()) {
            Map<String, String> tables = tenantTables(c);
            Map<String, List<String>> columns = new TreeMap<>();
            for (Map.Entry<String, String> t : tables.entrySet()) {
                columns.put(t.getKey(), idColumns(c, t.getKey()));
                for (String col : columns.get(t.getKey())) {
                    try (PreparedStatement ps = c.prepareStatement("SELECT DISTINCT CAST(" + col + " AS VARCHAR) FROM "
                            + t.getKey() + " WHERE CAST(" + t.getValue() + " AS VARCHAR) = ? AND " + col + " IS NOT NULL")) {
                        ps.setString(1, B);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                String v = rs.getString(1);
                                if (v != null && v.length() >= 6 && !v.equals(B)) {
                                    ofB.computeIfAbsent(t.getKey().toLowerCase(Locale.ROOT), k -> new TreeMap<>())
                                            .computeIfAbsent(col, k -> new LinkedHashSet<>()).add(v);
                                    all.add(v);
                                }
                            }
                        }
                    }
                }
            }
            Set<String> shared = new HashSet<>();
            Array candidates = c.createArrayOf("varchar", all.toArray());
            for (Map.Entry<String, String> t : tables.entrySet()) {
                for (String col : columns.get(t.getKey())) {
                    try (PreparedStatement ps = c.prepareStatement("SELECT DISTINCT CAST(" + col + " AS VARCHAR) FROM "
                            + t.getKey() + " WHERE (" + t.getValue() + " IS NULL OR CAST(" + t.getValue()
                            + " AS VARCHAR) <> ?) AND CAST(" + col + " AS VARCHAR) = ANY (?)")) {
                        ps.setString(1, B);
                        ps.setArray(2, candidates);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                shared.add(rs.getString(1));
                            }
                        }
                    }
                }
            }
            for (Map.Entry<String, Map<String, Set<String>>> t : ofB.entrySet()) {
                for (Set<String> values : t.getValue().values()) {
                    values.stream().filter(v -> !shared.contains(v)).limit(3)
                            .forEach(v -> idsB.computeIfAbsent(t.getKey(), k -> new LinkedHashSet<>()).add(v));
                }
            }
            valuesOfB.addAll(List.of(B, BK_B, BK_B_REST, SECRET_B));
            all.stream().filter(v -> !shared.contains(v) && v.length() >= 10).forEach(valuesOfB::add);
            valuesOfB.addAll(reserved);
        }
    }

    /** Tenant B's rows of one table: how often each row (by the md5 of its full text) occurs, and an excerpt of it. */
    record Rows(Map<String, Integer> counts, Map<String, String> excerpts) {
        static final Rows NONE = new Rows(Map.of(), Map.of());

        int size() {
            return counts.values().stream().mapToInt(Integer::intValue).sum();
        }

        /** The rows of this snapshot that {@code other} does not have (with multiplicity), as excerpts. */
        List<String> minus(Rows other) {
            List<String> out = new ArrayList<>();
            counts.forEach((md5, n) -> {
                for (int i = other.counts.getOrDefault(md5, 0); i < n; i++) {
                    out.add(excerpts.get(md5));
                }
            });
            return out;
        }
    }

    /** {table -> rows} of tenant B in every tenant-scoped table: any added, removed or changed row is a difference. */
    private Map<String, Rows> snapshotOfB() throws SQLException {
        Map<String, Rows> out = new TreeMap<>();
        try (Connection c = dataSource.getConnection()) {
            for (Map.Entry<String, String> t : tenantTables(c).entrySet()) {
                Map<String, Integer> counts = new TreeMap<>();
                Map<String, String> excerpts = new TreeMap<>();
                try (PreparedStatement ps = c.prepareStatement("SELECT md5(x::text), left(x::text, 400) FROM " + t.getKey()
                        + " x WHERE CAST(x." + t.getValue() + " AS VARCHAR) = ?")) {
                    ps.setString(1, B);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            counts.merge(rs.getString(1), 1, Integer::sum);
                            excerpts.putIfAbsent(rs.getString(1), rs.getString(2));
                        }
                    }
                }
                out.put(t.getKey().toLowerCase(Locale.ROOT), new Rows(counts, excerpts));
            }
        }
        return out;
    }

    /** Tenant B's snapshot once its own background work (outbox relay, history) has settled. */
    private Map<String, Rows> quiescentSnapshotOfB() throws Exception {
        Map<String, Rows> last = snapshotOfB();
        long end = System.currentTimeMillis() + 90_000;
        while (System.currentTimeMillis() < end) {
            Thread.sleep(5_000);
            Map<String, Rows> now = snapshotOfB();
            if (counts(now).equals(counts(last))) {
                return now;
            }
            last = now;
        }
        throw new AssertionError("tenant B's rows never settled: " + counts(last));
    }

    private static Map<String, Map<String, Integer>> counts(Map<String, Rows> snapshot) {
        Map<String, Map<String, Integer>> out = new TreeMap<>();
        snapshot.forEach((t, r) -> out.put(t, r.counts()));
        return out;
    }
}
