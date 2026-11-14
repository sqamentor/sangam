// SPDX-License-Identifier: Apache-2.0
package org.sangam.ai.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import org.sangam.ai.agent.AgentTool;
import org.sangam.ai.llm.Llm;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.Json;
import org.sangam.integration.connector.SecretResolver;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Agent-to-agent interoperability from the public A2A protocol, JSON-RPC binding: specification v1.0.1 (wire version
 * {@code 1.0}: {@code SendMessage}, {@code GetTask}, {@code CancelTask}, {@code ListTasks}) and 0.3 ({@code message/send},
 * {@code tasks/get}); agent card. Outbound tries 1.0, then 0.3 when the peer refuses the method or version. Streaming,
 * push notifications and the extended agent card are not offered: the card says so and the methods answer with the
 * errors the specification prescribes for that case. Outbound calls are agent tools: approved, fenced and identified by
 * the operationId, which is sent as the {@code messageId} so a repeat is recognisable to the remote agent. Inbound
 * messages start a process under a configured service principal. Decision 171: no conformance claim until the
 * conformance tests have run.
 */
public final class A2a {

    /** Where a host serves {@link Server#agentCard()}. */
    public static final String WELL_KNOWN_CARD_PATH = "/.well-known/agent-card.json";
    private static final String VERSION_1_0 = "1.0";
    private static final String VERSION_0_3 = "0.3";
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_PARAMS = -32602;
    private static final int TASK_NOT_FOUND = -32001;
    private static final int TASK_NOT_CANCELABLE = -32002;
    private static final int PUSH_NOT_SUPPORTED = -32003;
    private static final int UNSUPPORTED_OPERATION = -32004;
    private static final int VERSION_NOT_SUPPORTED = -32009;
    /** v1.0.1 ListTasks: page size default 50, from 1 to 100. */
    static final int DEFAULT_PAGE_SIZE = 50;
    static final int MAX_PAGE_SIZE = 100;

    private A2a() {
    }

    // ---- outbound ------------------------------------------------------------------------------

    /** Outbound tool for one allow-listed remote agent. The transport's egress policy is the second allow-list. */
    public static AgentTool remoteAgent(String name, URI endpoint, String bearerRef, HttpTransport transport, SecretResolver secrets) {
        Llm.ToolSpec spec = new Llm.ToolSpec(name, "Delegate a request to the remote agent " + name, Map.of("type", "object",
                "properties", Map.of("input", Map.of("type", "string")), "required", List.of("input")));
        return new AgentTool() {
            @Override
            public Llm.ToolSpec spec() {
                return spec;
            }

            @Override
            public Result invoke(Context ctx, Map<String, Object> args) {
                String input = String.valueOf(args.get("input"));
                String messageId = ctx.operationId().toString();
                Map<String, Object> message = Map.of("role", "ROLE_USER", "messageId", messageId, "parts", List.of(Map.of("text", input)));
                Result first = send(ctx, "SendMessage", message, VERSION_1_0);
                if (first != null) {
                    return first;
                }
                // The peer refused the 1.0 method or version without acting on it, so the 0.3 shape is safe to try.
                Map<String, Object> legacy = Map.of("role", "user", "messageId", messageId, "parts", List.of(Map.of("kind", "text", "text", input)));
                Result second = send(ctx, "message/send", legacy, VERSION_0_3);
                return second != null ? second : new Failed("the remote agent refused A2A 1.0 and 0.3");
            }

            /** Null when the peer refused this method or version. */
            private Result send(Context ctx, String method, Map<String, Object> message, String version) {
                Map<String, String> h = new LinkedHashMap<>();
                h.put("Content-Type", "application/json");
                h.put("A2A-Version", version);
                if (bearerRef != null) {
                    h.put("Authorization", "Bearer " + secrets.require(bearerRef));
                }
                byte[] body = Json.bytes(Map.of("jsonrpc", "2.0", "id", ctx.operationId().toString(), "method", method, "params", Map.of("message", message)));
                return switch (transport.execute(ctx.tenantId(), HttpTransport.Call.post(endpoint, h, body, false), 0)) {
                    case HttpTransport.Outcome.NoEffect n -> new Retry(n.reason());
                    case HttpTransport.Outcome.Unknown u -> new Pending("UNKNOWN_OUTCOME: " + u.reason());
                    case HttpTransport.Outcome.Response r when r.status() >= 500 -> new Pending("UNKNOWN_OUTCOME: HTTP " + r.status());
                    case HttpTransport.Outcome.Response r -> {
                        JsonNode t = jsonOrNull(r.body());
                        int code = t == null ? 0 : t.path("error").path("code").asInt();
                        if (code == METHOD_NOT_FOUND || code == VERSION_NOT_SUPPORTED) {
                            yield null;
                        }
                        if (!r.is2xx()) {
                            yield new Failed("HTTP " + r.status());
                        }
                        if (t == null || t.has("error")) {
                            yield new Failed(t == null ? "not JSON" : t.path("error").path("message").asText());
                        }
                        String refused = refusal(t.path("result"));
                        if (refused != null) {
                            yield new Failed(refused);
                        }
                        yield new Done(Map.of("result", Json.MAPPER.convertValue(t.path("result"), Object.class)));
                    }
                };
            }
        };
    }

