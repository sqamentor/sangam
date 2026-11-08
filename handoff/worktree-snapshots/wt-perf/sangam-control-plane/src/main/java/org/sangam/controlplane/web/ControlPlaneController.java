// SPDX-License-Identifier: Apache-2.0
package org.sangam.controlplane.web;

import java.security.Principal;
import java.util.List;
import java.util.Map;

import org.sangam.controlplane.audit.ControlPlaneAudit;
import org.sangam.controlplane.cells.Cell;
import org.sangam.controlplane.cells.CellDirectory;
import org.sangam.controlplane.move.TenantMove;
import org.sangam.controlplane.move.TenantMoveRepository;
import org.sangam.controlplane.move.TenantMoveService;
import org.sangam.controlplane.placement.PlacementService;
import org.sangam.controlplane.registry.Tenant;
import org.sangam.controlplane.registry.TenantRegistry;
import org.sangam.controlplane.routing.Route;
import org.sangam.controlplane.routing.RoutingService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Control-plane REST API ({@code /api/v1}). Routes: tenant lookups for gateways and cells. Cells: registration and
 * heartbeat. Admin: tenants, placement, cell lifecycle, moves, audit. Errors are RFC 9457 problem+json.
 */
@RestController
@RequestMapping("/api/v1")
public class ControlPlaneController {

    private final TenantRegistry registry;
    private final CellDirectory cells;
    private final PlacementService placement;
    private final RoutingService routing;
    private final TenantMoveService moveService;
    private final TenantMoveRepository moves;
    private final ControlPlaneAudit audit;

    public ControlPlaneController(TenantRegistry registry, CellDirectory cells, PlacementService placement,
                                  RoutingService routing, TenantMoveService moveService, TenantMoveRepository moves,
                                  ControlPlaneAudit audit) {
        this.registry = registry;
        this.cells = cells;
        this.placement = placement;
        this.routing = routing;
        this.moveService = moveService;
        this.moves = moves;
        this.audit = audit;
    }

    // ---- routing (GATEWAY, CELL, ROLLUP)

    @GetMapping("/routes/tenants/{code}")
    public Route route(@PathVariable String code) {
        return routing.route(code);
    }

    /** Incremental snapshot: every route changed after {@code sinceMs} (0 = full). */
    @GetMapping("/routes")
    public Map<String, Object> routes(@RequestParam(defaultValue = "0") long sinceMs) {
        long now = System.currentTimeMillis();
        List<Route> changed = routing.changedSince(sinceMs);
        return Map.of("asOfMs", now, "routes", changed);
    }

    // ---- cells (CELL)

    @PutMapping("/cells/{cellId}")
    public Cell register(@PathVariable String cellId, @RequestBody CellDirectory.Registration r, Principal p) {
        return cells.register(cellId, r, name(p));
    }

    @PostMapping("/cells/{cellId}/heartbeat")
    public ResponseEntity<Void> heartbeat(@PathVariable String cellId, @RequestBody CellDirectory.Heartbeat h) {
        cells.heartbeat(cellId, h);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/cells")
    public List<Cell> cells() {
        return cells.all();
    }

    @GetMapping("/cells/{cellId}")
    public Cell cell(@PathVariable String cellId) {
        return cells.get(cellId);
    }

    // ---- admin (ADMIN)

    @PostMapping("/admin/tenants")
    public ResponseEntity<Tenant> createTenant(@RequestBody CreateTenant body, Principal p) {
        Tenant t = registry.create(new TenantRegistry.NewTenant(body.tenantCode(), body.parentCode(), body.levelKind(),
                body.name(), body.jurisdiction(), body.residency(), body.sector(), body.expectedStartsPerSec()), name(p));
        if (body.place() == null || body.place()) {
            t = placement.place(t.tenantCode(), name(p));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(t);
    }

    public record CreateTenant(String tenantCode, String parentCode, String levelKind, String name, String jurisdiction,
                               String residency, String sector, Double expectedStartsPerSec, Boolean place) {
    }

    @GetMapping("/admin/tenants/{code}")
    public Tenant tenant(@PathVariable String code) {
        return registry.get(code);
    }

    @GetMapping("/admin/tenants/{code}/children")
    public List<Tenant> children(@PathVariable String code) {
        return registry.children(code);
    }

    @PostMapping("/admin/tenants/{code}/place")
    public Tenant place(@PathVariable String code, Principal p) {
        return placement.place(code, name(p));
    }

    @GetMapping("/admin/tenants/{code}/placement-candidates")
    public List<PlacementService.Candidate> candidates(@PathVariable String code) {
        return placement.candidates(registry.get(code), null);
    }

    @PostMapping("/admin/tenants/{code}/moves")
    public ResponseEntity<TenantMove> move(@PathVariable String code, @RequestBody MoveRequest body, Principal p) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(moveService.start(code, body.targetCell(), body.reason(), name(p)));
    }

    public record MoveRequest(String targetCell, String reason) {
    }

    @GetMapping("/admin/tenants/{code}/moves")
    public List<TenantMove> movesOf(@PathVariable String code) {
        return moves.forTenant(registry.get(code).tenantCode());
    }

    @GetMapping("/admin/moves/{moveId}")
    public TenantMove moveById(@PathVariable String moveId) {
        return moves.get(moveId);
    }

    @PostMapping("/admin/cells/{cellId}/placement")
    public Cell placementSwitch(@PathVariable String cellId, @RequestParam boolean open, Principal p) {
        cells.setPlacementOpen(cellId, open, name(p));
        return cells.get(cellId);
    }

    @PostMapping("/admin/cells/{cellId}/retire")
    public Cell retire(@PathVariable String cellId, Principal p) {
        cells.retire(cellId, registry, name(p));
        return cells.get(cellId);
    }

    @GetMapping("/admin/audit")
    public List<Map<String, Object>> audit(@RequestParam String subject, @RequestParam(defaultValue = "100") int limit) {
        return audit.forSubject(subject, limit);
    }

    // ---- errors

    @ExceptionHandler(TenantRegistry.NotFound.class)
    public ProblemDetail notFound(TenantRegistry.NotFound e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail badRequest(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler({IllegalStateException.class, PlacementService.NoCapacity.class})
    public ProblemDetail conflict(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    private static String name(Principal p) {
        return p == null ? "anonymous" : p.getName();
    }
}
