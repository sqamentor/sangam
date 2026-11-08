// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/** DecisionEvaluateResult: output rows (each a VariableMap) plus the evaluated version (R33). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DecisionResult(
        String decisionDefinitionId, String decisionKey, Integer version, List<Map<String, Object>> result, String outputDigest, String auditEventId) {
}
