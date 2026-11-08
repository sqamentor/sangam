// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.web;

import java.util.List;

import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.sangam.server.plugins.PluginManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/v1/admin/plugins}: list, enable, disable (effective at restart), audit trail. Platform administrators only. */
@RestController
@RequestMapping("/api/v1/admin/plugins")
public class PluginAdminController {

    private final PluginManager plugins;
    private final PermissionPort permissions;

    public PluginAdminController(PluginManager plugins, PermissionPort permissions) {
        this.plugins = plugins;
        this.permissions = permissions;
    }

    private void admin(RequestContext ctx) {
        permissions.require(ctx, Permissions.TENANT_ADMIN);
        if (!ctx.inGroup(Permissions.GROUP_ADMIN)) {
            throw ApiProblem.notPermitted("platform administration");
        }
    }

    @GetMapping
    public List<PluginManager.PluginInfo> list(RequestContext ctx) {
        admin(ctx);
        return plugins.plugins();
    }

    @GetMapping("/audit")
    public List<String> audit(RequestContext ctx) {
        admin(ctx);
        return plugins.auditTrail();
    }

    @PostMapping("/{id}/enable")
    public PluginManager.PluginInfo enable(RequestContext ctx, @PathVariable String id) {
        return set(ctx, id, true);
    }

    @PostMapping("/{id}/disable")
    public PluginManager.PluginInfo disable(RequestContext ctx, @PathVariable String id) {
        return set(ctx, id, false);
    }

    private PluginManager.PluginInfo set(RequestContext ctx, String id, boolean enabled) {
        admin(ctx);
        try {
            return plugins.setEnabled(id, enabled, ctx.principalId());
        } catch (IllegalArgumentException e) {
            throw ApiProblem.notFound("plugin", id);
        }
    }
}
