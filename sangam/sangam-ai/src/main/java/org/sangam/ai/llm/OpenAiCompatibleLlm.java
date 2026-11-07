// SPDX-License-Identifier: Apache-2.0
package org.sangam.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import org.sangam.integration.connector.EgressPolicy;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.Json;
import org.sangam.integration.connector.RetryPolicy;
import org.sangam.integration.connector.SecretResolver;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client for the widely implemented {@code /chat/completions} and {@code /embeddings} HTTP shape, which self-hosted
 * model servers also expose (R56). Every call passes the tenant's {@link EgressPolicy}: without an allow-list entry the
 * call is refused and nothing leaves the host.
 */
public final class OpenAiCompatibleLlm implements LlmPort {

    public record Config(URI baseUri, String model, String embeddingModel, String apiKeyRef, Duration timeout) {
    }

    private final Config config;
    private final HttpTransport transport;
    private final SecretResolver secrets;

    public OpenAiCompatibleLlm(Config config, EgressPolicy egress, SecretResolver secrets) {
        this(config, new HttpTransport(egress, null, Duration.ofSeconds(10), config.timeout(),
                new RetryPolicy(1, Duration.ofMillis(100), Duration.ofSeconds(1), 0.5)), secrets);
    }

    public OpenAiCompatibleLlm(Config config, HttpTransport transport, SecretResolver secrets) {
        this.config = config;
        this.transport = transport;
        this.secrets = secrets;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public String modelId() {
        return config.model();
    }

    @Override
    public Llm.Response complete(Llm.Request r) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.model());
        body.put("temperature", r.temperature());
        if (r.maxTokens() > 0) {
            body.put("max_tokens", r.maxTokens());
        }
        List<Map<String, Object>> msgs = new ArrayList<>();
        for (Llm.Message m : r.messages()) {
            Map<String, Object> wire = new LinkedHashMap<>();
            wire.put("role", m.role());
            wire.put("content", m.content());
            if (m.toolCallId() != null) {
                wire.put("tool_call_id", m.toolCallId());
            }
            if (!m.toolCalls().isEmpty()) {
                List<Map<String, Object>> calls = new ArrayList<>();
                for (Llm.ToolCall c : m.toolCalls()) {
                    calls.add(Map.of("id", c.id(), "type", "function",
                            "function", Map.of("name", c.name(), "arguments", Json.write(c.arguments()))));
                }
                wire.put("tool_calls", calls);
            }
            msgs.add(wire);
        }
        body.put("messages", msgs);
        if (!r.tools().isEmpty()) {
            List<Map<String, Object>> tools = new ArrayList<>();
            for (Llm.ToolSpec t : r.tools()) {
                tools.add(Map.of("type", "function", "function",
                        Map.of("name", t.name(), "description", t.description(), "parameters", t.jsonSchema())));
            }
            body.put("tools", tools);
        }
        JsonNode root = post(r.tenantId(), "/chat/completions", body);
        JsonNode msg = root.path("choices").path(0).path("message");
        if (msg.isMissingNode()) {
            throw new Llm.CallException("provider answer has no choices[0].message", false);
        }
        List<Llm.ToolCall> calls = new ArrayList<>();
        for (JsonNode c : msg.path("tool_calls")) {
            JsonNode f = c.path("function");
            calls.add(new Llm.ToolCall(c.path("id").asText(), f.path("name").asText(), Json.readMap(f.path("arguments").asText("{}"))));
        }
        JsonNode u = root.path("usage");
        return new Llm.Response(msg.path("content").asText(""), calls, root.path("model").asText(config.model()),
                u.path("prompt_tokens").asLong(), u.path("completion_tokens").asLong());
    }

    @Override
    public float[] embed(String tenantId, String text) {
        if (config.embeddingModel() == null) {
            throw new Llm.CallException("no embedding model configured", false);
        }
        JsonNode v = post(tenantId, "/embeddings", Map.of("model", config.embeddingModel(), "input", text))
                .path("data").path(0).path("embedding");
        if (!v.isArray() || v.isEmpty()) {
            throw new Llm.CallException("provider answer has no embedding", false);
        }
        float[] out = new float[v.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = (float) v.get(i).asDouble();
        }
        return out;
    }

    private JsonNode post(String tenantId, String path, Object body) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        if (config.apiKeyRef() != null) {
            headers.put("Authorization", "Bearer " + secrets.require(config.apiKeyRef()));
        }
        URI uri = URI.create(config.baseUri().toString().replaceAll("/+$", "") + path);
        HttpTransport.Outcome o = transport.execute(tenantId, HttpTransport.Call.post(uri, headers, Json.bytes(body), false), 0);
        return switch (o) {
            case HttpTransport.Outcome.NoEffect ne -> throw new Llm.CallException(ne.reason(), !ne.reason().startsWith("EGRESS_DENIED"));
            case HttpTransport.Outcome.Unknown u -> throw new Llm.CallException(u.reason(), true);
            case HttpTransport.Outcome.Response resp -> {
                if (!resp.is2xx()) {
                    throw new Llm.CallException("provider HTTP " + resp.status(), resp.status() == 429 || resp.status() >= 500);
                }
                JsonNode t = Json.tree(resp.body());
                if (t == null) {
                    throw new Llm.CallException("provider answer is not JSON", false);
                }
                yield t;
            }
        };
    }
}
