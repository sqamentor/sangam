// SPDX-License-Identifier: Apache-2.0
package org.sangam.ai.llm;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Simulator profile: in-process, no network. Answers come from a queue of scripted responses, then from a responder
 * function. It simulates a provider's protocol; it has no intelligence. Embeddings are feature-hashed token counts, which
 * give stable lexical similarity only.
 */
public final class SimulatedLlm implements LlmPort {

    public static final int EMBEDDING_DIMENSIONS = 256;

    private final Deque<Llm.Response> scripted = new ArrayDeque<>();
    private final Function<Llm.Request, String> responder;

    public SimulatedLlm(Function<Llm.Request, String> responder) {
        this.responder = responder;
    }

    public SimulatedLlm() {
        this(r -> "");
    }

    public SimulatedLlm script(String content) {
        scripted.add(new Llm.Response(content, List.of(), modelId(), 1, 1));
        return this;
    }

    public SimulatedLlm scriptToolCalls(Llm.ToolCall... calls) {
        scripted.add(new Llm.Response("", List.of(calls), modelId(), 1, 1));
        return this;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public String modelId() {
        return "simulator";
    }

    @Override
    public synchronized Llm.Response complete(Llm.Request request) {
        Llm.Response next = scripted.poll();
        return next != null ? next : new Llm.Response(responder.apply(request), List.of(), modelId(), 1, 1);
    }

    @Override
    public float[] embed(String tenantId, String text) {
        float[] v = new float[EMBEDDING_DIMENSIONS];
        for (String token : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (!token.isEmpty()) {
                int h = java.util.Arrays.hashCode(token.getBytes(StandardCharsets.UTF_8));
                v[Math.floorMod(h, EMBEDDING_DIMENSIONS)] += 1f;
            }
        }
        return v;
    }
}
