// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/** {@code POST /case-instances}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StartCaseRequest(
        String key, String businessKey, Map<String, Object> variables) {
}
