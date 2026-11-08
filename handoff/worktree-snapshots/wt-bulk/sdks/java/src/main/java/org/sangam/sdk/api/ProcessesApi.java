// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.api;

import org.sangam.sdk.http.ApiRequest;
import org.sangam.sdk.http.HttpTransport;
import org.sangam.sdk.model.CaseInstance;
import org.sangam.sdk.model.CorrelateMessageRequest;
import org.sangam.sdk.model.MessageResult;
import org.sangam.sdk.model.Page;
import org.sangam.sdk.model.ProcessInstance;
import org.sangam.sdk.model.SignalRequest;
import org.sangam.sdk.model.StartCaseRequest;
import org.sangam.sdk.model.StartProcessRequest;
import org.sangam.sdk.model.TerminateRequest;
import org.sangam.sdk.model.TerminationResult;

import java.util.Map;

/** Process and case instances, messages and signals. The tenant always comes from the token (VI.4 rule 6). */
public final class ProcessesApi extends ApiSupport {

    public ProcessesApi(HttpTransport http) {
        super(http);
    }

    /**
     * {@code POST /process-instances}. Pass a business-derived Idempotency-Key (for example the bill number) to make
     * "start once" safe across client retries; 503 BACKPRESSURE is retried with back-off by the transport.
     */
    public ProcessInstance start(StartProcessRequest request, String idempotencyKey) {
        return call(ApiRequest.post("/process-instances").body(request).idempotencyKey(idempotencyKey), ProcessInstance.class);
    }

    public ProcessInstance start(String key, String businessKey, Map<String, Object> variables) {
        return start(new StartProcessRequest(key, businessKey, variables, null, null, null, null), null);
    }

    /** Filters: key, businessKey, state, startedAfter, startedBefore, withIncidents, superProcessInstanceId. */
    public Page<ProcessInstance> list(Map<String, ?> filters, Integer page, Integer size) {
        return readPage(ApiRequest.get("/process-instances").query(filters).query("page", page).query("size", size),
                ProcessInstance.class);
    }

    public ProcessInstance get(String processInstanceId) {
        return call(ApiRequest.get("/process-instances/" + seg(processInstanceId)), ProcessInstance.class);
    }

    public Map<String, Object> variables(String processInstanceId) {
        return call(ApiRequest.get("/process-instances/" + seg(processInstanceId) + "/variables"), MAP);
    }

    /** Business cancel (VI.5): runs cancel/compensation paths; open external work goes to CANCEL_REQUESTED. */
    public TerminationResult cancel(String processInstanceId, TerminateRequest request, String idempotencyKey) {
        return call(ApiRequest.post("/process-instances/" + seg(processInstanceId) + "/cancel").body(request)
                .idempotencyKey(idempotencyKey), TerminationResult.class);
    }

    public TerminationResult cancel(String processInstanceId, String reason) {
        return cancel(processInstanceId, new TerminateRequest(reason, null), null);
    }

    public MessageResult correlateMessage(CorrelateMessageRequest request, String idempotencyKey) {
        return call(ApiRequest.post("/messages").body(request).idempotencyKey(idempotencyKey), MessageResult.class);
    }

    public void signal(SignalRequest request, String idempotencyKey) {
        call(ApiRequest.post("/signals").body(request).idempotencyKey(idempotencyKey), Void.class);
    }

    /** {@code POST /case-instances} (CMMN 1.1). */
    public CaseInstance startCase(StartCaseRequest request, String idempotencyKey) {
        return call(ApiRequest.post("/case-instances").body(request).idempotencyKey(idempotencyKey), CaseInstance.class);
    }

    public CaseInstance getCase(String caseInstanceId) {
        return call(ApiRequest.get("/case-instances/" + seg(caseInstanceId)), CaseInstance.class);
    }
}
