// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.ai;

import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.sangam.ai.a2a.A2a;
import org.sangam.ai.agent.AgentDefinition;
import org.sangam.ai.agent.AgentRuntime;
import org.sangam.ai.classify.DocumentClassifier;
import org.sangam.ai.draft.DraftService;
import org.sangam.ai.expr.ExpressionPlayground;
import org.sangam.ai.mcp.McpServer;
import org.sangam.ai.rag.KnowledgeBaseService;

/** The sangam-ai services bound by sangam-server. Its presence activates {@link AiRestConfiguration}. */
public record AiApi(AgentRuntime runtime, KnowledgeBaseService knowledge, DraftService drafts, DocumentClassifier classifier,
                    ExpressionPlayground playground, Function<String, Optional<AgentDefinition>> agents, RunLister runs, McpServer mcp,
                    BiFunction<String, String, Optional<A2a.Server>> a2a) {

    public interface RunLister {
        List<AgentRuntime.Run> list(String tenantId, String principalOrNull, int limit);
    }
}
