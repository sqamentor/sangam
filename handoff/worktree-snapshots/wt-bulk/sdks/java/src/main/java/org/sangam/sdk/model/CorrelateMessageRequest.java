// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/** MessageRequest of {@code POST /messages}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CorrelateMessageRequest(
        String messageName, String businessKey, String processInstanceId, Map<String, Object> correlationKeys,
        Map<String, Object> variables, Boolean all) {
}
