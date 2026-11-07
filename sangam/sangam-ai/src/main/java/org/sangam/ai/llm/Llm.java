// SPDX-License-Identifier: Apache-2.0
package org.sangam.ai.llm;

import java.util.List;
import java.util.Map;

/** Provider-neutral value types of the LLM port. */
public final class Llm {

    private Llm() {
    }

    public record ToolSpec(String name, String description, Map<String, Object> jsonSchema) {
    }

    public record ToolCall(String id, String name, Map<String, Object> arguments) {
    }

    /** {@code role} is system, user, assistant or tool; {@code toolCallId} is set on tool messages only. */
    public record Message(String role, String content, String toolCallId, List<ToolCall> toolCalls) {
        public Message {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }

        public static Message system(String c) {
            return new Message("system", c, null, null);
        }

        public static Message user(String c) {
            return new Message("user", c, null, null);
        }

        public static Message tool(String id, String c) {
            return new Message("tool", c, id, null);
        }
    }

    public record Request(String tenantId, List<Message> messages, List<ToolSpec> tools, int maxTokens, double temperature) {
        public Request {
            messages = List.copyOf(messages);
            tools = tools == null ? List.of() : List.copyOf(tools);
        }

        public static Request of(String tenantId, String system, String user, int maxTokens) {
            return new Request(tenantId, List.of(Message.system(system), Message.user(user)), List.of(), maxTokens, 0.0);
        }
    }

    public record Response(String content, List<ToolCall> toolCalls, String model, long inputTokens, long outputTokens) {
        public Response {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }
    }

    /** No provider is configured: the air-gapped default. No outbound call was attempted. */
    public static final class DisabledException extends RuntimeException {
        public DisabledException() {
            super("LLM_DISABLED: no LLM provider is configured");
        }
    }

    /** The provider call failed; {@code retryable} means the same request may be repeated. */
    public static final class CallException extends RuntimeException {
        private final boolean retryable;

        public CallException(String message, boolean retryable) {
            super(message);
            this.retryable = retryable;
        }

        public boolean retryable() {
            return retryable;
        }
    }
}
