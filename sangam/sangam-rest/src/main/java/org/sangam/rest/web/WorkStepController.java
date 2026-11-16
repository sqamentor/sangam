// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.sangam.rest.adapter.operaton.WorkStepJournal;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.WorkPort;
import org.sangam.rest.support.Permissions;
import org.sangam.rest.support.TxSupport;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The step journal behind the SDK helper {@code context.step(name, fn)} (M-025, R37 / E02; contract
 * {@code getWorkStep}, {@code recordWorkStep}, {@code listWorkSteps}). A worker records each side-effecting step of a
 * leased work item under the work's stable operationId (R36), so a later attempt replays the recorded result instead
 * of calling the remote API again. Reads and writes of one step are fenced like the other {@code /work} calls
 * (VI.3 rule 2): the lease check and the journal row share one transaction ({@link WorkPort#inFencedWork}).
 *
 * <p>NOT exactly-once for remote APIs (R37): a crash between the remote call and the outcome leaves the step
 * {@code INTENT}; the helper then looks the outcome up or reports outcome-unknown, never re-issues blindly.
 */
@RestController
@RequestMapping("/api/v1/work")
public class WorkStepController {

    /** Contract {@code WorkStep}. */
    public record WorkStep(String operationId, String name, String state, Object result, String providerRef,
                           String error, String attemptId, Instant recordedAt) {
    }

    /** Contract {@code WorkStepRequest}. */
    public record WorkStepRequest(String workerId, Long lockVersion, String attemptId, String state, Object result,
                                  String providerRef, String error) {
    }

    private final WorkPort work;
    private final PermissionPort permissions;
    private final TxSupport tx;
    private final WorkStepJournal journal;

    public WorkStepController(WorkPort work, PermissionPort permissions, TxSupport tx) {
        this.work = work;
        this.permissions = permissions;
        this.tx = tx;
        this.journal = new WorkStepJournal(tx.dialect());
    }

    /** UI-DEMO-1: a step name is any printable ASCII, '/' included (refused encoded in a path segment), so it is a query parameter. */
    @GetMapping("/{id}/step")
    public WorkStep get(RequestContext ctx, @PathVariable String id, @RequestParam String stepName,
                        @RequestParam String workerId, @RequestParam long lockVersion, @RequestParam String attemptId) {
        permissions.require(ctx, Permissions.WORK_EXECUTE);
        Telemetry.workId(id);
        String name = WorkStepJournal.requireName(stepName);
        requireFence(workerId, attemptId);
        return work.inFencedWork(ctx, id, workerId, lockVersion, attemptId, (c, lease) -> {
            Telemetry.operationId(String.valueOf(lease.operationId()));
            return journal.find(c, lease.operationId(), name).map(WorkStepController::view)
                    .orElseThrow(() -> ApiProblem.notFound("work step", name));
        });
    }

    @PutMapping("/{id}/step")
    public WorkStep record(RequestContext ctx, @PathVariable String id, @RequestParam String stepName,
                           @RequestBody WorkStepRequest body) {
        permissions.require(ctx, Permissions.WORK_EXECUTE);
        Telemetry.workId(id);
        String name = WorkStepJournal.requireName(stepName);
        if (body == null) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "a WorkStepRequest body is required");
        }
        requireFence(body.workerId(), body.attemptId());
        if (body.lockVersion() == null) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "lockVersion is required");
        }
        WorkStepJournal.State state = state(body.state());
        return work.inFencedWork(ctx, id, body.workerId(), body.lockVersion(), body.attemptId(), (c, lease) -> {
            Telemetry.operationId(String.valueOf(lease.operationId()));
            return view(journal.record(c, lease, name, state, body.result(), body.providerRef(), body.error()));
        });
    }

    @GetMapping("/{id}/steps")
    public List<WorkStep> list(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, Permissions.OPS_READ);
        Telemetry.workId(id);
        UUID op = work.operationIdOfWork(ctx, id).orElse(null);
        List<WorkStepJournal.Step> steps = tx.inTx(() -> op != null
                ? journal.listByOperation(tx.connection(), ctx.tenantId(), op)
                : journal.listByWork(tx.connection(), ctx.tenantId(), id));
        if (op == null && steps.isEmpty()) {
            throw ApiProblem.notFound("external work", id);
        }
        return steps.stream().map(WorkStepController::view).toList();
    }

    private static void requireFence(String workerId, String attemptId) {
        if (workerId == null || workerId.isBlank()) {
            throw ApiProblem.badRequest("WORKER_ID_REQUIRED", "workerId is required");
        }
        if (attemptId == null || attemptId.isBlank()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "attemptId is required");
        }
    }

    private static WorkStepJournal.State state(String s) {
        if (s == null || s.isBlank()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "state is required (INTENT, SUCCEEDED or FAILED)");
        }
        try {
            return WorkStepJournal.State.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "state must be INTENT, SUCCEEDED or FAILED");
        }
    }

    static WorkStep view(WorkStepJournal.Step s) {
        return new WorkStep(s.operationId().toString(), s.name(), s.state().name(), WorkStepJournal.result(s),
                s.providerRef(), s.error(), s.attemptId(), s.recordedAt());
    }
}
