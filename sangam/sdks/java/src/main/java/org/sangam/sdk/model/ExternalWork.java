// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.Map;

/**
 * A locked unit of external work (sangam-api.yaml ExternalWork; VI.3). The handler runs only after this LOCKED
 * confirmation with its {@code lockVersion} was received (A-1). {@code operationId} is stable across retries and lease
 * expiry (R36) and is the idempotency key for downstream calls; {@code attemptId} changes per lease (A-2).
 * Variables are a VariableMap: plain JSON or R33 {@link TypedValue} objects. Every field is optional in the contract,
 * so none is a primitive; the worker starts an item only when id, attemptId and lockVersion are present (A-1).
 * Irreversibility is not a work field: it is configured on the subscription (and server side, irreversibleTopics).
 * {@code operation} is the server-recorded ledger entry of {@code operationId} (SL-16; null from servers before it).
 * {@code traceContext} is the W3C trace context the engine stored for the task (M-062); absent when none was stored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExternalWork(
        String id, String topic, String tenantId, String processInstanceId, String processDefinitionKey, String activityId,
        String activityInstanceId, String businessKey, Map<String, Object> variables, Long lockVersion, String attemptId,
        String operationId, Instant lockExpiresAt, Integer retries, Long priority, String errorMessage,
        WorkOperation operation, TraceContext traceContext) {

    /** The shape before M-062 (no trace context); kept so existing callers compile unchanged. */
    public ExternalWork(String id, String topic, String tenantId, String processInstanceId, String processDefinitionKey,
                        String activityId, String activityInstanceId, String businessKey, Map<String, Object> variables,
                        Long lockVersion, String attemptId, String operationId, Instant lockExpiresAt, Integer retries,
                        Long priority, String errorMessage, WorkOperation operation) {
        this(id, topic, tenantId, processInstanceId, processDefinitionKey, activityId, activityInstanceId, businessKey,
                variables, lockVersion, attemptId, operationId, lockExpiresAt, retries, priority, errorMessage, operation,
                null);
    }
}
