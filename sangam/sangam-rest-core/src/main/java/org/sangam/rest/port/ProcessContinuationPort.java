// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * M-060, owner decision 149: continue-as-new (contract {@code routes/continuation.py}, operations
 * {@code continueProcessInstanceAsNew} and {@code getProcessContinuationChain}). The rules (active root instance of the
 * caller's tenant, one engine transaction, idempotency by {@code continuationRequestId}, object checks
 * {@code PROCESS_INSTANCE MODIFY} and, for another named version, {@code PROCESS_DEFINITION START}) live in the engine
 * ({@code org.sangam.engine.continuation.ProcessContinuations}); the port carries the REST shapes only.
 */
public interface ProcessContinuationPort {

    /** Contract schema {@code ContinueAsNewRequest}. */
    record ContinueAsNewRequest(String continuationRequestId, String reason, String processDefinitionId,
                                List<String> carryVariables, Map<String, Object> variables) {
    }

    /** Contract schema {@code ContinuationEnded}: kind USER_TASK, EXTERNAL_TASK, JOB, TIMER or MESSAGE_SUBSCRIPTION. */
    record ContinuationEnded(String kind, int ended) {
    }

    /** Contract schema {@code ContinueAsNewResult}; outcome CREATED or EXISTING (then {@code ended} is empty). */
    record ContinueAsNewResult(String requestId, String predecessorId, String successorId, String chainId, int generation,
                               String businessKey, String processDefinitionId, String outcome, String actor,
                               String reason, Instant continuedAt, List<ContinuationEnded> ended) {
    }

    /** Contract schema {@code ProcessContinuationLink}. */
    record ProcessContinuationLink(String predecessorId, String successorId, int generation, String requestId,
                                   String actor, String reason, Instant continuedAt) {
    }

    /** Contract schema {@code ProcessContinuationChain}: {@code instanceIds} oldest first, the last one is current. */
    record ProcessContinuationChain(String chainId, List<String> instanceIds, List<ProcessContinuationLink> links) {
    }

    /**
     * Ends the active root instance and starts exactly one successor in one transaction. 409
     * {@code CONTINUATION_NOT_ALLOWED} (extension {@code reason}, and {@code successorId} for ALREADY_CONTINUED), 404
     * for an unknown instance or one of another tenant, 400 {@code VALIDATION_FAILED}, 403 without the object
     * permission.
     */
    ContinueAsNewResult continueAsNew(RequestContext ctx, String processInstanceId, ContinueAsNewRequest request);

    /** The continuation chain of the instance in the caller's tenant (one instance and no links if never continued). */
    ProcessContinuationChain chain(RequestContext ctx, String processInstanceId);
}
