// SPDX-License-Identifier: Apache-2.0
package org.sangam.field.schedule;

import org.sangam.field.ports.FieldPorts.DecisionPort;

import java.sql.Connection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Inspector rotation (N05): the same inspector should not keep the same unit. The built-in rule excludes the last N
 * distinct inspectors of the target and picks the least-loaded eligible inspector (ties by id). An optional DMN
 * decision ({@code allowed} boolean output) gets {@code targetRef}, {@code candidate} and {@code recentInspectors} and
 * can veto further candidates (conflict-of-interest lists, jurisdiction rules, all as data).
 */
public final class RotationRule {

    private final AssignmentService assignments;
    private final DecisionPort decisions;

    public RotationRule(AssignmentService assignments, DecisionPort decisions) {
        this.assignments = assignments;
        this.decisions = decisions;
    }

    public Optional<String> pick(Connection c, String tenantId, String targetRef, List<String> pool, int excludeLast,
                                 String decisionKey) {
        if (pool == null || pool.isEmpty()) {
            return Optional.empty();
        }
        List<String> recent = excludeLast > 0 ? assignments.recentAssignees(c, tenantId, targetRef, excludeLast) : List.of();
        List<String> eligible = pool.stream().filter(p -> !recent.contains(p)).filter(p -> allowed(tenantId, decisionKey, targetRef, p, recent))
                .toList();
        if (eligible.isEmpty()) {
            // rotation cannot be honoured with this pool: fall back to the least recently used inspector
            eligible = pool.stream().sorted(Comparator.comparingInt(p -> -recent.indexOf(p))).limit(1).toList();
        }
        Map<String, Integer> load = new LinkedHashMap<>();
        for (String p : eligible) {
            load.put(p, assignments.openLoad(c, tenantId, p));
        }
        return eligible.stream().min(Comparator.<String>comparingInt(load::get).thenComparing(p -> p));
    }

    private boolean allowed(String tenantId, String decisionKey, String targetRef, String candidate, List<String> recent) {
        if (decisionKey == null || decisionKey.isBlank()) {
            return true;
        }
        List<Map<String, Object>> r = decisions.evaluate(tenantId, decisionKey,
                Map.of("targetRef", targetRef, "candidate", candidate, "recentInspectors", recent));
        return r.isEmpty() || !Boolean.FALSE.equals(r.get(0).get("allowed"));
    }
}
