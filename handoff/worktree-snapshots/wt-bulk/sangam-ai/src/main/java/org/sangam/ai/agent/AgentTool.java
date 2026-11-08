// SPDX-License-Identifier: Apache-2.0
package org.sangam.ai.agent;

import org.sangam.ai.llm.Llm;

import java.util.Map;
import java.util.UUID;

/**
 * One capability an agent may call. {@link #effectful()} defaults to true: a tool that changes anything outside the run
 * never executes without a recorded human approval.
 */
public interface AgentTool {

    Llm.ToolSpec spec();

    default boolean effectful() {
        return true;
    }

    Result invoke(Context context, Map<String, Object> arguments);

    /** Identity of one tool call; {@code operationId} is stable across retries of that call. */
    record Context(String tenantId, String runId, String toolCallId, String principalId, String rootId, String businessKey,
                   UUID operationId, String attemptId, long lockVersion, int depth) {
    }

    /** Result of a call. Only {@link Done} and {@link Failed} are recorded; the others make the run wait or retry. */
    sealed interface Result permits Done, Failed, Pending, Retry {
    }

    record Done(Map<String, Object> output) implements Result {
    }

    /** A definite failure the LLM is told about. */
    record Failed(String message) implements Result {
    }

    /** No result yet: unknown outcome under reconciliation, or a child run waiting. The run parks until resumed. */
    record Pending(String reason) implements Result {
    }

    /** Transient failure with no recorded effect: try the same operationId again on a later step. */
    record Retry(String reason) implements Result {
    }
}
