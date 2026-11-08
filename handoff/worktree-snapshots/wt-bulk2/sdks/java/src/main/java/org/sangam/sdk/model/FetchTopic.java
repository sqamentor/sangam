// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** TopicRequest of fetch-and-lock. Lease default 5 min, maximum 24 h (VI.3 rule 3). {@code variables} null = all. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FetchTopic(
        String name, long lockDurationMs, List<String> variables, Boolean localVariables, String processDefinitionKey, String businessKey) {
}
