// SPDX-License-Identifier: Apache-2.0
package org.sangam.controlplane.move;

import java.util.HashMap;
import java.util.Map;

import org.operaton.bpm.engine.RuntimeService;
import org.sangam.controlplane.audit.ControlPlaneAudit;
import org.sangam.controlplane.cells.Cell;
import org.sangam.controlplane.cells.CellDirectory;
import org.sangam.controlplane.config.ControlPlaneProperties;
import org.sangam.controlplane.placement.PlacementService;
import org.sangam.controlplane.registry.Tenant;
import org.sangam.controlplane.registry.TenantRegistry;
import org.sangam.core.layer.Hashes;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Starts tenant moves (SCALE-ARCHITECTURE.md section 7). The move itself is the BPMN process
 * {@value #PROCESS_KEY} on the control plane's own engine (async steps, job retries, timers, error-path rollback).
 * The process instance business key is the move id, so a duplicate start for the same move is rejected by the engine.
 */
@Service
public class TenantMoveService {

    public static final String PROCESS_KEY = "sangam-tenant-move";

    private final TenantRegistry registry;
    private final CellDirectory cells;
    private final PlacementService placement;
    private final TenantMoveRepository moves;
    private final RuntimeService runtime;
    private final ControlPlaneAudit audit;
    private final ControlPlaneProperties props;

    public TenantMoveService(TenantRegistry registry, CellDirectory cells, PlacementService placement,
                             TenantMoveRepository moves, RuntimeService runtime, ControlPlaneAudit audit,
                             ControlPlaneProperties props) {
        this.registry = registry;
        this.cells = cells;
        this.placement = placement;
        this.moves = moves;
        this.runtime = runtime;
        this.audit = audit;
        this.props = props;
    }

    @Transactional
    public TenantMove start(String tenantCode, String targetCell, String reason, String actor) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("a reason is required for a tenant move");
        }
        Tenant t = registry.get(tenantCode);
        if (t.cellId() == null) {
            throw new IllegalStateException("tenant " + tenantCode + " is not placed");
        }
        if (!Tenant.ACTIVE.equals(t.status())) {
            throw new IllegalStateException("tenant " + tenantCode + " is " + t.status());
        }
        if (moves.forTenant(t.tenantCode()).stream().anyMatch(TenantMove::active)) {
            throw new IllegalStateException("tenant " + tenantCode + " already has an active move");
        }
        String target;
        if (targetCell == null || targetCell.isBlank()) {
            target = placement.moveTarget(t).map(c -> c.cell().cellId())
                    .orElseThrow(() -> new PlacementService.NoCapacity("no target cell for " + tenantCode));
        } else {
            Cell c = cells.get(targetCell);
            String why = placement.reject(c, t);
            if (why != null) {
                throw new IllegalStateException("target cell " + targetCell + " rejected: " + why);
            }
            target = c.cellId();
        }
        if (target.equals(t.cellId())) {
            throw new IllegalArgumentException("target cell equals source cell");
        }
        String moveId = Hashes.uuidV7().toString();
        moves.insert(new TenantMove(moveId, t.tenantCode(), t.cellId(), target, "REQUESTED", null, actor, reason, 0,
                null, null, null, System.currentTimeMillis(), null, null));
        Map<String, Object> vars = new HashMap<>();
        vars.put("moveId", moveId);
        vars.put("tenant", t.tenantCode());
        vars.put("sourceCell", t.cellId());
        vars.put("targetCell", target);
        vars.put("routeEpoch", t.routeEpoch());
        vars.put("retention", props.moveRetention().toString());
        vars.put("drained", false);
        String pid = runtime.startProcessInstanceByKey(PROCESS_KEY, moveId, vars).getId();
        moves.setProcess(moveId, pid);
        audit.record(actor, "MOVE_REQUESTED", "tenant:" + t.tenantCode(),
                "move=" + moveId + " " + t.cellId() + "->" + target + " reason=" + reason);
        return moves.get(moveId);
    }
}
