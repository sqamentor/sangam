// SPDX-License-Identifier: Apache-2.0
package org.sangam.controlplane.cells;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import org.sangam.cell.id.CellId;
import org.sangam.controlplane.audit.ControlPlaneAudit;
import org.sangam.controlplane.config.ControlPlaneProperties;
import org.sangam.controlplane.placement.PlacementService;
import org.sangam.controlplane.registry.TenantRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cell directory with health and capacity (SCALE-ARCHITECTURE.md section 5.2). Registration is an idempotent upsert
 * keyed by cell id; heartbeats update one row per cell (one writer per cell, every 10 s: no contention).
 */
@Service
public class CellDirectory {

    private static final String COLS = "cell_id, region, jurisdiction, residency, api_base_url, admin_base_url, db_vendor,"
            + " capacity_starts_per_sec, capacity_tenants, status, placement_open, starts_per_sec, p99_command_ms,"
            + " active_nodes, db_pool_usage_pct, outbox_fill_pct, tenants_reported, version, last_heartbeat_ms,"
            + " registered_at_ms, dedicated_tenant";

    private final JdbcTemplate jdbc;
    private final ControlPlaneAudit audit;
    private final ControlPlaneProperties props;
    private final RowMapper<Cell> mapper = this::map;

    public CellDirectory(JdbcTemplate jdbc, ControlPlaneAudit audit, ControlPlaneProperties props) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.props = props;
    }

    public record Registration(String region, String jurisdiction, String residency, String apiBaseUrl,
                               String adminBaseUrl, String dbVendor, Integer capacityStartsPerSec,
                               Integer capacityTenants, String version, String dedicatedTenant) {
    }

    /**
     * M-016: the stored form of a registration's {@code dedicatedTenant}: null for a shared cell, else trimmed and
     * upper-case like the registry's tenant codes, at most 64 characters ({@code CP_CELL.dedicated_tenant}).
     */
    static String dedication(String requested) {
        if (requested == null || requested.isBlank()) {
            return null;
        }
        String t = requested.trim().toUpperCase(java.util.Locale.ROOT);
        if (t.length() > 64 || "*".equals(t)) {
            throw new IllegalArgumentException("dedicatedTenant must be one tenant code of at most 64 characters");
        }
        return t;
    }

    /**
     * M-016: a cell's dedication may change only when every tenant it hosts satisfies the new one
     * ({@link PlacementService#dedication}); otherwise a re-registration would leave a foreign tenant on a dedicated
     * cell, or a DATABASE tenant on a shared one.
     */
    private void requireHostedTenantsAllow(String cellId, String dedication) {
        List<String> conflicts = jdbc.query("SELECT tenant_code, isolation FROM CP_TENANT WHERE cell_id = ?"
                        + " AND status <> 'RETIRED' ORDER BY tenant_code",
                (rs, i) -> {
                    String why = PlacementService.dedication(dedication, rs.getString(1), rs.getString(2));
                    return why == null ? null : rs.getString(1) + " (" + why + ")";
                }, cellId).stream().filter(java.util.Objects::nonNull).toList();
        if (!conflicts.isEmpty()) {
            throw new IllegalStateException("cell " + cellId + " cannot become " + (dedication == null ? "shared"
                    : "dedicated to " + dedication) + " while it hosts " + conflicts + "; move them first");
        }
    }

    public record Heartbeat(String status, double startsPerSec, double p99CommandMs, int activeNodes,
                            double dbPoolUsagePct, double outboxFillPct, long tenants, String version) {
    }

    @Transactional
    public Cell register(String cellId, Registration r, String actor) {
        String id = CellId.of(cellId).value();
        if (r.apiBaseUrl() == null || r.apiBaseUrl().isBlank() || r.residency() == null || r.jurisdiction() == null) {
            throw new IllegalArgumentException("apiBaseUrl, jurisdiction and residency are required");
        }
        long now = System.currentTimeMillis();
        int cap = r.capacityStartsPerSec() == null ? 500 : r.capacityStartsPerSec();
        int capT = r.capacityTenants() == null ? 2000 : r.capacityTenants();
        String dedication = dedication(r.dedicatedTenant());
        Optional<Cell> existing = find(id);
        if (existing.isPresent()) {
            if (Cell.RETIRED.equals(existing.get().status())) {
                throw new IllegalStateException("cell " + id + " is retired; cell ids are never reused");
            }
            if (!existing.get().residency().equalsIgnoreCase(r.residency())) {
                throw new IllegalStateException("cell " + id + " residency is immutable (" + existing.get().residency() + ")");
            }
            String before = existing.get().dedicatedTenant();
            boolean changed = !java.util.Objects.equals(before, dedication);
            if (changed) {
                requireHostedTenantsAllow(id, dedication);
            }
            jdbc.update("UPDATE CP_CELL SET region = ?, jurisdiction = ?, api_base_url = ?, admin_base_url = ?, db_vendor = ?,"
                            + " capacity_starts_per_sec = ?, capacity_tenants = ?, version = ?, last_heartbeat_ms = ?,"
                            + " dedicated_tenant = ? WHERE cell_id = ?",
                    r.region(), r.jurisdiction().toUpperCase(), r.apiBaseUrl(), r.adminBaseUrl(), r.dbVendor(), cap, capT,
                    r.version(), now, dedication, id);
            if (changed) {
                audit.record(actor, "CELL_DEDICATION", "cell:" + id, "from=" + before + " to=" + dedication);
            }
        } else {
            jdbc.update("INSERT INTO CP_CELL(" + COLS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    id, r.region() == null ? id : r.region(), r.jurisdiction().toUpperCase(), r.residency().toUpperCase(),
                    r.apiBaseUrl(), r.adminBaseUrl(), r.dbVendor(), cap, capT, Cell.UP, 0, 0.0, 0.0, 0, 0.0, 0.0, 0L,
                    r.version(), now, now, dedication);
            audit.record(actor, "CELL_REGISTERED", "cell:" + id, "residency=" + r.residency() + " capacity=" + cap
                    + (dedication == null ? "" : " dedicatedTenant=" + dedication));
        }
        return get(id);
    }

    public void heartbeat(String cellId, Heartbeat h) {
        String status = h.status() == null ? Cell.UP : h.status().toUpperCase();
        if (!List.of(Cell.UP, Cell.DEGRADED, Cell.DRAINING).contains(status)) {
            throw new IllegalArgumentException("heartbeat status must be UP, DEGRADED or DRAINING");
        }
        int n = jdbc.update("UPDATE CP_CELL SET status = CASE WHEN status = 'RETIRED' THEN status ELSE ? END,"
                        + " starts_per_sec = ?, p99_command_ms = ?, active_nodes = ?, db_pool_usage_pct = ?, outbox_fill_pct = ?,"
                        + " tenants_reported = ?, version = ?, last_heartbeat_ms = ? WHERE cell_id = ?",
                status, h.startsPerSec(), h.p99CommandMs(), h.activeNodes(), h.dbPoolUsagePct(), h.outboxFillPct(),
                h.tenants(), h.version(), System.currentTimeMillis(), CellId.of(cellId).value());
        if (n != 1) {
            throw new TenantRegistry.NotFound("cell " + cellId + " is not registered");
        }
    }

    public Optional<Cell> find(String cellId) {
        return jdbc.query("SELECT " + COLS + " FROM CP_CELL WHERE cell_id = ?", mapper, cellId).stream().findFirst();
    }

    public Cell get(String cellId) {
        return find(cellId).orElseThrow(() -> new TenantRegistry.NotFound("cell " + cellId + " not found"));
    }

    public List<Cell> all() {
        return jdbc.query("SELECT " + COLS + " FROM CP_CELL ORDER BY cell_id", mapper);
    }

    /**
     * M-016 (decision 51): the tenant this cell is dedicated to ({@code CP_CELL.dedicated_tenant}), or empty for a
     * shared cell. A dedicated cell is a server instance bound to one tenant's database.
     */
    public Optional<String> dedicatedTenant(String cellId) {
        return jdbc.query("SELECT dedicated_tenant FROM CP_CELL WHERE cell_id = ?", (rs, i) -> rs.getString(1), cellId)
                .stream().filter(t -> t != null && !t.isBlank()).findFirst();
    }

    @Transactional
    public void setPlacementOpen(String cellId, boolean open, String actor) {
        jdbc.update("UPDATE CP_CELL SET placement_open = ? WHERE cell_id = ?", open ? 1 : 0, cellId);
        audit.record(actor, open ? "CELL_PLACEMENT_OPEN" : "CELL_PLACEMENT_CLOSED", "cell:" + cellId, null);
    }

    @Transactional
    public void retire(String cellId, TenantRegistry registry, String actor) {
        if (!registry.onCell(cellId).isEmpty()) {
            throw new IllegalStateException("cell " + cellId + " still hosts tenants; move them first");
        }
        jdbc.update("UPDATE CP_CELL SET status = 'RETIRED', placement_open = 0 WHERE cell_id = ?", cellId);
        audit.record(actor, "CELL_RETIRED", "cell:" + cellId, null);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private Cell map(ResultSet rs, int i) throws SQLException {
        String status = rs.getString(10);
        long last = rs.getLong(19);
        if (!Cell.RETIRED.equals(status) && System.currentTimeMillis() - last > props.heartbeatTimeout().toMillis()) {
            status = Cell.DOWN;
        }
        return new Cell(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getInt(8), rs.getInt(9), status, rs.getInt(11) == 1,
                rs.getDouble(12), rs.getDouble(13), rs.getInt(14), rs.getDouble(15), rs.getDouble(16), rs.getLong(17),
                rs.getString(18), last, rs.getLong(20), blankToNull(rs.getString(21)));
    }
}
