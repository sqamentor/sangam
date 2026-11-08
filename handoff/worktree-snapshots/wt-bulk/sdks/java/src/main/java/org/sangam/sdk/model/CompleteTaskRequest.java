// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * Decision on a task bound to the payload hash and case version shown (VI.4 rule 5, K4, K9). {@code payloadHash}
 * (hex SHA-256 of the material payload) is required; a mismatch gives 409 APPROVAL_INVALIDATED.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CompleteTaskRequest(
        String payloadHash, Long caseVersion, String decision, String remarks, String formVersion, Map<String, Object> variables) {
}
