// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** Outcome of cancel: open external work goes to CANCEL_REQUESTED (VI.5). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TerminationResult(
        String processInstanceId, String state, List<String> openOperationIds, Integer cancelRequestedWork, Integer tombstones) {
}
