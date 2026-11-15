// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.history;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.operaton.bpm.engine.impl.persistence.deploy.Deployer;
import org.operaton.bpm.engine.impl.persistence.entity.DeploymentEntity;
import org.operaton.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.operaton.bpm.engine.repository.ProcessDefinition;

/**
 * M-057 (owner decision 88): history levels a model declares for itself, extension property
 * {@code sangam:historyLevel} ({@code none | activity | audit | full}) on the process and on any activity. Keyed by the
 * process <b>definition id</b> (so every deployed version keeps its own declaration), then {@code <definitionId>#<activityId>}.
 * Read by {@link PerDefinitionHistoryLevel}. Used only when the opt-in setting {@code historyModelOverrides} is on.
 *
 * <p>Filled in two steps, because a definition has no id while it is parsed (the engine's deployer assigns a new id, or
 * loads the stored one, after the parse): the parse listener checks each declaration and keeps it on the parsed
 * definition ({@link #declare}); {@link DeployRule}, a post-deployer, registers the declarations under the definition id
 * once the engine's deployer has set it. Both run on a new deployment and on a cache load (engine restart), so always
 * before the definition runs.
 */
public final class ModelHistoryLevels {

    public static final String PROPERTY = "sangam:historyLevel";

    /** Property of a parsed process definition: its declarations, activity id ("" for the process) to level name. */
    static final String DECLARED = "sangam.historyLevels";

    private final Map<String, String> levels = new ConcurrentHashMap<>();

    /**
     * Checks a declaration and keeps it on the parsed definition, for {@link DeployRule}.
     *
     * @param activityId null for the process itself
     * @throws IllegalArgumentException for an unknown level name
     */
    public static void declare(ProcessDefinitionEntity definition, String activityId, String level) {
        PerDefinitionHistoryLevel.level(level); // validates
        @SuppressWarnings("unchecked")
        Map<String, String> declared = (Map<String, String>) definition.getProperty(DECLARED);
        if (declared == null) {
            declared = new LinkedHashMap<>();
            definition.setProperty(DECLARED, declared);
        }
        declared.put(activityId == null ? "" : activityId, level);
    }

    /**
     * Records a declaration after checking the level name.
     *
     * @param definitionId the id of a deployed definition
     * @param activityId   null for the process itself
     * @throws IllegalArgumentException for an unknown level name
     */
    public void register(String definitionId, String activityId, String level) {
        if (definitionId == null) {
            throw new IllegalArgumentException("M-057: a declaration is registered under a definition id");
        }
        PerDefinitionHistoryLevel.level(level); // validates
        levels.put(activityId == null ? definitionId : definitionId + "#" + activityId, level);
    }

    /** Clears the declarations of a definition before it is (re-)registered. */
    public void clear(String definitionId) {
        levels.keySet().removeIf(k -> k.equals(definitionId) || k.startsWith(definitionId + "#"));
    }

    /** The declared level name for {@code key} (definition id, or definition id # activity id), or null. */
    public String get(String key) {
        return levels.get(key);
    }

    /** Registers what the parse kept on each definition of the deployment, under the id the engine's deployer set. */
    public Deployer deployRule() {
        return new DeployRule(this);
    }

    /** Post-deployer: runs after the engine's BPMN deployer, for a new deployment and for a cache load. */
    static final class DeployRule implements Deployer {

        private final ModelHistoryLevels registry;

        DeployRule(ModelHistoryLevels registry) {
            this.registry = registry;
        }

        @Override
        public void deploy(DeploymentEntity deployment) {
            List<ProcessDefinition> definitions = deployment.getDeployedProcessDefinitions();
            if (definitions == null) {
                return;
            }
            for (ProcessDefinition d : definitions) {
                registry.clear(d.getId());
                Object declared = d instanceof ProcessDefinitionEntity e ? e.getProperty(DECLARED) : null;
                if (declared instanceof Map<?, ?> m) {
                    m.forEach((activity, level) -> registry.register(d.getId(),
                            "".equals(activity) ? null : String.valueOf(activity), String.valueOf(level)));
                }
            }
        }
    }
}