// SPDX-License-Identifier: Apache-2.0
package org.sangam.ai.agent;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Executable form of an agent definition. {@link #fromRegistryDocument} reads the document the R72 registry stores
 * ({@code registry:agents}), which is what gives stored definitions a runtime.
 *
 * @param type          ASSISTANT calls the LLM and tools; ORCHESTRATOR additionally has sub-agents as tools, bounded by
 *                      {@code maxDepth}; EXTERNAL delegates the whole input to {@code externalTool}
 * @param allowedTools  tool names the LLM may call; anything else is refused
 * @param knowledgeBase optional knowledge base key searched before the first LLM call
 */
public record AgentDefinition(String key, int version, Type type, String systemPrompt, List<String> allowedTools, int maxSteps,
                              int maxDepth, long maxTokens, String externalTool, String knowledgeBase) {

    public enum Type { ASSISTANT, ORCHESTRATOR, EXTERNAL }

    public AgentDefinition {
        allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
        if (maxSteps <= 0 || maxTokens <= 0 || maxDepth < 0) {
            throw new IllegalArgumentException("agent budgets must be positive");
        }
        if (type == Type.EXTERNAL && (externalTool == null || externalTool.isBlank())) {
            throw new IllegalArgumentException("an EXTERNAL agent needs externalTool");
        }
    }

    public static AgentDefinition fromRegistryDocument(Map<String, Object> d) {
        Object tools = d.get("tools");
        List<String> names = tools instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
        return new AgentDefinition(String.valueOf(d.get("key")), num(d.get("revision"), 1),
                Type.valueOf(String.valueOf(d.getOrDefault("type", "ASSISTANT")).toUpperCase(Locale.ROOT)),
                String.valueOf(d.getOrDefault("systemPrompt", "")), names, num(d.get("maxSteps"), 10), num(d.get("maxDepth"), 0),
                num(d.get("maxTokens"), 20_000), d.get("externalTool") == null ? null : String.valueOf(d.get("externalTool")),
                d.get("knowledgeBase") == null ? null : String.valueOf(d.get("knowledgeBase")));
    }

    private static int num(Object v, int fallback) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        return v == null ? fallback : Integer.parseInt(v.toString());
    }
}
