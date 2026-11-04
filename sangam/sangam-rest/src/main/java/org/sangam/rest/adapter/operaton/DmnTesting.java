// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.operaton.bpm.dmn.engine.DmnDecision;
import org.operaton.bpm.dmn.engine.DmnDecisionTableResult;
import org.operaton.bpm.dmn.engine.DmnEngine;
import org.operaton.bpm.dmn.engine.DmnEngineConfiguration;
import org.operaton.bpm.dmn.engine.delegate.DmnDecisionTableEvaluationEvent;
import org.operaton.bpm.dmn.engine.delegate.DmnDecisionTableEvaluationListener;
import org.operaton.bpm.dmn.engine.delegate.DmnEvaluatedDecisionRule;
import org.operaton.bpm.dmn.engine.impl.DefaultDmnEngineConfiguration;
import org.operaton.bpm.model.dmn.Dmn;
import org.operaton.bpm.model.dmn.DmnModelInstance;
import org.operaton.bpm.model.dmn.HitPolicy;
import org.operaton.bpm.model.dmn.instance.Decision;
import org.operaton.bpm.model.dmn.instance.DecisionTable;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.support.TypedVariables;

/**
 * Offline DMN evaluation for model tests: power-table checks (IX.5 rule 3) and package tests (R80). Uses a private
 * Operaton DMN engine (no process engine, no history), so tests never touch runtime state.
 */
public final class DmnTesting {

    /** Outcome of one evaluation: the hit-policy result, the error if evaluation failed, and the rule ids that match. */
    public record Evaluation(List<Map<String, Object>> results, String error, List<String> matchingRuleIds) {
    }

    private static final ThreadLocal<List<String>> MATCHED = ThreadLocal.withInitial(ArrayList::new);
    private static final DmnEngine ENGINE = buildEngine();

    private DmnTesting() {
    }

    private static DmnEngine buildEngine() {
        DefaultDmnEngineConfiguration cfg =
                (DefaultDmnEngineConfiguration) DmnEngineConfiguration.createDefaultDmnEngineConfiguration();
        DmnDecisionTableEvaluationListener listener = (DmnDecisionTableEvaluationEvent event) -> {
            for (DmnEvaluatedDecisionRule r : event.getMatchingRules()) {
                MATCHED.get().add(r.getId());
            }
        };
        cfg.customPostDecisionTableEvaluationListeners(List.of(listener));
        return cfg.buildEngine();
    }

    /** Parses DMN XML; a parse failure is {@code 422 MODEL_INVALID}. */
    public static DmnModelInstance parse(String dmnXml) {
        try {
            return Dmn.readModelFromStream(new ByteArrayInputStream(dmnXml.getBytes(StandardCharsets.UTF_8)));
        } catch (RuntimeException e) {
            throw ApiProblem.unprocessable("MODEL_INVALID", "The DMN XML cannot be parsed: " + e.getMessage());
        }
    }

    /** The decision tables of the model (decisions without a table are skipped). */
    public static List<Decision> decisionTables(DmnModelInstance model) {
        List<Decision> out = new ArrayList<>();
        for (Decision d : model.getModelElementsByType(Decision.class)) {
            if (d.getExpression() instanceof DecisionTable) {
                out.add(d);
            }
        }
        return out;
    }

    /** The table's hit policy name ("UNIQUE" when absent, the DMN default). */
    public static String hitPolicy(Decision decision) {
        HitPolicy hp = ((DecisionTable) decision.getExpression()).getHitPolicy();
        return hp == null ? "UNIQUE" : hp.name();
    }

    /**
     * Evaluates {@code decisionKey} twice: with its own hit policy (the real result) and as COLLECT (to learn which
     * rules match, which a UNIQUE table cannot report when it fails on an overlap).
     */
    public static Evaluation evaluate(String dmnXml, String decisionKey, Map<String, Object> inputs) {
        Map<String, Object> vars = TypedVariables.fromJson(inputs == null ? Map.of() : inputs);
        List<Map<String, Object>> results = null;
        String error = null;
        try {
            DmnDecisionTableResult r = ENGINE.evaluateDecisionTable(decision(parse(dmnXml), decisionKey), vars);
            results = new ArrayList<>();
            for (Map<String, Object> entry : r.getResultList()) {
                results.add(TypedVariables.toJson(entry));
            }
        } catch (ApiProblem p) {
            throw p;
        } catch (RuntimeException e) {
            error = e.getMessage();
        }
        DmnModelInstance collect = parse(dmnXml);
        for (Decision d : decisionTables(collect)) {
            DecisionTable t = (DecisionTable) d.getExpression();
            t.setHitPolicy(HitPolicy.COLLECT);
            t.setAggregation(null);
        }
        List<String> matched = new ArrayList<>();
        MATCHED.get().clear();
        try {
            ENGINE.evaluateDecisionTable(decision(collect, decisionKey), vars);
            matched.addAll(MATCHED.get());
        } catch (RuntimeException e) {
            if (error == null) {
                error = e.getMessage();
            }
        } finally {
            MATCHED.get().clear();
        }
        return new Evaluation(results, error, matched);
    }

    /** True when {@code expected} equals the first result entry, or (several entries) appears among them. */
    public static boolean matches(Map<String, Object> expected, List<Map<String, Object>> results) {
        if (results == null) {
            return false;
        }
        if (expected == null || expected.isEmpty()) {
            return results.isEmpty();
        }
        for (Map<String, Object> r : results) {
            boolean all = true;
            for (Map.Entry<String, Object> e : expected.entrySet()) {
                if (!same(e.getValue(), r.get(e.getKey()))) {
                    all = false;
                    break;
                }
            }
            if (all) {
                return true;
            }
        }
        return false;
    }

    /** First result entry for reporting (or the error). */
    public static Map<String, Object> actual(Evaluation ev) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (ev.error() != null) {
            m.put("error", ev.error());
        } else if (ev.results() != null && ev.results().size() == 1) {
            m.putAll(ev.results().get(0));
        } else if (ev.results() != null) {
            m.put("results", ev.results());
        }
        return m;
    }

    private static DmnDecision decision(DmnModelInstance model, String key) {
        List<DmnDecision> all = ENGINE.parseDecisions(model);
        for (DmnDecision d : all) {
            if (key == null || key.equals(d.getKey())) {
                if (!d.isDecisionTable()) {
                    throw ApiProblem.unprocessable("MODEL_INVALID", "Decision " + d.getKey() + " is not a decision table");
                }
                return d;
            }
        }
        throw ApiProblem.unprocessable("MODEL_INVALID", "Decision " + key + " is not in the model");
    }

    private static boolean same(Object expected, Object actual) {
        Object a = actual;
        if (a instanceof Map<?, ?> m && m.containsKey("value")) {
            a = m.get("value");
        }
        if (Objects.equals(expected, a)) {
            return true;
        }
        if (expected == null || a == null) {
            return false;
        }
        Object e = expected instanceof Map<?, ?> m && m.containsKey("value") ? m.get("value") : expected;
        try {
            return new java.math.BigDecimal(String.valueOf(e)).compareTo(new java.math.BigDecimal(String.valueOf(a))) == 0;
        } catch (NumberFormatException ex) {
            return String.valueOf(e).equals(String.valueOf(a));
        }
    }
}
