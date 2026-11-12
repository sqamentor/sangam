// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import org.sangam.rest.port.InstanceHandlerPort;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * M-059 (L6, E21): the named query and update handlers a process model declares. A query needs
 * {@code process.read}; an update is a modification, so it needs {@code process.modify} and a reason (R49).
 */
@RestController
@RequestMapping("/api/v1")
public class InstanceHandlerController {

    private final InstanceHandlerPort handlers;
    private final PermissionPort permissions;

    public InstanceHandlerController(InstanceHandlerPort handlers, PermissionPort permissions) {
        this.handlers = handlers;
        this.permissions = permissions;
    }

    @PostMapping("/process-instances/{id}/queries/{name}")
    public InstanceHandlerPort.QueryResult query(RequestContext ctx, @PathVariable String id,
                                                 @PathVariable String name,
                                                 @RequestBody(required = false) InstanceHandlerPort.QueryRequest body) {
        permissions.require(ctx, Permissions.PROCESS_READ);
        Telemetry.processInstance(id);
        return handlers.query(ctx, id, name, body == null ? new InstanceHandlerPort.QueryRequest(null) : body);
    }

    @PostMapping("/process-instances/{id}/updates/{name}")
    public InstanceHandlerPort.UpdateResult update(RequestContext ctx, @PathVariable String id,
                                                   @PathVariable String name,
                                                   @RequestBody InstanceHandlerPort.UpdateRequest body) {
        permissions.require(ctx, Permissions.PROCESS_MODIFY);
        ProcessController.requireReason(body == null ? null : body.reason());
        Telemetry.processInstance(id);
        return handlers.update(ctx, id, name, body);
    }
}
