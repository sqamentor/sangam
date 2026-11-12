// SPDX-License-Identifier: Apache-2.0
package org.sangam.scriptworker;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.sangam.sdk.model.TypedValue;
import org.sangam.sdk.worker.BpmnError;
import org.sangam.sdk.worker.TaskCancelledException;
import org.sangam.sdk.worker.TaskContext;
import org.sangam.sdk.worker.TaskFailedException;
import org.sangam.sdk.worker.TaskHandler;

/**
 * The lease-protocol side of one script task (VI.3): reads the request by the {@link ScriptTopics} convention, runs
 * it in the sandbox and maps the {@link ScriptOutcome}:
 * <ul>
 *   <li>completed: the result variables complete the work item (decimals stay typed {@code Decimal});</li>
 *   <li>BPMN error raised by the script: {@link BpmnError}, the model routes it;</li>
 *   <li>invalid request, script error, limit exceeded: a failure with no retries left, so an incident at once. These
 *       are deterministic (the same script on the same input fails the same way); retrying would only burn CPU;</li>
 *   <li>sandbox failure: a failure with the subscription's normal retries (no outside effect happened);</li>
 *   <li>cancelled: {@link TaskCancelledException}, which the SDK acknowledges or unlocks.</li>
 * </ul>
 */
public final class ScriptTaskHandler implements TaskHandler {

    private final ScriptRunner runner;
    private final ScriptLimits limits;
    private final ObjectMapper json = new ObjectMapper();

    public ScriptTaskHandler(ScriptRunner runner, ScriptLimits limits) {
        this.runner = runner;
        this.limits = limits;
    }

    @Override
    public Map<String, Object> handle(TaskContext ctx) throws Exception {
        ScriptRequest request;
        try {
            request = ScriptRequest.from(ctx.topic(), plain(ctx.variables()), limits);
        } catch (ScriptRequest.InvalidScriptException e) {
            throw incident("invalid script task: " + e.getMessage(), null, e);
        }
        ScriptOutcome outcome = runner.run(request, context(ctx), () -> cancelReason(ctx));
        return switch (outcome) {
            case ScriptOutcome.Completed c -> typed(c.variables());
            case ScriptOutcome.BpmnErrorRaised b -> throw new BpmnError(b.code(), b.message(), typed(b.variables()));
            case ScriptOutcome.ScriptError s -> throw incident("script error: " + s.message(), s.details(), null);
            case ScriptOutcome.LimitExceeded l -> throw incident("script limit: " + l.message(), l.details(), null);
            case ScriptOutcome.SandboxFailure f -> throw new TaskFailedException("sandbox: " + f.message(), f.details(),
                    null, null, null);
            case ScriptOutcome.Cancelled x -> throw new TaskCancelledException(x.reason());
        };
    }

    private static TaskFailedException incident(String message, String details, Throwable cause) {
        return new TaskFailedException(message, details, 0, null, cause);
    }

    static String cancelReason(TaskContext ctx) {
        if (ctx.isCancelRequested()) {
            return "cancel requested by server";
        }
        if (ctx.isLeaseLost()) {
            return "lease lost";
        }
        if (ctx.isShuttingDown()) {
            return "worker shutting down";
        }
        return Thread.currentThread().isInterrupted() ? "interrupted" : null;
    }

    /** Identifiers only; never the token, the URL or any configuration of the worker. */
    static Map<String, Object> context(TaskContext ctx) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("workId", ctx.workId());
        c.put("topic", ctx.topic());
        c.put("operationId", ctx.operationId());
        c.put("processInstanceId", ctx.processInstanceId());
        c.put("businessKey", ctx.businessKey());
        return c;
    }

    /** Typed variables to plain JSON values the script can read (decimals as numbers, dates as ISO text). */
    Map<String, Object> plain(Map<String, Object> vars) {
        Map<String, Object> out = new LinkedHashMap<>();
        vars.forEach((k, v) -> out.put(k, plainValue(v)));
        return out;
    }

    private Object plainValue(Object raw) {
        Object v = TypedValue.unwrap(raw, Object.class, json);
        return switch (v) {
            case null -> null;
            case Instant i -> i.toString();
            case LocalDate d -> d.toString();
            case byte[] b -> Base64.getEncoder().encodeToString(b);
            case Map<?, ?> m -> {
                Map<String, Object> copy = new LinkedHashMap<>();
                m.forEach((k, x) -> copy.put(String.valueOf(k), plainValue(x)));
                yield copy;
            }
            case List<?> l -> l.stream().map(this::plainValue).toList();
            default -> v;
        };
    }

    /** Top-level decimals go back typed, so an amount computed by a script is never stored as binary floating point. */
    static Map<String, Object> typed(Map<String, Object> vars) {
        Map<String, Object> out = new LinkedHashMap<>();
        vars.forEach((k, v) -> out.put(k, v instanceof BigDecimal d ? TypedValue.decimal(d) : v));
        return out;
    }
}
