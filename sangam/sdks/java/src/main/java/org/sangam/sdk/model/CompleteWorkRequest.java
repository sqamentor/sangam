// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/** WorkCompleteRequest of {@code POST /work/{id}/complete}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CompleteWorkRequest(
        String workerId, long lockVersion, String attemptId, Map<String, Object> variables, Map<String, Object> localVariables) {
}
