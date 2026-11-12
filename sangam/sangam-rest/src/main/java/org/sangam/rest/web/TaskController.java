// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import java.util.List;
import java.util.Map;

import org.sangam.rest.port.DocumentPort;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.TaskPort;
import org.sangam.rest.support.Permissions;
import org.sangam.rest.support.TypedVariables;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Human tasks: claim/complete (payloadHash, VI.4 rule 5)/return (R43)/delegate, comments and attachments (FLOWABLE-INTO-OPERATON FC-40). */
@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {

    private final TaskPort tasks;
    private final DocumentPort documents;
    private final PermissionPort permissions;

    public TaskController(TaskPort tasks, DocumentPort documents, PermissionPort permissions) {
        this.tasks = tasks;
        this.documents = documents;
        this.permissions = permissions;
    }

    @GetMapping
    public ResponseEntity<List<TaskPort.Task>> list(RequestContext ctx,
            @RequestParam(required = false) String assignee, @RequestParam(required = false) Boolean mine,
            @RequestParam(required = false) Boolean unassigned, @RequestParam(required = false) String candidateGroup,
            @RequestParam(required = false) String seatId, @RequestParam(required = false) String processInstanceId,
            @RequestParam(required = false) String caseInstanceId, @RequestParam(required = false) String businessKey,
            @RequestParam(required = false) String processDefinitionKey,
            @RequestParam(required = false) String taskDefinitionKey, @RequestParam(required = false) String state,
            @RequestParam(required = false) String sortBy, @RequestParam(required = false) String sortOrder,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) List<String> variableNames) {
        permissions.require(ctx, Permissions.TASK_READ);
        var filter = new TaskPort.TaskFilter(assignee, mine, unassigned, candidateGroup, seatId, processInstanceId,
                caseInstanceId, businessKey, processDefinitionKey, taskDefinitionKey, state, sortBy, sortOrder,
                variableNames);
        return Lists.ok(tasks.list(ctx, filter, Lists.page(page, size)));
    }

    @GetMapping("/{id}")
    public TaskPort.TaskDetail get(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, Permissions.TASK_READ);
        return tasks.get(ctx, id);
    }

    @GetMapping("/{id}/variables")
    public Map<String, Object> variables(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, Permissions.TASK_READ);
        return tasks.variables(ctx, id);
    }

    /** SL-37: embedded/deployed form for the task UI; task users cannot (and need not) reach /engine-rest. */
    @GetMapping("/{id}/form")
    public TaskPort.TaskForm form(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, Permissions.TASK_READ);
        return tasks.form(ctx, id);
    }

    @PostMapping("/{id}/claim")
    public ResponseEntity<Void> claim(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, Permissions.TASK_WRITE);
        tasks.claim(ctx, id);
        return ResponseEntity.noContent().header(Lists.RESOURCE_REF, id).build();
    }

    @PostMapping("/{id}/unclaim")
    public ResponseEntity<Void> unclaim(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, Permissions.TASK_WRITE);
        tasks.unclaim(ctx, id);
        return ResponseEntity.noContent().header(Lists.RESOURCE_REF, id).build();
    }

    @PostMapping("/{id}/complete")
    public ResponseEntity<TaskPort.CompleteTaskResult> complete(RequestContext ctx, @PathVariable String id,
                                                                @RequestBody TaskPort.CompleteTaskRequest body) {
        permissions.require(ctx, Permissions.TASK_WRITE);
        var req = new TaskPort.CompleteTaskRequest(body.payloadHash(), body.caseVersion(), body.decision(),
                body.remarks(), body.formVersion(), TypedVariables.fromJson(body.variables()), body.reasonCode(),
                body.nextSeatId(), body.nextSeatReason());
        TaskPort.CompleteTaskResult r = tasks.complete(ctx, id, req);
        Telemetry.processInstance(r.processInstanceId());
        return Lists.okRef(id, r);
    }

    @PostMapping("/{id}/return")
    public ResponseEntity<TaskPort.CompleteTaskResult> returnTask(RequestContext ctx, @PathVariable String id,
                                                                  @RequestBody TaskPort.ReturnTaskRequest body) {
        permissions.require(ctx, Permissions.TASK_WRITE);
        if (body.reason() == null || body.reason().isBlank()) {
            throw org.sangam.rest.port.ApiProblem.badRequest("REASON_REQUIRED", "A return needs a reason (CASE_RETURNED)");
        }
        var req = new TaskPort.ReturnTaskRequest(body.reason(), body.reasonCode(), body.targetActivityId(),
                TypedVariables.fromJson(body.variables()), body.targetSeatId(), body.targetLevel(), body.payloadHash(),
                body.caseVersion());
        return Lists.okRef(id, tasks.returnTask(ctx, id, req));
    }

    @PostMapping("/{id}/delegate")
    public ResponseEntity<Void> delegate(RequestContext ctx, @PathVariable String id,
                                         @RequestBody TaskPort.DelegateTaskRequest body) {
        permissions.require(ctx, Permissions.TASK_WRITE);
        if ((body.toUserId() == null) == (body.toSeatId() == null)) {
            throw org.sangam.rest.port.ApiProblem.badRequest("VALIDATION_FAILED", "exactly one of toUserId or toSeatId is required");
        }
        tasks.delegate(ctx, id, body);
        return ResponseEntity.noContent().header(Lists.RESOURCE_REF, id).build();
    }

    @GetMapping("/{id}/comments")
    public ResponseEntity<List<TaskPort.Comment>> comments(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, Permissions.TASK_READ);
        return Lists.ok(tasks.comments(ctx, id));
    }

    @PostMapping("/{id}/comments")
    public ResponseEntity<TaskPort.Comment> addComment(RequestContext ctx, @PathVariable String id,
                                                       @RequestBody TaskPort.AddCommentRequest body) {
        permissions.require(ctx, Permissions.TASK_WRITE);
        if (body.message() == null || body.message().isBlank()) {
            throw org.sangam.rest.port.ApiProblem.badRequest("VALIDATION_FAILED", "message is required");
        }
        TaskPort.Comment c = tasks.addComment(ctx, id, body);
        return Lists.created("/api/v1/tasks/" + id + "/comments", c.id(), c);
    }

    @GetMapping("/{id}/attachments")
    public ResponseEntity<List<DocumentPort.Document>> attachments(RequestContext ctx, @PathVariable String id,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        permissions.require(ctx, Permissions.DOCUMENT_READ);
        return Lists.ok(documents.list(ctx, new DocumentPort.DocumentFilter(id, null, null, null), Lists.page(page, size)));
    }

    @PostMapping(value = "/{id}/attachments", consumes = "multipart/form-data")
    public ResponseEntity<DocumentPort.Document> addAttachment(RequestContext ctx, @PathVariable String id,
            @RequestPart("file") MultipartFile file, @RequestParam(required = false) String name) throws java.io.IOException {
        permissions.require(ctx, Permissions.DOCUMENT_WRITE);
        DocumentPort.Document d = documents.upload(ctx, new DocumentPort.UploadRequest(
                name != null ? name : file.getOriginalFilename(), file.getContentType(), file.getBytes(), id, null, null,
                null));
        return Lists.created("/api/v1/documents/" + d.id(), d.id(), d);
    }
}
