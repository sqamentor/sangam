// SPDX-License-Identifier: Apache-2.0
package org.sangam.ai.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.sangam.ai.agent.AgentTool;
import org.sangam.ai.llm.Llm;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.Json;
import org.sangam.integration.connector.SecretResolver;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Dual-era MCP client over Streamable HTTP. It probes with {@code server/discover} (stateless revision 2026-07-28: version in
 * {@code _meta}, {@code Mcp-Method}, {@code Mcp-Name} and {@code Mcp-Param-*} headers) and falls back to the {@code initialize}
 * handshake (2025-11-25 and earlier), using the version the server negotiated. Answers may be JSON or SSE; SSE notifications
 * before the response are skipped. Servers are allow-listed by the {@code EgressPolicy} of the transport, and tools by
 * {@code toolFilter}. Tool annotations are untrusted unless {@code trustAnnotations} is set for this server, so by default every
 * tool needs human approval. Server-initiated requests and {@code input_required} results are not handled.
 */
public final class McpClient {

    private static final System.Logger LOG = System.getLogger(McpClient.class.getName());

    public static final String MODERN_VERSION = "2026-07-28";
    public static final List<String> LEGACY_VERSIONS = List.of("2025-11-25", "2025-06-18", "2025-03-26");
    private static final int MAX_PAGES = 100;
    private static final int UNSUPPORTED_VERSION = -32022;

    public record Config(String id, URI endpoint, String tenantId, String bearerRef, Set<String> toolFilter, boolean trustAnnotations) {
        public Config(String id, URI endpoint, String tenantId, String bearerRef, Set<String> toolFilter) {
            this(id, endpoint, tenantId, bearerRef, toolFilter, false);
        }
    }

    public static final class McpException extends RuntimeException {
        public McpException(String m) {
            super(m);
        }
    }

    private final Config config;
    private final HttpTransport transport;
    private final SecretResolver secrets;
    private final AtomicLong ids = new AtomicLong();
    private volatile String sessionId;
    private volatile String version;
    private volatile boolean modern;
    private volatile boolean initialized;

    public McpClient(Config config, HttpTransport transport, SecretResolver secrets) {
        this.config = config;
        this.transport = transport;
        this.secrets = secrets;
    }

    public synchronized void initialize() {
        if (initialized) {
            return;
        }
        modern = true;
        version = MODERN_VERSION;
        long id = ids.incrementAndGet();
        HttpTransport.Outcome.Response r = post(request(id, "server/discover", Map.of()), "server/discover", null, Map.of());
        JsonNode body = parseQuietly(r, id);
        JsonNode supported = null;
        if (body != null && r.is2xx() && body.path("result").has("supportedVersions")) {
            supported = body.path("result").path("supportedVersions");
        } else if (body != null && body.path("error").path("code").asInt() == UNSUPPORTED_VERSION) {
            supported = body.path("error").path("data").path("supported");
        }
        if (supported != null && contains(supported, MODERN_VERSION)) {
            initialized = true;
            return;
        }
        // Not a modern answer, or a server without a shared modern version: use the handshake.
        modern = false;
        version = LEGACY_VERSIONS.get(0);
        sessionId = null;
        JsonNode init = rpc("initialize", Map.of("protocolVersion", version, "capabilities", Map.of(),
                "clientInfo", Map.of("name", "sangam", "version", "0.1.0")), Map.of());
        String negotiated = init.path("protocolVersion").asText();
        if (!LEGACY_VERSIONS.contains(negotiated)) {
            throw new McpException("server offered unsupported MCP version " + negotiated);
        }
        version = negotiated;
        post(Map.of("jsonrpc", "2.0", "method", "notifications/initialized"), "notifications/initialized", null, Map.of());
        initialized = true;
    }

    /** The negotiated protocol version, or null before {@link #initialize()}. */
    public String protocolVersion() {
        return initialized ? version : null;
    }

