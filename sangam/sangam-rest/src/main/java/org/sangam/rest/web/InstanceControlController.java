// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import java.util.List;

import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.InstanceControlPort;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.ProcessPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Consequence previews (O03, O08, R63), suspension of instances and definitions (FLOWABLE-INTO-OPERATON D-32), cross-boundary moves (FLOWABLE-INTO-OPERATON FC-17)
 * and the historic external-task log (PF-01).
 */
@RestController
@RequestMapping("/api/v1")
public class InstanceControlController {

    private static final int MAX_INSTRUCTIONS = 200;

    private final InstanceControlPort control;
    private final PermissionPort permissions;

    public InstanceControlController(InstanceControlPort control, PermissionPort permissions) {
        this.control = control;
        this.permissions = permissions;
    }

    @GetMapping("/process-instances/{id}/cancel-preview")
    public InstanceControlPort.ConsequencePreview cancelPreview(RequestContext ctx, @PathVariable String id,
            @RequestParam(required = false, defaultValue = "BUSINESS") String mode) {
        permissions.require(ctx, Permissions.PROCESS_READ);
        if (!mode.equals("BUSINESS") && !mode.equals("TECHNICAL")) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "mode must be BUSINESS or TECHNICAL");
        }
        Telemetry.processInstance(id);
        return control.cancelPreview(ctx, id, mode);
    }

    @PostMapping("/process-instances/{id}/suspension")
    public ResponseEntity<ProcessPort.ProcessInstance> suspension(RequestContext ctx, @PathVariable String id,
            @RequestBody InstanceControlPort.SuspensionRequest body) {
        permissions.require(ctx, Permissions.PROCESS_WRITE);
        ProcessController.requireReason(body.reason());
        Telemetry.processInstance(id);
        return Lists.okRef(id, control.setSuspension(ctx, id, body));
    }

    @PostMapping("/process-definitions/{id}/suspension")
    public ResponseEntity<ProcessPort.ProcessDefinition> definitionSuspension(RequestContext ctx, @PathVariable String id,
            @RequestBody InstanceControlPort.SuspensionRequest body) {
        permissions.require(ctx, Permissions.PROCESS_MODIFY);
        ProcessController.requireReason(body.reason());
        return Lists.okRef(id, control.setDefinitionSuspension(ctx, id, body));
    }

    @PostMapping("/process-instances/{id}/modify/preview")
    public InstanceControlPort.ConsequencePreview modifyPreview(RequestContext ctx, @PathVariable String id,
            @RequestBody InstanceControlPort.ModifyPreviewRequest body) {
        permissions.require(ctx, Permissions.PROCESS_READ);
        if (body.instructions() == null || body.instructions().isEmpty()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "at least one instruction is required");
        }
        if (body.instructions().size() > MAX_INSTRUCTIONS) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "at most " + MAX_INSTRUCTIONS + " instructions");
        }
        return control.modifyPreview(ctx, id, body);
    }

    @PostMapping("/process-instances/{id}/cross-boundary-move")
    public ResponseEntity<ProcessPort.ProcessInstance> crossBoundaryMove(RequestContext ctx, @PathVariable String id,
            @RequestBody InstanceControlPort.CrossBoundaryMoveRequest body) {
        permissions.require(ctx, Permissions.PROCESS_MODIFY);
        ProcessController.requireReason(body.reason());
        if (body.fromActivityInstanceId() == null || body.targetInstanceId() == null
                || body.targetActivityId() == null) {
            throw ApiProblem.badRequest("VALIDATION_FAILED",
                    "fromActivityInstanceId, targetInstanceId and targetActivityId are required");
        }
        Telemetry.processInstance(id);
        return Lists.okRef(id, control.crossBoundaryMove(ctx, id, body));
    }

    @GetMapping("/history/external-task-log")
    public ResponseEntity<List<InstanceControlPort.ExternalTaskLogEntry>> externalTaskLog(RequestContext ctx,
            @ModelAttribute HistoryAuditController.HistoryQuery q) {
        permissions.require(ctx, Permissions.HISTORY_READ);
        return Lists.ok(control.externalTaskLog(ctx, q.filter().requireOnly("/history/external-task-log", true, false), Lists.page(q.page(), q.size())));
    }
}
