// SPDX-License-Identifier: Apache-2.0
package org.sangam.controlplane.registry;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import org.sangam.controlplane.audit.ControlPlaneAudit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant registry (SCALE-ARCHITECTURE.md section 5.1). Tenant codes are globally unique and immutable; they are the
 * prefix of business keys (section 6), so they never change on a move. Every route change bumps {@code route_epoch}.
 */
@Service
public class TenantRegistry {

    private static final Pattern CODE = Pattern.compile("[A-Z0-9][A-Z0-9_-]{0,63}");
    private static final String COLS = "tenant_code, parent_code, path, depth, level_kind, name, jurisdiction, residency,"
            + " sector, expected_starts_per_sec, cell_id, status, route_epoch, created_at_ms, updated_at_ms";

    static final RowMapper<Tenant> MAPPER = TenantRegistry::map;

    private final JdbcTemplate jdbc;
    private final ControlPlaneAudit audit;

    public TenantRegistry(JdbcTemplate jdbc, ControlPlaneAudit audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record NewTenant(String tenantCode, String parentCode, String levelKind, String name, String jurisdiction,
                            String residency, String sector, Double expectedStartsPerSec) {
    }

    @Transactional
    public Tenant create(NewTenant n, String actor) {
        String code = normalise(n.tenantCode());
        if (find(code).isPresent()) {
            throw new IllegalStateException("tenant " + code + " already exists");
        }
        Tenant parent = null;
        if (n.parentCode() != null && !n.parentCode().isBlank()) {
            parent = find(normalise(n.parentCode()))
                    .orElseThrow(() -> new IllegalArgumentException("parent tenant " + n.parentCode() + " not found"));
        }
        String path = parent == null ? code : parent.path() + "/" + code;
        if (path.length() > 512) {
            throw new IllegalArgumentException("hierarchy path longer than 512 characters");
        }
        int depth = parent == null ? 0 : parent.depth() + 1;
        String jurisdiction = firstNonBlank(n.jurisdiction(), parent == null ? null : parent.jurisdiction());
        String residency = firstNonBlank(n.residency(), parent == null ? null : parent.residency());
        if (jurisdiction == null || residency == null) {
            throw new IllegalArgumentException("jurisdiction and residency are required for a root tenant");
        }
        if (parent != null && !ResidencyRules.within(residency, parent.residency())) {
            throw new IllegalArgumentException("residency " + residency + " is not within the parent's " + parent.residency());
        }
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO CP_TENANT(" + COLS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                code, parent == null ? null : parent.tenantCode(), path, depth,
                n.levelKind() == null ? "ORG" : n.levelKind().toUpperCase(Locale.ROOT), n.name() == null ? code : n.name(),
                jurisdiction.toUpperCase(Locale.ROOT), residency.toUpperCase(Locale.ROOT), n.sector(),
                n.expectedStartsPerSec() == null ? 0.0 : n.expectedStartsPerSec(), null, Tenant.ACTIVE, 0L, now, now);
        audit.record(actor, "TENANT_CREATED", "tenant:" + code, "path=" + path + " residency=" + residency);
        return get(code);
    }

    public Optional<Tenant> find(String code) {
        List<Tenant> l = jdbc.query("SELECT " + COLS + " FROM CP_TENANT WHERE tenant_code = ?", MAPPER, normalise(code));
        return l.stream().findFirst();
    }

    public Tenant get(String code) {
        return find(code).orElseThrow(() -> new NotFound("tenant " + code + " not found"));
    }

    public List<Tenant> children(String code) {
        return jdbc.query("SELECT " + COLS + " FROM CP_TENANT WHERE parent_code = ? ORDER BY tenant_code", MAPPER,
                normalise(code));
    }

    public List<Tenant> onCell(String cellId) {
        return jdbc.query("SELECT " + COLS + " FROM CP_TENANT WHERE cell_id = ? AND status <> 'RETIRED'", MAPPER, cellId);
    }

    /** All routable tenants changed after {@code sinceEpochMs} (gateway bulk cache refresh). */
    public List<Tenant> routesChangedSince(long sinceMs) {
        return jdbc.query("SELECT " + COLS + " FROM CP_TENANT WHERE updated_at_ms > ? AND cell_id IS NOT NULL", MAPPER, sinceMs);
    }

    public double expectedLoadOnCell(String cellId) {
        Double d = jdbc.queryForObject("SELECT COALESCE(SUM(expected_starts_per_sec), 0) FROM CP_TENANT"
                + " WHERE cell_id = ? AND status <> 'RETIRED'", Double.class, cellId);
        return d == null ? 0.0 : d;
    }

    public long countOnCell(String cellId) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM CP_TENANT WHERE cell_id = ? AND status <> 'RETIRED'",
                Long.class, cellId);
        return n == null ? 0 : n;
    }

    /** Assigns (or re-assigns at cut-over) the cell. Compare-and-set on the route epoch, so two moves cannot race. */
    @Transactional
    public Tenant assignCell(String code, String cellId, long expectedEpoch, String actor, String reason) {
        int n = jdbc.update("UPDATE CP_TENANT SET cell_id = ?, status = 'ACTIVE', route_epoch = route_epoch + 1,"
                        + " updated_at_ms = ? WHERE tenant_code = ? AND route_epoch = ?",
                cellId, System.currentTimeMillis(), normalise(code), expectedEpoch);
        if (n != 1) {
            throw new IllegalStateException("route of " + code + " changed concurrently (epoch " + expectedEpoch + ")");
        }
        audit.record(actor, "ROUTE_ASSIGNED", "tenant:" + code, "cell=" + cellId + " reason=" + reason);
        return get(code);
    }

    @Transactional
    public void setStatus(String code, String status, String actor) {
        jdbc.update("UPDATE CP_TENANT SET status = ?, route_epoch = route_epoch + 1, updated_at_ms = ? WHERE tenant_code = ?",
                status, System.currentTimeMillis(), normalise(code));
        audit.record(actor, "TENANT_STATUS", "tenant:" + code, status);
    }

    static String normalise(String code) {
        if (code == null) {
            throw new IllegalArgumentException("tenant code is required");
        }
        String c = code.trim().toUpperCase(Locale.ROOT);
        if (!CODE.matcher(c).matches()) {
            throw new IllegalArgumentException("invalid tenant code '" + code + "' (A-Z, 0-9, _ and -, max 64)");
        }
        return c;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : (b != null && !b.isBlank() ? b : null);
    }

    private static Tenant map(ResultSet rs, int i) throws SQLException {
        return new Tenant(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getDouble(10), rs.getString(11),
                rs.getString(12), rs.getLong(13), rs.getLong(14), rs.getLong(15));
    }

    /** 404 marker. */
    public static final class NotFound extends RuntimeException {
        public NotFound(String m) {
            super(m);
        }
    }
}
