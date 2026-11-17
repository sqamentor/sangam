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
import org.operaton.bpm.engine.repository.Deployment;

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
     * Evaluates {@code key} (owner decision 202, GP-19 with GP-37):
     * <ol>
     *   <li>the tenant's own table deployed separately from the pack (a deployment of the tenant whose source is not
     *       {@link org.sangam.packs.spi.PackDeployer#SOURCE}), latest version: a department-specific override wins
     *       also over the rule a running case started with;</li>
     *   <li>with a {@code deploymentId} ("deployment" binding), the decision deployed together with the calling process
     *       version, so a running case keeps the rule version it started with; a newer pack version deployed for the
     *       tenant never reaches it (ChangeAdoptionTest);</li>
     *   <li>otherwise, or when that deployment has no such decision: the tenant's latest version, then the latest
     *       shared version.</li>
     * </ol>
     */
    public static Evaluation evaluate(ProcessEngineServices services, String tenantId, String key,
                                      Map<String, Object> variables, String deploymentId) {
        DecisionDefinition def = resolve(services, tenantId, key, deploymentId);
        DecisionService ds = services.getDecisionService();
        DmnDecisionResult r = ds.evaluateDecisionById(def.getId()).variables(variables).evaluate();
        return new Evaluation(key, def.getVersion(), def.getId(), r);
    }

    /**
     * True when the decision table {@code key} that {@link #evaluate} would use reads an input whose expression is
     * exactly {@code variable} (decision 402 (9): a power table keyed by caseType).
     */
    public static boolean readsInput(ProcessEngineServices services, String tenantId, String key, String variable) {
        DecisionDefinition def = resolve(services, tenantId, key, null);
        org.operaton.bpm.model.dmn.DmnModelInstance model = services.getRepositoryService()
                .getDmnModelInstance(def.getId());
        for (org.operaton.bpm.model.dmn.instance.Input in
                : model.getModelElementsByType(org.operaton.bpm.model.dmn.instance.Input.class)) {
            var table = in.getParentElement();                         // the decision table
            var decision = table == null ? null : table.getParentElement();
            if (!(decision instanceof org.operaton.bpm.model.dmn.instance.Decision d) || !key.equals(d.getId())) {
                continue;
            }
            org.operaton.bpm.model.dmn.instance.InputExpression e = in.getInputExpression();
            if (e != null && e.getText() != null && variable.equals(e.getText().getTextContent().trim())) {
                return true;
            }
        }
        return false;
    }

    /** The decision definition {@link #evaluate} uses for {@code key} (the order documented there). */
    static DecisionDefinition resolve(ProcessEngineServices services, String tenantId, String key,
                                      String deploymentId) {
        RepositoryService repo = services.getRepositoryService();
        DecisionDefinition def = null;
        if (tenantId != null) {
            def = tenantOwnTable(repo, tenantId, key);
        }
        if (def == null && deploymentId != null) {
            def = repo.createDecisionDefinitionQuery().decisionDefinitionKey(key).deploymentId(deploymentId)
                    .singleResult();
        }
        if (def == null && tenantId != null) {
            def = repo.createDecisionDefinitionQuery().decisionDefinitionKey(key).tenantIdIn(tenantId)
                    .latestVersion().singleResult();
        }
        if (def == null) {
            def = repo.createDecisionDefinitionQuery().decisionDefinitionKey(key).withoutTenantId()
                    .latestVersion().singleResult();
        }
        if (def == null) {
            throw new ProcessEngineException("no decision definition '" + key + "' for tenant " + tenantId);
        }
        return def;
    }

    /**
     * The latest version of {@code key} the tenant deployed separately from the pack, or null. A pack deployment (source
     * {@link org.sangam.packs.spi.PackDeployer#SOURCE}), including a department-specific table shipped inside the
     * tenant's pack deployment, is bound per case instead and never counts here.
     */
    static DecisionDefinition tenantOwnTable(RepositoryService repo, String tenantId, String key) {
        List<DecisionDefinition> versions = repo.createDecisionDefinitionQuery().decisionDefinitionKey(key)
                .tenantIdIn(tenantId).orderByDecisionDefinitionVersion().desc().list();
        for (DecisionDefinition d : versions) {
            Deployment dep = repo.createDeploymentQuery().deploymentId(d.getDeploymentId()).singleResult();
            if (dep != null && !org.sangam.packs.spi.PackDeployer.SOURCE.equals(dep.getSource())) {
                return d;
            }
        }
        return null;
    }
}
