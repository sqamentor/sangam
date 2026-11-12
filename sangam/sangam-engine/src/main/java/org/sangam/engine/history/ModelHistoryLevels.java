// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.history;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * M-057 (owner decision 88): history levels a model declares for itself, extension property
 * {@code sangam:historyLevel} ({@code none | activity | audit | full}) on the process and on any activity. Keyed by the
 * process <b>definition id</b> (so every deployed version keeps its own declaration), then {@code <definitionId>#<activityId>}.
 * Filled by the parse listener whenever a definition is parsed (deploy and cache load, so always before the
 * definition runs); read by {@link PerDefinitionHistoryLevel}. Used only when the opt-in setting
 * {@code historyModelOverrides} is on.
 */
public final class ModelHistoryLevels {

    public static final String PROPERTY = "sangam:historyLevel";

    private final Map<String, String> levels = new ConcurrentHashMap<>();

    /**
     * Records a declaration after checking the level name.
     *
     * @param activityId null for the process itself
     * @throws IllegalArgumentException for an unknown level name
     */
    public void register(String definitionId, String activityId, String level) {
        PerDefinitionHistoryLevel.level(level); // validates
        levels.put(activityId == null ? definitionId : definitionId + "#" + activityId, level);
    }

    /** Clears the declarations of a definition before it is (re-)parsed. */
    public void clear(String definitionId) {
        levels.keySet().removeIf(k -> k.equals(definitionId) || k.startsWith(definitionId + "#"));
    }

    /** The declared level name for {@code key} (definition id, or definition id # activity id), or null. */
    public String get(String key) {
        return levels.get(key);
    }
}
