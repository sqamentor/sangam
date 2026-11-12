// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.DocumentPort;
import org.sangam.rest.port.DynamicPort;
import org.sangam.rest.port.LinkPort;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.ProcessPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.sangam.rest.support.TypedVariables;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Process instances, definitions, messages and signals (ARCHITECTURE §4); cancel/delete (VI.5); resubmit and version
 * history (R43); modification (R49, FLOWABLE-INTO-OPERATON FC-17); triggers (FLOWABLE-INTO-OPERATON FC-12); ad-hoc (FLOWABLE-INTO-OPERATON FC-01) and injection (FLOWABLE-INTO-OPERATON FC-15); links (FLOWABLE-INTO-OPERATON FC-31/32).
 */
@RestController
@RequestMapping("/api/v1")
public class ProcessController {

    private final ProcessPort processes;
    private final DynamicPort dynamic;
    private final LinkPort links;
    private final DocumentPort documents;
    private final PermissionPort permissions;

    public ProcessController(ProcessPort processes, DynamicPort dynamic, LinkPort links, DocumentPort documents,
                             PermissionPort permissions) {
        this.processes = processes;
        this.dynamic = dynamic;
        this.links = links;
        this.documents = documents;
        this.permissions = permissions;
    }

    @PostMapping("/process-instances")
    public ResponseEntity<ProcessPort.ProcessInstance> start(RequestContext ctx,
                                                             @RequestBody ProcessPort.StartProcessRequest body) {
        permissions.require(ctx, Permissions.PROCESS_START);
        if (body.key() == null || body.key().isBlank()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "key is required");
        }
        Telemetry.businessKey(body.businessKey());
        var req = new ProcessPort.StartProcessRequest(body.key(), body.businessKey(),
                TypedVariables.fromJson(body.variables()), body.version(), body.packageHash(), body.formVersion(),
                body.startBeforeActivityIds());
        ProcessPort.ProcessInstance pi = processes.start(ctx, req);
        Telemetry.processInstance(pi.id());
        return Lists.created("/api/v1/process-instances/" + pi.id(), pi.id(), pi);
    }

    @GetMapping("/process-instances")
    public ResponseEntity<List<ProcessPort.ProcessInstance>> list(RequestContext ctx,
            @RequestParam(required = false) String key, @RequestParam(required = false) String businessKey,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startedAfter,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startedBefore,
            @RequestParam(required = false) Boolean withIncidents,
            @RequestParam(required = false) String superProcessInstanceId,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        permissions.require(ctx, Permissions.PROCESS_READ);
        var f = new ProcessPort.ProcessFilter(key, businessKey, state, startedAfter, startedBefore, withIncidents,
                superProcessInstanceId);
        return Lists.ok(processes.list(ctx, f, Lists.page(page, size)));
    }

    @GetMapping("/process-instances/{id}")
    public ProcessPort.ProcessInstance get(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, Permissions.PROCESS_READ);
        return processes.get(ctx, id);
    }

    @GetMapping("/process-instances/{id}/activity-tree")
    public ProcessPort.ActivityInstance activityTree(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, Permissions.PROCESS_READ);
        return processes.activityTree(ctx, id);
    }

    @GetMapping("/process-instances/{id}/variables")
    public Map<String, Object> variables(RequestContext ctx, @PathVariable String id,
                                         @RequestParam(required = false) String activityInstanceId) {
        permissions.require(ctx, Permissions.PROCESS_READ);
        return processes.variables(ctx, id, activityInstanceId);
    }

    @PutMapping("/process-instances/{id}/variables")
    public ResponseEntity<Map<String, Object>> setVariables(RequestContext ctx, @PathVariable String id,
                                                            @RequestBody Map<String, Object> body) {
        permissions.require(ctx, Permissions.PROCESS_WRITE);
        return Lists.okRef(id, processes.setVariables(ctx, id, TypedVariables.fromJson(body)));
    }

    @PostMapping("/process-instances/{id}/cancel")
    public ResponseEntity<ProcessPort.TerminationResult> cancel(RequestContext ctx, @PathVariable String id,
                                                                @RequestBody ProcessPort.TerminateRequest body) {
        permissions.require(ctx, Permissions.PROCESS_CANCEL);
        requireReason(body.reason());
        return Lists.okRef(id, processes.cancel(ctx, id, body));
    }

    @DeleteMapping("/process-instances/{id}")
    public ResponseEntity<ProcessPort.TerminationResult> delete(RequestContext ctx, @PathVariable String id,
            @RequestParam(required = false) String reason, @RequestParam(required = false) String reasonCode) {
        permissions.require(ctx, Permissions.PROCESS_DELETE);
        requireReason(reason);
        return Lists.okRef(id, processes.delete(ctx, id, new ProcessPort.TerminateRequest(reason, reasonCode)));
    }

    @PostMapping("/process-instances/{id}/resubmit")
    public ResponseEntity<ProcessPort.CaseVersion> resubmit(RequestContext ctx, @PathVariable String id,
                                                            @RequestBody ProcessPort.ResubmitRequest body) {
        permissions.require(ctx, Permissions.PROCESS_WRITE);
        var req = new ProcessPort.ResubmitRequest(TypedVariables.fromJson(body.variables()), body.reason(),
                body.expectedVersion());
        return Lists.okRef(id, processes.resubmit(ctx, id, req));
    }

    @GetMapping("/process-instances/{id}/versions")
    public ResponseEntity<List<ProcessPort.CaseVersion>> versions(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, Permissions.PROCESS_READ);
        return Lists.ok(processes.versions(ctx, id));
    }

    @PostMapping("/process-instances/{id}/modify")
    public ResponseEntity<ProcessPort.ProcessInstance> modify(RequestContext ctx, @PathVariable String id,
                                                              @RequestBody ProcessPort.ModifyRequest body) {
        permissions.require(ctx, Permissions.PROCESS_MODIFY);
        requireReason(body.reason());
        if (body.instructions() == null || body.instructions().isEmpty()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "at least one instruction is required");
        }
        return Lists.okRef(id, processes.modify(ctx, id, body));
    }

    @PostMapping("/process-instances/{id}/trigger")
    public ResponseEntity<ProcessPort.ProcessInstance> trigger(RequestContext ctx, @PathVariable String id,
                                                               @RequestBody ProcessPort.TriggerRequest body) {
        permissions.require(ctx, Permissions.PROCESS_WRITE);
        var req = new ProcessPort.TriggerRequest(body.executionId(), body.activityId(),
                TypedVariables.fromJson(body.variables()), body.reason());
        return Lists.okRef(id, processes.trigger(ctx, id, req));
    }

    @GetMapping("/process-instances/{id}/links")
    public ResponseEntity<List<LinkPort.EntityLink>> links(RequestContext ctx, @PathVariable String id,
                                                           @RequestParam(required = false) String linkType) {
        permissions.require(ctx, Permissions.PROCESS_READ);
        return Lists.ok(links.links(ctx, "bpmn", id, linkType));
    }

    @GetMapping("/process-instances/{id}/attachments")
    public ResponseEntity<List<org.sangam.rest.port.DocumentPort.Document>> attachments(RequestContext ctx,
            @PathVariable String id, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        permissions.require(ctx, Permissions.DOCUMENT_READ);
        return Lists.ok(documents.list(ctx, new DocumentPort.DocumentFilter(null, id, null, null), Lists.page(page, size)));
    }

    @GetMapping("/process-instances/{id}/ad-hoc/{adHocActivityId}")
    public DynamicPort.AdHocState adHocState(RequestContext ctx, @PathVariable String id,
                                             @PathVariable String adHocActivityId) {
        permissions.require(ctx, Permissions.PROCESS_READ);
        return dynamic.adHocState(ctx, id, adHocActivityId);
    }

    @PostMapping("/process-instances/{id}/ad-hoc/{adHocActivityId}/start")
    public ResponseEntity<DynamicPort.AdHocState> startAdHoc(RequestContext ctx, @PathVariable String id,
            @PathVariable String adHocActivityId, @RequestBody DynamicPort.StartAdHocActivityRequest body) {
        permissions.require(ctx, Permissions.PROCESS_WRITE);
        var req = new DynamicPort.StartAdHocActivityRequest(body.activityId(), TypedVariables.fromJson(body.variables()),
                body.reason());
        return Lists.okRef(id, dynamic.startAdHocActivity(ctx, id, adHocActivityId, req));
    }

    @PostMapping("/process-instances/{id}/ad-hoc/{adHocActivityId}/complete")
    public ResponseEntity<DynamicPort.AdHocState> completeAdHoc(RequestContext ctx, @PathVariable String id,
            @PathVariable String adHocActivityId, @RequestParam(required = false) String reason) {
        permissions.require(ctx, Permissions.PROCESS_WRITE);
        return Lists.okRef(id, dynamic.completeAdHoc(ctx, id, adHocActivityId, reason));
    }

    @PostMapping("/process-instances/{id}/inject")
    public ResponseEntity<DynamicPort.InjectionResult> inject(RequestContext ctx, @PathVariable String id,
                                                              @RequestBody DynamicPort.InjectionRequest body) {
        permissions.require(ctx, Permissions.PROCESS_MODIFY);
        requireReason(body.reason());
        return Lists.okRef(id, dynamic.inject(ctx, id, body));
    }

    @GetMapping("/process-definitions")
    public ResponseEntity<List<ProcessPort.ProcessDefinition>> definitions(RequestContext ctx,
            @RequestParam(required = false) String key, @RequestParam(required = false) Boolean latest,
            @RequestParam(required = false) String deploymentId,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        permissions.require(ctx, Permissions.PROCESS_READ);
        return Lists.ok(processes.definitions(ctx, new ProcessPort.DefinitionFilter(key, latest, deploymentId),
                Lists.page(page, size)));
    }

    @GetMapping("/process-definitions/{id}")
    public ProcessPort.ProcessDefinition definition(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, Permissions.PROCESS_READ);
        return processes.definition(ctx, id);
    }

    @GetMapping(value = "/process-definitions/{id}/xml", produces = MediaType.APPLICATION_XML_VALUE)
    public String definitionXml(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, Permissions.PROCESS_READ);
        return processes.definitionXml(ctx, id);
    }

    @PostMapping("/messages")
    public ResponseEntity<ProcessPort.MessageResult> correlate(RequestContext ctx,
                                                               @RequestBody ProcessPort.MessageRequest body) {
        permissions.require(ctx, Permissions.MESSAGE_SEND);
        if (body.messageName() == null || body.messageName().isBlank()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "messageName is required");
        }
        Telemetry.businessKey(body.businessKey());
        var req = new ProcessPort.MessageRequest(body.messageName(), body.businessKey(), body.processInstanceId(),
                TypedVariables.fromJson(body.correlationKeys()), TypedVariables.fromJson(body.variables()), body.all());
        ProcessPort.MessageResult r = processes.correlate(ctx, req);
        return Lists.okRef(String.join(",", r.processInstanceIds()), r);
    }

    @PostMapping("/signals")
    public ResponseEntity<Void> signal(RequestContext ctx, @RequestBody ProcessPort.SignalRequest body) {
        permissions.require(ctx, Permissions.MESSAGE_SEND);
        if (body.name() == null || body.name().isBlank()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "name is required");
        }
        processes.signal(ctx, new ProcessPort.SignalRequest(body.name(), body.executionId(),
                TypedVariables.fromJson(body.variables())));
        return ResponseEntity.noContent().build();
    }

    static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw ApiProblem.badRequest("REASON_REQUIRED", "This action requires a reason (R63, R42)");
        }
    }
}
