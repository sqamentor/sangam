// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.ProcessContinuationPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * M-060, owner decision 149: continue-as-new (contract {@code routes/continuation.py}). The endpoint permission is
 * {@value #PROCESS_CONTINUE} (sangam-server {@code SecurityPermissionBridge.PROCESS_CONTINUE}, mapped to
 * {@code PROCESS_INSTANCE MODIFY}); the object checks, the state rules and the idempotency of
 * {@code continuationRequestId} are the engine's, inside its one transaction. The R41 command type
 * {@code process.continue-as-new} and the idempotency key handling come from the contract through
 * {@link CommandCatalog}. A host without the engine port answers 501 {@code CAPABILITY_NOT_INSTALLED}.
 */
@RestController
@RequestMapping("/api/v1")
public class ProcessContinuationController {

    /** Contract permission of {@code continueProcessInstanceAsNew}. */
    public static final String PROCESS_CONTINUE = "process.continue";

    private final ObjectProvider<ProcessContinuationPort> continuations;
    private final PermissionPort permissions;

    public ProcessContinuationController(ObjectProvider<ProcessContinuationPort> continuations,
                                         PermissionPort permissions) {
        this.continuations = continuations;
        this.permissions = permissions;
    }

    @PostMapping("/process-instances/{id}/continue-as-new")
    public ProcessContinuationPort.ContinueAsNewResult continueAsNew(RequestContext ctx, @PathVariable String id,
            @RequestBody(required = false) ProcessContinuationPort.ContinueAsNewRequest body) {
        permissions.require(ctx, PROCESS_CONTINUE);
        if (body == null) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "a ContinueAsNewRequest body is required");
        }
        if (body.continuationRequestId() == null || body.continuationRequestId().isBlank()
                || body.continuationRequestId().length() > 255) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "continuationRequestId is required (1..255 characters)");
        }
        ProcessController.requireReason(body.reason());
        Telemetry.processInstance(id);
        return port().continueAsNew(ctx, id, body);
    }

    @GetMapping("/process-instances/{id}/continuations")
    public ProcessContinuationPort.ProcessContinuationChain chain(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, Permissions.PROCESS_READ);
        Telemetry.processInstance(id);
        return port().chain(ctx, id);
    }

    private ProcessContinuationPort port() {
        ProcessContinuationPort p = continuations.getIfAvailable();
        if (p == null) {
            throw ApiProblem.notInstalled("process continuation", "sangam-engine");
        }
        return p;
    }
}
