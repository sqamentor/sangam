// SPDX-License-Identifier: Apache-2.0
package org.sangam.ai.agent;

import org.sangam.ai.llm.Llm;
import org.sangam.core.layer.Hashes;
import org.sangam.integration.connector.ConnectorInvoker;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Built-in tool kinds: a connector call through the operation ledger, and a sub-agent. */
public final class AgentTools {

    private static final UUID NS = UUID.fromString("7f6d7a0e-1f5a-5e7d-8c33-6a1c2d9e0b42");

    private AgentTools() {
    }

    /** Effectful connector call: intent, dispatch and outcome go through {@link ConnectorInvoker} (exactly-once by contract). */
    public static AgentTool connector(Llm.ToolSpec spec, ConnectorInvoker invoker, String connectorId, String operation) {
        return new AgentTool() {
            @Override
            public Llm.ToolSpec spec() {
                return spec;
            }

            @Override
            public Result invoke(Context ctx, Map<String, Object> args) {
                ConnectorInvoker.Invocation inv = new ConnectorInvoker.Invocation(ctx.tenantId(), ctx.rootId(), ctx.runId(), 0, ctx.toolCallId(),
                        ctx.businessKey(), connectorId, operation, args, ctx.attemptId(), ctx.lockVersion(), Instant.now().plusSeconds(60), ctx.operationId());
                return switch (invoker.invoke(inv)) {
                    case ConnectorInvoker.Completed c -> new Done(c.output());
                    case ConnectorInvoker.Failed f -> new Failed(f.code() + ": " + f.message());
                    case ConnectorInvoker.Retry r -> new Retry(r.message());
                    case ConnectorInvoker.Unknown u -> new Pending("UNKNOWN_OUTCOME: " + u.reason());
                    case ConnectorInvoker.Stale s -> new Pending("STALE_LEASE: " + s.message());
                };
            }
        };
    }

    /**
     * Sub-agent for an ORCHESTRATOR. The child run id derives from (parent, tool call), so resuming cannot start a second
     * child. The child's own effects need their own approvals, so the sub-agent call itself is not effectful.
     */
    public static AgentTool subAgent(Llm.ToolSpec spec, AgentDefinition child, AgentRuntime runtime, int maxDepth) {
        return new AgentTool() {
            @Override
            public Llm.ToolSpec spec() {
                return spec;
            }

            @Override
            public boolean effectful() {
                return false;
            }

            @Override
            public Result invoke(Context ctx, Map<String, Object> args) {
                if (ctx.depth() + 1 > maxDepth) {
                    return new Failed("BUDGET_EXCEEDED: maxDepth " + maxDepth);
                }
                String childId = Hashes.uuidV5(NS, ctx.runId() + "|" + ctx.toolCallId()).toString();
                runtime.start(childId, child, ctx.tenantId(), ctx.principalId(), String.valueOf(args.getOrDefault("input", "")), ctx.rootId(),
                        ctx.businessKey(), ctx.runId(), ctx.depth() + 1);
                AgentRuntime.Run r = runtime.drive(childId, ctx.attemptId() + ":child");
                return switch (r.state()) {
                    case COMPLETED -> new Done(Map.of("result", String.valueOf(r.result()), "childRunId", childId));
                    case FAILED, CANCELLED -> new Failed("child " + childId + " " + r.state() + ": " + r.result());
                    case RUNNABLE -> new Retry("child " + childId + " still runnable");
                    case WAITING_APPROVAL, WAITING_EXTERNAL -> new Pending("child " + childId + " " + r.state());
                };
            }
        };
    }
}
