// SPDX-License-Identifier: Apache-2.0
package org.sangam.field.scoring;

import org.sangam.field.FieldException;
import org.sangam.field.ports.FieldPorts.DecisionPort;
import org.sangam.field.template.ChecklistValidator;
import org.sangam.field.template.FieldTemplate;
import org.sangam.field.template.FieldTemplate.Item;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Visit scoring (N05). WEIGHTED_SUM computes a 0–100 score from the compliant share of every visible, scored item;
 * a non-compliant critical item forces {@code criticalFailGrade}. DMN hands the answers plus the computed weighted
 * score to a decision (via {@link DecisionPort}) that returns {@code score}, {@code grade}, {@code passed} and optional
 * {@code findings}. Non-compliant items always produce {@link Finding}s, which feed penalty DMN or CAPA (UP-18).
 */
public final class ScoringService {

    public record Finding(String itemId, String code, String severity, String description) {
    }

    public record ScoreResult(String method, double score, String grade, boolean passed, boolean criticalFailed,
                              Map<String, Double> itemScores, List<Finding> findings, Map<String, Object> decisionOutput) {
    }

    private final DecisionPort decisions;

    public ScoringService(DecisionPort decisions) {
        this.decisions = decisions;
    }

    public ScoreResult score(String tenantId, FieldTemplate t, Map<String, Object> answers, Map<String, Object> context) {
        Map<String, Object> ans = answers == null ? Map.of() : answers;
        Map<String, Double> itemScores = new LinkedHashMap<>();
        List<Finding> findings = new ArrayList<>();
        double sumW = 0, sum = 0;
        boolean criticalFailed = false;
        for (Item i : t.items()) {
            if (!ChecklistValidator.visible(i, ans) || !i.scored()) {
                continue;
            }
            double s = itemScore(i, ans.get(i.id()));
            itemScores.put(i.id(), s);
            double w = i.weightOrDefault();
            sumW += w;
            sum += w * s;
            if (s < 1) {
                String sev = i.severity() != null ? i.severity() : (i.critical() ? "CRITICAL" : "MINOR");
                String label = FieldTemplate.label(i.label(), null);
                findings.add(new Finding(i.id(), i.findingCode() != null ? i.findingCode() : "NON_COMPLIANT:" + i.id(), sev,
                        label == null ? i.id() : label));
                criticalFailed |= i.critical() && s == 0;
            }
        }
        double weighted = sumW == 0 ? 100 : Math.round(sum / sumW * 10000) / 100.0;
        FieldTemplate.Scoring cfg = t.scoring();
        double pass = cfg.passScore() == null ? 50 : cfg.passScore();
        if (cfg.method() == FieldTemplate.Method.DMN) {
            Map<String, Object> vars = new LinkedHashMap<>();
            if (context != null) {
                vars.putAll(context);
            }
            ans.forEach((k, v) -> vars.put(k, v));
            vars.put("answers", ans);
            vars.put("weightedScore", weighted);
            vars.put("criticalFailed", criticalFailed);
            vars.put("nonCompliantItems", findings.stream().map(Finding::itemId).toList());
            vars.put("templateKey", t.key());
            List<Map<String, Object>> out = decisions.evaluate(tenantId, cfg.decisionKey(), vars);
            if (out.isEmpty()) {
                throw new FieldException("DECISION_NO_RESULT", "scoring decision " + cfg.decisionKey() + " matched no rule");
            }
            Map<String, Object> r = out.get(0);
            double score = r.get("score") instanceof Number n ? n.doubleValue() : weighted;
            String grade = r.get("grade") == null ? grade(cfg, score, criticalFailed) : String.valueOf(r.get("grade"));
            boolean passed = r.get("passed") instanceof Boolean b ? b : score >= pass && !criticalFailed;
            if (r.get("findings") instanceof Collection<?> extra) {
                for (Object f : extra) {
                    findings.add(new Finding(null, String.valueOf(f), "DECISION", "raised by decision " + cfg.decisionKey()));
                }
            }
            return new ScoreResult("DMN", score, grade, passed, criticalFailed, itemScores, findings, r);
        }
        if (cfg.method() == FieldTemplate.Method.NONE) {
            return new ScoreResult("NONE", weighted, null, !criticalFailed, criticalFailed, itemScores, findings, Map.of());
        }
        return new ScoreResult("WEIGHTED_SUM", weighted, grade(cfg, weighted, criticalFailed), weighted >= pass && !criticalFailed,
                criticalFailed, itemScores, findings, Map.of());
    }

    static String grade(FieldTemplate.Scoring cfg, double score, boolean criticalFailed) {
        if (criticalFailed && cfg.criticalFailGrade() != null) {
            return cfg.criticalFailGrade();
        }
        if (cfg.grades() == null) {
            return null;
        }
        return cfg.grades().stream().sorted(Comparator.comparingDouble(FieldTemplate.Grade::minScore).reversed())
                .filter(g -> score >= g.minScore()).map(FieldTemplate.Grade::code).findFirst().orElse(null);
    }

    /** 0..1 compliance of one answer. */
    static double itemScore(Item i, Object a) {
        if (a == null) {
            return 0;
        }
        return switch (i.type()) {
            case BOOLEAN -> {
                boolean v = a instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(a));
                boolean expected = i.expected() == null || Boolean.parseBoolean(String.valueOf(i.expected()));
                yield v == expected ? 1 : 0;
            }
            case CHOICE -> {
                FieldTemplate.Option o = ChecklistValidator.option(i, a);
                if (o == null) {
                    yield 0;
                }
                if (o.score() != null) {
                    yield clamp(o.score());
                }
                yield i.expected() == null || String.valueOf(i.expected()).equals(o.value()) ? 1 : 0;
            }
            case MULTI_CHOICE -> {
                if (!(a instanceof Collection<?> c) || c.isEmpty()) {
                    yield 0;
                }
                double s = 0;
                for (Object x : c) {
                    FieldTemplate.Option o = ChecklistValidator.option(i, x);
                    s += o == null ? 0 : (o.score() == null ? 1 : o.score());
                }
                yield clamp(s / c.size());
            }
            case NUMBER -> {
                Double d = ChecklistValidator.number(a);
                if (d == null) {
                    yield 0;
                }
                yield (i.min() == null || d >= i.min()) && (i.max() == null || d <= i.max()) ? 1 : 0;
            }
            default -> 1;
        };
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
