// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/** WorkBpmnErrorRequest: a business error routed by the model. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BpmnErrorRequest(
        String workerId, long lockVersion, String attemptId, String errorCode, String errorMessage, Map<String, Object> variables) {
}
