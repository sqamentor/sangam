// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import java.util.List;
import java.util.Map;

import org.sangam.rest.port.AdminPort;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.CasePort;
import org.sangam.rest.port.OpsPort;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.WorkspacePort;
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
 * Staff workspace (S02, S03, S07–S09, S15, R76): {@code /me} with seats, notifications, delegations and leave; inbox
 * counts and bulk actions (FLOWABLE-INTO-OPERATON D-34); candidate seats, field diff and lifecycle (FLOWABLE-INTO-OPERATON FC-52) of a task; the start catalogue;
 * case comments, participants and reactivation (FLOWABLE-INTO-OPERATON FC-59); the field-inspection app (EC-16).
 */
@RestController
@RequestMapping("/api/v1")
public class WorkspaceController {

    private final WorkspacePort workspace;
    private final CasePort cases;
    private final PermissionPort permissions;

    public WorkspaceController(WorkspacePort workspace, CasePort cases, PermissionPort permissions) {
        this.workspace = workspace;
        this.cases = cases;
        this.permissions = permissions;
    }

    /** {@code {reason}} body for reactivation. */
    public record ReasonBody(String reason) {
    }

    // ---------------------------------------------------------------- me

    @GetMapping("/me")
    public WorkspacePort.Me me(RequestContext ctx) {
        return workspace.me(ctx);
    }

    @GetMapping("/me/notifications")
    public ResponseEntity<List<WorkspacePort.Notification>> notifications(RequestContext ctx,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return Lists.ok(workspace.notifications(ctx, Lists.page(page, size)));
    }

    @PostMapping("/me/notifications/{id}/read")
    public ResponseEntity<Void> markRead(RequestContext ctx, @PathVariable String id) {
        workspace.markNotificationRead(ctx, id);
        return ResponseEntity.noContent().header(Lists.RESOURCE_REF, id).build();
    }

    @GetMapping("/me/delegations")
    public ResponseEntity<List<AdminPort.Assignment>> myDelegations(RequestContext ctx) {
        return Lists.ok(workspace.myDelegations(ctx));
    }

