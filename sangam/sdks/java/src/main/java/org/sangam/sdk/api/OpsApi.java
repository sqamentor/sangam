// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.api;

import org.sangam.sdk.http.ApiRequest;
import org.sangam.sdk.http.HttpTransport;
import org.sangam.sdk.model.Page;
import org.sangam.sdk.model.ReconciliationItem;
import org.sangam.sdk.model.ResolveReconciliationRequest;

import java.util.HashMap;
import java.util.Map;

/** Operations {@code /api/v1/ops/**}: incidents, reconciliation workbench (VI.3 UNKNOWN_OUTCOME), outbox, jobs. */
public final class OpsApi extends ApiSupport {

    public OpsApi(HttpTransport http) {
        super(http);
    }

    public ResourceApi incidents() { return new ResourceApi(http, "/ops/incidents"); }
    public ResourceApi outbox() { return new ResourceApi(http, "/ops/outbox"); }
    public ResourceApi jobs() { return new ResourceApi(http, "/ops/jobs"); }
    public ResourceApi batches() { return new ResourceApi(http, "/ops/batches"); }

    /** {@code POST /ops/incidents/{id}/retry} with an audited reason. */
    public Map<String, Object> retryIncident(String incidentId, String reason, String idempotencyKey) {
        return incidents().action(incidentId, "retry", reasonBody(reason), idempotencyKey);
    }

    /** {@code POST /ops/jobs/{id}/retries}. */
    public Map<String, Object> setJobRetries(String jobId, int retries, String reason, String idempotencyKey) {
        Map<String, Object> body = reasonBody(reason);
        body.put("retries", retries);
        return jobs().action(jobId, "retries", body, idempotencyKey);
    }

    /** {@code POST /ops/outbox/{outboxId}/requeue}. */
    public Map<String, Object> requeueOutbox(String outboxId, String reason, String idempotencyKey) {
        return outbox().action(outboxId, "requeue", reasonBody(reason), idempotencyKey);
    }

    /** {@code GET /ops/outbox/stats}. */
    public Map<String, Object> outboxStats() {
        return call(ApiRequest.get("/ops/outbox/stats"), MAP);
    }

    /** Filters: state (OPEN|RESOLVED), kind (UNKNOWN_OUTCOME|LATE_RESULT_AFTER_DELETE). */
    public Page<ReconciliationItem> reconciliation(String state, String kind, Integer page, Integer size) {
        return readPage(ApiRequest.get("/ops/reconciliation").query("state", state).query("kind", kind)
                .query("page", page).query("size", size), ReconciliationItem.class);
    }

    public ReconciliationItem reconciliationItem(String id) {
        return call(ApiRequest.get("/ops/reconciliation/" + seg(id)), ReconciliationItem.class);
    }

    /** Resolve with {@code CONFIRM_SUCCESS}, {@code RETRY_SAME_OPERATION} (same operationId) or {@code MARK_FAILED}, with evidence. */
    public ReconciliationItem resolve(String id, ResolveReconciliationRequest request, String idempotencyKey) {
        return call(ApiRequest.post("/ops/reconciliation/" + seg(id) + "/resolve").body(request).idempotencyKey(idempotencyKey),
                ReconciliationItem.class);
    }

    private static Map<String, Object> reasonBody(String reason) {
        Map<String, Object> m = new HashMap<>();
        if (reason != null) m.put("reason", reason);
        return m;
    }
}
