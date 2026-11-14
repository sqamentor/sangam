// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.ops;

import java.util.List;
import java.util.Map;

import org.sangam.backup.BackupConsole;
import org.sangam.backup.BackupException;
import org.sangam.backup.BackupTargets;
import org.sangam.rest.modules.ModulePaging;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.sangam.rest.support.TxSupport;
import org.sangam.rest.web.Lists;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Owner decision 155 (M-102): the backup console for platform operators. Lists the targets, starts a backup now and
 * shows the run history; restore stays the documented offline procedure. The rules live in
 * {@link BackupConsole} (sangam-backup, plain Java); this class is only the HTTP adapter. The console is off unless
 * {@code sangam.backup.console.kubernetes.enabled=true}; then every route answers 501
 * {@code BACKUP_CONSOLE_NOT_CONFIGURED}.
 *
 * <p>The trigger's audit event is committed in its own transaction before the platform is called
 * ({@link BackupConsole.Audit}), so an audit failure starts nothing.
 */
@RestController
@RequestMapping("/api/v1/ops/backup")
public class BackupController {

    private final ObjectProvider<BackupConsole> console;
    private final PermissionPort permissions;
    private final AuditPort audit;
    private final TxSupport tx;

    public BackupController(ObjectProvider<BackupConsole> console, PermissionPort permissions, AuditPort audit, TxSupport tx) {
        this.console = console;
        this.permissions = permissions;
        this.audit = audit;
        this.tx = tx;
    }

    /** {@code BackupTriggerRequest} of the contract. */
    public record TriggerRequest(String reason) {
    }

    @GetMapping("/targets")
    public ResponseEntity<List<BackupTargets.Target>> targets(RequestContext ctx) {
        permissions.require(ctx, Permissions.OPS_READ);
        BackupConsole c = console();
        return Lists.ok(call(c::targets));
    }

    @PostMapping("/targets/{id}/runs")
    public ResponseEntity<BackupTargets.Run> trigger(RequestContext ctx, @PathVariable String id, @RequestBody(required = false) TriggerRequest body) {
        permissions.require(ctx, Permissions.OPS_WRITE);
        BackupConsole c = console();
        String reason = body == null ? null : body.reason();
        BackupConsole.Audit sink = (chain, type, payload) -> tx.inNewTx(() -> audit.record(ctx, chain, type, payload));
        BackupTargets.Run run = call(() -> c.trigger(id, ctx.principalId(), reason, sink));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(run);
    }

    @GetMapping("/runs")
    public ResponseEntity<List<BackupTargets.Run>> runs(RequestContext ctx, @RequestParam(required = false) String target,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        permissions.require(ctx, Permissions.OPS_READ);
        BackupConsole c = console();
        return ModulePaging.slice(call(() -> c.runs(target)), page, size);
    }

    private BackupConsole console() {
        BackupConsole c = console.getIfAvailable();
        if (c == null) {
            throw new ApiProblem(501, "BACKUP_CONSOLE_NOT_CONFIGURED", null,
                    "the backup console is off; set sangam.backup.console.kubernetes.enabled=true (decision 155)",
                    Map.of("capability", "backup console"), null);
        }
        return c;
    }

    /** {@link BackupException} kinds: INVALID 400, NOT_FOUND 404, CONFLICT 409, UPSTREAM 502. */
    static <T> T call(java.util.function.Supplier<T> work) {
        try {
            return work.get();
        } catch (BackupException e) {
            throw problem(e);
        }
    }

    static ApiProblem problem(BackupException e) {
        return switch (e.kind()) {
            case INVALID -> ApiProblem.badRequest(e.code(), e.getMessage());
            case NOT_FOUND -> new ApiProblem(404, "NOT_FOUND", e.getMessage()).with("resource", "backup target");
            case CONFLICT -> ApiProblem.conflict(e.code(), e.getMessage());
            case UPSTREAM -> new ApiProblem(502, e.code(), e.getMessage());
        };
    }
}
