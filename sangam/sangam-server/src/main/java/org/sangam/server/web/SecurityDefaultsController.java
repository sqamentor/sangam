// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.web;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.sangam.core.layer.Transactions;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.sangam.security.SangamSecurity;
import org.sangam.core.security.SangamSecurityException;
import org.sangam.server.bridge.SecurityIdentities;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/admin/security/module-defaults}: installs sangam-security's module role templates (every
 * registered module resource type) and, with {@code selfService}, the person-conditioned self-service grants for the
 * caller's tenant ({@code ModuleDefaults.install}). Idempotent; also run automatically at tenant onboarding.
 */
@RestController
@RequestMapping("/api/v1/admin/security")
public class SecurityDefaultsController {

    public record ModuleDefaultsRequest(Boolean selfService) {
    }

    private final ObjectProvider<SangamSecurity> security;
    private final ObjectProvider<DataSource> dataSource;
    private final PermissionPort permissions;

    public SecurityDefaultsController(ObjectProvider<SangamSecurity> security, ObjectProvider<DataSource> dataSource,
                                      PermissionPort permissions) {
        this.security = security;
        this.dataSource = dataSource;
        this.permissions = permissions;
    }

    @PostMapping("/module-defaults")
    public Map<String, Object> install(RequestContext ctx, @RequestBody(required = false) ModuleDefaultsRequest body) {
        permissions.require(ctx, Permissions.TENANT_ADMIN);
        SangamSecurity sec = security.getIfAvailable();
        if (sec == null) {
            throw ApiProblem.notInstalled("module role templates", "sangam-security");
        }
        boolean selfService = body == null || body.selfService() == null || body.selfService();
        try {
            Transactions.of(dataSource.getObject()).inTransaction(c -> {
                sec.defaults().install(c, SecurityIdentities.of(ctx), selfService);
                return null;
            });
        } catch (SangamSecurityException e) {
            throw new ApiProblem(e.httpStatus(), e.code(), e.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenantId", ctx.tenantId());
        out.put("roleTemplates", sec.defaults().roleTemplates().keySet());
        out.put("selfService", selfService);
        return out;
    }
}
