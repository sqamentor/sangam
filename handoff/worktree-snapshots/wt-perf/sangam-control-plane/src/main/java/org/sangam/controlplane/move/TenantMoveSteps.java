// SPDX-License-Identifier: Apache-2.0
package org.sangam.controlplane.move;

import java.util.Map;
import java.util.Objects;

import org.operaton.bpm.engine.delegate.BpmnError;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.sangam.controlplane.audit.ControlPlaneAudit;
import org.sangam.controlplane.registry.Tenant;
import org.sangam.controlplane.registry.TenantRegistry;
import org.springframework.stereotype.Component;

/**
 * Steps of {@code bpmn/tenant-move.bpmn}, called as {@code ${moveSteps.<step>(execution)}}. Every step is idempotent
 * (it may run again after a job retry): cell calls carry the move id as Idempotency-Key, registry writes are
 * compare-and-set. A non-retryable cell answer or a checksum mismatch throws BPMN error {@value #MOVE_FAILED}, which
 * routes to {@link #rollback}. Retryable failures (5xx, I/O) propagate and use the job retry cycle (R5/PT1M), after
 * which an incident is raised for the operator.
 *
 * <p>Guarantee: there is no instant at which both cells accept writes for the tenant. The source is frozen before the
 * copy and stays frozen until the route epoch has moved to the target (cut-over); a rollback unfreezes it only after
 * the target import has been dropped.
 */
@Component("moveSteps")
public class TenantMoveSteps {

    public static final String MOVE_FAILED = "MOVE_FAILED";

    private final CellOps cells;
    private final TenantRegistry registry;
    private final TenantMoveRepository moves;
    private final ControlPlaneAudit audit;

    public TenantMoveSteps(CellOps cells, TenantRegistry registry, TenantMoveRepository moves, ControlPlaneAudit audit) {
        this.cells = cells;
        this.registry = registry;
        this.moves = moves;
        this.audit = audit;
    }

    public void prepare(DelegateExecution x) {
        String moveId = s(x, "moveId");
        moves.setState(moveId, "PREPARING");
        guard(() -> cells.prepareImport(s(x, "targetCell"), s(x, "tenant"), moveId));
        audit.record("move:" + moveId, "MOVE_TARGET_PREPARED", "tenant:" + s(x, "tenant"), s(x, "targetCell"));
    }

    public void freeze(DelegateExecution x) {
        String moveId = s(x, "moveId");
        String tenant = s(x, "tenant");
        Tenant t = registry.get(tenant);
        if (!Tenant.DRAINING.equals(t.status())) {
            registry.setStatus(tenant, Tenant.DRAINING, "move:" + moveId);
        }
        moves.setState(moveId, "DRAINING");
        guard(() -> cells.freeze(s(x, "sourceCell"), tenant, moveId));
    }

    public void checkDrained(DelegateExecution x) {
        CellOps.DrainStatus st = cells.drainStatus(s(x, "sourceCell"), s(x, "tenant"));
        x.setVariable("drained", st.drained());
        x.setVariable("drainStatus", st.toString());
    }

    public void copy(DelegateExecution x) {
        String moveId = s(x, "moveId");
        String tenant = s(x, "tenant");
        registry.setStatus(tenant, Tenant.MOVING, "move:" + moveId);
        moves.setState(moveId, "COPYING");
        CellOps.ExportHandle h = guardGet(() -> cells.export(s(x, "sourceCell"), tenant, moveId));
        Map<String, Long> loaded = guardGet(() -> cells.importFrom(s(x, "targetCell"), tenant, moveId, h));
        long rows = loaded.values().stream().mapToLong(Long::longValue).sum();
        if (rows != h.rows()) {
            throw new BpmnError(MOVE_FAILED, "imported " + rows + " rows, exported " + h.rows());
        }
        moves.recordCopy(moveId, rows, h.watermark());
        x.setVariable("sourceAuditHead", h.auditHead());
        audit.record("move:" + moveId, "MOVE_COPIED", "tenant:" + tenant, "rows=" + rows + " watermark=" + h.watermark());
    }

    public void verify(DelegateExecution x) {
        String moveId = s(x, "moveId");
        String tenant = s(x, "tenant");
        moves.setState(moveId, "VERIFYING");
        CellOps.TenantChecksum src = cells.checksum(s(x, "sourceCell"), tenant);
        CellOps.TenantChecksum tgt = cells.checksum(s(x, "targetCell"), tenant);
        moves.recordChecksums(moveId, src.contentHash(), tgt.contentHash());
        if (!Objects.equals(src.contentHash(), tgt.contentHash()) || !src.rowCounts().equals(tgt.rowCounts())) {
            audit.record("move:" + moveId, "MOVE_VERIFY_FAILED", "tenant:" + tenant,
                    "source=" + src.rowCounts() + " target=" + tgt.rowCounts());
            throw new BpmnError(MOVE_FAILED, "checksum mismatch");
        }
        audit.record("move:" + moveId, "MOVE_VERIFIED", "tenant:" + tenant, "hash=" + src.contentHash());
    }

    public void cutover(DelegateExecution x) {
        String moveId = s(x, "moveId");
        String tenant = s(x, "tenant");
        String target = s(x, "targetCell");
        guard(() -> cells.activate(target, tenant, moveId, (String) x.getVariable("sourceAuditHead")));
        Tenant t = registry.get(tenant);
        if (!target.equals(t.cellId())) {
            registry.assignCell(tenant, target, t.routeEpoch(), "move:" + moveId, "tenant move " + moveId);
        }
        guard(() -> cells.markMoved(s(x, "sourceCell"), tenant, moveId, target));
        moves.setState(moveId, "RETAINING");
        audit.record("move:" + moveId, "MOVE_CUT_OVER", "tenant:" + tenant, s(x, "sourceCell") + "->" + target);
    }

    public void purgeSource(DelegateExecution x) {
        String moveId = s(x, "moveId");
        guard(() -> cells.purge(s(x, "sourceCell"), s(x, "tenant"), moveId));
        moves.finish(moveId, "COMPLETED", null);
        audit.record("move:" + moveId, "MOVE_COMPLETED", "tenant:" + s(x, "tenant"), "source purged");
    }

    public void rollback(DelegateExecution x) {
        String moveId = s(x, "moveId");
        String tenant = s(x, "tenant");
        moves.setState(moveId, "ROLLING_BACK");
        cells.dropImport(s(x, "targetCell"), tenant, moveId);
        cells.unfreeze(s(x, "sourceCell"), tenant, moveId);
        Tenant t = registry.get(tenant);
        if (!Tenant.ACTIVE.equals(t.status())) {
            registry.setStatus(tenant, Tenant.ACTIVE, "move:" + moveId);
        }
        Object err = x.getVariable("moveError");
        moves.finish(moveId, "ROLLED_BACK", err == null ? null : err.toString());
        audit.record("move:" + moveId, "MOVE_ROLLED_BACK", "tenant:" + tenant, err == null ? null : err.toString());
    }

    private static String s(DelegateExecution x, String name) {
        Object v = x.getVariable(name);
        if (v == null) {
            throw new IllegalStateException("process variable " + name + " missing");
        }
        return v.toString();
    }

    private static void guard(Runnable r) {
        guardGet(() -> {
            r.run();
            return null;
        });
    }

    private static <T> T guardGet(java.util.function.Supplier<T> s) {
        try {
            return s.get();
        } catch (HttpCellOps.CellOpException e) {
            if (e.retryable()) {
                throw e;
            }
            throw new BpmnError(MOVE_FAILED, e.getMessage());
        }
    }
}
