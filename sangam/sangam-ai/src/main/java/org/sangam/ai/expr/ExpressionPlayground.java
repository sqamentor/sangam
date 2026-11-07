// SPDX-License-Identifier: Apache-2.0
package org.sangam.ai.expr;

import org.operaton.bpm.dmn.feel.impl.FeelEngine;
import org.operaton.bpm.dmn.feel.impl.scala.ScalaFeelEngineFactory;
import org.operaton.bpm.engine.variable.Variables;
import org.sangam.ai.llm.Llm;
import org.sangam.ai.llm.LlmPort;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Expression playground and assistant. Evaluates FEEL only: FEEL has no side effects and no access to beans or Java types.
 * JUEL is refused, because it can invoke methods. A time limit bounds the wait; an expression that overruns gets an
 * interrupt, but a tight loop in the FEEL engine can keep its daemon thread busy until it ends. The assistant only proposes
 * text, and the user runs it here before saving.
 */
public final class ExpressionPlayground {

    public record Evaluation(boolean ok, Object value, String error, long millis) {
    }

    public static final int MAX_CHARS = 8000;

    private static final FeelEngine ENGINE = new ScalaFeelEngineFactory().createInstance();

    private final Duration limit;
    private final LlmPort llm;

    public ExpressionPlayground(Duration limit, LlmPort llm) {
        this.limit = limit;
        this.llm = llm;
    }

    public Evaluation evaluate(String expression, Map<String, Object> sampleVariables) {
        long t0 = System.nanoTime();
        if (expression == null || expression.isBlank() || expression.length() > MAX_CHARS) {
            return new Evaluation(false, null, "expression must have 1 to " + MAX_CHARS + " characters", 0);
        }
        ExecutorService one = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "sangam-feel-playground");
            t.setDaemon(true);
            return t;
        });
        try {
            Future<Object> f = one.submit(() -> ENGINE.evaluateSimpleExpression(expression,
                    Variables.fromMap(sampleVariables == null ? Map.of() : sampleVariables).asVariableContext()));
            Object v = f.get(limit.toMillis(), TimeUnit.MILLISECONDS);
            return new Evaluation(true, v, null, (System.nanoTime() - t0) / 1_000_000);
        } catch (TimeoutException e) {
            return new Evaluation(false, null, "time limit of " + limit.toMillis() + " ms exceeded", limit.toMillis());
        } catch (ExecutionException e) {
            return new Evaluation(false, null, e.getCause() == null ? e.toString() : e.getCause().getMessage(), (System.nanoTime() - t0) / 1_000_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Evaluation(false, null, "interrupted", 0);
        } finally {
            one.shutdownNow();
        }
    }

    /** Returns a proposed FEEL expression. Nothing is stored or evaluated. */
    public String propose(String tenantId, String intent, Map<String, Object> sampleVariables) {
        return llm.complete(Llm.Request.of(tenantId, "Output one FEEL expression and nothing else.",
                intent + "\nAvailable variables: " + (sampleVariables == null ? "[]" : sampleVariables.keySet()), 500)).content().strip();
    }

    /** Evaluation with no variables separates syntax errors from missing-variable errors by the engine's message. */
    public static List<String> syntaxErrors(String expression) {
        if (expression == null || expression.isBlank()) {
            return List.of("empty expression");
        }
        try {
            ENGINE.evaluateSimpleExpression(expression, Variables.createVariables().asVariableContext());
            return List.of();
        } catch (RuntimeException e) {
            String m = String.valueOf(e.getMessage());
            return m.toLowerCase(java.util.Locale.ROOT).contains("syntax") || m.toLowerCase(java.util.Locale.ROOT).contains("parse")
                    ? List.of("FEEL: " + m) : List.of();
        }
    }
}
