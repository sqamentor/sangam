// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 202 answer of outcome-unknown: the opened reconciliation item. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OutcomeUnknownResult(
        String workId, String operationId, String reconciliationItemId, String state) {
}
