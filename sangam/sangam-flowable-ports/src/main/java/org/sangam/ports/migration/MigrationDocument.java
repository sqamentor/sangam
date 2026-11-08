// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.migration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * JSON migration document (FLOWABLE-INTO-OPERATON FC-18; Flowable {@code ProcessInstanceMigrationDocument}). Example:
 * <pre>{@code
 * { "source": {"key":"bill","version":3}, "target": {"key":"bill","version":4},
 *   "mapEqualActivities": true, "updateEventTriggers": true,
 *   "mappings": [ {"from":["review"],"to":["review2"]},              (1:1)
 *                 {"from":["a","b"],"to":["ab"]},                   (N:1)
 *                 {"from":["check"],"to":["checkA","checkB"]} ],    (1:N: first target migrated, others started)
 *   "variables": {"migratedFrom": "v3"},
 *   "preHook": "beanName", "postHook": "beanName",
 *   "processInstanceIds": ["..."] }
 * }</pre>
 * {@code source}/{@code target} take either {@code id} or {@code key}+{@code version} (+{@code tenantId}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MigrationDocument(@JsonProperty("source") DefinitionRef source,
                                @JsonProperty("target") DefinitionRef target,
                                @JsonProperty("mapEqualActivities") Boolean mapEqualActivities,
                                @JsonProperty("updateEventTriggers") Boolean updateEventTriggers,
                                @JsonProperty("mappings") List<Mapping> mappings,
                                @JsonProperty("variables") Map<String, Object> variables,
                                @JsonProperty("preHook") String preHook,
                                @JsonProperty("postHook") String postHook,
                                @JsonProperty("processInstanceIds") List<String> processInstanceIds,
                                @JsonProperty("skipCustomListeners") Boolean skipCustomListeners,
                                @JsonProperty("skipIoMappings") Boolean skipIoMappings) {

    public MigrationDocument {
        mappings = mappings == null ? List.of() : List.copyOf(mappings);
        variables = variables == null ? Map.of() : Map.copyOf(variables);
        processInstanceIds = processInstanceIds == null ? List.of() : List.copyOf(processInstanceIds);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DefinitionRef(@JsonProperty("id") String id, @JsonProperty("key") String key,
                                @JsonProperty("version") Integer version, @JsonProperty("tenantId") String tenantId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Mapping(@JsonProperty("from") List<String> from, @JsonProperty("to") List<String> to) {
    }
}
