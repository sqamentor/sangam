// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/** {@code POST /signals}: broadcast, or targeted at {@code executionId}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SignalRequest(
        String name, String executionId, Map<String, Object> variables) {
}
