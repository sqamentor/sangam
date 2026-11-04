// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import java.util.List;

import io.micrometer.core.instrument.Metrics;

import org.sangam.rest.adapter.operaton.TrackingWorkPort;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.WorkPort;
import org.sangam.rest.support.Permissions;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Worker API (ARCHITECTURE §4, VI.3, FC-36). Fenced by (workerId, lockVersion, attemptId): 409 STALE_LEASE. */
@RestController
@RequestMapping("/api/v1/work")
public class WorkController {

    private final WorkPort work;
    private final PermissionPort permissions;

    public WorkController(WorkPort work, PermissionPort permissions) {
        this.work = work;
        this.permissions = permissions;
    }

    @PostMapping("/fetch-and-lock")
    public List<WorkPort.ExternalWork> fetchAndLock(RequestContext ctx, @RequestBody WorkPort.FetchAndLockRequest body,
            @RequestHeader(value = "X-Sangam-Sdk", required = false) String sdk,
            @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        permissions.require(ctx, Permissions.WORK_EXECUTE);
        if (body.workerId() == null || body.workerId().isBlank()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "workerId is required");
        }
        if (body.topics() == null || body.topics().isEmpty()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "at least one topic is required");
        }
        if (work instanceof TrackingWorkPort tracking) {
            tracking.noteSdk(ctx.tenantId(), body.workerId(), sdk != null ? sdk : userAgent);
        }
        return work.fetchAndLock(ctx, body);
    }

    @PostMapping("/{id}/extend-lock")
    public WorkPort.LockState extendLock(RequestContext ctx, @PathVariable String id,
                                         @RequestBody WorkPort.ExtendLockRequest body) {
        permissions.require(ctx, Permissions.WORK_EXECUTE);
        Telemetry.workId(id);
        WorkPort.LockState state = work.extendLock(ctx, id, body);
        Metrics.counter("sangam.lease.extended").increment();
        return state;
    }

    @PostMapping("/{id}/complete")
    public ResponseEntity<Void> complete(RequestContext ctx, @PathVariable String id,
                                         @RequestBody WorkPort.CompleteRequest body) {
        permissions.require(ctx, Permissions.WORK_EXECUTE);
        Telemetry.workId(id);
        work.complete(ctx, id, body);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/fail")
    public ResponseEntity<Void> fail(RequestContext ctx, @PathVariable String id, @RequestBody WorkPort.FailRequest body) {
        permissions.require(ctx, Permissions.WORK_EXECUTE);
        Telemetry.workId(id);
        work.fail(ctx, id, body);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/bpmn-error")
    public ResponseEntity<Void> bpmnError(RequestContext ctx, @PathVariable String id,
                                          @RequestBody WorkPort.BpmnErrorRequest body) {
        permissions.require(ctx, Permissions.WORK_EXECUTE);
        Telemetry.workId(id);
        if (body.errorCode() == null || body.errorCode().isBlank()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "errorCode is required");
        }
        work.bpmnError(ctx, id, body);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/unlock")
    public ResponseEntity<Void> unlock(RequestContext ctx, @PathVariable String id, @RequestBody WorkPort.UnlockRequest body) {
        permissions.require(ctx, Permissions.WORK_EXECUTE);
        Telemetry.workId(id);
        work.unlock(ctx, id, body);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/outcome-unknown")
    public ResponseEntity<WorkPort.OutcomeUnknownResult> outcomeUnknown(RequestContext ctx, @PathVariable String id,
                                                                        @RequestBody WorkPort.OutcomeUnknownRequest body) {
        permissions.require(ctx, Permissions.WORK_EXECUTE);
        Telemetry.workId(id);
        WorkPort.OutcomeUnknownResult r = work.outcomeUnknown(ctx, id, body);
        Telemetry.operationId(r.operationId());
        return Lists.accepted(r.reconciliationItemId(), r);
    }

    @PostMapping("/{id}/cancel-ack")
    public ResponseEntity<Void> cancelAck(RequestContext ctx, @PathVariable String id,
                                          @RequestBody WorkPort.CancelAckRequest body) {
        permissions.require(ctx, Permissions.WORK_EXECUTE);
        Telemetry.workId(id);
        work.cancelAck(ctx, id, body);
        return ResponseEntity.noContent().build();
    }
}
