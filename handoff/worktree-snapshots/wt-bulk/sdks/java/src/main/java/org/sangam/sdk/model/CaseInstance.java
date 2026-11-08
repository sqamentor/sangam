// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

/** A CMMN case instance. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CaseInstance(
        String id, String caseDefinitionId, String caseDefinitionKey, String businessKey, String tenantId, String state,
        Instant created, Object executions, String reactivatedFrom) {
}
