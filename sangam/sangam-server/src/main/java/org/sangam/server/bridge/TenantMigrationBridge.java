// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.operaton.bpm.engine.ProcessEngineException;
import org.sangam.core.tenant.ExecutionTenantPolicy;
import org.sangam.ports.tenancy.ChangeTenantService;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.TenantMigrationPort;

/**
 * Owner decision 381(3): {@link TenantMigrationPort} over {@link TenantFallbackInventory} (read-only scan of the engine
 * tables) and {@link ChangeTenantService#moveInstance} (the change-tenant cut-over: a new instance of the target
 * tenant's copy of the definition at the same active activities, variables and business key; the old one cancelled; a
 * {@code change-tenant} link). A repair is accepted only for an instance the inventory lists at that moment and only
 * to a tenant other than its current engine tenant; it is audited as {@value #REPAIRED} with the evidence reference,
 * reason and actor, on the new instance's chain and the old one's.
 */
public final class TenantMigrationBridge implements TenantMigrationPort {

    /** Audit event of a repaired instance. */
    public static final String REPAIRED = "TENANT_INSTANCE_REPAIRED";

    private final DataSource engineDataSource;
    private final String tablePrefix;
    private final ChangeTenantService changeTenant;
    private final AuditPort audit;

    public TenantMigrationBridge(DataSource engineDataSource, String tablePrefix, ChangeTenantService changeTenant,
                                 AuditPort audit) {
        this.engineDataSource = engineDataSource;
        this.tablePrefix = tablePrefix == null ? "" : tablePrefix;
        this.changeTenant = changeTenant;
        this.audit = audit;
    }

    @Override
    public List<TenantFallbackInstance> inventory(RequestContext ctx) {
        return scan().stream().map(TenantMigrationBridge::view).toList();
    }

    @Override
    public TenantRepairResult repair(RequestContext ctx, String processInstanceId, TenantRepairRequest request) {
        TenantFallbackInventory.Entry entry = scan().stream()
                .filter(e -> e.processInstanceId().equals(processInstanceId)).findFirst()
                .orElseThrow(() -> ApiProblem.conflict("NOT_IN_INVENTORY", "Process instance " + processInstanceId
                        + " is not a running root instance that the engine-tenant-only rule refuses; nothing to repair"));
        String target = request.targetTenant();
        String from = ExecutionTenantPolicy.normalise(entry.engineTenant());
        if (target.equals(from)) {
            throw ApiProblem.conflict("INVALID_TRANSITION", "The instance already runs in the named tenant; its "
                    + "conflicting tenant variable is business data and is not changed here");
        }
        String moved;
        try {
            moved = changeTenant.moveInstance(processInstanceId, target, ctx.principalId());
        } catch (ProcessEngineException | IllegalArgumentException e) {
            throw ApiProblem.unprocessable("REPAIR_REFUSED", "The cut-over was refused: " + e.getMessage());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("processInstanceId", processInstanceId);
        payload.put("newProcessInstanceId", moved);
        payload.put("fromTenant", from);
        payload.put("targetTenant", target);
        payload.put("definitionKey", entry.definitionKey());
        payload.put("definitionTenant", entry.definitionTenant());
        payload.put("finding", entry.finding().code());
        payload.put("evidenceRef", request.evidenceRef());
        payload.put("reason", request.reason());
        payload.put("actor", ctx.actor());
        audit.record(ctx, moved, REPAIRED, payload);
        audit.record(ctx, processInstanceId, REPAIRED, payload);
        return new TenantRepairResult(processInstanceId, moved, from, target, entry.finding().code(),
                request.evidenceRef());
    }

    private List<TenantFallbackInventory.Entry> scan() {
        try (Connection c = engineDataSource.getConnection()) {
            return TenantFallbackInventory.scan(c, tablePrefix);
        } catch (SQLException e) {
            throw ApiProblem.unavailable("ENGINE_UNAVAILABLE", "The engine tables cannot be read", 30);
        }
    }

    static TenantFallbackInstance view(TenantFallbackInventory.Entry e) {
        return new TenantFallbackInstance(e.processInstanceId(), e.definitionKey(), e.engineTenant(),
                e.definitionTenant(), e.finding().code(), e.reliedOnTheFallback(),
                e.claims().stream().map(c -> new TenantClaim(c.variableName(), c.value())).toList());
    }
}
