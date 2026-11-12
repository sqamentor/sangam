// SPDX-License-Identifier: Apache-2.0
package org.sangam.controlplane.placement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.sangam.controlplane.cells.Cell;
import org.sangam.controlplane.cells.CellDirectory;
import org.sangam.controlplane.config.ControlPlaneProperties;
import org.sangam.controlplane.registry.ResidencyRules;
import org.sangam.controlplane.registry.Tenant;
import org.sangam.controlplane.registry.TenantRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Placement policy (SCALE-ARCHITECTURE.md section 5.3), in order:
 * <ol>
 *   <li><b>Dedication (M-016, decision 51):</b> a cell dedicated to one tenant's database hosts only that tenant, and a
 *       tenant recorded with isolation DATABASE goes only to the cell dedicated to it ({@link #dedication}). Moves
 *       obey the same rule (they use {@link #reject}).</li>
 *   <li><b>Hard filters:</b> cell UP, placement open, residency of the cell within the tenant's zone, projected
 *       expected load at most {@code targetUtilisation} x capacity, tenant count below capacity, outbox fill and DB
 *       pool usage below their limits.</li>
 *   <li><b>Hierarchy affinity:</b> prefer the cell of the nearest placed ancestor, then of placed siblings, so a
 *       state's districts and hospitals share a cell and their workflows stay intra-cell (no cross-cell messages).</li>
 *   <li><b>Headroom:</b> most remaining capacity wins (spreads load); ties by cell id (deterministic).</li>
 * </ol>
 */
@Service
public class PlacementService {

    private final TenantRegistry registry;
    private final CellDirectory cells;
    private final ControlPlaneProperties props;

    public PlacementService(TenantRegistry registry, CellDirectory cells, ControlPlaneProperties props) {
        this.registry = registry;
        this.cells = cells;
        this.props = props;
    }

    public record Candidate(Cell cell, int affinity, double headroom, String reason) {
    }

    public List<Candidate> candidates(Tenant t, String excludeCell) {
        List<String> ancestorCells = ancestorCells(t);
        List<Candidate> out = new ArrayList<>();
        for (Cell c : cells.all()) {
            if (c.cellId().equals(excludeCell)) {
                continue;
            }
            String rejected = reject(c, t);
            if (rejected != null) {
                continue;
            }
            double load = registry.expectedLoadOnCell(c.cellId()) + t.expectedStartsPerSec();
            double headroom = c.capacityStartsPerSec() * props.targetUtilisation() - load;
            int idx = ancestorCells.indexOf(c.cellId());
            int affinity = idx < 0 ? Integer.MAX_VALUE : idx;
            out.add(new Candidate(c, affinity, headroom, idx < 0 ? "headroom" : "hierarchy affinity (level " + idx + ")"));
        }
        out.sort(Comparator.comparingInt(Candidate::affinity)
                .thenComparing(Comparator.comparingDouble(Candidate::headroom).reversed())
                .thenComparing(c -> c.cell().cellId()));
        return out;
    }

    /** Null when the cell may host the tenant, else the reason. */
    public String reject(Cell c, Tenant t) {
        String dedicated = dedication(cells.dedicatedTenant(c.cellId()).orElse(null), t.tenantCode(),
                registry.isolation(t.tenantCode()));
        if (dedicated != null) {
            return dedicated;
        }
        if (!Cell.UP.equals(c.status())) {
            return "cell status " + c.status();
        }
        if (!c.placementOpen()) {
            return "placement closed";
        }
        if (!ResidencyRules.cellSatisfies(c.residency(), t.residency())) {
            return "residency " + c.residency() + " outside tenant zone " + t.residency();
        }
        if (c.outboxFillPct() >= props.maxOutboxFillPct()) {
            return "outbox fill " + c.outboxFillPct() + "%";
        }
        if (c.dbPoolUsagePct() >= props.maxDbPoolUsagePct()) {
            return "db pool usage " + c.dbPoolUsagePct() + "%";
        }
        if (registry.countOnCell(c.cellId()) >= c.capacityTenants()) {
            return "tenant capacity reached";
        }
        double load = registry.expectedLoadOnCell(c.cellId()) + t.expectedStartsPerSec();
        if (load > c.capacityStartsPerSec() * props.targetUtilisation()) {
            return "projected load " + load + " starts/s above target utilisation";
        }
        return null;
    }

    /**
     * M-016 dedication rule (decision 51), null when it allows the placement, else the reason:
     * <ul>
     *   <li>a cell dedicated to a tenant hosts that tenant only;</li>
     *   <li>a tenant recorded with isolation {@value TenantRegistry#DATABASE} goes only to the cell dedicated to it.</li>
     * </ul>
     * {@value TenantRegistry#SHARED} and {@value TenantRegistry#SCHEMA} tenants go to shared cells as before (the
     * meaning of SCHEMA is owner question Q-W3H-5). Tenant codes are compared upper-case, as the registry stores them.
     */
    public static String dedication(String cellDedicatedTenant, String tenantCode, String isolation) {
        boolean dedicatedCell = cellDedicatedTenant != null && !cellDedicatedTenant.isBlank();
        if (dedicatedCell && !cellDedicatedTenant.trim().equalsIgnoreCase(tenantCode)) {
            return "cell dedicated to tenant " + cellDedicatedTenant.trim();
        }
        if (TenantRegistry.DATABASE.equals(isolation) && !dedicatedCell) {
            return "tenant isolation DATABASE needs the cell dedicated to it";
        }
        return null;
    }

    @Transactional
    public Tenant place(String tenantCode, String actor) {
        Tenant t = registry.get(tenantCode);
        if (t.cellId() != null) {
            return t;
        }
        boolean database = TenantRegistry.DATABASE.equals(registry.isolation(t.tenantCode()));
        Candidate best = candidates(t, null).stream().findFirst()
                .orElseThrow(() -> new NoCapacity(database
                        ? "no cell dedicated to " + t.tenantCode() + " can host it (isolation DATABASE): register the"
                                + " tenant's dedicated cell (sangam.tenancy.dedicated-tenant=" + t.tenantCode() + ") first"
                        : "no cell can host " + tenantCode + " (residency " + t.residency() + "): add a cell in that zone"));
        return registry.assignCell(t.tenantCode(), best.cell().cellId(), t.routeEpoch(), actor, "placement: " + best.reason());
    }

    public Optional<Candidate> moveTarget(Tenant t) {
        return candidates(t, t.cellId()).stream().findFirst();
    }

    private List<String> ancestorCells(Tenant t) {
        List<String> out = new ArrayList<>();
        String parent = t.parentCode();
        while (parent != null) {
            Optional<Tenant> p = registry.find(parent);
            if (p.isEmpty()) {
                break;
            }
            if (p.get().cellId() != null && !out.contains(p.get().cellId())) {
                out.add(p.get().cellId());
            }
            for (Tenant sib : registry.children(parent)) {
                if (sib.cellId() != null && !out.contains(sib.cellId())) {
                    out.add(sib.cellId());
                }
            }
            parent = p.get().parentCode();
        }
        return out;
    }

    /** 409 marker. */
    public static final class NoCapacity extends RuntimeException {
        public NoCapacity(String m) {
            super(m);
        }
    }
}
