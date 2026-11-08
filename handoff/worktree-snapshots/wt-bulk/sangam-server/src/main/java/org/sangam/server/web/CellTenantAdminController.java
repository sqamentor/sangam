// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.web;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.web.Lists;
import org.sangam.server.bridge.CellTenantMover;
import org.sangam.server.bridge.CellTenantStateStore;
import org.sangam.server.bridge.CellTenantStateStore.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cell tenant-admin API for tenant moves (IR-4; contract {@code sangam-control-plane/.../move/CellOps}), under
 * {@code /api/v1/admin/cell/tenants/{tenant}}. Callers need role {@code CELL_ADMIN} (SecurityConfig); every mutation
 * carries the control plane's {@code Idempotency-Key} (R41, command types {@code cell.tenant.*}).
 * <p>State machine per tenant ({@link CellTenantStateStore}): target ACTIVE → IMPORTING (prepare) → ACTIVE (activate)
 * or dropped; source ACTIVE → FROZEN (freeze) → MOVED (moved) → PURGED (purge), or FROZEN → ACTIVE (unfreeze).
 * The pull handle {@code GET /api/v1/admin/cell/exports/{exportId}} is authenticated by the one-time export token
 * ({@code X-Sangam-Export-Token}) instead of a principal, because the target cell pulls it.
 */
@RestController
@RequestMapping("/api/v1/admin/cell")
@ConditionalOnProperty(name = "sangam.cell.id")
public class CellTenantAdminController {

    public static final String ROLE = "CELL_ADMIN";
    private static final Logger LOG = LoggerFactory.getLogger(CellTenantAdminController.class);

    private final CellTenantMover mover;
    private final CellTenantStateStore states;

    public CellTenantAdminController(CellTenantMover mover, CellTenantStateStore states,
            org.springframework.beans.factory.ObjectProvider<org.sangam.server.config.OnboardingWiring.OnboardingRestorer> restorer,
            org.springframework.beans.factory.ObjectProvider<org.sangam.server.config.OnboardingWiring.OnboardingMoveGuard> moveGuard) {
        this.moveGuard = moveGuard;
        this.restorer = restorer;
        this.mover = mover;
        this.states = states;
    }

    private final org.springframework.beans.factory.ObjectProvider<org.sangam.server.config.OnboardingWiring.OnboardingRestorer> restorer;

    private final org.springframework.beans.factory.ObjectProvider<org.sangam.server.config.OnboardingWiring.OnboardingMoveGuard> moveGuard;

    private void refuseOpenOnboarding(String tenant) {
        moveGuard.ifAvailable(g -> {
            if (g.openRun(tenant)) {
                throw ApiProblem.conflict("ONBOARDING_IN_PROGRESS", "tenant " + tenant
                        + " has an open onboarding run; finish or cancel it before moving the tenant");
            }
        });
    }

    public record MoveRequest(String moveId) {
    }

    public record ImportRequest(String moveId, String url, String token, String watermark) {
    }

    public record ActivateRequest(String moveId, String sourceAuditHead) {
    }

    public record MovedRequest(String moveId, String targetCell) {
    }

    @PostMapping("/tenants/{tenant}/import/prepare")
    public ResponseEntity<Map<String, Object>> prepareImport(RequestContext ctx, @PathVariable String tenant,
                                                             @RequestBody(required = false) MoveRequest body) {
        requireRole(ctx);
        refuseOpenOnboarding(tenant);
        CellTenantStateStore.TenantState s = states.read(tenant);
        String moveId = moveId(body == null ? null : body.moveId());
        if (s.state() == State.IMPORTING && moveId.equals(s.moveId())) {
            return Lists.okRef(tenant, state(s));
        }
        if (s.state() != State.ACTIVE) {
            throw ApiProblem.conflict("INVALID_TRANSITION", "tenant " + tenant + " is " + s.state());
        }
        if (!mover.checksum(tenant).rowCounts().isEmpty()) {
            throw ApiProblem.conflict("TENANT_NOT_EMPTY", "tenant " + tenant + " already has rows in this cell");
        }
        LOG.info("Tenant {} prepared for import (move {}) by {}", tenant, moveId, ctx.principalId());
        return Lists.okRef(tenant, state(states.set(tenant, State.IMPORTING, moveId, null, ctx.principalId())));
    }

