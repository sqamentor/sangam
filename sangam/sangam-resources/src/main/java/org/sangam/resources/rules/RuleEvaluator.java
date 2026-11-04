// SPDX-License-Identifier: Apache-2.0
package org.sangam.resources.rules;

import org.operaton.bpm.dmn.engine.DmnDecision;
import org.operaton.bpm.dmn.engine.DmnDecisionResult;
import org.operaton.bpm.dmn.engine.DmnEngine;
import org.operaton.bpm.dmn.engine.DmnEngineConfiguration;
import org.sangam.resources.support.ResourcesException;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Evaluates DMN decisions with Operaton's standalone DMN engine (DMN 1.3+ / FEEL). Parsed decisions are cached by
 * content hash; a published table version is immutable. The evaluation result carries the table hash so every decision
 * that changes a booking, a waitlist position, a refund or a roster is auditable.
 */
public final class RuleEvaluator {

    /** The matched rows plus the evidence of which table produced them. */
    public record Outcome(String key, String hash, List<Map<String, Object>> rows) {
        public Optional<Map<String, Object>> first() {
            return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
        }

        public Object value(String output) {
            return rows.isEmpty() ? null : rows.get(0).get(output);
        }
    }

    private final DecisionSource source;
    private final DmnEngine engine;
    private final Map<String, DmnDecision> cache = new ConcurrentHashMap<>();

    public RuleEvaluator(DecisionSource source) {
        this(source, null);
    }

    public RuleEvaluator(DecisionSource source, DmnEngine engine) {
        this.source = source;
        this.engine = engine != null ? engine : DmnEngineConfiguration.createDefaultDmnEngineConfiguration().buildEngine();
    }

    public boolean has(String tenantId, String key) {
        return key != null && source.find(tenantId, key).isPresent();
    }

    /** Evaluates the decision {@code key}; empty when no table is configured for it (callers fall back to profile data). */
    public Optional<Outcome> evaluate(String tenantId, String key, Map<String, Object> inputs) {
        if (key == null) {
            return Optional.empty();
        }
        Optional<DecisionSource.Decision> found = source.find(tenantId, key);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        DecisionSource.Decision d = found.get();
        DmnDecision parsed = cache.computeIfAbsent(d.hash() + "|" + d.decisionId(), k -> {
            try {
                return engine.parseDecision(d.decisionId(),
                        new ByteArrayInputStream(d.dmnXml().getBytes(StandardCharsets.UTF_8)));
            } catch (RuntimeException e) {
                throw ResourcesException.rule("DMN_INVALID", "decision table " + key + " is invalid: " + e.getMessage());
            }
        });
        DmnDecisionResult result;
        try {
            result = engine.evaluateDecision(parsed, new LinkedHashMap<>(inputs));
        } catch (RuntimeException e) {
            throw ResourcesException.rule("DMN_FAILED", "decision table " + key + " failed: " + e.getMessage());
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : result.getResultList()) {
            rows.add(new LinkedHashMap<>(row));
        }
        return Optional.of(new Outcome(key, d.hash(), rows));
    }
}
