// SPDX-License-Identifier: Apache-2.0
package org.sangam.ai;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.sangam.ai.a2a.A2a;
import org.sangam.ai.agent.AgentTool;
import org.sangam.ai.llm.Llm;
import org.sangam.ai.llm.OpenAiCompatibleLlm;
import org.sangam.ai.mcp.McpClient;
import org.sangam.ai.mcp.McpServer;
import org.sangam.integration.connector.EgressPolicy;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.SecretResolver;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The HTTP protocol clients against loopback servers, including our own MCP and A2A servers. Not conformance tests against third parties. */
class ProtocolLoopbackTest {

    HttpServer server;
    final SecretResolver secrets = SecretResolver.of(Map.of());

    URI serve(HttpHandler h) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", h);
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    static void reply(com.sun.net.httpserver.HttpExchange x, int status, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().add("Content-Type", "application/json");
        x.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
        if (b.length > 0) {
            x.getResponseBody().write(b);
        }
        x.close();
    }

    static AgentTool.Context ctx() {
        return new AgentTool.Context("t", "run", "call", "alice", "root", null, UUID.randomUUID(), "a", 1, 0);
    }

    @Test
    void llmClientMapsToolCallsAndUsage() throws Exception {
        List<String> bodies = new ArrayList<>();
        URI base = serve(x -> {
            bodies.add(new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            reply(x, 200, "{\"model\":\"m1\",\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":4},\"choices\":[{\"message\":{\"content\":null,\"tool_calls\":[{\"id\":\"c\",\"function\":{\"name\":\"f\",\"arguments\":\"{\\\"a\\\":1}\"}}]}}]}");
        });
        var llm = new OpenAiCompatibleLlm(new OpenAiCompatibleLlm.Config(base, "m1", null, null, Duration.ofSeconds(5)), HttpTransport.forSimulator(), secrets);
        Llm.Response r = llm.complete(new Llm.Request("t", List.of(Llm.Message.user("hi")), List.of(new Llm.ToolSpec("f", "d", Map.of("type", "object"))), 10, 0));
        assertEquals("f", r.toolCalls().get(0).name());
        assertEquals(1, ((Number) r.toolCalls().get(0).arguments().get("a")).intValue());
        assertEquals(7, r.inputTokens() + r.outputTokens());
        assertTrue(bodies.get(0).contains("\"tools\""));
    }

    @Test
    void llmClientMakesNoCallWithoutEgressRule() throws Exception {
        int[] hits = {0};
        URI base = serve(x -> {
            hits[0]++;
            reply(x, 200, "{}");
        });
        var llm = new OpenAiCompatibleLlm(new OpenAiCompatibleLlm.Config(base, "m", null, null, Duration.ofSeconds(5)), EgressPolicy.denyAll(), secrets);
        Llm.CallException e = assertThrows(Llm.CallException.class, () -> llm.complete(Llm.Request.of("t", "s", "u", 1)));
        assertFalse(e.retryable());
        assertEquals(0, hits[0]);
    }

    @Test
    void mcpClientTalksToOurServerAndFiltersTools() throws Exception {
        McpServer mcp = new McpServer((m, p, q, b, t, r) -> new McpServer.Reply(200, "ok:" + m + ":" + p + ":" + r), x -> true);
        URI base = serve(x -> {
            String reqBody = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String a = mcp.handle(reqBody, new McpServer.Caller("t", "tok"));
            reply(x, a == null ? 202 : 200, a == null ? "" : a);
        });
        McpClient untrusting = new McpClient(new McpClient.Config("plat", base, "t", null, Set.of("get_task")), HttpTransport.forSimulator(), secrets);
        assertTrue(untrusting.tools().get(0).effectful(), "annotations are untrusted by default");
        McpClient c = new McpClient(new McpClient.Config("plat", base, "t", null, Set.of("get_task", "complete_task"), true), HttpTransport.forSimulator(), secrets);
        List<AgentTool> tools = c.tools();
        assertEquals(McpClient.MODERN_VERSION, c.protocolVersion());
        assertEquals(2, tools.size());
        AgentTool read = tools.stream().filter(t -> t.spec().name().equals("plat.get_task")).findFirst().orElseThrow();
        AgentTool write = tools.stream().filter(t -> t.spec().name().equals("plat.complete_task")).findFirst().orElseThrow();
        assertFalse(read.effectful());
        assertTrue(write.effectful(), "a write tool needs approval");
        assertEquals("ok:GET:/tasks/9:null", ((AgentTool.Done) read.invoke(ctx(), Map.of("id", "9"))).output().get("text"));
        assertEquals("ok:POST:/tasks/9/complete:why", ((AgentTool.Done) write.invoke(ctx(), Map.of("id", "9", "reason", "why"))).output().get("text"));
        assertInstanceOf(AgentTool.Failed.class, write.invoke(ctx(), Map.of("id", "9")), "missing reason");
    }

