// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.JavaDelegate;

import org.sangam.core.layer.CanonicalJson;
import org.sangam.core.layer.Hashes;

/**
 * Business-rule task implementation: evaluates a DMN decision with tenant override (GP-37) and records
 * {@code RULE_EVALUATED} with the decision key, deployed version, inputs digest and output (X.4.0.3, GP-19).
 *
 * <p>Fields: {@code decisionKey}; {@code resultPrefix} (outputs become {@code <prefix><Output>}, default no prefix);
 * {@code collect} (true: all rows are stored as a JSON array in {@code <prefix>Rows}, GP-26 COLLECT);
 * {@code required} (true: an empty result is an error); {@code binding} ({@code deployment}, the default: the rule
 * deployed with this process version, so in-flight cases keep their rule, GP-19 / J-P3-E08; {@code latest}: the rule
 * in force today, used for competence routing). The evaluated version is stored as {@code <key>_version}.
 */
public class DecisionDelegate implements JavaDelegate {

    private Expression decisionKey;
    private Expression resultPrefix;
    private Expression collect;
    private Expression required;
    private Expression binding;

    @Override
    public void execute(DelegateExecution ex) {
        String key = Vars.field(decisionKey, ex);
        String prefix = Vars.field(resultPrefix, ex, "");
        Map<String, Object> in = new HashMap<>(ex.getVariables());
        String deploymentId = null;
        if ("deployment".equals(Vars.field(binding, ex, "deployment"))) {
            deploymentId = ex.getProcessEngineServices().getRepositoryService()
                    .getProcessDefinition(ex.getProcessDefinitionId()).getDeploymentId();
        }
        Decisions.Evaluation ev = Decisions.evaluate(ex.getProcessEngineServices(), Vars.tenant(ex), key, in,
                deploymentId);
        Object output;
        if (Vars.fieldBool(collect, ex, false)) {
            List<Map<String, Object>> rows = ev.all();
            Vars.setJsonDocument(ex, prefix + "Rows", rows);
            ex.setVariable(prefix + "RowCount", rows.size());
            output = rows.size();
        } else {
            Map<String, Object> row = ev.single();
            if (row.isEmpty() && Vars.fieldBool(required, ex, true)) {
                throw new ProcessEngineException("decision " + key + " v" + ev.version() + " matched no rule");
            }
            for (Map.Entry<String, Object> e : row.entrySet()) {
                String name = prefix.isEmpty() ? e.getKey()
                        : prefix + Character.toUpperCase(e.getKey().charAt(0)) + e.getKey().substring(1);
                ex.setVariable(name, Vars.engineValue(e.getValue()));
            }
            output = row;
        }
        ex.setVariable(key.replace('-', '_') + "_version", ev.version());
        Map<String, Object> digestInput = new HashMap<>();
        for (Map.Entry<String, Object> e : ex.getVariables().entrySet()) {
            Object v = Vars.canonical(e.getValue());
            if (v == null || v instanceof String || v instanceof Boolean || v instanceof Integer || v instanceof Long) {
                digestInput.put(e.getKey(), v);
            }
        }
        Vars.audit(ex, "RULE_EVALUATED", Vars.map("decision", key, "version", ev.version(),
                "inputsDigest", Hashes.hex(Hashes.sha256(CanonicalJson.write(digestInput))).substring(0, 16),
                "output", String.valueOf(output)));
    }
}