    /**
     * Why a returned task is not a success, or null. A 1.0 answer wraps the task ({@code {"task": ...}}); a 0.3 answer is
     * the task. Failed, rejected and canceled tasks are failures; a task waiting for input or authorization is one too,
     * because this tool cannot supply either (a later turn of the agent may send a new message).
     */
    static String refusal(JsonNode result) {
        JsonNode task = result.has("task") ? result.path("task") : result;
        String state = task.path("status").path("state").asText("");
        String s = state.toUpperCase(java.util.Locale.ROOT).replace('-', '_');
        s = s.startsWith("TASK_STATE_") ? s.substring("TASK_STATE_".length()) : s;
        return switch (s) {
            case "FAILED", "REJECTED", "CANCELED", "CANCELLED" -> "the remote task ended " + state;
            case "INPUT_REQUIRED", "AUTH_REQUIRED" -> "the remote agent asks for " + (s.startsWith("AUTH") ? "authorization" : "more input")
                    + " (" + state + "), which this tool cannot supply";
            default -> null;
        };
    }

    private static JsonNode jsonOrNull(byte[] body) {
        try {
            return Json.tree(body);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---- inbound -------------------------------------------------------------------------------

    /** Starts the mapped process and reports its status. {@code businessKey} is the idempotency handle. */
    public interface ProcessGateway {
        String start(String tenantId, String servicePrincipal, String processKey, String businessKey, Map<String, Object> variables);

        String status(String tenantId, String servicePrincipal, String instanceId);

        /**
         * CancelTask: cancels the instance if {@code callerId} started it and it is still cancelable, and returns its
         * state afterwards (A2A state name in lower case, for example {@code canceled}); null when there is no such
         * task for this caller; throws {@link IllegalStateException} when it is no longer cancelable. The default
         * refuses the operation ({@link UnsupportedOperationException}), which the server reports as
         * UnsupportedOperationError.
         */
        default String cancel(String tenantId, String servicePrincipal, String instanceId, String callerId) {
            throw new UnsupportedOperationException("CancelTask is not implemented by this gateway");
        }

        /**
         * ListTasks: the tasks of {@code processKey} that {@code callerId} started, newest status first, filtered by
         * {@code contextId}, {@code state} (lower-case A2A state) and {@code statusTimestampAfter} (ISO-8601) when given;
         * {@code pageToken} is the token of the previous page or null. The default refuses the operation.
         */
        default TaskPage list(String tenantId, String servicePrincipal, String processKey, String callerId, String contextId,
                              String state, String statusTimestampAfter, int pageSize, String pageToken) {
            throw new UnsupportedOperationException("ListTasks is not implemented by this gateway");
        }
    }

    /** One task in a {@link TaskPage}: the instance id (also the context id) and its lower-case A2A state. */
    public record TaskSummary(String id, String state) {
    }

    /** One page of ListTasks; {@code nextPageToken} is null or empty on the last page. */
    public record TaskPage(List<TaskSummary> tasks, String nextPageToken, int totalSize) {
    }

    public record InboundConfig(String tenantId, String servicePrincipal, String processKey, String agentName, String description, String url,
                                Predicate<String> callerAllowed) {
    }

    public static final class Server {
        private final InboundConfig config;
        private final ProcessGateway gateway;

        public Server(InboundConfig config, ProcessGateway gateway) {
            this.config = config;
            this.gateway = gateway;
        }

        public Map<String, Object> agentCard() {
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("name", config.agentName());
            card.put("description", config.description());
            card.put("supportedInterfaces", List.of(
                    Map.of("url", config.url(), "protocolBinding", "JSONRPC", "protocolVersion", VERSION_1_0),
                    Map.of("url", config.url(), "protocolBinding", "JSONRPC", "protocolVersion", VERSION_0_3)));
            // url and protocolVersion are the 0.3 card fields, kept for 0.3 clients.
            card.put("url", config.url());
            card.put("version", "0.1.0");
            card.put("protocolVersion", "0.3.0");
            card.put("capabilities", Map.of("streaming", false, "pushNotifications", false, "extendedAgentCard", false));
            card.put("defaultInputModes", List.of("text/plain"));
            card.put("defaultOutputModes", List.of("text/plain"));
            card.put("skills", List.of(Map.of("id", config.processKey(), "name", config.processKey(), "description", config.description(), "tags", List.of())));
            return card;
        }

        /** Handles one JSON-RPC request from an authenticated caller. */
        public String handle(String callerId, String json) {
            return handle(callerId, json, null);
        }

        /** As {@link #handle(String, String)}, with the request's {@code A2A-Version} header (empty means 0.3). */
        public String handle(String callerId, String json, String a2aVersion) {
            JsonNode req;
            try {
                req = Json.MAPPER.readTree(json);
            } catch (java.io.IOException e) {
                return err(null, -32700, "parse error");
            }
            JsonNode id = req.get("id");
            if (!config.callerAllowed().test(callerId)) {
                return err(id, -32000, "caller not allowed");
            }
            if (a2aVersion != null && !a2aVersion.isBlank() && !List.of(VERSION_1_0, VERSION_0_3).contains(a2aVersion.trim())) {
                return err(id, VERSION_NOT_SUPPORTED, "unsupported A2A version; supported: 1.0, 0.3");
            }
            JsonNode params = req.path("params");
            return switch (req.path("method").asText()) {
                case "SendMessage" -> send(id, params.path("message"), true, callerId);
                case "GetTask" -> get(id, params.path("id").asText(), true);
                case "CancelTask" -> cancel(id, params.path("id").asText(), callerId);
                case "ListTasks" -> list(id, params, callerId);
                // v1.0.1 3.3.4: capabilities.streaming is false
                case "SendStreamingMessage", "SubscribeToTask" -> err(id, UNSUPPORTED_OPERATION, "streaming is not supported by this agent");
                // v1.0.1 3.3.4: capabilities.pushNotifications is false
                case "CreateTaskPushNotificationConfig", "GetTaskPushNotificationConfig", "ListTaskPushNotificationConfigs",
                     "DeleteTaskPushNotificationConfig" -> err(id, PUSH_NOT_SUPPORTED, "push notifications are not supported by this agent");
                // v1.0.1 3.3.4: capabilities.extendedAgentCard is false
                case "GetExtendedAgentCard" -> err(id, UNSUPPORTED_OPERATION, "this agent has no extended agent card");
                case "message/send" -> send(id, params.path("message"), false, callerId);
                case "tasks/get" -> get(id, params.path("id").asText(), false);
                default -> err(id, METHOD_NOT_FOUND, "method not found");
            };
        }

        private String cancel(JsonNode id, String taskId, String callerId) {
            if (taskId.isBlank()) {
                return err(id, INVALID_PARAMS, "id is required");
            }
            String state;
            try {
                state = gateway.cancel(config.tenantId(), config.servicePrincipal(), taskId, callerId);
            } catch (UnsupportedOperationException e) {
                return err(id, UNSUPPORTED_OPERATION, "CancelTask is not supported by this agent");
            } catch (IllegalStateException e) {
                return err(id, TASK_NOT_CANCELABLE, "the task is not in a cancelable state");
            }
            return state == null ? err(id, TASK_NOT_FOUND, "task not found") : ok(id, task1(taskId, state));
        }

        private String list(JsonNode id, JsonNode params, String callerId) {
            int pageSize = params.has("pageSize") ? params.path("pageSize").asInt(-1) : DEFAULT_PAGE_SIZE;
            if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
                return err(id, INVALID_PARAMS, "pageSize must be from 1 to " + MAX_PAGE_SIZE);
            }
            String status = text(params, "status");
            String state = status == null ? null : status.toLowerCase(java.util.Locale.ROOT).replaceFirst("^task_state_", "").replace('_', '-');
            TaskPage page;
            try {
                page = gateway.list(config.tenantId(), config.servicePrincipal(), config.processKey(), callerId, text(params, "contextId"),
                        state, text(params, "statusTimestampAfter"), pageSize, text(params, "pageToken"));
            } catch (UnsupportedOperationException e) {
                return err(id, UNSUPPORTED_OPERATION, "ListTasks is not supported by this agent");
            }
            List<Map<String, Object>> tasks = page.tasks().stream().map(t -> task1(t.id(), t.state())).toList();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("tasks", tasks);
            // v1.0.1: always present, empty on the last page
            r.put("nextPageToken", page.nextPageToken() == null ? "" : page.nextPageToken());
            r.put("pageSize", pageSize);
            r.put("totalSize", page.totalSize());
            return ok(id, r);
        }

        private static String text(JsonNode params, String field) {
            String v = params.path(field).asText("");
            return v.isBlank() ? null : v;
        }

        private String get(JsonNode id, String taskId, boolean v1) {
            String state = gateway.status(config.tenantId(), config.servicePrincipal(), taskId);
            if (state == null) {
                return err(id, TASK_NOT_FOUND, "task not found");
            }
            return ok(id, v1 ? task1(taskId, state) : task(taskId, state));
        }

        private String send(JsonNode id, JsonNode message, boolean v1, String callerId) {
            String messageId = message.path("messageId").asText("");
            if (messageId.isBlank()) {
                return err(id, INVALID_PARAMS, "messageId is required");
            }
            StringBuilder text = new StringBuilder();
            for (JsonNode p : message.path("parts")) {
                text.append(p.path("text").asText(""));
            }
            // a2aCaller: who may list and cancel the task later (ListTasks returns only the caller's own tasks)
            String instance = gateway.start(config.tenantId(), config.servicePrincipal(), config.processKey(), "a2a:" + messageId,
                    Map.of("a2aMessageId", messageId, "a2aInput", text.toString(), "a2aCaller", String.valueOf(callerId)));
            return ok(id, v1 ? Map.of("task", task1(instance, "submitted")) : task(instance, "submitted"));
        }

        /** A2A 1.0 task: no kind discriminator; the state is a ProtoJSON enum name. */
        private static Map<String, Object> task1(String instanceId, String state) {
            String name = "TASK_STATE_" + state.toUpperCase(java.util.Locale.ROOT).replace('-', '_');
            return Map.of("id", instanceId, "contextId", instanceId, "status", Map.of("state", name));
        }

        private static Map<String, Object> task(String instanceId, String state) {
            return Map.of("kind", "task", "id", instanceId, "contextId", instanceId, "status", Map.of("state", state));
        }

        private static String ok(JsonNode id, Object result) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("jsonrpc", "2.0");
            m.put("id", id);
            m.put("result", result);
            return Json.write(m);
        }

        private static String err(JsonNode id, int code, String message) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("jsonrpc", "2.0");
            m.put("id", id);
            m.put("error", Map.of("code", code, "message", message));
            return Json.write(m);
        }
    }
}
