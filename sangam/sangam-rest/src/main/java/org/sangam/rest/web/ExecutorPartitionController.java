// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import java.util.List;
import java.util.Set;

import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.ExecutorPartitionPort;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * M-056, owner decision 146: the executor partition map and partition-count changes (contract
 * {@code routes/executor.py}). Every route needs the platform-only permission {@link Permissions#ADMIN_EXECUTOR}
 * ({@code executor.admin}, X.2.1.7; a tenant administrator never holds it). The R41 command type
 * {@code executor.partitions.resize} and the idempotency key handling come from the contract through
 * {@link CommandCatalog}. A host without the engine port answers 501 {@code CAPABILITY_NOT_INSTALLED}.
 */
@RestController
@RequestMapping("/api/v1")
public class ExecutorPartitionController {

    static final Set<String> STATES = Set.of("REQUESTED", "IN_PROGRESS", "COMPLETED", "FAILED");

    private final ObjectProvider<ExecutorPartitionPort> executor;
    private final PermissionPort permissions;

    public ExecutorPartitionController(ObjectProvider<ExecutorPartitionPort> executor, PermissionPort permissions) {
        this.executor = executor;
        this.permissions = permissions;
    }

    @GetMapping("/admin/executor/partitions")
    public ExecutorPartitionPort.ExecutorPartitionMap map(RequestContext ctx) {
        permissions.require(ctx, Permissions.ADMIN_EXECUTOR);
        return port().map(ctx);
    }

    @PostMapping("/admin/executor/partitions")
    public ResponseEntity<ExecutorPartitionPort.ExecutorPartitionResize> resize(RequestContext ctx,
            @RequestBody(required = false) ExecutorPartitionPort.ExecutorPartitionResizeRequest body) {
        permissions.require(ctx, Permissions.ADMIN_EXECUTOR);
        if (body == null || body.partitionCount() == null) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "partitionCount is required");
        }
        if (body.reason() == null || body.reason().isBlank() || body.reason().length() > 1000) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "reason is required (1..1000 characters, R42)");
        }
        ExecutorPartitionPort.ExecutorPartitionResize r = port().resize(ctx, body);
        return Lists.accepted(r.id(), r);
    }

    @GetMapping("/admin/executor/partitions/resizes")
    public ResponseEntity<List<ExecutorPartitionPort.ExecutorPartitionResize>> list(RequestContext ctx,
            @RequestParam(required = false) String state, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        permissions.require(ctx, Permissions.ADMIN_EXECUTOR);
        if (state != null && !STATES.contains(state)) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "state must be one of " + STATES);
        }
        return Lists.ok(port().list(ctx, state, Lists.page(page, size)));
    }

    @GetMapping("/admin/executor/partitions/resizes/{id}")
    public ExecutorPartitionPort.ExecutorPartitionResize get(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, Permissions.ADMIN_EXECUTOR);
        return port().get(ctx, id);
    }

    private ExecutorPartitionPort port() {
        ExecutorPartitionPort p = executor.getIfAvailable();
        if (p == null) {
            throw ApiProblem.notInstalled("executor partition administration", "sangam-engine");
        }
        return p;
    }
}
