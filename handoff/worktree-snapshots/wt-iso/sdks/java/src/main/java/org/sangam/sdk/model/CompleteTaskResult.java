// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** Answer of task complete. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CompleteTaskResult(
        String taskId, String processInstanceId, String decision, String payloadHash, Boolean instanceEnded, List<String> nextTaskIds) {
}
