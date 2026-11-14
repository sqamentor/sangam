// SPDX-License-Identifier: Apache-2.0
package org.sangam.ai.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.sangam.ai.agent.AgentTool;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.Json;
import org.sangam.integration.connector.SecretResolver;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decision 171: the MCP 2026-07-28 multi round-trip ({@code input_required}) in {@link McpClient}, against a loopback
 * server written from the specification text. Not a conformance test against another implementation.
 */
class McpInputRequiredTest {

    HttpServer server;
    final List<JsonNode> calls = new ArrayList<>();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** A modern server whose tools/call answer is {@code answer} applied to the request's params. */
    URI serve(Function<JsonNode, String> answer) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", x -> {
            JsonNode j = Json.MAPPER.readTree(x.getRequestBody().readAllBytes());
            String m = j.path("method").asText();
            String result = switch (m) {
                case "server/discover" -> "{\"supportedVersions\":[\"2026-07-28\"],\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"s\",\"version\":\"1\"}}";
                case "tools/list" -> "{\"tools\":[{\"name\":\"a\",\"inputSchema\":{\"type\":\"object\"}}]}";
                case "tools/call" -> {
                    calls.add(j);
                    yield answer.apply(j.path("params"));
                }
                default -> null;
            };
            reply(x, result == null ? "{\"jsonrpc\":\"2.0\",\"id\":" + j.get("id") + ",\"error\":{\"code\":-32601,\"message\":\"no\"}}"
                    : "{\"jsonrpc\":\"2.0\",\"id\":" + j.get("id") + ",\"result\":" + result + "}");
        });
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    static void reply(HttpExchange x, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().add("Content-Type", "application/json");
        x.sendResponseHeaders(200, b.length);
        x.getResponseBody().write(b);
        x.close();
    }

    static AgentTool.Context ctx() {
        return new AgentTool.Context("t", "run", "call", "alice", "root", null, UUID.randomUUID(), "a", 1, 0);
    }

    AgentTool tool(URI base) {
        McpClient c = new McpClient(new McpClient.Config("s", base, "t", null, Set.of("a")), HttpTransport.forSimulator(), SecretResolver.of(Map.of()));
        return c.tools().get(0);
    }

    static final String DONE = "{\"resultType\":\"complete\",\"content\":[{\"type\":\"text\",\"text\":\"done\"}]}";

    @Test
    void retriesWithANewIdAndEchoesTheRequestStateExactly() throws Exception {
        URI base = serve(p -> p.has("requestState") ? DONE : "{\"resultType\":\"input_required\",\"requestState\":\"s-1 \\u00e9\"}");
        AgentTool.Result r = tool(base).invoke(ctx(), Map.of("x", 1));
        assertEquals("done", ((AgentTool.Done) r).output().get("text"));
        assertEquals(2, calls.size());
        assertFalse(calls.get(0).path("params").has("requestState"), "no requestState before the server gave one");
        assertEquals("s-1 \u00e9", calls.get(1).path("params").path("requestState").asText(), "echoed exactly, as a top-level params field");
        assertNotEquals(calls.get(0).get("id"), calls.get(1).get("id"), "the retry is a new JSON-RPC request");
        assertEquals(1, calls.get(1).path("params").path("arguments").path("x").asInt(), "the original arguments are sent again");
    }

    @Test
    void retriesWithoutRequestStateWhenTheServerGaveNone() throws Exception {
        int[] n = {0};
        URI base = serve(p -> n[0]++ == 0 ? "{\"resultType\":\"input_required\"}" : DONE);
        assertInstanceOf(AgentTool.Done.class, tool(base).invoke(ctx(), Map.of()));
        assertEquals(2, calls.size());
        assertFalse(calls.get(1).path("params").has("requestState"), "must not include requestState when none was given");
    }

    @Test
    void inputRequestsForUndeclaredCapabilitiesFailTheCall() throws Exception {
        URI base = serve(p -> "{\"resultType\":\"input_required\",\"inputRequests\":{\"ask\":{\"method\":\"elicitation/create\",\"params\":{}}},\"requestState\":\"s\"}");
        AgentTool.Result r = tool(base).invoke(ctx(), Map.of());
        AgentTool.Failed f = assertInstanceOf(AgentTool.Failed.class, r);
        assertTrue(f.message().contains("ask"), f.message());
        assertEquals(1, calls.size(), "no retry: this client cannot answer an elicitation");
    }

    @Test
    void theRoundsAreBounded() throws Exception {
        URI base = serve(p -> "{\"resultType\":\"input_required\",\"requestState\":\"again\"}");
        assertInstanceOf(AgentTool.Failed.class, tool(base).invoke(ctx(), Map.of()));
        assertEquals(McpClient.MAX_INPUT_ROUNDS, calls.size());
    }

    @Test
    void aResultWithoutResultTypeIsComplete() throws Exception {
        URI base = serve(p -> "{\"content\":[{\"type\":\"text\",\"text\":\"plain\"}]}");
        assertEquals("plain", ((AgentTool.Done) tool(base).invoke(ctx(), Map.of())).output().get("text"));
        assertEquals(1, calls.size());
    }

    @Test
    void theClientDeclaresNoInputCapability() throws Exception {
        URI base = serve(p -> DONE);
        tool(base).invoke(ctx(), Map.of());
        JsonNode caps = calls.get(0).path("params").path("_meta").path("io.modelcontextprotocol/clientCapabilities");
        assertTrue(caps.isObject() && caps.size() == 0, "no elicitation, sampling or roots: " + caps);
    }
}
