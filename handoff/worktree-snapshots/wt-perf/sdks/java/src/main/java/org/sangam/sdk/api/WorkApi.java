// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.api;

import com.fasterxml.jackson.core.type.TypeReference;
import org.sangam.sdk.http.ApiRequest;
import org.sangam.sdk.http.HttpTransport;
import org.sangam.sdk.model.BpmnErrorRequest;
import org.sangam.sdk.model.CompleteWorkRequest;
import org.sangam.sdk.model.ExtendLockRequest;
import org.sangam.sdk.model.ExtendLockResult;
import org.sangam.sdk.model.ExternalWork;
import org.sangam.sdk.model.FailWorkRequest;
import org.sangam.sdk.model.FetchAndLockRequest;
import org.sangam.sdk.model.OutcomeUnknownRequest;
import org.sangam.sdk.model.OutcomeUnknownResult;
import org.sangam.sdk.model.UnlockRequest;

import java.time.Duration;
import java.util.List;

/**
 * Worker API {@code /api/v1/work} (ARCHITECTURE §4, VI.3). Every call after fetch carries
 * {@code (workerId, lockVersion, attemptId)}; a stale lockVersion raises
 * {@link org.sangam.sdk.error.StaleLeaseException} (409 stale-lease) and the server changed nothing.
 *
 * <p>Most applications use {@link org.sangam.sdk.worker.SangamWorker}, which drives these calls.
 * The {@code idempotencyKey} argument is sent as {@code Idempotency-Key}; the worker derives it from the
 * work item's operationId so that a repeated HTTP call is answered from the server's idempotency store (R41).
 */
public final class WorkApi extends ApiSupport {

    private static final TypeReference<List<ExternalWork>> WORK_LIST = new TypeReference<>() { };

    public WorkApi(HttpTransport http) {
        super(http);
    }

    /** Long-poll fetch-and-lock; the HTTP timeout is the async timeout plus a margin. Not retried by the transport. */
    public List<ExternalWork> fetchAndLock(FetchAndLockRequest request) {
        Duration t = Duration.ofMillis(request.asyncResponseTimeoutMs()).plusSeconds(15);
        List<ExternalWork> l = call(ApiRequest.post("/work/fetch-and-lock").body(request).timeout(t).noRetry(), WORK_LIST);
        return l == null ? List.of() : l;
    }

    public ExtendLockResult extendLock(String workId, ExtendLockRequest request, String idempotencyKey) {
        return call(ApiRequest.post("/work/" + seg(workId) + "/extend-lock").body(request).idempotencyKey(idempotencyKey),
                ExtendLockResult.class);
    }

    public void complete(String workId, CompleteWorkRequest request, String idempotencyKey) {
        call(ApiRequest.post("/work/" + seg(workId) + "/complete").body(request).idempotencyKey(idempotencyKey), Void.class);
    }

    public void fail(String workId, FailWorkRequest request, String idempotencyKey) {
        call(ApiRequest.post("/work/" + seg(workId) + "/fail").body(request).idempotencyKey(idempotencyKey), Void.class);
    }

    public void bpmnError(String workId, BpmnErrorRequest request, String idempotencyKey) {
        call(ApiRequest.post("/work/" + seg(workId) + "/bpmn-error").body(request).idempotencyKey(idempotencyKey), Void.class);
    }

    public void unlock(String workId, UnlockRequest request, String idempotencyKey) {
        call(ApiRequest.post("/work/" + seg(workId) + "/unlock").body(request).idempotencyKey(idempotencyKey), Void.class);
    }

    /** VI.3.2 rule 4: report that the downstream outcome is unknown (opens a reconciliation item). */
    public OutcomeUnknownResult outcomeUnknown(String workId, OutcomeUnknownRequest request, String idempotencyKey) {
        return call(ApiRequest.post("/work/" + seg(workId) + "/outcome-unknown").body(request).idempotencyKey(idempotencyKey), OutcomeUnknownResult.class);
    }

    /** VI.3.1: CANCEL_REQUESTED --worker ack--> CANCELLED. */
    public void cancelAck(String workId, UnlockRequest request, String idempotencyKey) {
        call(ApiRequest.post("/work/" + seg(workId) + "/cancel-ack").body(request).idempotencyKey(idempotencyKey), Void.class);
    }
}