    @PostMapping("/tenants/{tenant}/freeze")
    public ResponseEntity<Map<String, Object>> freeze(RequestContext ctx, @PathVariable String tenant,
                                                      @RequestBody(required = false) MoveRequest body) {
        requireRole(ctx);
        refuseOpenOnboarding(tenant);
        CellTenantStateStore.TenantState s = states.read(tenant);
        String moveId = moveId(body == null ? null : body.moveId());
        if (s.state() == State.FROZEN) {
            return Lists.okRef(tenant, state(s));
        }
        if (s.state() != State.ACTIVE) {
            throw ApiProblem.conflict("INVALID_TRANSITION", "tenant " + tenant + " is " + s.state());
        }
        mover.auditCell(ctx, tenant, "TENANT_FROZEN", Map.of("moveId", moveId));
        return Lists.okRef(tenant, state(states.set(tenant, State.FROZEN, moveId, null, ctx.principalId())));
    }

    @GetMapping("/tenants/{tenant}/drain-status")
    public CellTenantMover.DrainStatus drainStatus(RequestContext ctx, @PathVariable String tenant) {
        requireRole(ctx);
        return mover.drainStatus(tenant);
    }

    @PostMapping("/tenants/{tenant}/export")
    public ResponseEntity<CellTenantMover.ExportHandle> export(RequestContext ctx, @PathVariable String tenant,
                                                               @RequestBody(required = false) MoveRequest body) {
        requireRole(ctx);
        requireState(tenant, State.FROZEN);
        refuseOpenOnboarding(tenant);
        CellTenantMover.ExportHandle h = mover.export(ctx, tenant, moveId(body == null ? null : body.moveId()));
        return Lists.okRef(tenant, h);
    }

