// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.isolation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
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
 * with a spoofed tenant header and tenant query parameters. Two oracles: (1) no successful response carries tenant B's
 * tenant id, business key, secret variable value or an id of B that the request did not itself contain; (2) a content
 * hash of every tenant-B row of every tenant-scoped table is unchanged afterwards, so no read, claim, completion,
 * correlation, signal, suspension, deletion, variable write, comment or audit row reached tenant B. {@code /engine-rest}
 * is exercised the same way with a tenant-A engine administrator.
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
    static final String SECRET_B = RUN + "-SECRET-B";
    static final ObjectMapper JSON = new ObjectMapper();
    static final Pattern VAR = Pattern.compile("\\{\\*?([A-Za-z0-9_]+)(?::([^}]*))?}");

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
    @Value("${local.server.port}")
    int port;

    String tokenA;
    String engineAdminA;
    String taskA;
    String taskB;
    String piB;
    /** Tenant B's ids of every tenant-scoped table, by table. */
    final Map<String, Set<String>> idsB = new TreeMap<>();
    /** Every leak found, as "surface operation -> detail". */
    final List<String> leaks = new ArrayList<>();
    Map<String, String> before;

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
        send(tokenB, "POST", "/api/v1/process-instances",
                Map.of("key", KEY, "businessKey", BK_B + "-rest", "variables", Map.of("note", SECRET_B)), Map.of());
        send(tokenB, "POST", "/api/v1/events/definitions", Map.of("key", RUN + "-evdef", "name", "SL-45 event",
                "startProcessKey", KEY, "businessKeyPointer", "/billId"), Map.of());
        send(tokenB, "POST", "/api/v1/events/channels", Map.of("key", RUN + "-chan", "name", "SL-45 channel",
                "direction", "INBOUND", "transport", "REST", "eventDefinitionKeys", List.of(RUN + "-evdef"),
                "active", true), Map.of());
        taskA = processEngine.getTaskService().createTaskQuery().tenantIdIn(A).processInstanceBusinessKey(BK_A)
                .singleResult().getId();
        taskB = processEngine.getTaskService().createTaskQuery().processInstanceId(piB).singleResult().getId();
        tokenA = bearer(A, "sl45-a-admin", ADMIN_GROUPS);
        engineAdminA = bearer(A, "sl45-a-engine", List.of("sangam-engine-admin", "sangam-user"));
        harvestIdsOfB();
        before = quiescentSnapshotOfB();
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
                        check(op + " (guessed id)", path, send(tokenA, m.name(), path, body, Map.of(), stream));
                        check(op + " (spoofed tenant header + query)", path, send(tokenA, m.name(),
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
                check("GET /engine-rest/" + l + q, "", send(engineAdminA, "GET", "/engine-rest/" + l + q, null, Map.of()));
            }
        }
        String defB = processEngine.getRepositoryService().createProcessDefinitionQuery().processDefinitionKey(KEY)
                .tenantIdIn(B).singleResult().getId();
        String depB = processEngine.getRepositoryService().createDeploymentQuery().tenantIdIn(B).list().get(0).getId();
        String[][] calls = {
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
                        "{\"processDefinitionKey\":\"" + KEY + "\",\"suspended\":true}"},
                {"PUT", "/engine-rest/process-definition/key/" + KEY + "/suspended", "{\"suspended\":true}"},
                {"POST", "/engine-rest/process-instance/delete", "{\"processInstanceIds\":[\"" + piB + "\"]}"},
                {"POST", "/engine-rest/process-instance/suspended-async",
                        "{\"processInstanceIds\":[\"" + piB + "\"],\"suspended\":true}"},
                {"POST", "/engine-rest/message", "{\"messageName\":\"" + MSG + "\",\"businessKey\":\"" + BK_B + "\"}"},
                {"POST", "/engine-rest/message", "{\"messageName\":\"" + MSG + "\",\"tenantId\":\"" + B
                        + "\",\"all\":true}"},
                {"POST", "/engine-rest/signal", "{\"name\":\"" + SIG + "\"}"},
                {"POST", "/engine-rest/signal", "{\"name\":\"" + SIG + "\",\"tenantId\":\"" + B + "\"}"},
                {"POST", "/engine-rest/process-definition/key/" + KEY + "/tenant-id/" + B + "/start", "{}"},
                {"POST", "/engine-rest/process-definition/" + defB + "/start", "{}"},
                {"DELETE", "/engine-rest/deployment/" + depB + "?cascade=true", null},
        };
        for (String[] c : calls) {
            Reply r = send(engineAdminA, c[0], c[1], c[2], Map.of());
            check(c[0] + " " + c[1], c[1], r);
            if (r.status / 100 == 2) {
                leaks.add(c[0] + " " + c[1] + " -> " + r.status + " (accepted on tenant B's object) " + abbreviate(r.body));
            }
        }
        // a deployment into tenant B through the multipart API
        Reply dep = deployMultipart(engineAdminA, B);
        if (dep.status / 100 == 2) {
            leaks.add("POST /engine-rest/deployment/create tenant-id=B -> " + dep.status + " " + abbreviate(dep.body));
        }
        assertNoLeaks();
    }

    // ------------------------------------------------------------------ oracle 2: tenant B's rows are untouched

    @Test
    @Order(99)
    void tenantBRowsAreUnchanged() throws Exception {
        Map<String, String> after = snapshotOfB();
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, String> e : before.entrySet()) {
            if (!e.getValue().equals(after.get(e.getKey()))) {
                changed.add(e.getKey() + ": " + e.getValue() + " -> " + after.get(e.getKey()));
            }
        }
        for (String t : after.keySet()) {
            if (!before.containsKey(t) && !after.get(t).startsWith("0/")) {
                changed.add(t + ": new rows of tenant B " + after.get(t));
            }
        }
        assertEquals(List.of(), changed, "tenant A's calls changed tenant B's rows");
        assertEquals(1, processEngine.getTaskService().createTaskQuery().taskId(taskB).taskUnassigned().active().count(),
                "tenant B's task is still open, unclaimed and active");
    }

    // ------------------------------------------------------------------ support

    private void check(String op, String requestPath, Reply r) {
        if (r.status / 100 != 2) {
            return;
        }
        String body = r.body;
        List<String> found = new ArrayList<>();
        for (String marker : List.of(B, BK_B, SECRET_B)) {
            if (body.contains(marker)) {
                found.add(marker);
            }
        }
        for (Map.Entry<String, Set<String>> e : idsB.entrySet()) {
            for (String id : e.getValue()) {
                if (id.length() >= 10 && body.contains(id) && !requestPath.contains(id)) {
                    found.add(e.getKey() + ":" + id);
                }
            }
        }
        if (!found.isEmpty()) {
            leaks.add(op + " -> " + r.status + " carries " + found + " " + abbreviate(body));
        }
    }

    private void assertNoLeaks() {
        List<String> copy = List.copyOf(leaks);
        leaks.clear();
        assertEquals(List.of(), copy, "cross-tenant leaks");
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
                    e.getValue().stream().limit(2).forEach(candidates::add);
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

    /** A batch/bulk body naming tenant B's objects under the usual id keys. */
    private Map<String, Object> batchBody() {
        List<String> all = new ArrayList<>(List.of(taskB, piB));
        idsB.values().forEach(s -> s.stream().limit(1).forEach(all::add));
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

    private void harvestIdsOfB() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            for (Map.Entry<String, String> t : tenantTables(c).entrySet()) {
                List<String> idCols = new ArrayList<>();
                try (PreparedStatement ps = c.prepareStatement("SELECT quote_ident(attname) FROM pg_attribute"
                        + " WHERE attrelid = CAST(? AS regclass) AND attnum > 0 AND NOT attisdropped"
                        + " AND (lower(attname) IN ('id', 'id_', 'key', 'key_', 'code') OR lower(attname) LIKE '%\\_id'"
                        + " OR lower(attname) LIKE '%\\_id\\_') AND lower(attname) NOT LIKE 'tenant%'")) {
                    ps.setString(1, t.getKey());
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            idCols.add(rs.getString(1));
                        }
                    }
                }
                for (String col : idCols) {
                    try (PreparedStatement ps = c.prepareStatement("SELECT DISTINCT CAST(" + col + " AS VARCHAR) FROM "
                            + t.getKey() + " WHERE CAST(" + t.getValue() + " AS VARCHAR) = ? LIMIT 3")) {
                        ps.setString(1, B);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                String v = rs.getString(1);
                                if (v != null && v.length() >= 6 && !v.equals(B)) {
                                    idsB.computeIfAbsent(t.getKey().toLowerCase(Locale.ROOT), k -> new LinkedHashSet<>()).add(v);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** {table -> "count/md5"} of tenant B's rows in every tenant-scoped table. */
    private Map<String, String> snapshotOfB() throws SQLException {
        Map<String, String> out = new TreeMap<>();
        try (Connection c = dataSource.getConnection()) {
            for (Map.Entry<String, String> t : tenantTables(c).entrySet()) {
                try (PreparedStatement ps = c.prepareStatement("SELECT count(*), coalesce(md5(string_agg(md5(x::text), ','"
                        + " ORDER BY md5(x::text))), '-') FROM " + t.getKey() + " x WHERE CAST(x." + t.getValue() + " AS VARCHAR) = ?")) {
                    ps.setString(1, B);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        out.put(t.getKey().toLowerCase(Locale.ROOT), rs.getLong(1) + "/" + rs.getString(2));
                    }
                }
            }
        }
        return out;
    }

    /** Tenant B's snapshot once its own background work (outbox relay, history) has settled. */
    private Map<String, String> quiescentSnapshotOfB() throws Exception {
        Map<String, String> last = snapshotOfB();
        long end = System.currentTimeMillis() + 90_000;
        while (System.currentTimeMillis() < end) {
            Thread.sleep(5_000);
            Map<String, String> now = snapshotOfB();
            if (now.equals(last)) {
                return now;
            }
            last = now;
        }
        throw new AssertionError("tenant B's rows never settled: " + last);
    }
}
