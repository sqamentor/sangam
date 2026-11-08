// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongFunction;

import org.sangam.core.api.EngineAdapter;
import org.sangam.core.api.LeaseTable;
import org.sangam.ports.support.PortsProblem;
import org.sangam.ports.worker.FlowableWorkerCompat;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.WorkPort;
import org.sangam.rest.support.Permissions;
import org.sangam.rest.support.TxSupport;
import org.sangam.rest.support.TypedVariables;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Flowable external-worker REST compatibility (FLOWABLE-INTO-OPERATON FC-36, FLOWABLE-INTO-OPERATON FC-37): existing Flowable external-worker clients (Java, JS,
 * Python, .NET, Go) can point at {@code /api/v1/compat/flowable/external-job-api}. Requests are translated by
 * sangam-flowable-ports' {@link FlowableWorkerCompat} onto the fenced Sangam worker API ({@link WorkPort}), so lease
 * fencing (409 {@code STALE_LEASE}), tombstones (410) and audit are the same as on {@code /api/v1/work}.
 *
 * <p><b>Fencing (FW-07, audit PR-1).</b> The acquire response carries {@code sangamLockVersion} and
 * {@code sangamAttemptId}; a client must echo {@code sangamLockVersion}, else the call is refused with
 * {@code 409 FENCING_TOKEN_REQUIRED} (Flowable's own unfenced completion is not re-created). Only with
 * {@code sangam.compat.flowable.allow-unfenced=true} (default false, legacy clients that cannot echo it) is an
 * unfenced call accepted, and then only when it echoes {@code sangamAttemptId}, which must equal the lease's current
 * attempt id. The lease is read in the same transaction as the completion (no TOCTOU window), and every unfenced call
 * is audited ({@code UNFENCED_WORKER_CALL}).
 */
@RestController
@RequestMapping("/api/v1/compat/flowable/external-job-api")
@ConditionalOnClass(FlowableWorkerCompat.class)
@ConditionalOnProperty(name = "sangam.modules.ports.enabled", havingValue = "true", matchIfMissing = true)
public class FlowableWorkerCompatController {

    private final WorkPort work;
    private final PermissionPort permissions;
    private final TxSupport tx;
    private final AuditPort audit;
    private final boolean allowUnfenced;

    public FlowableWorkerCompatController(WorkPort work, PermissionPort permissions, TxSupport tx, AuditPort audit,
            @Value("${sangam.compat.flowable.allow-unfenced:false}") boolean allowUnfenced) {
        this.work = work;
        this.permissions = permissions;
        this.tx = tx;
        this.audit = audit;
        this.allowUnfenced = allowUnfenced;
    }

    @PostMapping(value = "/acquire/jobs", consumes = MediaType.APPLICATION_JSON_VALUE)
    public List<Map<String, Object>> acquire(RequestContext ctx, @RequestBody String body) {
        permissions.require(ctx, Permissions.WORK_EXECUTE);
        Map<String, Object> m = translate(() -> FlowableWorkerCompat.toFetchAndLock(body));
        @SuppressWarnings("unchecked")
        Map<String, Object> topic = ((List<Map<String, Object>>) m.get("topics")).get(0);
        WorkPort.FetchAndLockRequest req = new WorkPort.FetchAndLockRequest(String.valueOf(m.get("workerId")),
                List.of(new WorkPort.TopicRequest(String.valueOf(topic.get("name")),
                        ((Number) topic.get("lockDurationMs")).longValue(), null, null, null, null)),
                ((Number) m.get("maxTasks")).intValue(), null, null);
        List<Map<String, Object>> out = new ArrayList<>();
        for (WorkPort.ExternalWork w : work.fetchAndLock(ctx, req)) {
            out.add(FlowableWorkerCompat.toFlowableJob(toCore(w), w.tenantId()));
        }
        return out;
    }

    @PostMapping(value = "/acquire/jobs/{jobId}/complete", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> complete(RequestContext ctx, @PathVariable String jobId, @RequestBody String body) {
        permissions.require(ctx, Permissions.WORK_EXECUTE);
        Map<String, Object> m = translate(() -> FlowableWorkerCompat.toComplete(body, FlowableWorkerCompat.UNFENCED));
        fenced(ctx, jobId, m, "complete", lv -> {
            work.complete(ctx, jobId, new WorkPort.CompleteRequest(worker(m), lv, attempt(m), vars(m), null, null));
            return null;
        });
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/acquire/jobs/{jobId}/bpmnError", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> bpmnError(RequestContext ctx, @PathVariable String jobId, @RequestBody String body) {
        permissions.require(ctx, Permissions.WORK_EXECUTE);
        Map<String, Object> m = translate(() -> FlowableWorkerCompat.toComplete(body, FlowableWorkerCompat.UNFENCED));
        if (m.get("errorCode") == null) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "errorCode is required");
        }
        fenced(ctx, jobId, m, "bpmnError", lv -> {
            work.bpmnError(ctx, jobId, new WorkPort.BpmnErrorRequest(worker(m), lv, attempt(m),
                    String.valueOf(m.get("errorCode")), null, vars(m)));
            return null;
        });
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/acquire/jobs/{jobId}/cmmnTerminate", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> cmmnTerminate(RequestContext ctx, @PathVariable String jobId, @RequestBody String body) {
        permissions.require(ctx, Permissions.WORK_EXECUTE);
        Map<String, Object> m = translate(() -> FlowableWorkerCompat.toCmmnTerminate(body, FlowableWorkerCompat.UNFENCED));
        fenced(ctx, jobId, m, "cmmnTerminate", lv -> {
            work.complete(ctx, jobId, new WorkPort.CompleteRequest(worker(m), lv, attempt(m), vars(m), null, null));
            return null;
        });
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/acquire/jobs/{jobId}/fail", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> fail(RequestContext ctx, @PathVariable String jobId, @RequestBody String body) {
        permissions.require(ctx, Permissions.WORK_EXECUTE);
        Map<String, Object> m = translate(() -> FlowableWorkerCompat.toFail(body, FlowableWorkerCompat.UNFENCED));
        fenced(ctx, jobId, m, "fail", lv -> {
            work.fail(ctx, jobId, new WorkPort.FailRequest(worker(m), lv, attempt(m),
                    String.valueOf(m.get("errorMessage")), null, ((Number) m.get("retries")).intValue(),
                    ((Number) m.get("retryBackoffMs")).longValue()));
            return null;
        });
        return ResponseEntity.noContent().build();
    }

    /**
     * Runs {@code call} with the fencing token. Echoed token: used as is. No token: {@code 409 FENCING_TOKEN_REQUIRED},
     * unless the opt-in is on; then the attempt id is required, the lease is resolved and compared inside the same
     * transaction as the call, and the call is audited.
     */
    private void fenced(RequestContext ctx, String jobId, Map<String, Object> m, String action, LongFunction<Void> call) {
        long token = translate(() -> FlowableWorkerCompat.fencingToken(m, allowUnfenced));
        if (token != FlowableWorkerCompat.UNFENCED) {
            call.apply(token);
            return;
        }
        String attemptId = attempt(m);
        if (attemptId == null || attemptId.isBlank()) {
            throw ApiProblem.conflict("FENCING_TOKEN_REQUIRED",
                    "an unfenced call must echo sangamAttemptId from the acquire response");
        }
        String worker = worker(m);
        tx.runInTx(() -> {
            LeaseTable.Row row = LeaseTable.lock(tx.connection(), jobId);
            if (row == null || !worker.equals(row.workerId())) {
                throw ApiProblem.staleLease(jobId, "NOT_OWNER", "no lease of worker " + worker + " on " + jobId);
            }
            if (!attemptId.equals(row.attemptId())) {
                throw ApiProblem.staleLease(jobId, "STALE_VERSION", "sangamAttemptId does not match the current attempt");
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("work_id", jobId);
            payload.put("action", action);
            payload.put("worker_id", worker);
            payload.put("attempt_id", attemptId);
            payload.put("lock_version", row.lockVersion());
            audit.record(ctx, "sec:" + ctx.tenantId(), "UNFENCED_WORKER_CALL", payload);
            call.apply(row.lockVersion());
        });
    }

    /** Ports translation errors ({@link PortsProblem}: INVALID_INPUT, FENCING_TOKEN_REQUIRED, ...) → problem+json. */
    private static <T> T translate(java.util.function.Supplier<T> s) {
        try {
            return s.get();
        } catch (PortsProblem p) {
            throw new ApiProblem(p.status(), p.code(), p.getMessage());
        } catch (IllegalArgumentException e) {
            throw ApiProblem.badRequest("INVALID_INPUT", e.getMessage());
        }
    }

    /** REST worker record → the core record {@link FlowableWorkerCompat#toFlowableJob} maps (one job mapping). */
    private static EngineAdapter.ExternalWork toCore(WorkPort.ExternalWork w) {
        return new EngineAdapter.ExternalWork(w.id(), w.topic(), w.processInstanceId(), w.activityId(),
                w.activityInstanceId(), w.retries() == null ? 0 : w.retries(), w.lockExpiresAt(), w.lockVersion(),
                w.attemptId(), w.variables(), w.operationId());
    }

    private static String worker(Map<String, Object> m) {
        return String.valueOf(m.get("workerId"));
    }

    private static String attempt(Map<String, Object> m) {
        Object a = m.get("attemptId");
        return a == null ? null : a.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> vars(Map<String, Object> m) {
        Object v = m.get("variables");
        return v instanceof Map<?, ?> map ? TypedVariables.fromJson((Map<String, Object>) map) : Map.of();
    }
}
