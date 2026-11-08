// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;

/** A process instance. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProcessInstance(
        String id, String processDefinitionId, String processDefinitionKey, Integer processDefinitionVersion,
        String businessKey, String tenantId, String state, Boolean ended, Instant startTime, Instant endTime,
        List<String> activeActivityIds, Long caseVersion, String startedBy, String businessStatus,
        String superProcessInstanceId, String rootProcessInstanceId) {
}
