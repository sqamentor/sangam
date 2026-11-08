// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * Body of {@code POST /work/{id}/outcome-unknown} (VI.3.2 rule 4, CX-5): the downstream effect may or may not have
 * happened. Opens an UNKNOWN_OUTCOME reconciliation item; never repeated automatically. {@code evidence} is a VariableMap.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OutcomeUnknownRequest(
        String workerId, long lockVersion, String attemptId, String detail, Map<String, Object> evidence) {
}
