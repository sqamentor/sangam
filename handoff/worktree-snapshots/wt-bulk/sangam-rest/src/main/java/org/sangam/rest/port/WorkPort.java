// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Worker API (ARCHITECTURE §4, VI.3, FLOWABLE-INTO-OPERATON FC-36 / FLOWABLE-INTO-OPERATON FC-11 external-worker parity for BPMN and CMMN-hosted work).
 * Every call after fetch carries {@code (workerId, lockVersion, attemptId)}; a stale value is
 * {@code 409 STALE_LEASE} and changes nothing (VI.3 rule 2). A result for a deleted instance is
 * {@code 410 GONE_INSTANCE_DELETED} (VI.5 rule 3). The worker principal is bound to its tenant (K14).
 */
public interface WorkPort {

    record TopicRequest(String name, Long lockDurationMs, List<String> variables, Boolean localVariables,
                        String processDefinitionKey, String businessKey) {
    }

    record FetchAndLockRequest(String workerId, List<TopicRequest> topics, Integer maxTasks,
                               Long asyncResponseTimeoutMs, Boolean usePriority) {
    }

    /**
     * One locked unit of external work. {@code operationId} = UUIDv5(tenant, root, activityInstanceId, loop) and is
     * stable across retries, redelivery and lease expiry (VI.3 rule 4); workers forward it downstream.
     */
    record ExternalWork(String id, String topic, String tenantId, String processInstanceId, String processDefinitionKey,
                        String activityId, String activityInstanceId, String businessKey,
                        Map<String, Object> variables, long lockVersion, String attemptId, String operationId,
                        Instant lockExpiresAt, Integer retries, Long priority, String errorMessage,
                        WorkOperation operation) {
    }

    /**
     * The operation-ledger state of {@code operationId} recorded by the server when it leased the work (R37: intent
     * before dispatch). {@code mayDispatch=false} with {@code SUCCEEDED}: the downstream effect already happened, complete
     * from {@code storedOutcome} ({@code {output, providerRef, duplicate}}) without calling the downstream again.
     * {@code attempts > 1}: a repeat attempt (class B: look the outcome up first).
     */
    record WorkOperation(String state, int attempts, String capabilityClass, boolean mayDispatch, Object storedOutcome) {
    }

    /** The downstream's answer reported with a completion: its reference (for example a payment reference) and details. */
    record WorkOutcome(String reference, Map<String, Object> details) {
    }

    record ExtendLockRequest(String workerId, long lockVersion, String attemptId, long newDurationMs) {
    }

    /** Heartbeat response: carries {@code cancelRequested} (VI.3 rule 3). */
    record LockState(String id, long lockVersion, Instant lockExpiresAt, boolean cancelRequested) {
    }

    record CompleteRequest(String workerId, long lockVersion, String attemptId, Map<String, Object> variables,
                           Map<String, Object> localVariables, WorkOutcome outcome) {
    }

    record FailRequest(String workerId, long lockVersion, String attemptId, String errorMessage, String errorDetails,
                       Integer retries, Long retryTimeoutMs) {
    }

    record BpmnErrorRequest(String workerId, long lockVersion, String attemptId, String errorCode, String errorMessage,
                            Map<String, Object> variables) {
    }

    record UnlockRequest(String workerId, long lockVersion, String attemptId) {
    }

    /** The worker cannot know whether its downstream call took effect (VI.3 rule 4, CX-5). */
    record OutcomeUnknownRequest(String workerId, long lockVersion, String attemptId, String detail,
                                 Map<String, Object> evidence) {
    }

    record OutcomeUnknownResult(String workId, String operationId, String reconciliationItemId, String state) {
    }

    record CancelAckRequest(String workerId, long lockVersion, String attemptId) {
    }

    List<ExternalWork> fetchAndLock(RequestContext ctx, FetchAndLockRequest request);

    LockState extendLock(RequestContext ctx, String workId, ExtendLockRequest request);

    void complete(RequestContext ctx, String workId, CompleteRequest request);

    void fail(RequestContext ctx, String workId, FailRequest request);

    void bpmnError(RequestContext ctx, String workId, BpmnErrorRequest request);

    void unlock(RequestContext ctx, String workId, UnlockRequest request);

    OutcomeUnknownResult outcomeUnknown(RequestContext ctx, String workId, OutcomeUnknownRequest request);

    void cancelAck(RequestContext ctx, String workId, CancelAckRequest request);
}
