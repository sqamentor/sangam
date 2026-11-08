// SPDX-License-Identifier: Apache-2.0
package org.sangam.programme.dashboard;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Outbox;
import org.sangam.programme.ports.ProgrammePorts.DashboardPort;
import org.sangam.programme.ports.ProgrammePorts.DashboardRow;
import org.sangam.programme.ports.ProgrammePorts.HierarchyPort;
import org.sangam.programme.progress.ProgressService;
import org.sangam.programme.progress.VarianceService;
import org.sangam.programme.rollup.RollupService;
import org.sangam.programme.support.Db;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Dashboard feed for C14 (N15 "feeding C14 dashboards from the read model"). A snapshot per programme, period and
 * node holds physical / financial progress, the gap, return pendency and open alerts plus every indicator's period and
 * cumulative value. Snapshots are stored in {@code PG_DASHBOARD_SNAPSHOT} (query side) and, when their content hash
 * changes, published on the outbox destination {@link #DESTINATION} and through the {@link DashboardPort} (push side).
 */
public final class DashboardFeed {

    public static final Outbox.Destination DESTINATION = Outbox.Destination.unordered("programme-dashboard");

    public record Snapshot(String programmeId, String periodId, String orgUnit, BigDecimal physicalPct, BigDecimal financialPct,
                           BigDecimal gapPct, int returnsDue, int returnsAccepted, int returnsOverdue, int openAlerts,
                           Map<String, Object> payload, String contentHash, Instant computedAt) {
    }

    private static final int MAX_NODES = 20_000;

    private final Dialect dialect;
    private final ProgressService progress;
    private final RollupService rollup;
    private final VarianceService variance;
    private final HierarchyPort hierarchy;
    private final Outbox outbox;
    private final DashboardPort port;

    public DashboardFeed(Dialect dialect, ProgressService progress, RollupService rollup, VarianceService variance, HierarchyPort hierarchy,
                         Outbox outbox, DashboardPort port) {
        this.dialect = dialect;
        this.progress = progress;
        this.rollup = rollup;
        this.variance = variance;
        this.hierarchy = hierarchy;
        this.outbox = outbox;
        this.port = port == null ? DashboardPort.NONE : port;
    }

    /** Recomputes the snapshot of one node; publishes it when it changed. */
    public Snapshot refresh(Connection c, String tenant, String programmeId, String periodId, String orgUnit) {
        String t = Db.tenant(tenant);
        ProgressService.Progress pr = progress.progress(c, t, programmeId, periodId, orgUnit);
        Set<String> nodes = subtree(c, t, orgUnit);
        int due = 0;
        int accepted = 0;
        int overdue = 0;
        java.time.LocalDate today = Db.today(c, dialect);
        List<String> list = new ArrayList<>(nodes);
        for (int i = 0; i < list.size(); i += 500) {
            List<String> chunk = list.subList(i, Math.min(list.size(), i + 500));
            List<Object> params = new ArrayList<>(List.of(t, programmeId, periodId));
            params.addAll(chunk);
            for (Object[] row : Jdbc.query(c, "SELECT state, due_on FROM PG_RETURN WHERE tenant_id=? AND programme_id=? AND period_id=? AND org_unit IN "
                    + Db.in(chunk.size()), rs -> new Object[]{rs.getString("state"), Db.date(rs, "due_on")}, params.toArray())) {
                String st = (String) row[0];
                due++;
                if ("ACCEPTED".equals(st) || "FROZEN".equals(st)) {
                    accepted++;
                } else if (("DUE".equals(st) || "DRAFT".equals(st) || "RETURNED".equals(st)) && row[1] != null
                        && today.isAfter((java.time.LocalDate) row[1])) {
                    overdue++;
                }
            }
        }
        int alerts = (int) variance.openCount(c, t, programmeId, orgUnit);
        Map<String, Object> indicators = new LinkedHashMap<>();
        for (RollupService.Achievement a : rollup.atNode(c, t, programmeId, periodId, orgUnit)) {
            indicators.put(a.indicatorKey(), Db.kv("period", plain(a.periodValue()), "cumulative", plain(a.cumulativeValue()),
                    "source", a.source().name()));
        }
        Map<String, Object> payload = Db.kv("indicators", indicators, "plannedPhysicalPct", plain(pr.plannedPhysicalPct()),
                "releasedPct", plain(pr.releasedPct()), "utilisationOfReleasedPct", plain(pr.utilisationOfReleasedPct()),
                "allocated", plain(pr.allocated()), "released", plain(pr.released()), "expended", plain(pr.expended()),
                "physicalBasis", pr.physicalBasis());
        Map<String, Object> material = Db.kv("physical", plain(pr.physicalPct()), "financial", plain(pr.financialPct()), "gap", plain(pr.gapPct()),
                "due", due, "accepted", accepted, "overdue", overdue, "alerts", alerts, "payload", payload);
        String hash = Db.hashOf(material);
        Optional<Snapshot> old = get(c, t, programmeId, periodId, orgUnit);
        if (old.isPresent() && old.get().contentHash().equals(hash)) {
            return old.get();
        }
        Jdbc.update(c, "DELETE FROM PG_DASHBOARD_SNAPSHOT WHERE tenant_id=? AND programme_id=? AND period_id=? AND org_unit=?",
                t, programmeId, periodId, orgUnit);
        Jdbc.update(c, "INSERT INTO PG_DASHBOARD_SNAPSHOT(tenant_id, programme_id, period_id, org_unit, physical_pct, financial_pct, gap_pct,"
                        + " returns_due, returns_accepted, returns_overdue, open_alerts, payload_json, content_hash, computed_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?," + dialect.now() + ")",
                t, programmeId, periodId, orgUnit, pr.physicalPct(), pr.financialPct(), pr.gapPct(), due, accepted, overdue, alerts,
                Db.json(payload), hash);
        DashboardRow row = new DashboardRow(t, programmeId, periodId, orgUnit, pr.physicalPct(), pr.financialPct(), pr.gapPct(), due, accepted,
                overdue, alerts, indicators);
        if (outbox != null) {
            outbox.append(c, t, "dashboard:" + programmeId + ":" + periodId + ":" + orgUnit, "programme.dashboard.snapshot",
                    Db.json(Db.kv("programmeId", programmeId, "periodId", periodId, "orgUnit", orgUnit, "contentHash", hash, "data", material)),
                    List.of(DESTINATION));
        }
        port.publish(c, row);
        return get(c, t, programmeId, periodId, orgUnit).orElseThrow();
    }

    /** Refreshes {@code rootNode} and every node below it (bottom-up order is not needed: snapshots read rolled-up rows). */
    public int refreshTree(Connection c, String tenant, String programmeId, String periodId, String rootNode) {
        int n = 0;
        for (String node : subtree(c, Db.tenant(tenant), rootNode)) {
            refresh(c, tenant, programmeId, periodId, node);
            n++;
        }
        return n;
    }

    public Optional<Snapshot> get(Connection c, String tenant, String programmeId, String periodId, String orgUnit) {
        return Jdbc.one(c, "SELECT * FROM PG_DASHBOARD_SNAPSHOT WHERE tenant_id=? AND programme_id=? AND period_id=? AND org_unit=?",
                DashboardFeed::map, Db.tenant(tenant), programmeId, periodId, orgUnit);
    }

    /** Snapshots of the children of {@code node} (drill-down tiles). */
    public List<Snapshot> children(Connection c, String tenant, String programmeId, String periodId, String node) {
        List<Snapshot> out = new ArrayList<>();
        for (String k : hierarchy.children(c, Db.tenant(tenant), node)) {
            get(c, tenant, programmeId, periodId, k).ifPresent(out::add);
        }
        return out;
    }

    private Set<String> subtree(Connection c, String t, String root) {
        Set<String> out = new LinkedHashSet<>();
        Deque<String> q = new ArrayDeque<>(List.of(root));
        while (!q.isEmpty() && out.size() < MAX_NODES) {
            String n = q.poll();
            if (out.add(n)) {
                q.addAll(hierarchy.children(c, t, n));
            }
        }
        return out;
    }

    private static String plain(org.sangam.core.money.Money v) {
        return v == null ? null : v.toPlainString();
    }

    private static String plain(BigDecimal v) {
        return v == null ? null : v.stripTrailingZeros().toPlainString();
    }

    static Snapshot map(ResultSet rs) throws SQLException {
        return new Snapshot(rs.getString("programme_id"), rs.getString("period_id"), rs.getString("org_unit"), rs.getBigDecimal("physical_pct"),
                rs.getBigDecimal("financial_pct"), rs.getBigDecimal("gap_pct"), rs.getInt("returns_due"), rs.getInt("returns_accepted"),
                rs.getInt("returns_overdue"), rs.getInt("open_alerts"), Db.map(rs.getString("payload_json")), rs.getString("content_hash"),
                Jdbc.instant(rs, "computed_at"));
    }
}
