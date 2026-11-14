// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.web;

import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.sangam.rest.web.Lists;
import org.sangam.server.bridge.HistoryMigrationBridge;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Owner decision 258 (FC-18), contract {@code routes/history_migration.py}: POST
 * /api/v1/admin/history/process-instances/{id}/migrate moves the history of an ended instance to a later version of its
 * definition. Needs {@value Permissions#HISTORY_MIGRATE}; the R41 command {@code history.process-instance.migrate} and
 * its idempotency handling come from the contract (a replay re-checks the permission, SecurityPermissionBridge). The
 * answer's resource reference is the process instance id. Without the engine bridge it answers 501.
 */
@RestController
@RequestMapping("/api/v1/admin/history/process-instances")
public class HistoryMigrationController {

    public record HistoryInstanceMigrationRequest(String targetDefinitionId, String reason) {
    }

    private final ObjectProvider<HistoryMigrationBridge> migrations;
    private final PermissionPort permissions;

    public HistoryMigrationController(ObjectProvider<HistoryMigrationBridge> migrations, PermissionPort permissions) {
        this.migrations = migrations;
        this.permissions = permissions;
    }

    @PostMapping("/{id}/migrate")
    public ResponseEntity<HistoryMigrationBridge.HistoryInstanceMigrationResult> migrate(RequestContext ctx,
            @PathVariable String id, @RequestBody(required = false) HistoryInstanceMigrationRequest body) {
        permissions.require(ctx, Permissions.HISTORY_MIGRATE);
        if (body == null) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "targetDefinitionId and reason are required");
        }
        HistoryMigrationBridge b = migrations.getIfAvailable();
        if (b == null) {
            throw ApiProblem.notInstalled("historic instance migration", "sangam-engine (SangamProcessEnginePlugin)");
        }
        HistoryMigrationBridge.HistoryInstanceMigrationResult r = b.migrate(ctx, id, body.targetDefinitionId(),
                body.reason());
        return Lists.okRef(r.processInstanceId(), r);
    }
}
