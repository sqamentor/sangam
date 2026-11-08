// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import java.util.List;

import org.sangam.rest.port.DebugPort;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Model debugger (R80, FLOWABLE-INTO-OPERATON FC-44): sandbox tenants only, never on a production profile (403 DEBUGGER_DISABLED). */
@RestController
@RequestMapping("/api/v1/debug")
public class DebugController {

    private final DebugPort debug;
    private final PermissionPort permissions;

    public DebugController(DebugPort debug, PermissionPort permissions) {
        this.debug = debug;
        this.permissions = permissions;
    }

    @GetMapping("/status")
    public DebugPort.Status status(RequestContext ctx) {
        permissions.require(ctx, Permissions.DEBUG_USE);
        return debug.status(ctx);
    }

    @GetMapping("/instances/{id}/breakpoints")
    public List<String> breakpoints(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, Permissions.DEBUG_USE);
        return debug.breakpoints(ctx, id);
    }

    @PutMapping("/instances/{id}/breakpoints")
    public ResponseEntity<List<String>> setBreakpoints(RequestContext ctx, @PathVariable String id,
                                                       @RequestBody DebugPort.BreakpointsRequest body) {
        permissions.require(ctx, Permissions.DEBUG_USE);
        return Lists.okRef(id, debug.setBreakpoints(ctx, id, body));
    }

    @PostMapping("/instances/{id}/step")
    public ResponseEntity<DebugPort.StepResult> step(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, Permissions.DEBUG_USE);
        return Lists.okRef(id, debug.step(ctx, id));
    }
}
