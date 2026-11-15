// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.Map;

/**
 * Task as seen through the VI.4 policy (sangam-api.yaml Task).
 *
 * @param amount    M-042 task index: the amount as a decimal string in major units with exactly the currency's fraction
 *                  digits (MoneyAmount; never a binary floating-point number), or null
 * @param currency  the ISO 4217 code of {@code amount}; null with it
 * @param reference the reference the process's index rule set at creation, or null
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskView(
        String id, String name, String description, String taskDefinitionKey, String processInstanceId,
        String processDefinitionKey, String caseInstanceId, String businessKey, String assignee, String owner,
        String seatId, String tenantId, Instant created, Instant due, Instant followUp, Instant lastUpdated,
        Instant claimTime, String state, Integer priority, String formKey, String delegationState,
        Map<String, Object> variables, String amount, String currency, String reference) {
}
