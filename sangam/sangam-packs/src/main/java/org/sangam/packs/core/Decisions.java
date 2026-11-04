// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import java.util.List;
import java.util.Map;

import org.operaton.bpm.dmn.engine.DmnDecisionResult;
import org.operaton.bpm.engine.DecisionService;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.ProcessEngineServices;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.repository.DecisionDefinition;

/**
 * DMN evaluation with tenant override (GP-37: a department's own table wins over the shared one) and version
 * reporting for the {@code RULE_EVALUATED} audit event (key, version, inputs digest, output, X.4.0.3).
 */
public final class Decisions {

    private Decisions() {
    }

    public record Evaluation(String key, int version, String definitionId, DmnDecisionResult result) {
        public Map<String, Object> single() {
            if (result.isEmpty()) {
                return Map.of();
            }
            if (result.size() > 1) {
                throw new ProcessEngineException("decision " + key + " returned " + result.size()
                        + " rows where one was expected");
            }
            return result.getSingleResult().getEntryMap();
        }

        public List<Map<String, Object>> all() {
            return result.getResultList();
        }
    }

    public static Evaluation evaluate(ProcessEngineServices services, String tenantId, String key,
                                      Map<String, Object> variables) {
        return evaluate(services, tenantId, key, variables, null);
    }

    /**
     * Evaluates {@code key}. With a {@code deploymentId} (GP-19 "deployment" binding) the decision deployed together
     * with the calling process version is used, so in-flight cases keep the rule they started under; otherwise, or
     * when that deployment has no such decision, the latest shared version. A tenant's own deployed table always wins
     * (GP-37 department-specific override).
     */
    public static Evaluation evaluate(ProcessEngineServices services, String tenantId, String key,
                                      Map<String, Object> variables, String deploymentId) {
        RepositoryService repo = services.getRepositoryService();
        DecisionDefinition def = null;
        if (tenantId != null) {
            // a department's own table wins (GP-37)
            def = repo.createDecisionDefinitionQuery().decisionDefinitionKey(key).tenantIdIn(tenantId)
                    .latestVersion().singleResult();
        }
        if (def == null && deploymentId != null) {
            def = repo.createDecisionDefinitionQuery().decisionDefinitionKey(key).deploymentId(deploymentId)
                    .singleResult();
        }
        if (def == null) {
            def = repo.createDecisionDefinitionQuery().decisionDefinitionKey(key).withoutTenantId()
                    .latestVersion().singleResult();
        }
        if (def == null) {
            throw new ProcessEngineException("no decision definition '" + key + "' for tenant " + tenantId);
        }
        DecisionService ds = services.getDecisionService();
        DmnDecisionResult r = ds.evaluateDecisionById(def.getId()).variables(variables).evaluate();
        return new Evaluation(key, def.getVersion(), def.getId(), r);
    }
}
