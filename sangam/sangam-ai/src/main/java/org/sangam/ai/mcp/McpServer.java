// SPDX-License-Identifier: Apache-2.0
package org.sangam.ai.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.sangam.integration.connector.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Platform MCP server: transport-neutral JSON-RPC handling over the REST API. Every tool is one REST call made with the
 * caller's own token, so authorization, tenancy and four-eyes stay in REST and this class holds no privilege. Read tools
 * are always listed. Write tools appear only for tenants in {@code writeEnabled}, and require a {@code reason} that is sent
 * to REST.
 */
public final class McpServer {

    /** The REST API as seen with the caller's token. */
    @FunctionalInterface
    public interface RestGateway {
        Reply call(String method, String path, Map<String, String> query, Object body, String callerToken, String reason);
    }

    public record Reply(int status, String body) {
    }

    public record Caller(String tenantId, String bearerToken) {
    }

    private record Tool(String name, String description, boolean write, String method, String pathTemplate, List<String> required) {
    }

    private static final List<Tool> TOOLS = List.of(
            new Tool("list_tasks", "List tasks visible to the caller", false, "GET", "/tasks", List.of()),
            new Tool("get_task", "Read one task", false, "GET", "/tasks/{id}", List.of("id")),
            new Tool("get_process_instance", "Read one process instance", false, "GET", "/process-instances/{id}", List.of("id")),
            new Tool("list_process_definitions", "List process definitions", false, "GET", "/process-definitions", List.of()),
            new Tool("get_process_definition_xml", "Read the BPMN of a definition", false, "GET", "/process-definitions/{id}/xml", List.of("id")),
            new Tool("audit_timeline", "Read the audit timeline of a business key", false, "GET", "/audit/{businessKey}/timeline", List.of("businessKey")),
            new Tool("list_incidents", "List incidents", false, "GET", "/ops/incidents", List.of()),
            new Tool("start_process", "Start a process instance", true, "POST", "/process-instances", List.of("key", "reason")),
            new Tool("complete_task", "Complete a task", true, "POST", "/tasks/{id}/complete", List.of("id", "reason")));

    private static final String META_VERSION = "io.modelcontextprotocol/protocolVersion";
    private static final int UNSUPPORTED_VERSION = -32022;
    private static final int HEADER_MISMATCH = -32020;
    private static final Map<String, String> SERVER_INFO = Map.of("name", "sangam", "version", "0.1.0");
    private static final List<String> SUPPORTED = List.of("2026-07-28", "2025-11-25", "2025-06-18", "2025-03-26");

    private final RestGateway rest;
    private final Predicate<String> writeEnabled;

    public McpServer(RestGateway rest, Predicate<String> writeEnabled) {
        this.rest = rest;
        this.writeEnabled = writeEnabled;
    }

    /** Handles one JSON-RPC message. Returns the JSON answer, or null for a notification. */
    public String handle(String requestJson, Caller caller) {
        JsonNode req;
        try {
            req = Json.MAPPER.readTree(requestJson);
        } catch (java.io.IOException e) {
            return error(null, -32700, "parse error");
        }
        JsonNode id = req.get("id");
        String method = req.path("method").asText("");
        if (id == null) {
            return null;
        }
        JsonNode requested = req.path("params").path("_meta").get(META_VERSION);
        if (requested != null) {
            return modern(id, method, requested.asText(), req.path("params"), caller);
        }
        return switch (method) {
            case "initialize" -> {
                String asked = req.path("params").path("protocolVersion").asText();
                yield result(id, Map.of("protocolVersion", McpClient.LEGACY_VERSIONS.contains(asked) ? asked : McpClient.LEGACY_VERSIONS.get(0),
                        "capabilities", Map.of("tools", Map.of()), "serverInfo", SERVER_INFO));
            }
            case "ping" -> result(id, Map.of());
            case "tools/list" -> result(id, Map.of("tools", list(caller)));
            case "tools/call" -> call(id, req.path("params"), caller);
            default -> error(id, -32601, "method not found: " + method);
        };
    }

