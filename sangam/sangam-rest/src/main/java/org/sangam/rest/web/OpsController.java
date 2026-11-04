// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import java.util.List;

import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.OpsPort;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operations console API (O01–O09, R63, R64): incidents, reconciliation (VI.3 rule 4, VI.5), outbox repair (VI.2.3),
 * jobs, batches, migration (R48, FC-18), restart, history cleanup. State changes require a reason.
 */
@RestController
@RequestMapping("/api/v1/ops")
public class OpsController {

    private final OpsPort ops;
    private final PermissionPort permissions;

    public OpsController(OpsPort ops, PermissionPort permissions) {
        this.ops = ops;
        this.permissions = permissions;
    }

    @GetMapping("/incidents")
    public ResponseEntity<List<OpsPort.Incident>> incidents(RequestContext ctx,
            @RequestParam(required = false) String type, @RequestParam(required = false) String processInstanceId,
            @RequestParam(required = false) String processDefinitionKey,
            @RequestParam(required = false) String activityId,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        permissions.require(ctx, Permissions.OPS_READ);
        return Lists.ok(ops.incidents(ctx, new OpsPort.IncidentFilter(type, processInstanceId, processDefinitionKey,
                activityId), Lists.page(page, size)));
    }

    @GetMapping("/incidents/{id}")
    public OpsPort.Incident incident(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, Permissions.OPS_READ);
        return ops.incident(ctx, id);
    }

    @PostMapping("/incidents/{id}/retry")
    public ResponseEntity<OpsPort.Incident> retryIncident(RequestContext ctx, @PathVariable String id,
                                                          @RequestBody OpsPort.IncidentAction body) {
        permissions.require(ctx, Permissions.OPS_WRITE);
        ProcessController.requireReason(body.reason());
        return Lists.okRef(id, ops.retryIncident(ctx, id, body));
    }

    @PostMapping("/incidents/{id}/resolve")
    public ResponseEntity<OpsPort.Incident> resolveIncident(RequestContext ctx, @PathVariable String id,
                                                            @RequestBody OpsPort.IncidentAction body) {
        permissions.require(ctx, Permissions.OPS_WRITE);
        ProcessController.requireReason(body.reason());
        return Lists.okRef(id, ops.resolveIncident(ctx, id, body));
    }

    @GetMapping("/reconciliation")
    public ResponseEntity<List<OpsPort.ReconciliationItem>> reconciliation(RequestContext ctx,
            @RequestParam(required = false) String state, @RequestParam(required = false) String kind,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        permissions.require(ctx, Permissions.OPS_READ);
        return Lists.ok(ops.reconciliation(ctx, state, kind, Lists.page(page, size)));
    }

    @GetMapping("/reconciliation/{id}")
    public OpsPort.ReconciliationItem reconciliationItem(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, Permissions.OPS_READ);
        return ops.reconciliationItem(ctx, id);
    }

    @PostMapping("/reconciliation/{id}/resolve")
    public ResponseEntity<OpsPort.ReconciliationItem> resolveReconciliation(RequestContext ctx, @PathVariable String id,
                                                                            @RequestBody OpsPort.ResolveRequest body) {
        permissions.require(ctx, Permissions.RECON_RESOLVE);
        if (body.action() == null) {
            throw ApiProblem.badRequest("VALIDATION_FAILED",
                    "action is required: CONFIRM_SUCCESS | RETRY_SAME_OPERATION | MARK_FAILED");
        }
        return Lists.okRef(id, ops.resolveReconciliation(ctx, id, body));
    }

    @GetMapping("/outbox")
    public ResponseEntity<List<OpsPort.OutboxDelivery>> outbox(RequestContext ctx,
            @RequestParam(required = false) String state, @RequestParam(required = false) String destination,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        permissions.require(ctx, Permissions.OPS_READ);
        return Lists.ok(ops.outbox(ctx, state, destination, Lists.page(page, size)));
    }

    @GetMapping("/outbox/stats")
    public OpsPort.OutboxStats outboxStats(RequestContext ctx) {
        permissions.require(ctx, Permissions.OPS_READ);
        return ops.outboxStats(ctx);
    }

    @PostMapping("/outbox/{outboxId}/requeue")
    public ResponseEntity<OpsPort.OutboxDelivery> requeue(RequestContext ctx, @PathVariable String outboxId,
                                                          @RequestBody OpsPort.OutboxRepairRequest body) {
        permissions.require(ctx, Permissions.OPS_WRITE);
        ProcessController.requireReason(body.reason());
        return Lists.okRef(outboxId, ops.requeueOutbox(ctx, outboxId, body));
    }

    @PostMapping("/outbox/{outboxId}/skip")
    public ResponseEntity<OpsPort.OutboxDelivery> skip(RequestContext ctx, @PathVariable String outboxId,
                                                       @RequestBody OpsPort.OutboxRepairRequest body) {
        permissions.require(ctx, Permissions.OPS_WRITE);
        ProcessController.requireReason(body.reason());
        return Lists.okRef(outboxId, ops.skipOutbox(ctx, outboxId, body));
    }

    @GetMapping("/jobs")
    public ResponseEntity<List<OpsPort.Job>> jobs(RequestContext ctx,
            @RequestParam(required = false) String processInstanceId,
            @RequestParam(required = false) String processDefinitionKey,
            @RequestParam(required = false) Boolean withException, @RequestParam(required = false) Boolean timers,
            @RequestParam(required = false) Boolean executable,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        permissions.require(ctx, Permissions.OPS_READ);
        return Lists.ok(ops.jobs(ctx, new OpsPort.JobFilter(processInstanceId, processDefinitionKey, withException,
                timers, executable), Lists.page(page, size)));
    }

    @PostMapping("/jobs/{id}/retries")
    public ResponseEntity<OpsPort.Job> jobRetries(RequestContext ctx, @PathVariable String id,
                                                  @RequestBody OpsPort.JobRetriesRequest body) {
        permissions.require(ctx, Permissions.OPS_WRITE);
        ProcessController.requireReason(body.reason());
        return Lists.okRef(id, ops.setJobRetries(ctx, id, body));
    }

    @PostMapping("/jobs/{id}/execute")
    public ResponseEntity<OpsPort.Job> executeJob(RequestContext ctx, @PathVariable String id,
                                                  @RequestParam(required = false) String reason) {
        permissions.require(ctx, Permissions.OPS_WRITE);
        ProcessController.requireReason(reason);
        return Lists.okRef(id, ops.executeJob(ctx, id, reason));
    }

    @PostMapping("/jobs/{id}/suspension")
    public ResponseEntity<OpsPort.Job> jobSuspension(RequestContext ctx, @PathVariable String id,
                                                     @RequestBody OpsPort.JobSuspensionRequest body) {
        permissions.require(ctx, Permissions.OPS_WRITE);
        ProcessController.requireReason(body.reason());
        return Lists.okRef(id, ops.setJobSuspension(ctx, id, body));
    }

    @PostMapping("/batches/preview")
    public OpsPort.BatchPreview previewBatch(RequestContext ctx, @RequestBody OpsPort.BatchRequest body) {
        permissions.require(ctx, Permissions.BATCH_ADMIN);
        return ops.previewBatch(ctx, body);
    }

    @PostMapping("/batches")
    public ResponseEntity<OpsPort.Batch> createBatch(RequestContext ctx, @RequestBody OpsPort.BatchRequest body) {
        permissions.require(ctx, Permissions.BATCH_ADMIN);
        ProcessController.requireReason(body.reason());
        OpsPort.Batch b = ops.createBatch(ctx, body);
        return Lists.created("/api/v1/ops/batches/" + b.id(), b.id(), b);
    }

    @GetMapping("/batches")
    public ResponseEntity<List<OpsPort.Batch>> batches(RequestContext ctx, @RequestParam(required = false) String state,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        permissions.require(ctx, Permissions.OPS_READ);
        return Lists.ok(ops.batches(ctx, state, Lists.page(page, size)));
    }

    @GetMapping("/batches/{id}")
    public OpsPort.Batch batch(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, Permissions.OPS_READ);
        return ops.batch(ctx, id);
    }

    @GetMapping("/batches/{id}/items")
    public ResponseEntity<List<OpsPort.BatchItem>> batchItems(RequestContext ctx, @PathVariable String id,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        permissions.require(ctx, Permissions.OPS_READ);
        return Lists.ok(ops.batchItems(ctx, id, state, Lists.page(page, size)));
    }

    @PostMapping("/batches/{id}/control")
    public ResponseEntity<OpsPort.Batch> controlBatch(RequestContext ctx, @PathVariable String id,
                                                      @RequestBody OpsPort.BatchControlRequest body) {
        permissions.require(ctx, Permissions.BATCH_ADMIN);
        ProcessController.requireReason(body.reason());
        return Lists.okRef(id, ops.controlBatch(ctx, id, body));
    }

    @PostMapping("/migrations/plan")
    public OpsPort.MigrationPlan planMigration(RequestContext ctx, @RequestBody OpsPort.MigrationPlanRequest body) {
        permissions.require(ctx, Permissions.BATCH_ADMIN);
        return ops.planMigration(ctx, body);
    }

    @PostMapping("/migrations/execute")
    public ResponseEntity<OpsPort.Batch> executeMigration(RequestContext ctx,
                                                          @RequestBody OpsPort.MigrationExecuteRequest body) {
        permissions.require(ctx, Permissions.BATCH_ADMIN);
        ProcessController.requireReason(body.reason());
        OpsPort.Batch b = ops.executeMigration(ctx, body);
        return Lists.accepted(b.id(), b);
    }

    @PostMapping("/restarts")
    public ResponseEntity<OpsPort.Batch> restart(RequestContext ctx, @RequestBody OpsPort.RestartRequest body) {
        permissions.require(ctx, Permissions.BATCH_ADMIN);
        ProcessController.requireReason(body.reason());
        OpsPort.Batch b = ops.restart(ctx, body);
        return Lists.accepted(b.id(), b);
    }

    @GetMapping("/cleanup")
    public OpsPort.CleanupStatus cleanupStatus(RequestContext ctx) {
        permissions.require(ctx, Permissions.OPS_READ);
        return ops.cleanupStatus(ctx);
    }

    @PostMapping("/cleanup")
    public ResponseEntity<OpsPort.CleanupStatus> triggerCleanup(RequestContext ctx,
                                                                @RequestParam(required = false) String reason) {
        permissions.require(ctx, Permissions.OPS_WRITE);
        ProcessController.requireReason(reason);
        OpsPort.CleanupStatus s = ops.triggerCleanup(ctx, reason);
        return Lists.accepted(s.jobId(), s);
    }
}
