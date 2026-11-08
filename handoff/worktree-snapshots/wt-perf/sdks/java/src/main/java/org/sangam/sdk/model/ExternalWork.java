// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.Map;

/**
 * A locked unit of external work (sangam-api.yaml ExternalWork; VI.3). The handler runs only after this LOCKED
 * confirmation with its {@code lockVersion} was received (A-1). {@code operationId} is stable across retries and lease
 * expiry (R36) and is the idempotency key for downstream calls; {@code attemptId} changes per lease (A-2).
 * Variables are a VariableMap: plain JSON or R33 {@link TypedValue} objects. {@code effect} is not in the contract
 * (reserved; irreversibility is configured on the subscription). {@code operation} is the server-recorded ledger entry
 * of {@code operationId} (SL-16; null from servers before it).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExternalWork(
        String id, String topic, String tenantId, String processInstanceId, String processDefinitionKey, String activityId,
        String activityInstanceId, String businessKey, Map<String, Object> variables, long lockVersion, String attemptId,
        String operationId, Instant lockExpiresAt, Integer retries, Long priority, String errorMessage, String effect,
        WorkOperation operation) {
}
