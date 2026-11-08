// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import java.util.List;

import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.PowerTablePort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Delegation-of-powers DMN tables (IX.5 rule 3, K6, A04), distinct from person delegation (/admin/delegations). */
@RestController
@RequestMapping("/api/v1/admin/power-tables")
public class PowerTableController {

    private final PowerTablePort tables;
    private final PermissionPort permissions;

    public PowerTableController(PowerTablePort tables, PermissionPort permissions) {
        this.tables = tables;
        this.permissions = permissions;
    }

    @GetMapping
    public ResponseEntity<List<PowerTablePort.PowerTable>> list(RequestContext ctx,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        permissions.require(ctx, Permissions.ADMIN_READ);
        return Lists.ok(tables.list(ctx, q, Lists.page(page, size)));
    }

    @PostMapping
    public ResponseEntity<PowerTablePort.PowerTable> create(RequestContext ctx,
                                                            @RequestBody PowerTablePort.PowerTable body) {
        permissions.require(ctx, Permissions.ADMIN_WRITE);
        PowerTablePort.PowerTable t = tables.create(ctx, body);
        return Lists.created("/api/v1/admin/power-tables/" + t.id(), t.id(), t);
    }

    @GetMapping("/{id}")
    public PowerTablePort.PowerTable get(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, Permissions.ADMIN_READ);
        return tables.get(ctx, id);
    }

    @PutMapping("/{id}")
    public ResponseEntity<PowerTablePort.PowerTable> update(RequestContext ctx, @PathVariable String id,
                                                            @RequestBody PowerTablePort.PowerTable body) {
        permissions.require(ctx, Permissions.ADMIN_WRITE);
        PowerTablePort.PowerTable t = tables.update(ctx, id, body);
        return Lists.okRef(t.id(), t);
    }

    @PostMapping("/{id}/check")
    public PowerTablePort.CheckResult check(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, Permissions.ADMIN_READ);
        return tables.check(ctx, id);
    }

    @PostMapping("/{id}/submit")
    public ResponseEntity<PowerTablePort.PowerTable> submit(RequestContext ctx, @PathVariable String id,
            @RequestBody(required = false) PowerTablePort.TransitionRequest body) {
        permissions.require(ctx, Permissions.ADMIN_WRITE);
        return Lists.okRef(id, tables.transition(ctx, id, "submit", body));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<PowerTablePort.PowerTable> approve(RequestContext ctx, @PathVariable String id,
            @RequestBody(required = false) PowerTablePort.TransitionRequest body) {
        permissions.require(ctx, Permissions.POWER_TABLE_APPROVE);
        return Lists.okRef(id, tables.transition(ctx, id, "approve", body));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<PowerTablePort.PowerTable> reject(RequestContext ctx, @PathVariable String id,
            @RequestBody(required = false) PowerTablePort.TransitionRequest body) {
        permissions.require(ctx, Permissions.POWER_TABLE_APPROVE);
        ProcessController.requireReason(body == null ? null : body.reason());
        return Lists.okRef(id, tables.transition(ctx, id, "reject", body));
    }
}
