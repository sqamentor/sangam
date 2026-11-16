// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import java.util.List;

import org.sangam.core.tenant.ExecutionTenantPolicy;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.TenantMigrationPort;
import org.sangam.rest.support.Permissions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Owner decision 381(3) (decisions 145 and 199): the tenant migration inventory and its audited repair
 * ({@code routes/tenant_migration.py}). Every route needs the platform-only {@link Permissions#TENANT_MIGRATION_ADMIN};
 * nothing reaches the engine without it. A repair names the owning tenant, an ownership-evidence reference and a
 * reason, all required; the R41 command type is {@code tenant.migration.repair}. Without the engine port: 501.
 */
@RestController
@RequestMapping("/api/v1/admin/tenant-migration")
public class TenantMigrationController {

    static final int MAX_TENANT = 64;
    static final int MAX_EVIDENCE = 500;
    static final int MAX_REASON = 1000;

    private final ObjectProvider<TenantMigrationPort> migration;
    private final PermissionPort permissions;

    public TenantMigrationController(ObjectProvider<TenantMigrationPort> migration, PermissionPort permissions) {
        this.migration = migration;
        this.permissions = permissions;
    }

    @GetMapping("/instances")
    public List<TenantMigrationPort.TenantFallbackInstance> inventory(RequestContext ctx) {
        permissions.require(ctx, Permissions.TENANT_MIGRATION_ADMIN);
        return port().inventory(ctx);
    }

    @PostMapping("/instances/{id}/repair")
    public TenantMigrationPort.TenantRepairResult repair(RequestContext ctx, @PathVariable String id,
                                                         @RequestBody(required = false)
                                                         TenantMigrationPort.TenantRepairRequest body) {
        permissions.require(ctx, Permissions.TENANT_MIGRATION_ADMIN);
        if (body == null) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "a body with targetTenant, evidenceRef and reason is required");
        }
        String target = ExecutionTenantPolicy.normalise(body.targetTenant());
        if (target == null || target.length() > MAX_TENANT || !target.equals(target.trim())) {
            throw invalid("targetTenant", "targetTenant names the owning tenant (1.." + MAX_TENANT
                    + " characters, not the shared scope)");
        }
        String evidence = required(body.evidenceRef(), "evidenceRef", MAX_EVIDENCE,
                "evidenceRef names the ownership evidence checked (decision 145)");
        String reason = required(body.reason(), "reason", MAX_REASON, "reason is required");
        return port().repair(ctx, id, new TenantMigrationPort.TenantRepairRequest(target, evidence, reason));
    }

    private TenantMigrationPort port() {
        TenantMigrationPort p = migration.getIfAvailable();
        if (p == null) {
            throw ApiProblem.notInstalled("tenant migration (decision 145)", "the server's process engine");
        }
        return p;
    }

    private static String required(String value, String field, int max, String message) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw invalid(field, message + " (1.." + max + " characters)");
        }
        return value.trim();
    }

    private static ApiProblem invalid(String field, String message) {
        return ApiProblem.badRequest("VALIDATION_FAILED", message).with("field", field);
    }
}
