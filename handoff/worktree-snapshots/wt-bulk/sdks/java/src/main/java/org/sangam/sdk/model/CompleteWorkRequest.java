// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * WorkCompleteRequest of {@code POST /work/{id}/complete}. {@code outcome} (optional) carries the downstream reference;
 * the server records the ledger outcome SUCCEEDED with the engine completion (SL-16).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CompleteWorkRequest(
        String workerId, long lockVersion, String attemptId, Map<String, Object> variables, Map<String, Object> localVariables,
        WorkOutcome outcome) {

    public CompleteWorkRequest(String workerId, long lockVersion, String attemptId, Map<String, Object> variables,
                               Map<String, Object> localVariables) {
        this(workerId, lockVersion, attemptId, variables, localVariables, null);
    }
}
