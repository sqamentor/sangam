// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

/** Task as seen through the VI.4 policy (sangam-api.yaml Task). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskView(
        String id, String name, String description, String taskDefinitionKey, String processInstanceId,
        String processDefinitionKey, String caseInstanceId, String businessKey, String assignee, String owner,
        String seatId, String tenantId, Instant created, Instant due, Instant followUp, Instant lastUpdated,
        Instant claimTime, String state, Integer priority, String formKey, String delegationState) {
}