    /** Stateless revision 2026-07-28: each request carries its version; results carry resultType and server identity. */
    private String modern(JsonNode id, String method, String version, JsonNode params, Caller caller) {
        if (!McpClient.MODERN_VERSION.equals(version)) {
            return errorWithData(id, UNSUPPORTED_VERSION, "Unsupported protocol version", Map.of("supported", SUPPORTED, "requested", version));
        }
        return switch (method) {
            case "server/discover" -> {
                Map<String, Object> r = modernResult();
                r.put("supportedVersions", SUPPORTED);
                r.put("capabilities", Map.of("tools", Map.of()));
                r.put("ttlMs", 3_600_000);
                r.put("cacheScope", "public");
                yield result(id, r);
            }
            case "tools/list" -> {
                Map<String, Object> r = modernResult();
                r.put("tools", list(caller));
                // The list depends on the caller's tenant, so shared caches must not keep it.
                r.put("ttlMs", 60_000);
                r.put("cacheScope", "private");
                yield result(id, r);
            }
            case "tools/call" -> {
                JsonNode answer = Json.tree(call(id, params, caller).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                if (answer.has("error")) {
                    yield Json.write(answer);
                }
                Map<String, Object> r = modernResult();
                r.putAll(Json.MAPPER.convertValue(answer.get("result"), Map.class));
                yield result(id, r);
            }
            default -> error(id, -32601, "method not found: " + method);
        };
    }

    private static Map<String, Object> modernResult() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("resultType", "complete");
        r.put("_meta", Map.of("io.modelcontextprotocol/serverInfo", SERVER_INFO));
        return r;
    }

    /**
     * Header-to-body check for an HTTP host of this server (2026-07-28 Server Validation). Returns the error JSON to send with
     * HTTP 400, or null when the request may go to {@link #handle}. Legacy requests, which carry no version in {@code _meta},
     * are not checked. {@code Mcp-Param-*} headers are not checked because no tool here declares {@code x-mcp-header}.
     */
    public static String validateHttpHeaders(String requestJson, String protocolVersionHeader, String methodHeader, String nameHeader) {
        JsonNode req;
        try {
            req = Json.MAPPER.readTree(requestJson);
        } catch (java.io.IOException e) {
            return null;
        }
        JsonNode version = req.path("params").path("_meta").get(META_VERSION);
        if (version == null) {
            return null;
        }
        JsonNode id = req.get("id");
        String method = req.path("method").asText();
        if (!version.asText().equals(protocolVersionHeader)) {
            return error(id, HEADER_MISMATCH, "Header mismatch: MCP-Protocol-Version");
        }
        if (!method.equals(methodHeader)) {
            return error(id, HEADER_MISMATCH, "Header mismatch: Mcp-Method");
        }
        if ("tools/call".equals(method) && !McpClient.headerValue(req.path("params").path("name").asText()).equals(nameHeader)) {
            return error(id, HEADER_MISMATCH, "Header mismatch: Mcp-Name");
        }
        return null;
    }

    private List<Map<String, Object>> list(Caller caller) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Tool t : TOOLS) {
            if (t.write && !writeEnabled.test(caller.tenantId())) {
                continue;
            }
            Map<String, Object> props = new LinkedHashMap<>();
            t.required.forEach(r -> props.put(r, Map.of("type", "string")));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", t.name);
            m.put("description", t.description);
            m.put("inputSchema", Map.of("type", "object", "properties", props, "required", t.required));
            m.put("annotations", Map.of("readOnlyHint", !t.write));
            out.add(m);
        }
        return out;
    }

    private String call(JsonNode id, JsonNode params, Caller caller) {
        String name = params.path("name").asText();
        Tool tool = TOOLS.stream().filter(t -> t.name.equals(name)).findFirst().orElse(null);
        if (tool == null || (tool.write && !writeEnabled.test(caller.tenantId()))) {
            return error(id, -32602, "unknown tool: " + name);
        }
        Map<String, Object> args = new LinkedHashMap<>(params.has("arguments")
                ? Json.MAPPER.convertValue(params.get("arguments"), Map.class) : Map.of());
        for (String r : tool.required) {
            if (args.get(r) == null || args.get(r).toString().isBlank()) {
                return toolResult(id, "missing argument: " + r, true);
            }
        }
        String path = tool.pathTemplate;
        for (String r : tool.required) {
            if (path.contains("{" + r + "}")) {
                path = path.replace("{" + r + "}", java.net.URLEncoder.encode(args.remove(r).toString(), java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        String reason = tool.write ? String.valueOf(args.remove("reason")) : null;
        Map<String, String> query = new LinkedHashMap<>();
        Object body = null;
        if (tool.write) {
            body = args;
        } else {
            args.forEach((k, v) -> query.put(k, String.valueOf(v)));
        }
        Reply reply = rest.call(tool.method, path, query, body, caller.bearerToken(), reason);
        return toolResult(id, reply.body(), reply.status() >= 400);
    }

    private static String toolResult(JsonNode id, String text, boolean isError) {
        return result(id, Map.of("content", List.of(Map.of("type", "text", "text", text == null ? "" : text)), "isError", isError));
    }

    private static String result(JsonNode id, Object result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("result", result);
        return Json.write(m);
    }

    private static String errorWithData(JsonNode id, int code, String message, Object data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("error", Map.of("code", code, "message", message, "data", data));
        return Json.write(m);
    }

    private static String error(JsonNode id, int code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("error", Map.of("code", code, "message", message));
        return Json.write(m);
    }
}
