// SPDX-License-Identifier: Apache-2.0
package org.sangam.field.adapters;

import org.operaton.bpm.engine.DecisionService;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.dmn.DecisionsEvaluationBuilder;
import org.sangam.field.FieldException;
import org.sangam.field.ports.FieldPorts.DecisionPort;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link DecisionPort} over Operaton's {@link DecisionService} (DMN 1.6 / FEEL). Scoring, risk weighting and rotation
 * decisions are ordinary DMN deployed by a pack; the geo FEEL functions (sangam-geo) are available inside them.
 *
 * @param tenantScoped when true the decision is looked up with the Sangam tenant as the Operaton definition tenant id
 *                     first, then without a tenant (shared pack decisions)
 */
public final class OperatonDecisionAdapter implements DecisionPort {

    private final DecisionService decisions;
    private final boolean tenantScoped;

    public OperatonDecisionAdapter(ProcessEngine engine, boolean tenantScoped) {
        this.decisions = engine.getDecisionService();
        this.tenantScoped = tenantScoped;
    }

    @Override
    public List<Map<String, Object>> evaluate(String tenantId, String decisionKey, Map<String, Object> variables) {
        Map<String, Object> vars = variables == null ? Map.of() : variables;
        RuntimeException first = null;
        if (tenantScoped && tenantId != null) {
            try {
                return copy(decisions.evaluateDecisionByKey(decisionKey).decisionDefinitionTenantId(tenantId).variables(vars));
            } catch (RuntimeException e) {
                first = e;
            }
        }
        try {
            DecisionsEvaluationBuilder b = decisions.evaluateDecisionByKey(decisionKey);
            if (tenantScoped) {
                b = b.decisionDefinitionWithoutTenantId();
            }
            return copy(b.variables(vars));
        } catch (RuntimeException e) {
            FieldException fe = new FieldException("DECISION_FAILED", "decision " + decisionKey + " failed: " + e.getMessage(), e);
            if (first != null) {
                fe.addSuppressed(first);
            }
            throw fe;
        }
    }

    private static List<Map<String, Object>> copy(DecisionsEvaluationBuilder b) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> m : b.evaluate().getResultList()) {
            out.add(new LinkedHashMap<>(m));
        }
        return out;
    }
}