    /** The server's tools after the allow-list filter, as agent tools named {@code <id>.<tool>}. */
    public List<AgentTool> tools() {
        initialize();
        List<AgentTool> out = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            JsonNode res = rpc("tools/list", cursor == null ? Map.of() : Map.of("cursor", cursor), Map.of());
            for (JsonNode t : res.path("tools")) {
                String name = t.path("name").asText();
                if (config.toolFilter().contains(name)) {
                    toTool(t, name, out);
                }
            }
            cursor = res.path("nextCursor").asText(null);
            if (cursor == null || cursor.isEmpty()) {
                return out;
            }
        }
        throw new McpException("tools/list did not finish within " + MAX_PAGES + " pages");
    }

    private void toTool(JsonNode t, String name, List<AgentTool> out) {
        Map<List<String>, String> mirrored = new LinkedHashMap<>();
        if (modern && !headerParams(t.path("inputSchema"), mirrored)) {
            LOG.log(System.Logger.Level.WARNING, "MCP tool {0} excluded: invalid x-mcp-header", name);
            return;
        }
        boolean readOnly = config.trustAnnotations() && t.path("annotations").path("readOnlyHint").asBoolean(false);
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = Json.MAPPER.convertValue(t.path("inputSchema"), Map.class);
        Llm.ToolSpec spec = new Llm.ToolSpec(config.id() + "." + name, t.path("description").asText(""), schema);
        out.add(new AgentTool() {
            @Override
            public Llm.ToolSpec spec() {
                return spec;
            }

            @Override
            public boolean effectful() {
                return !readOnly;
            }

            @Override
            public Result invoke(Context ctx, Map<String, Object> args) {
                return call(name, args, readOnly, mirrored);
            }
        });
    }

    private AgentTool.Result call(String tool, Map<String, Object> args, boolean readOnly, Map<List<String>, String> mirrored) {
        JsonNode r;
        try {
            r = rpc("tools/call", Map.of("name", tool, "arguments", args), paramHeaders(args, mirrored));
        } catch (McpException e) {
            // After a non-read-only send the effect is unknown; MCP has no idempotency key or outcome lookup.
            return readOnly ? new AgentTool.Retry(e.getMessage()) : new AgentTool.Pending("UNKNOWN_OUTCOME: " + e.getMessage());
        }
        if ("input_required".equals(r.path("resultType").asText())) {
            return new AgentTool.Failed("the MCP server asked for more input, which this client does not support");
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode c : r.path("content")) {
            text.append(c.path("text").asText(""));
        }
        if (r.path("isError").asBoolean(false)) {
            return new AgentTool.Failed(text.toString());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("text", text.toString());
        if (r.has("structuredContent")) {
            out.put("structured", Json.MAPPER.convertValue(r.get("structuredContent"), Object.class));
        }
        return new AgentTool.Done(out);
    }

    JsonNode rpc(String method, Map<String, Object> params, Map<String, String> extraHeaders) {
        long id = ids.incrementAndGet();
        String name = "tools/call".equals(method) ? String.valueOf(params.get("name")) : null;
        HttpTransport.Outcome.Response r = post(request(id, method, params), method, name, extraHeaders);
        if (!modern && r.status() == 404 && sessionId != null && !"initialize".equals(method)) {
            // The legacy session ended; the spec requires a new one. The 404 means the request was not processed.
            synchronized (this) {
                sessionId = null;
                initialized = false;
                initialize();
            }
            id = ids.incrementAndGet();
            r = post(request(id, method, params), method, name, extraHeaders);
        }
        JsonNode root = parseQuietly(r, id);
        if (root != null && root.has("error")) {
            throw new McpException("MCP error " + root.path("error").path("code").asInt() + ": " + root.path("error").path("message").asText());
        }
        if (!r.is2xx()) {
            throw new McpException("HTTP " + r.status());
        }
        if (root == null) {
            throw new McpException("empty answer to " + method);
        }
        return root.path("result");
    }

    private Map<String, Object> request(long id, String method, Map<String, Object> params) {
        Map<String, Object> p = new LinkedHashMap<>(params);
        if (modern) {
            p.put("_meta", Map.of("io.modelcontextprotocol/protocolVersion", version,
                    "io.modelcontextprotocol/clientInfo", Map.of("name", "sangam", "version", "0.1.0"),
                    "io.modelcontextprotocol/clientCapabilities", Map.of()));
        }
        return Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", p);
    }

    private HttpTransport.Outcome.Response post(Map<String, Object> msg, String method, String name, Map<String, String> extraHeaders) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/json");
        h.put("Accept", "application/json, text/event-stream");
        h.put("MCP-Protocol-Version", version);
        if (modern) {
            h.put("Mcp-Method", method);
            if (name != null) {
                h.put("Mcp-Name", headerValue(name));
            }
            h.putAll(extraHeaders);
        } else if (sessionId != null) {
            h.put("MCP-Session-Id", sessionId);
        }
        if (config.bearerRef() != null) {
            h.put("Authorization", "Bearer " + secrets.require(config.bearerRef()));
        }
        HttpTransport.Outcome o = transport.execute(config.tenantId(), HttpTransport.Call.post(config.endpoint(), h, Json.bytes(msg), false), 0);
        if (!(o instanceof HttpTransport.Outcome.Response r)) {
            throw new McpException("transport: " + o);
        }
        if (!modern) {
            r.header("MCP-Session-Id").ifPresent(s -> sessionId = s);
        }
        return r;
    }

    /** The JSON-RPC response with this id, from a JSON or SSE body; null when there is none. */
    private static JsonNode parseQuietly(HttpTransport.Outcome.Response r, long id) {
        if (r.body().length == 0) {
            return null;
        }
        if (!r.header("Content-Type").orElse("").contains("text/event-stream")) {
            try {
                return Json.tree(r.body());
            } catch (RuntimeException e) {
                return null;
            }
        }
        StringBuilder data = new StringBuilder();
        for (String line : (r.bodyString() + "\n\n").split("\\R", -1)) {
            if (line.startsWith("data:")) {
                data.append(line.substring(5).trim());
            } else if (line.isEmpty() && data.length() > 0) {
                JsonNode n;
                try {
                    n = Json.tree(data.toString().getBytes(StandardCharsets.UTF_8));
                } catch (RuntimeException e) {
                    n = null;
                }
                data.setLength(0);
                if (n != null && n.path("id").asLong(-1) == id && (n.has("result") || n.has("error"))) {
                    return n;
                }
            }
        }
        return null;
    }

    private static boolean contains(JsonNode array, String value) {
        for (JsonNode n : array) {
            if (value.equals(n.asText())) {
                return true;
            }
        }
        return false;
    }

    // ---- x-mcp-header (2026-07-28 Streamable HTTP) ----------------------------------------------

    /** Collects annotated parameters. False when the tool definition breaks a constraint, so it must be excluded. */
    private static boolean headerParams(JsonNode schema, Map<List<String>, String> out) {
        Set<String> seen = new HashSet<>();
        if (!walk(schema, List.of(), out, seen)) {
            return false;
        }
        return countAnnotations(schema) == out.size();
    }

    private static boolean walk(JsonNode schema, List<String> path, Map<List<String>, String> out, Set<String> seen) {
        var fields = schema.path("properties").fields();
        while (fields.hasNext()) {
            var f = fields.next();
            List<String> p = new ArrayList<>(path);
            p.add(f.getKey());
            JsonNode prop = f.getValue();
            if (prop.has("x-mcp-header")) {
                String name = prop.path("x-mcp-header").asText("");
                String type = prop.path("type").asText("");
                if (!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+") || !seen.add(name.toLowerCase(java.util.Locale.ROOT))
                        || !Set.of("string", "integer", "boolean").contains(type)) {
                    return false;
                }
                out.put(p, name);
            }
            if (!walk(prop, p, out, seen)) {
                return false;
            }
        }
        return true;
    }

    private static int countAnnotations(JsonNode n) {
        int c = 0;
        if (n.isObject()) {
            c += n.has("x-mcp-header") ? 1 : 0;
        }
        for (JsonNode child : n) {
            c += countAnnotations(child);
        }
        return c;
    }

    private static Map<String, String> paramHeaders(Map<String, Object> args, Map<List<String>, String> mirrored) {
        Map<String, String> h = new LinkedHashMap<>();
        mirrored.forEach((path, name) -> {
            Object v = args;
            for (String key : path) {
                v = v instanceof Map<?, ?> m ? m.get(key) : null;
            }
            if (v != null) {
                h.put("Mcp-Param-" + name, headerValue(v.toString()));
            }
        });
        return h;
    }

    static String headerValue(String v) {
        boolean safe = v.equals(v.strip()) && !(v.startsWith("=?base64?") && v.endsWith("?="))
                && v.chars().allMatch(c -> c == 0x09 || (c >= 0x20 && c <= 0x7E));
        return safe ? v : "=?base64?" + Base64.getEncoder().encodeToString(v.getBytes(StandardCharsets.UTF_8)) + "?=";
    }
}
