// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * DecisionEvaluateRequest. {@code purpose} ROUTING / AUTHORITY / OTHER; ROUTING or AUTHORITY with a businessKey
 * is recorded as a class-B audit event.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EvaluateDecisionRequest(
        Map<String, Object> variables, Integer version, String purpose, String businessKey) {
}
