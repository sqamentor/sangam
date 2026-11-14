// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.temporal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.LookupResult;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.Json;
import org.sangam.integration.connector.SecretResolver;

/** Owner decision 163: start-and-await over the Temporal HTTP API against a loopback stub (no Temporal server). */
class TemporalConnectorTest {

    record Req(String method, String path, String query, Map<String, String> headers, String body) {
        JsonNode json() {
            return Json.tree(body);
        }
    }

    record Answer(int status, String body) {
    }

    private HttpServer server;
    private URI base;
    private final List<Req> requests = new CopyOnWriteArrayList<>();
    private volatile Function<Req, Answer> handler;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", x -> {
            Map<String, String> h = new LinkedHashMap<>();
            x.getRequestHeaders().forEach((k, v) -> h.put(k.toLowerCase(), String.join(",", v)));
            Req r = new Req(x.getRequestMethod(), x.getRequestURI().getRawPath(), x.getRequestURI().getRawQuery(), h,
                    new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requests.add(r);
            Answer a = handler.apply(r);
            byte[] b = a.body().getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().add("Content-Type", "application/json");
            x.sendResponseHeaders(a.status(), b.length == 0 ? -1 : b.length);
            if (b.length > 0) {
                x.getResponseBody().write(b);
            }
            x.close();
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private TemporalConnector connector(boolean shorthand, Duration await, String apiKeyRef, Set<String> allowed) {
        return new TemporalConnector(new TemporalConnector.Config("temporal-1", base, "ns1", HttpTransport.forSimulator(),
                SecretResolver.of(Map.of("secret://temporal/key", "test-api-key")), apiKeyRef, "OrderWorkflow", "orders",
                allowed, shorthand, Duration.ofSeconds(5), await, ConnectorProfile.SIMULATOR, null));
    }

    private TemporalConnector connector() {
        return connector(false, Duration.ofSeconds(5), null, Set.of());
    }

    private static ConnectorRequest request(UUID op, Map<String, Object> input) {
        return new ConnectorRequest("t1", op, "a1", 1, "root-1", null, TemporalConnector.OP_START, input, null);
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String completed(String resultJson) {
        return "{\"history\":{\"events\":[{\"eventId\":\"9\",\"eventType\":\"EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED\","
                + "\"workflowExecutionCompletedEventAttributes\":{\"result\":{\"payloads\":[{\"metadata\":{\"encoding\":\""
                + b64("json/plain") + "\"},\"data\":\"" + b64(resultJson) + "\"}]}}}]}}";
    }

    private static Answer startOk() {
        return new Answer(200, "{\"runId\":\"run-1\",\"started\":true}");
    }

    private static boolean isStart(Req r) {
        return r.method().equals("POST");
    }

    @Test
    void theStartIsKeyedByTheOperationIdAndTheResultIsReturned() {
        handler = r -> isStart(r) ? startOk() : new Answer(200, completed("{\"approved\":true}"));
        UUID op = UUID.randomUUID();
        ConnectorResult res = connector().invoke(request(op, Map.of("args", List.of(Map.of("order", 7), "x"))));
        ConnectorResult.Success s = assertInstanceOf(ConnectorResult.Success.class, res);
        assertEquals(Map.of("approved", true), s.output().get("result"));
        assertEquals(op.toString(), s.output().get("workflowId"));

        Req start = requests.get(0);
        assertEquals("/api/v1/namespaces/ns1/workflows/" + op, start.path());
        JsonNode b = start.json();
        assertEquals("OrderWorkflow", b.path("workflowType").path("name").asText());
        assertEquals("orders", b.path("taskQueue").path("name").asText());
        assertEquals(op.toString(), b.path("requestId").asText());
        assertEquals("WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE", b.path("workflowIdReusePolicy").asText());
        JsonNode p0 = b.path("input").path("payloads").get(0);
        assertEquals("json/plain", new String(Base64.getDecoder().decode(p0.path("metadata").path("encoding").asText()),
                StandardCharsets.UTF_8));
        assertEquals(7, Json.tree(Base64.getDecoder().decode(p0.path("data").asText())).path("order").asInt());

        Req await = requests.get(1);
        assertEquals("GET", await.method());
        assertEquals("/api/v1/namespaces/ns1/workflows/" + op + "/history", await.path());
        assertTrue(await.query().contains("waitNewEvent=true"), await.query());
        assertTrue(await.query().contains("HISTORY_EVENT_FILTER_TYPE_CLOSE_EVENT"), await.query());
        assertEquals(CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP, connector().descriptor().capabilityClass());
    }

    @Test
    void anAlreadyStartedWorkflowIsAwaitedNotStartedAgain() {
        handler = r -> isStart(r) ? new Answer(409, "{\"code\":6,\"message\":\"Workflow execution already started\"}")
                : new Answer(200, completed("42"));
        ConnectorResult res = connector().invoke(request(UUID.randomUUID(), Map.of("input", "x")));
        assertEquals(42, assertInstanceOf(ConnectorResult.Success.class, res).output().get("result"));
    }

    @Test
    void emptyLongPollPagesAreFollowedUntilTheCloseEvent() {
        AtomicInteger polls = new AtomicInteger();
        handler = r -> {
            if (isStart(r)) {
                return startOk();
            }
            return polls.incrementAndGet() < 3 ? new Answer(200, "{\"history\":{},\"nextPageToken\":\"tok+" + polls.get()
                    + "\"}") : new Answer(200, completed("\"done\""));
        };
        ConnectorResult res = connector().invoke(request(UUID.randomUUID(), Map.of()));
        assertEquals("done", assertInstanceOf(ConnectorResult.Success.class, res).output().get("result"));
        assertTrue(requests.get(2).query().contains("nextPageToken=tok%2B1"), requests.get(2).query());
    }

    @Test
    void aRunContinuedAsNewIsFollowed() {
        AtomicInteger polls = new AtomicInteger();
        handler = r -> {
            if (isStart(r)) {
                return startOk();
            }
            return polls.incrementAndGet() == 1 ? new Answer(200, "{\"history\":{\"events\":[{\"eventType\":"
                    + "\"WorkflowExecutionContinuedAsNew\"}]}}") : new Answer(200, completed("1"));
        };
        assertInstanceOf(ConnectorResult.Success.class, connector().invoke(request(UUID.randomUUID(), Map.of())));
        assertEquals(2, polls.get());
    }

    @Test
    void closedWithoutSuccessIsABusinessFailure() {
        handler = r -> isStart(r) ? startOk() : new Answer(200, "{\"history\":{\"events\":[{\"eventType\":"
                + "\"EVENT_TYPE_WORKFLOW_EXECUTION_FAILED\",\"workflowExecutionFailedEventAttributes\":{\"failure\":"
                + "{\"message\":\"credit check refused\"}}}]}}");
        ConnectorResult.BusinessFailure f = assertInstanceOf(ConnectorResult.BusinessFailure.class,
                connector().invoke(request(UUID.randomUUID(), Map.of())));
        assertEquals("TEMPORAL_WORKFLOW_FAILED", f.code());
        assertEquals("credit check refused", f.message());
        for (String[] k : new String[][]{{"EVENT_TYPE_WORKFLOW_EXECUTION_TIMED_OUT", "TEMPORAL_WORKFLOW_TIMED_OUT"},
                {"EVENT_TYPE_WORKFLOW_EXECUTION_CANCELED", "TEMPORAL_WORKFLOW_CANCELED"},
                {"EVENT_TYPE_WORKFLOW_EXECUTION_TERMINATED", "TEMPORAL_WORKFLOW_TERMINATED"}}) {
            handler = r -> isStart(r) ? startOk() : new Answer(200, "{\"history\":{\"events\":[{\"eventType\":\""
                    + k[0] + "\"}]}}");
            assertEquals(k[1], assertInstanceOf(ConnectorResult.BusinessFailure.class,
                    connector().invoke(request(UUID.randomUUID(), Map.of()))).code());
        }
    }

    @Test
    void aWorkflowStillRunningAtTheDeadlineIsATransientFailureWithAnEffect() {
        handler = r -> isStart(r) ? startOk() : new Answer(200, "{\"history\":{}}");
        ConnectorResult res = connector(false, Duration.ofMillis(300), null, Set.of())
                .invoke(request(UUID.randomUUID(), Map.of()));
        ConnectorResult.TransientFailure t = assertInstanceOf(ConnectorResult.TransientFailure.class, res);
        assertFalse(t.noEffect(), "the workflow was started");
    }

    @Test
    void startRefusalsAreMappedAndInvalidInputSendsNothing() {
        handler = r -> new Answer(400, "{\"code\":3,\"message\":\"bad task queue\"}");
        assertEquals("TEMPORAL_INVALID_REQUEST", assertInstanceOf(ConnectorResult.BusinessFailure.class,
                connector().invoke(request(UUID.randomUUID(), Map.of()))).code());
        handler = r -> new Answer(503, "{}");
        assertTrue(assertInstanceOf(ConnectorResult.TransientFailure.class,
                connector().invoke(request(UUID.randomUUID(), Map.of()))).noEffect());
        int before = requests.size();
        TemporalConnector limited = connector(false, Duration.ofSeconds(5), null, Set.of("OrderWorkflow"));
        assertEquals("INVALID_REQUEST", assertInstanceOf(ConnectorResult.BusinessFailure.class,
                limited.invoke(request(UUID.randomUUID(), Map.of("workflowType", "DropTables")))).code());
        assertEquals("INVALID_REQUEST", assertInstanceOf(ConnectorResult.BusinessFailure.class,
                limited.invoke(request(UUID.randomUUID(), Map.of("args", "not a list")))).code());
        ConnectorRequest other = new ConnectorRequest("t1", UUID.randomUUID(), "a1", 1, "r", null, "signal", Map.of(), null);
        assertEquals("UNSUPPORTED_OPERATION", assertInstanceOf(ConnectorResult.BusinessFailure.class,
                limited.invoke(other)).code());
        assertEquals(before, requests.size(), "refused input sends nothing");
    }

    @Test
    void shorthandPayloadsArePlainJsonValuesBothWays() {
        handler = r -> isStart(r) ? startOk() : new Answer(200, "{\"history\":{\"events\":[{\"eventType\":"
                + "\"EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED\",\"workflowExecutionCompletedEventAttributes\":"
                + "{\"result\":[{\"ok\":1}]}}]}}");
        ConnectorResult res = connector(true, Duration.ofSeconds(5), null, Set.of())
                .invoke(request(UUID.randomUUID(), Map.of("input", Map.of("order", 7))));
        assertEquals(Map.of("ok", 1), assertInstanceOf(ConnectorResult.Success.class, res).output().get("result"));
        assertEquals(7, requests.get(0).json().path("input").get(0).path("order").asInt());
    }

    @Test
    void theApiKeyIsABearerHeaderFromItsReference() {
        handler = r -> isStart(r) ? startOk() : new Answer(200, completed("1"));
        connector(false, Duration.ofSeconds(5), "secret://temporal/key", Set.of()).invoke(request(UUID.randomUUID(), Map.of()));
        assertEquals("Bearer test-api-key", requests.get(0).headers().get("authorization"));
        int before = requests.size();
        ConnectorResult r = connector(false, Duration.ofSeconds(5), "secret://temporal/missing", Set.of())
                .invoke(request(UUID.randomUUID(), Map.of()));
        assertTrue(assertInstanceOf(ConnectorResult.TransientFailure.class, r).noEffect());
        assertEquals(before, requests.size(), "nothing is sent without the key");
    }

    @Test
    void theLookupReadsTheCloseEventWithoutWaiting() {
        UUID op = UUID.randomUUID();
        handler = r -> new Answer(404, "{\"code\":5,\"message\":\"workflow not found\"}");
        assertInstanceOf(LookupResult.NotFound.class, connector().lookup(request(op, Map.of())));
        assertFalse(requests.get(0).query().contains("waitNewEvent"), requests.get(0).query());
        handler = r -> new Answer(200, "{\"history\":{\"events\":[]}}");
        assertInstanceOf(LookupResult.Unavailable.class, connector().lookup(request(op, Map.of())));
        handler = r -> new Answer(200, completed("{\"x\":2}"));
        LookupResult.Found ok = assertInstanceOf(LookupResult.Found.class, connector().lookup(request(op, Map.of())));
        assertTrue(ok.succeeded());
        assertEquals(Map.of("x", 2), ok.output().get("result"));
        handler = r -> new Answer(200, "{\"history\":{\"events\":[{\"eventType\":\"EVENT_TYPE_WORKFLOW_EXECUTION_TERMINATED\"}]}}");
        LookupResult.Found failed = assertInstanceOf(LookupResult.Found.class, connector().lookup(request(op, Map.of())));
        assertEquals("TEMPORAL_WORKFLOW_TERMINATED", failed.failureCode());
        handler = r -> new Answer(503, "{}");
        assertInstanceOf(LookupResult.Unavailable.class, connector().lookup(request(op, Map.of())));
    }

    @Test
    void closeEventKindsAreReadInBothEnumSpellings() {
        assertEquals("COMPLETED", TemporalConnector.kind(Json.tree("{\"eventType\":\"EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED\"}")));
        assertEquals("COMPLETED", TemporalConnector.kind(Json.tree("{\"event_type\":\"WorkflowExecutionCompleted\"}")));
        assertNull(TemporalConnector.kind(Json.tree("{\"eventType\":\"EVENT_TYPE_WORKFLOW_EXECUTION_STARTED\"}")));
        assertNull(TemporalConnector.kind(Json.tree("{\"eventType\":\"EVENT_TYPE_ACTIVITY_TASK_COMPLETED\"}")));
        assertNull(TemporalConnector.decodeFirst(Json.tree("{\"payloads\":[]}")));
    }

    /** Owner decision 216: the server's JSON Payloads forms (go.temporal.io/api payload_json.go, read 2026-11-13). */
    @Test
    void payloadsAreReadInTheServersShorthandAndStandardForms() {
        String binaryNull = "{\"metadata\":{\"encoding\":\"YmluYXJ5L251bGw=\"}}";
        assertNull(TemporalConnector.decodeFirst(Json.tree("[" + binaryNull + "]")), "shorthand binary/null is no result");
        assertNull(TemporalConnector.decodeFirst(Json.tree("{\"payloads\":[" + binaryNull + "]}")), "standard binary/null");
        assertNull(TemporalConnector.decodeFirst(Json.tree("[]")));
        assertEquals(Map.of("data", "x", "metadata", Map.of("encoding", "not-a-payload")),
                TemporalConnector.decodeFirst(Json.tree("[{\"data\":\"x\",\"metadata\":{\"encoding\":\"not-a-payload\"}}]")),
                "an array element is a plain value");
        assertEquals("hi", TemporalConnector.decodeFirst(Json.tree("[\"hi\"]")));
        // standard json/plain: encoding "json/plain" = anNvbi9wbGFpbg==, data {"a":1} = eyJhIjoxfQ==
        assertEquals(Map.of("a", 1), TemporalConnector.decodeFirst(Json.tree(
                "{\"payloads\":[{\"metadata\":{\"encoding\":\"anNvbi9wbGFpbg==\"},\"data\":\"eyJhIjoxfQ==\"}]}")));
        assertThrows(IllegalArgumentException.class, () -> TemporalConnector.decodeFirst(Json.tree(
                "{\"payloads\":[{\"data\":\"eyJhIjoxfQ==\"}]}")), "a standard Payload needs its encoding");
    }

    @Test
    void anUnreadableResultIsNeverASuccess() {
        ConnectorResult r = TemporalConnector.fromClose("w-1", Json.tree("{\"eventType\":"
                + "\"EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED\",\"workflowExecutionCompletedEventAttributes\":"
                + "{\"result\":{\"payloads\":[{\"data\":\"eyJhIjoxfQ==\"}]}}}"));
        assertEquals("TEMPORAL_RESULT_UNREADABLE", assertInstanceOf(ConnectorResult.BusinessFailure.class, r).code());
        ConnectorResult none = TemporalConnector.fromClose("w-1", Json.tree("{\"eventType\":"
                + "\"EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED\",\"workflowExecutionCompletedEventAttributes\":"
                + "{\"result\":[{\"metadata\":{\"encoding\":\"YmluYXJ5L251bGw=\"}}]}}"));
        assertFalse(assertInstanceOf(ConnectorResult.Success.class, none).output().containsKey("result"));
    }

    @Test
    void aShorthandArgumentShapedLikeAPayloadIsRefused() {
        handler = r -> isStart(r) ? startOk() : new Answer(200, completed("1"));
        TemporalConnector c = connector(true, Duration.ofSeconds(5), null, Set.of());
        for (Object arg : List.<Object>of(Map.of("metadata", Map.of("encoding", "x"), "data", "y"),
                Map.of("_protoMessageType", "t", "v", 1))) {
            ConnectorResult r = c.invoke(request(UUID.randomUUID(), Map.of("input", arg)));
            assertEquals("INVALID_REQUEST", assertInstanceOf(ConnectorResult.BusinessFailure.class, r).code());
        }
        assertTrue(requests.isEmpty(), "nothing is sent");
        ConnectorResult ok = c.invoke(request(UUID.randomUUID(), Map.of("input", Map.of("metadata", "m", "note", 1))));
        assertInstanceOf(ConnectorResult.Success.class, ok);
    }
}
