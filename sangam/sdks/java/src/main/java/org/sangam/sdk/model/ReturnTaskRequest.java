// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/** {@code POST /tasks/{id}/return}; {@code reason} is required. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReturnTaskRequest(
        String reason, String reasonCode, String targetActivityId, Map<String, Object> variables,
        String targetSeatId, Integer targetLevel, String payloadHash, Long caseVersion) {

    /** Without seat/level targeting and the approval binding (all optional in the contract). */
    public ReturnTaskRequest(String reason, String reasonCode, String targetActivityId, Map<String, Object> variables) {
        this(reason, reasonCode, targetActivityId, variables, null, null, null, null);
    }
}
