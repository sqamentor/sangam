// SPDX-License-Identifier: Apache-2.0
package org.sangam.ai.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.sangam.integration.connector.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decision 171: the A2A v1.0.1 JSON-RPC methods of {@link A2a.Server} and the outbound state mapping, written from the
 * specification text (a2a-protocol.org/v1.0.1). Not a conformance test against another implementation.
 */
class A2aV1ServerTest {

    static final A2a.InboundConfig CONFIG = new A2a.InboundConfig("t", "sp", "proc", "agent", "an agent", "https://example.invalid/a2a", c -> true);

    /** A gateway with only the two required methods: cancel and list keep their refusing defaults. */
    static final A2a.ProcessGateway MINIMAL = new A2a.ProcessGateway() {
        @Override
        public String start(String tenantId, String sp, String processKey, String businessKey, Map<String, Object> variables) {
            return "i1";
        }

        @Override
        public String status(String tenantId, String sp, String instanceId) {
            return "i1".equals(instanceId) ? "working" : null;
        }
    };

    static JsonNode call(A2a.Server s, String method, String params) {
        return Json.tree(s.handle("peer", "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"" + method + "\",\"params\":" + params + "}", "1.0")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static int code(JsonNode r) {
        return r.path("error").path("code").asInt();
    }

    @Test
    void theCardDeclaresNoStreamingPushOrExtendedCard() {
        Map<String, Object> card = new A2a.Server(CONFIG, MINIMAL).agentCard();
        assertEquals(Map.of("streaming", false, "pushNotifications", false, "extendedAgentCard", false), card.get("capabilities"));
        for (String f : List.of("name", "description", "supportedInterfaces", "version", "capabilities", "defaultInputModes", "defaultOutputModes", "skills")) {
            assertTrue(card.containsKey(f), "required card field " + f);
        }
    }

    @Test
    void unsupportedCapabilitiesAnswerWithThePrescribedErrors() {
        A2a.Server s = new A2a.Server(CONFIG, MINIMAL);
        assertEquals(-32004, code(call(s, "SendStreamingMessage", "{\"message\":{\"messageId\":\"m\"}}")));
        assertEquals(-32004, code(call(s, "SubscribeToTask", "{\"id\":\"i1\"}")));
        for (String m : List.of("CreateTaskPushNotificationConfig", "GetTaskPushNotificationConfig", "ListTaskPushNotificationConfigs",
                "DeleteTaskPushNotificationConfig")) {
            assertEquals(-32003, code(call(s, m, "{}")), m);
        }
        assertEquals(-32004, code(call(s, "GetExtendedAgentCard", "{}")));
        assertEquals(-32601, code(call(s, "NoSuchMethod", "{}")));
    }

    @Test
    void cancelAndListAreUnsupportedUntilTheGatewayImplementsThem() {
        A2a.Server s = new A2a.Server(CONFIG, MINIMAL);
        assertEquals(-32004, code(call(s, "CancelTask", "{\"id\":\"i1\"}")));
        assertEquals(-32004, code(call(s, "ListTasks", "{}")));
        assertEquals(-32001, code(call(s, "GetTask", "{\"id\":\"nope\"}")));
        assertEquals("TASK_STATE_WORKING", call(s, "GetTask", "{\"id\":\"i1\"}").path("result").path("status").path("state").asText());
    }

    @Test
    void cancelMapsNotFoundNotCancelableAndTheNewState() {
        List<String> callers = new ArrayList<>();
        A2a.Server s = new A2a.Server(CONFIG, new A2a.ProcessGateway() {
            @Override
            public String start(String tenantId, String sp, String processKey, String businessKey, Map<String, Object> variables) {
                return "i1";
            }

            @Override
            public String status(String tenantId, String sp, String instanceId) {
                return null;
            }

            @Override
            public String cancel(String tenantId, String sp, String instanceId, String callerId) {
                callers.add(callerId);
                return switch (instanceId) {
                    case "done" -> throw new IllegalStateException("completed");
                    case "run" -> "canceled";
                    default -> null;
                };
            }
        });
        assertEquals(-32001, code(call(s, "CancelTask", "{\"id\":\"other\"}")));
        assertEquals(-32002, code(call(s, "CancelTask", "{\"id\":\"done\"}")));
        JsonNode ok = call(s, "CancelTask", "{\"id\":\"run\"}");
        assertEquals("run", ok.path("result").path("id").asText());
        assertEquals("TASK_STATE_CANCELED", ok.path("result").path("status").path("state").asText());
        assertEquals(-32602, code(call(s, "CancelTask", "{}")), "id is required");
        assertEquals(List.of("peer", "peer", "peer"), callers, "the gateway decides visibility by the authenticated caller");
    }

    @Test
    void listValidatesThePageSizeAndAlwaysReturnsTheFourFields() {
        Object[] seen = new Object[4];
        A2a.Server s = new A2a.Server(CONFIG, new A2a.ProcessGateway() {
            @Override
            public String start(String tenantId, String sp, String processKey, String businessKey, Map<String, Object> variables) {
                return "i1";
            }

            @Override
            public String status(String tenantId, String sp, String instanceId) {
                return null;
            }

            @Override
            public A2a.TaskPage list(String tenantId, String sp, String processKey, String callerId, String contextId, String state,
                                     String after, int pageSize, String pageToken) {
                seen[0] = callerId;
                seen[1] = state;
                seen[2] = pageSize;
                seen[3] = pageToken;
                return new A2a.TaskPage(List.of(new A2a.TaskSummary("i1", "input-required")), null, 1);
            }
        });
        assertEquals(-32602, code(call(s, "ListTasks", "{\"pageSize\":0}")));
        assertEquals(-32602, code(call(s, "ListTasks", "{\"pageSize\":101}")));
        JsonNode r = call(s, "ListTasks", "{\"status\":\"TASK_STATE_INPUT_REQUIRED\"}").path("result");
        assertEquals("peer", seen[0]);
        assertEquals("input-required", seen[1]);
        assertEquals(A2a.DEFAULT_PAGE_SIZE, seen[2]);
        assertNull(seen[3]);
        assertEquals("", r.path("nextPageToken").asText("missing"), "empty on the last page, never absent");
        assertEquals(A2a.DEFAULT_PAGE_SIZE, r.path("pageSize").asInt());
        assertEquals(1, r.path("totalSize").asInt());
        assertEquals("TASK_STATE_INPUT_REQUIRED", r.path("tasks").get(0).path("status").path("state").asText());
        assertFalse(r.path("tasks").get(0).has("artifacts"), "includeArtifacts defaults to false");
        call(s, "ListTasks", "{\"pageSize\":100,\"pageToken\":\"p2\"}");
        assertEquals(100, seen[2]);
        assertEquals("p2", seen[3]);
    }

    @Test
    void sendRecordsTheCallerForLaterVisibility() {
        List<Map<String, Object>> vars = new ArrayList<>();
        A2a.Server s = new A2a.Server(CONFIG, new A2a.ProcessGateway() {
            @Override
            public String start(String tenantId, String sp, String processKey, String businessKey, Map<String, Object> variables) {
                vars.add(variables);
                return "i9";
            }

            @Override
            public String status(String tenantId, String sp, String instanceId) {
                return null;
            }
        });
        JsonNode r = call(s, "SendMessage", "{\"message\":{\"messageId\":\"m1\",\"role\":\"ROLE_USER\",\"parts\":[{\"text\":\"hi\"}]}}");
        assertEquals("i9", r.path("result").path("task").path("id").asText());
        assertEquals("peer", vars.get(0).get("a2aCaller"));
        assertEquals(-32602, code(call(s, "SendMessage", "{\"message\":{}}")), "messageId is required");
    }

    @Test
    void outboundTerminalAndInterruptedStatesAreNotSuccess() {
        for (String st : List.of("TASK_STATE_FAILED", "TASK_STATE_REJECTED", "TASK_STATE_CANCELED", "TASK_STATE_INPUT_REQUIRED",
                "TASK_STATE_AUTH_REQUIRED")) {
            assertTrue(A2a.refusal(Json.tree(("{\"task\":{\"status\":{\"state\":\"" + st + "\"}}}").getBytes())) != null, st);
        }
        for (String st : List.of("failed", "rejected", "canceled", "input-required", "auth-required")) {
            assertTrue(A2a.refusal(Json.tree(("{\"status\":{\"state\":\"" + st + "\"}}").getBytes())) != null, "0.3 " + st);
        }
        for (String st : List.of("TASK_STATE_SUBMITTED", "TASK_STATE_WORKING", "TASK_STATE_COMPLETED", "completed")) {
            assertNull(A2a.refusal(Json.tree(("{\"task\":{\"status\":{\"state\":\"" + st + "\"}}}").getBytes())), st);
        }
        assertNull(A2a.refusal(Json.tree("{\"message\":{\"messageId\":\"x\",\"role\":\"ROLE_AGENT\",\"parts\":[]}}".getBytes())),
                "a direct message answer is a success");
    }
}
