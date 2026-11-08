// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** Correlation answer. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MessageResult(
        String messageName, List<String> processInstanceIds, List<String> executionIds) {
}
