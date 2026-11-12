// SPDX-License-Identifier: Apache-2.0
package org.sangam.scriptworker;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Runs one {@link ScriptRequest} in the {@link Sandbox} and reads its answer.
 *
 * <p>Protocol with the harness (runtime {@code runner.js} / {@code runner.py}): the worker writes one JSON object on
 * stdin, {@code {"script", "variables", "context", "resultVariable"}}, and closes it; the harness answers one JSON
 * object on stdout: {@code {"ok":true,"result":{...},"hasValue":b,"value":v}}, or {@code {"ok":false,"bpmnError":
 * {"code","message","variables"}}}, or {@code {"ok":false,"error":{"type","message"}}}.
 *
 * <p>The worker trusts nothing it reads back: stdout is capped ({@link ScriptLimits#stdoutBytes()}, more is a limit
 * failure, never a truncated result), stderr is kept only up to its cap for the incident, the wall time is enforced
 * again from outside (the jail is killed, with its whole process tree), and a cancel or lost lease kills the jail at
 * once. Numbers with a fraction are read as {@code BigDecimal} (no binary floating point for amounts).
 */
public final class ScriptRunner {

    /** Extra wait after the sandbox's own wall limit before the worker kills the jail itself. */
    static final long GRACE_MS = 2_000;
    static final long POLL_MS = 100;

    private final Sandbox sandbox;
    private final ScriptLimits limits;
    private final ObjectMapper json = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    public ScriptRunner(Sandbox sandbox, ScriptLimits limits) {
        this.sandbox = sandbox;
        this.limits = limits;
    }

    /**
     * @param context    identifiers handed to the script (work id, process instance, business key, operation id)
     * @param cancelled  polled while the script runs; true kills the jail (cancel requested, lease lost, shutdown)
     */
    public ScriptOutcome run(ScriptRequest request, Map<String, Object> context, Supplier<String> cancelled) {
        List<String> command = sandbox.command(request.language(), limits);
        Process p;
        try {
            p = new ProcessBuilder(command).redirectInput(ProcessBuilder.Redirect.PIPE).start();
        } catch (IOException e) {
            return new ScriptOutcome.SandboxFailure("the sandbox could not start: " + e.getMessage(), String.join(" ", command));
        }
        CompletableFuture<Capture> out = CompletableFuture.supplyAsync(() -> capture(p.getInputStream(), limits.stdoutBytes()),
                Thread::startVirtualThread);
        CompletableFuture<Capture> err = CompletableFuture.supplyAsync(() -> capture(p.getErrorStream(), limits.stderrBytes()),
                Thread::startVirtualThread);
        try {
            try (OutputStream stdin = p.getOutputStream()) {
                Map<String, Object> in = new LinkedHashMap<>();
                in.put("script", request.source());
                in.put("variables", request.inputs());
                in.put("context", context);
                in.put("resultVariable", request.resultVariable());
                stdin.write(json.writeValueAsBytes(in));
            } catch (IOException e) {
                // the interpreter ended before reading its input; its exit and stderr tell why
            }
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(limits.wallTime().toMillis() + GRACE_MS);
            while (!p.waitFor(POLL_MS, TimeUnit.MILLISECONDS)) {
                String reason = cancelled.get();
                if (reason != null) {
                    kill(p);
                    return new ScriptOutcome.Cancelled(reason);
                }
                if (System.nanoTime() > deadline) {
                    kill(p);
                    return new ScriptOutcome.LimitExceeded("wall time " + limits.wallTime() + " exceeded", tail(err));
                }
                if (out.isDone() && out.getNow(null) != null && out.getNow(null).overflow()) {
                    kill(p);
                    return new ScriptOutcome.LimitExceeded("result larger than " + limits.stdoutBytes() + " bytes", tail(err));
                }
            }
            Capture o = out.get(GRACE_MS, TimeUnit.MILLISECONDS);
            return interpret(p.exitValue(), o, tail(err), request.resultVariable());
        } catch (InterruptedException e) {
            kill(p);
            Thread.currentThread().interrupt();
            return new ScriptOutcome.Cancelled("worker interrupted");
        } catch (ExecutionException | TimeoutException e) {
            kill(p);
            return new ScriptOutcome.SandboxFailure("could not read the script's answer: " + e, tail(err));
        }
    }

    ScriptOutcome interpret(int exit, Capture stdout, String stderr, String resultVariable) {
        if (stdout.overflow()) {
            return new ScriptOutcome.LimitExceeded("result larger than " + limits.stdoutBytes() + " bytes", stderr);
        }
        // read straight into maps (not a JsonNode tree, whose factory may strip trailing zeros of a decimal)
        Map<String, Object> answer;
        try {
            answer = stdout.bytes().length == 0 ? null : json.readValue(stdout.bytes(), MAP);
        } catch (IOException e) {
            answer = null;
        }
        if (answer == null || !(answer.get("ok") instanceof Boolean ok)) {
            // no harness answer: the jail ended the interpreter (a limit) or never ran it
            if (exit >= 128 && exit < 255) {
                return new ScriptOutcome.LimitExceeded("the script was stopped by a sandbox limit (exit " + exit
                        + ": signal " + (exit - 128) + ")", stderr);
            }
            return new ScriptOutcome.SandboxFailure("the script ended without an answer (exit " + exit + ")", stderr);
        }
        if (ok) {
            Map<String, Object> vars = new LinkedHashMap<>();
            if (answer.get("result") instanceof Map<?, ?> result) {
                result.forEach((k, v) -> vars.put(String.valueOf(k), v));
            }
            if (resultVariable != null && Boolean.TRUE.equals(answer.get("hasValue"))) {
                vars.put(resultVariable, answer.get("value"));
            }
            return new ScriptOutcome.Completed(vars);
        }
        if (answer.get("bpmnError") instanceof Map<?, ?> be && be.get("code") instanceof String code && !code.isBlank()) {
            Map<String, Object> vars = new LinkedHashMap<>();
            if (be.get("variables") instanceof Map<?, ?> v) {
                v.forEach((k, x) -> vars.put(String.valueOf(k), x));
            }
            return new ScriptOutcome.BpmnErrorRaised(code, String.valueOf(be.get("message") == null ? "" : be.get("message")),
                    vars);
        }
        Map<?, ?> error = answer.get("error") instanceof Map<?, ?> m ? m : Map.of();
        String message = (error.get("type") instanceof String t ? t : "Error") + ": "
                + (error.get("message") == null ? "" : error.get("message"));
        return new ScriptOutcome.ScriptError(clip(message, 500), stderr);
    }

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    record Capture(byte[] bytes, boolean overflow) {
    }

    /** Reads up to {@code cap} bytes and keeps draining the rest (so the child never blocks on a full pipe). */
    static Capture capture(InputStream in, int cap) {
        ByteArrayOutputStream kept = new ByteArrayOutputStream(Math.min(cap, 8192));
        boolean overflow = false;
        byte[] buf = new byte[8192];
        try (in) {
            int n;
            while ((n = in.read(buf)) >= 0) {
                int room = cap - kept.size();
                if (n > room) {
                    overflow = true;
                }
                if (room > 0) {
                    kept.write(buf, 0, Math.min(n, room));
                }
            }
        } catch (IOException e) {
            // the jail was killed; keep what was read
        }
        return new Capture(kept.toByteArray(), overflow);
    }

    private static String tail(CompletableFuture<Capture> err) {
        try {
            Capture c = err.get(500, TimeUnit.MILLISECONDS);
            return new String(c.bytes(), StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } catch (ExecutionException | TimeoutException e) {
            return "";
        }
    }

    /** Kills the jail and everything it started (nsjail is the parent of the whole tree; its PID namespace dies with it). */
    private static void kill(Process p) {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
    }

    private static String clip(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}
