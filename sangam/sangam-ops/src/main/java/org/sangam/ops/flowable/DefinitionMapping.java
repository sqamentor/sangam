// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.flowable;

import java.util.Map;
import java.util.Objects;

/**
 * M-135: which deployed Operaton definition receives the instances of one Flowable definition. The target version is
 * pinned: instances never move silently to the latest definition. Activity ids are kept by the model importer
 * ({@code FlowableModelImporter}), so the activity map only lists renamed elements; any other id maps to itself.
 *
 * @param flowableKey     the Flowable process definition key
 * @param flowableVersion the Flowable definition version, or null for every version of the key
 * @param targetKey       the Operaton process definition key
 * @param targetVersion   the Operaton definition version (required)
 * @param activityMap     Flowable activity id to Operaton activity id, for renamed elements only
 */
public record DefinitionMapping(String flowableKey, Integer flowableVersion, String targetKey, int targetVersion,
                                Map<String, String> activityMap) {

    public DefinitionMapping {
        Objects.requireNonNull(flowableKey, "flowableKey");
        Objects.requireNonNull(targetKey, "targetKey");
        if (targetVersion < 1) {
            throw new IllegalArgumentException("the target version must be pinned (>= 1)");
        }
        activityMap = activityMap == null ? Map.of() : Map.copyOf(activityMap);
    }

    public boolean matches(String key, int version) {
        return flowableKey.equals(key) && (flowableVersion == null || flowableVersion == version);
    }

    public String target(String flowableActivityId) {
        return activityMap.getOrDefault(flowableActivityId, flowableActivityId);
    }
}