    /** Pull of an export by the target cell; the export token is the credential. */
    @GetMapping("/exports/{exportId}")
    public ResponseEntity<InputStreamResource> download(@PathVariable String exportId,
            @RequestHeader(value = "X-Sangam-Export-Token", required = false) String token) throws java.io.IOException {
        InputStream in = mover.openExport(exportId, token);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Disposition", "attachment; filename=\"" + exportId + ".ndjson.gz\"")
                .body(new InputStreamResource(in));
    }

    @PostMapping("/tenants/{tenant}/import")
    public ResponseEntity<Map<String, Object>> importTenant(RequestContext ctx, @PathVariable String tenant,
                                                            @RequestBody ImportRequest body) {
        requireRole(ctx);
        requireState(tenant, State.IMPORTING);
        Map<String, Long> counts = mover.importFrom(ctx, tenant, body.moveId(), body.url(), body.token(),
                body.watermark());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rowCounts", counts);
        return Lists.okRef(tenant, out);
    }

    @GetMapping("/tenants/{tenant}/checksum")
    public CellTenantMover.TenantChecksum checksum(RequestContext ctx, @PathVariable String tenant) {
        requireRole(ctx);
        return mover.checksum(tenant);
    }

    @PostMapping("/tenants/{tenant}/activate")
    public ResponseEntity<Map<String, Object>> activate(RequestContext ctx, @PathVariable String tenant,
                                                        @RequestBody(required = false) ActivateRequest body) {
        requireRole(ctx);
        CellTenantStateStore.TenantState s = states.read(tenant);
        if (s.state() == State.ACTIVE) {
            return Lists.okRef(tenant, state(s));
        }
        if (s.state() != State.IMPORTING) {
            throw ApiProblem.conflict("INVALID_TRANSITION", "tenant " + tenant + " is " + s.state());
        }
        String head = mover.auditHead(tenant);
        String source = body == null ? null : body.sourceAuditHead();
        if (source != null && !source.isBlank() && !source.equals(head)) {
            throw ApiProblem.conflict("AUDIT_HEAD_MISMATCH", "the imported audit chain heads differ from the source")
                    .with("sourceAuditHead", source).with("targetAuditHead", head);
        }
        String moveId = moveId(body == null ? null : body.moveId());
        mover.auditCell(ctx, tenant, "TENANT_MOVED_IN", Map.of("moveId", moveId, "sourceAuditHead",
                source == null ? head : source));
        restorer.ifAvailable(r -> r.restore(tenant));
        return Lists.okRef(tenant, state(states.set(tenant, State.ACTIVE, moveId, null, ctx.principalId())));
    }

    @PostMapping("/tenants/{tenant}/moved")
    public ResponseEntity<Map<String, Object>> moved(RequestContext ctx, @PathVariable String tenant,
                                                     @RequestBody MovedRequest body) {
        requireRole(ctx);
        if (body.targetCell() == null || body.targetCell().isBlank()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "targetCell is required");
        }
        CellTenantStateStore.TenantState s = states.read(tenant);
        if (s.state() == State.MOVED) {
            return Lists.okRef(tenant, state(s));
        }
        if (s.state() != State.FROZEN) {
            throw ApiProblem.conflict("INVALID_TRANSITION", "tenant " + tenant + " must be FROZEN, is " + s.state());
        }
        String moveId = moveId(body.moveId());
        mover.auditCell(ctx, tenant, "TENANT_MOVED_OUT", Map.of("moveId", moveId, "targetCell", body.targetCell()));
        return Lists.okRef(tenant, state(states.set(tenant, State.MOVED, moveId, body.targetCell(),
                ctx.principalId())));
    }

    @PostMapping("/tenants/{tenant}/unfreeze")
    public ResponseEntity<Map<String, Object>> unfreeze(RequestContext ctx, @PathVariable String tenant,
                                                        @RequestBody(required = false) MoveRequest body) {
        requireRole(ctx);
        CellTenantStateStore.TenantState s = states.read(tenant);
        if (s.state() == State.ACTIVE) {
            return Lists.okRef(tenant, state(s));
        }
        if (s.state() != State.FROZEN) {
            throw ApiProblem.conflict("INVALID_TRANSITION", "tenant " + tenant + " is " + s.state());
        }
        String moveId = moveId(body == null ? null : body.moveId());
        mover.auditCell(ctx, tenant, "TENANT_UNFROZEN", Map.of("moveId", moveId));
        restorer.ifAvailable(r -> r.restore(tenant));
        return Lists.okRef(tenant, state(states.set(tenant, State.ACTIVE, moveId, null, ctx.principalId())));
    }

    @PostMapping("/tenants/{tenant}/import/drop")
    public ResponseEntity<Map<String, Object>> dropImport(RequestContext ctx, @PathVariable String tenant,
                                                          @RequestBody(required = false) MoveRequest body) {
        requireRole(ctx);
        CellTenantStateStore.TenantState s = states.read(tenant);
        if (s.state() == State.ACTIVE && s.moveId() == null) {
            return Lists.okRef(tenant, state(s));
        }
        if (s.state() != State.IMPORTING) {
            throw ApiProblem.conflict("INVALID_TRANSITION", "only an import in progress can be dropped; tenant is "
                    + s.state());
        }
        Map<String, Long> deleted = mover.deleteTenant(tenant, true);
        LOG.info("Partial import of tenant {} dropped (move {}): {}", tenant, s.moveId(), deleted);
        Map<String, Object> out = state(states.set(tenant, State.ACTIVE, s.moveId(), null, ctx.principalId()));
        out.put("deleted", deleted);
        return Lists.okRef(tenant, out);
    }

    @PostMapping("/tenants/{tenant}/purge")
    public ResponseEntity<Map<String, Object>> purge(RequestContext ctx, @PathVariable String tenant,
                                                     @RequestBody(required = false) MoveRequest body) {
        requireRole(ctx);
        CellTenantStateStore.TenantState s = states.read(tenant);
        if (s.state() == State.PURGED) {
            return Lists.okRef(tenant, state(s));
        }
        if (s.state() != State.MOVED) {
            throw ApiProblem.conflict("INVALID_TRANSITION", "only a MOVED tenant can be purged; tenant is " + s.state());
        }
        long holds = mover.activeLegalHolds(tenant);
        if (holds > 0) {
            throw ApiProblem.conflict("LEGAL_HOLD_ACTIVE", "tenant " + tenant + " has " + holds
                    + " active legal hold(s); purge refused (R61)").with("legalHolds", holds);
        }
        Map<String, Long> deleted = mover.deleteTenant(tenant, true);
        mover.deleteExports(tenant);
        LOG.info("Tenant {} purged after move {}: {}", tenant, s.moveId(), deleted);
        Map<String, Object> out = state(states.set(tenant, State.PURGED, s.moveId(), s.targetCell(), ctx.principalId()));
        out.put("deleted", deleted);
        return Lists.okRef(tenant, out);
    }

    private void requireState(String tenant, State expected) {
        CellTenantStateStore.TenantState s = states.read(tenant);
        if (s.state() != expected) {
            throw ApiProblem.conflict("INVALID_TRANSITION", "tenant " + tenant + " must be " + expected + ", is "
                    + s.state());
        }
    }

    private static void requireRole(RequestContext ctx) {
        if (!ctx.inGroup(ROLE) && !ctx.inGroup("sangam-cell-admin")) {
            throw ApiProblem.notPermitted("cell tenant administration (role CELL_ADMIN)");
        }
    }

    private static String moveId(String moveId) {
        return moveId == null || moveId.isBlank() ? "manual" : moveId;
    }

    private static Map<String, Object> state(CellTenantStateStore.TenantState s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tenant", s.tenant());
        m.put("state", s.state().name());
        m.put("moveId", s.moveId());
        m.put("targetCell", s.targetCell());
        m.put("since", s.since() == null ? null : s.since().toString());
        return m;
    }
}