    @Test
    void mcpClientFallsBackToLegacyAndReadsSseWithNotifications() throws Exception {
        List<String> versions = new ArrayList<>();
        URI base = serve(x -> {
            String b = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var j = org.sangam.integration.connector.Json.MAPPER.readTree(b);
            String m = j.path("method").asText();
            versions.add(m + "=" + x.getRequestHeaders().getFirst("MCP-Protocol-Version"));
            if (m.equals("server/discover")) {
                reply(x, 400, "");
            } else if (m.equals("initialize")) {
                reply(x, 200, "{\"jsonrpc\":\"2.0\",\"id\":" + j.get("id") + ",\"result\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{}}}");
            } else if (m.startsWith("notifications/")) {
                reply(x, 202, "");
            } else {
                String sse = "id: 1\ndata:\n\nevent: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{}}\n\n"
                        + "data: {\"jsonrpc\":\"2.0\",\"id\":" + j.get("id") + ",\"result\":{\"tools\":[{\"name\":\"a\",\"inputSchema\":{\"type\":\"object\"}}]}}\n\n";
                byte[] bytes = sse.getBytes(StandardCharsets.UTF_8);
                x.getResponseHeaders().add("Content-Type", "text/event-stream");
                x.sendResponseHeaders(200, bytes.length);
                x.getResponseBody().write(bytes);
                x.close();
            }
        });
        McpClient c = new McpClient(new McpClient.Config("s", base, "t", null, Set.of("a")), HttpTransport.forSimulator(), secrets);
        assertEquals(1, c.tools().size());
        assertEquals("2025-06-18", c.protocolVersion());
        assertTrue(versions.contains("tools/list=2025-06-18"), versions.toString());
    }

    @Test
    void mcpServerAnswersEachEra() {
        McpServer s = new McpServer((m, p, q, b, t, r) -> new McpServer.Reply(200, "ok"), x -> false);
        McpServer.Caller who = new McpServer.Caller("t", "tok");
        String meta = "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\"}";
        assertTrue(s.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"server/discover\",\"params\":{" + meta + "}}", who).contains("supportedVersions"));
        assertTrue(s.handle("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{" + meta + "}}", who).contains("\"cacheScope\":\"private\""));
        assertTrue(s.handle("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\",\"params\":{\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"1900-01-01\"}}}", who).contains("-32022"));
        assertTrue(s.handle("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\"}}", who).contains("\"protocolVersion\":\"2025-06-18\""));
        String call = "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"list_tasks\"," + meta + "}}";
        assertTrue(s.handle(call, who).contains("\"resultType\":\"complete\""));
        assertNull(McpServer.validateHttpHeaders(call, "2026-07-28", "tools/call", "list_tasks"));
        assertTrue(McpServer.validateHttpHeaders(call, "2026-07-28", "tools/call", "other").contains("-32020"));
    }

    @Test
    void a2aFallsBackTo03WhenThePeerRefuses10() throws Exception {
        List<String> methods = new ArrayList<>();
        URI base = serve(x -> {
            String b = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String m = org.sangam.integration.connector.Json.MAPPER.readTree(b).path("method").asText();
            methods.add(m);
            reply(x, 200, m.equals("SendMessage") ? "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32601,\"message\":\"nope\"}}"
                    : "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"kind\":\"task\",\"id\":\"x\"}}");
        });
        assertInstanceOf(AgentTool.Done.class, A2a.remoteAgent("p", base, null, HttpTransport.forSimulator(), secrets).invoke(ctx(), Map.of("input", "x")));
        assertEquals(List.of("SendMessage", "message/send"), methods);
    }

    @Test
    void a2aClientTalksToOurServerAndUnknownOutcomeParks() throws Exception {
        List<String> started = new ArrayList<>();
        A2a.Server a2a = new A2a.Server(new A2a.InboundConfig("t", "svc", "intake", "n", "d", "u", id -> id.equals("peer")), new A2a.ProcessGateway() {
            public String start(String t, String p, String k, String bk, Map<String, Object> v) {
                started.add(bk + ":" + v.get("a2aInput"));
                return "inst-1";
            }

            public String status(String t, String p, String id) {
                return "working";
            }
        });
        assertTrue(a2a.handle("stranger", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tasks/get\",\"params\":{\"id\":\"x\"}}").contains("caller not allowed"));
        URI base = serve(x -> reply(x, 200, a2a.handle("peer", new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))));
        AgentTool tool = A2a.remoteAgent("peer-agent", base, null, HttpTransport.forSimulator(), secrets);
        AgentTool.Context c = ctx();
        AgentTool.Result r = tool.invoke(c, Map.of("input", "please"));
        assertInstanceOf(AgentTool.Done.class, r);
        assertEquals(List.of("a2a:" + c.operationId() + ":please"), started);
        assertTrue(a2a.agentCard().containsKey("skills"));

        server.stop(0);
        server = null;
        URI broken = serve(x -> reply(x, 500, "{}"));
        assertInstanceOf(AgentTool.Pending.class, A2a.remoteAgent("p", broken, null, HttpTransport.forSimulator(), secrets).invoke(ctx(), Map.of("input", "x")));
    }
}