    @PostMapping("/me/leave-requests")
    public ResponseEntity<AdminPort.Assignment> requestLeave(RequestContext ctx,
                                                             @RequestBody WorkspacePort.LeaveRequest body) {
        permissions.require(ctx, Permissions.TASK_WRITE);
        if (body.seatId() == null || body.from() == null || body.to() == null) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "seatId, from and to are required");
        }
        if (body.substituteSeatId() == null && body.substitutePersonId() == null) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "substituteSeatId or substitutePersonId is required");
        }
        ProcessController.requireReason(body.reason());
        AdminPort.Assignment a = workspace.requestLeave(ctx, body);
        return Lists.created("/api/v1/admin/assignments/" + a.id(), a.id(), a);
    }

    // ---------------------------------------------------------------- tasks

    @GetMapping("/tasks/counts")
    public Map<String, Object> taskCounts(RequestContext ctx) {
        permissions.require(ctx, Permissions.TASK_READ);
        return workspace.taskCounts(ctx);
    }

    @PostMapping("/tasks/bulk-claim")
    public WorkspacePort.BulkResult bulkClaim(RequestContext ctx, @RequestBody WorkspacePort.BulkClaimRequest body) {
        permissions.require(ctx, Permissions.TASK_WRITE);
        requireIds(body.ids());
        return workspace.bulkClaim(ctx, body);
    }

    @PostMapping("/tasks/bulk-complete")
    public ResponseEntity<OpsPort.Batch> bulkComplete(RequestContext ctx,
                                                      @RequestBody WorkspacePort.BulkCompleteRequest body) {
        permissions.require(ctx, Permissions.TASK_WRITE);
        requireIds(body.ids());
        if (body.action() == null || body.action().isBlank()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "action is required");
        }
        OpsPort.Batch b = workspace.bulkComplete(ctx, body);
        return Lists.accepted(b.id(), b);
    }

    @GetMapping("/tasks/{id}/candidates")
    public ResponseEntity<List<WorkspacePort.CandidateSeat>> candidates(RequestContext ctx, @PathVariable String id,
                                                                        @RequestParam(required = false) String action) {
        permissions.require(ctx, Permissions.TASK_READ);
        return Lists.ok(workspace.candidates(ctx, id, action));
    }

    @GetMapping("/tasks/{id}/diff")
    public ResponseEntity<List<WorkspacePort.FieldChange>> diff(RequestContext ctx, @PathVariable String id,
                                                                @RequestParam long from, @RequestParam long to) {
        permissions.require(ctx, Permissions.TASK_READ);
        if (from < 1 || to < from) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "from must be >= 1 and to >= from");
        }
        return Lists.ok(workspace.diff(ctx, id, from, to));
    }

    @GetMapping("/tasks/{id}/lifecycle")
    public ResponseEntity<List<WorkspacePort.LifecycleEvent>> lifecycle(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, Permissions.TASK_READ);
        return Lists.ok(workspace.lifecycle(ctx, id));
    }

    // ---------------------------------------------------------------- catalogue and cases

    @GetMapping("/start-catalogue")
    public ResponseEntity<List<WorkspacePort.CatalogueEntry>> startCatalogue(RequestContext ctx) {
        permissions.require(ctx, Permissions.PROCESS_READ);
        return Lists.ok(workspace.startCatalogue(ctx));
    }

    @GetMapping("/case-instances/{id}/comments")
    public ResponseEntity<List<WorkspacePort.CaseComment>> caseComments(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, Permissions.CASE_READ);
        return Lists.ok(workspace.caseComments(ctx, id));
    }

    @PostMapping("/case-instances/{id}/comments")
    public ResponseEntity<WorkspacePort.CaseComment> addCaseComment(RequestContext ctx, @PathVariable String id,
            @RequestBody WorkspacePort.AddCaseCommentRequest body) {
        permissions.require(ctx, Permissions.CASE_WRITE);
        if (body.text() == null || body.text().isBlank()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "text is required");
        }
        WorkspacePort.CaseComment c = workspace.addCaseComment(ctx, id, body);
        return Lists.created("/api/v1/case-instances/" + id + "/comments", c.id(), c);
    }

    @GetMapping("/case-instances/{id}/participants")
    public ResponseEntity<List<WorkspacePort.Participant>> participants(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, Permissions.CASE_READ);
        return Lists.ok(workspace.participants(ctx, id));
    }

    @PostMapping("/case-instances/{id}/reactivation")
    public ResponseEntity<CasePort.CaseInstance> reactivate(RequestContext ctx, @PathVariable String id,
                                                            @RequestBody ReasonBody body) {
        permissions.require(ctx, Permissions.CASE_WRITE);
        ProcessController.requireReason(body == null ? null : body.reason());
        CasePort.CaseInstance ci = cases.instanceCommand(ctx, id,
                new CasePort.InstanceCommand("reactivate", null, body.reason()));
        Telemetry.businessKey(ci.businessKey());
        return Lists.created("/api/v1/case-instances/" + ci.id(), ci.id(), ci);
    }

    // ---------------------------------------------------------------- field app

    @GetMapping("/field/visits")
    public ResponseEntity<List<WorkspacePort.FieldVisit>> fieldVisits(RequestContext ctx) {
        permissions.require(ctx, Permissions.TASK_READ);
        return Lists.ok(workspace.fieldVisits(ctx));
    }

    @PostMapping("/field/visits/{id}/record")
    public ResponseEntity<Void> recordVisit(RequestContext ctx, @PathVariable String id,
                                            @RequestBody WorkspacePort.VisitRecord body) {
        permissions.require(ctx, Permissions.TASK_WRITE);
        if (body.answers() == null) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "answers are required");
        }
        workspace.recordVisit(ctx, id, body);
        return ResponseEntity.noContent().header(Lists.RESOURCE_REF, id).build();
    }

    private static void requireIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "ids must not be empty");
        }
        if (ids.size() > 500) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "at most 500 ids per bulk call");
        }
    }
}
