// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import java.util.List;
import java.util.Map;

import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RegistryPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
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
 * Declarative registries: services (R71, EC-07), SLA models (R79, EC-08), routing strategies (R82), templates (R74),
 * conversation channels (R81), agents (R72) and retention classes (R29, R61). The contract lists one concrete path
 * per kind; this controller serves them all.
 */
@RestController
@RequestMapping("/api/v1/admin/{kind:services|sla-models|routing-strategies|templates|conversation-channels|agents|retention-classes}")
public class RegistryController {

    private final RegistryPort registry;
    private final PermissionPort permissions;

    public RegistryController(RegistryPort registry, PermissionPort permissions) {
        this.registry = registry;
        this.permissions = permissions;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(RequestContext ctx, @PathVariable String kind,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        permissions.require(ctx, Permissions.ADMIN_READ);
        return Lists.ok(registry.list(ctx, kind, q, Lists.page(page, size)));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(RequestContext ctx, @PathVariable String kind,
                                                      @RequestBody Map<String, Object> body) {
        permissions.require(ctx, Permissions.ADMIN_WRITE);
        Map<String, Object> e = registry.create(ctx, kind, body);
        String id = String.valueOf(e.get("id"));
        return Lists.created("/api/v1/admin/" + kind + "/" + id, id, e);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(RequestContext ctx, @PathVariable String kind, @PathVariable String id) {
        permissions.require(ctx, Permissions.ADMIN_READ);
        return registry.get(ctx, kind, id);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(RequestContext ctx, @PathVariable String kind,
                                                      @PathVariable String id, @RequestBody Map<String, Object> body) {
        permissions.require(ctx, Permissions.ADMIN_WRITE);
        return Lists.okRef(id, registry.update(ctx, kind, id, body));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(RequestContext ctx, @PathVariable String kind, @PathVariable String id,
                                       @RequestParam(required = false) String reason) {
        permissions.require(ctx, Permissions.ADMIN_WRITE);
        ProcessController.requireReason(reason);
        registry.delete(ctx, kind, id, reason);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/{action:activate|retire|test}")
    public ResponseEntity<Map<String, Object>> action(RequestContext ctx, @PathVariable String kind,
            @PathVariable String id, @PathVariable String action,
            @RequestBody(required = false) Map<String, Object> body) {
        permissions.require(ctx, Permissions.ADMIN_WRITE);
        return Lists.okRef(id, registry.action(ctx, kind, id, action, body == null ? Map.of() : body));
    }
}
