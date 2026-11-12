// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.web;

import java.util.List;

import org.sangam.integration.inbox.PoisonInbound;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.RequestContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SL-142 / owner decision 61: the cell operator's view of inbound messages parked because their tenant left this cell
 * (MOVED or PURGED), under {@code /api/v1/admin/cell/parked-inbound}, role {@code CELL_ADMIN} like the other cell
 * tenant-admin routes ({@link CellTenantAdminController}). These routes are exempt from
 * {@link org.sangam.server.bridge.CellTenantStateFilter} on purpose: the tenant whose messages are parked answers 421
 * on every tenant route of this cell, so only a cell-level operator can list and replay them, across tenants.
 *
 * <p>Replay hands the message to the tenant's current cell through
 * {@link org.sangam.server.bridge.CellParkedReplayTransport} and closes the item through W2-N's
 * {@link PoisonInbound#replayParked} (REPLAYED, audited on {@code sec:<tenant>} with the actor and reason; one park and
 * replay store). A failed hand-over leaves the item open (503 REPLAY_FAILED).
 */
@RestController
@RequestMapping("/api/v1/admin/cell/parked-inbound")
@ConditionalOnProperty(name = "sangam.cell.id")
public class CellParkedInboundController {

    private final ObjectProvider<PoisonInbound> poison;
    private final ObjectProvider<PoisonInbound.ParkedReplay> transport;

    public CellParkedInboundController(ObjectProvider<PoisonInbound> poison,
                                       ObjectProvider<PoisonInbound.ParkedReplay> transport) {
        this.poison = poison;
        this.transport = transport;
    }

    /** {@code ReasonRequest} of the contract. */
    public record ReasonRequest(String reason) {
    }

    @GetMapping
    public List<PoisonInbound.Item> list(RequestContext ctx, @RequestParam(required = false) String tenant) {
        requireRole(ctx);
        List<PoisonInbound.Item> all = store().listParked();
        return tenant == null || tenant.isBlank() ? all
                : all.stream().filter(i -> tenant.trim().equals(i.tenantId())).toList();
    }

    @GetMapping("/{itemId}")
    public PoisonInbound.Parked get(RequestContext ctx, @PathVariable long itemId) {
        requireRole(ctx);
        try {
            return store().parked(itemId);
        } catch (RuntimeException e) {
            throw problem(e, itemId);
        }
    }

    @PostMapping("/{itemId}/replay")
    public ResponseEntity<Void> replay(RequestContext ctx, @PathVariable long itemId, @RequestBody ReasonRequest body) {
        requireRole(ctx);
        if (body == null || body.reason() == null || body.reason().isBlank()) {
            throw ApiProblem.badRequest("REASON_REQUIRED", "a reason is required");
        }
        PoisonInbound.ParkedReplay t = transport.getIfAvailable();
        if (t == null) {
            throw ApiProblem.unavailable("REPLAY_NOT_CONFIGURED",
                    "sangam.cell.gateway-url is not set: this cell cannot reach the tenant's new cell", 0);
        }
        try {
            store().replayParked(itemId, t, ctx.principalId(), body.reason().trim());
        } catch (RuntimeException e) {
            throw problem(e, itemId);
        }
        return ResponseEntity.noContent().build();
    }

    private PoisonInbound store() {
        PoisonInbound p = poison.getIfAvailable();
        if (p == null) {
            throw ApiProblem.notInstalled("parked inbound messages", "sangam-integration");
        }
        return p;
    }

    /** PoisonInbound's messages: "no ... item" = 404, "409 CODE: ..." = 409, a failed hand-over = 503. */
    static RuntimeException problem(RuntimeException e, long itemId) {
        if (e instanceof ApiProblem) {
            return e;
        }
        String m = e.getMessage() == null ? "" : e.getMessage();
        if (e instanceof IllegalArgumentException && m.startsWith("no ")) {
            return ApiProblem.notFound("parked inbound message", Long.toString(itemId));
        }
        if (m.startsWith("409 ")) {
            int colon = m.indexOf(':');
            String code = colon > 4 ? m.substring(4, colon).trim() : "CONFLICT";
            return ApiProblem.conflict(code, m.substring(colon < 0 ? 4 : colon + 1).trim());
        }
        if (e instanceof IllegalStateException && m.startsWith("replay of parked item")) {
            return ApiProblem.unavailable("REPLAY_FAILED", m, 0);
        }
        if (e instanceof IllegalArgumentException) {
            return ApiProblem.badRequest("VALIDATION_FAILED", m);
        }
        return e;
    }

    private static void requireRole(RequestContext ctx) {
        if (!ctx.inGroup(CellTenantAdminController.ROLE) && !ctx.inGroup("sangam-cell-admin")) {
            throw ApiProblem.notPermitted("cell parked-inbound administration (role CELL_ADMIN)");
        }
    }
}
