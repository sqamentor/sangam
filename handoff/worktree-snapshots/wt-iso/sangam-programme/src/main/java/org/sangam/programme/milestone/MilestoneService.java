// SPDX-License-Identifier: Apache-2.0
package org.sangam.programme.milestone;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.programme.ProgrammeException;
import org.sangam.programme.programme.ProgrammeService;
import org.sangam.programme.support.Db;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * N15 {@code Milestone}: planned and actual dates, weight, percent complete, dependencies and evidence (references to
 * field-capture records, content items or geo-tagged photos from N05; stored as opaque refs). Delay detection marks
 * overdue milestones DELAYED with the delay in days; weighted physical progress is the weight-averaged percent of the
 * non-dropped milestones.
 */
public final class MilestoneService {

    public enum Status { NOT_STARTED, IN_PROGRESS, DONE, DELAYED, DROPPED }

    public record Milestone(String id, String programmeId, String orgUnit, String name, int seq, LocalDate plannedStart, LocalDate plannedEnd,
                            LocalDate actualStart, LocalDate actualEnd, BigDecimal weight, BigDecimal percentComplete, Status status,
                            List<Object> dependsOn, List<Object> evidence, Integer delayDays, int rowVersion, String updatedBy,
                            Instant updatedAt) {
    }

    public record Progress(String milestoneId, int expectedVersion, BigDecimal percentComplete, LocalDate actualStart, LocalDate actualEnd,
                           List<Object> addEvidence, boolean drop, String reason) {
    }

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final Dialect dialect;
    private final ProgrammeService programmes;

    public MilestoneService(Dialect dialect, ProgrammeService programmes) {
        this.dialect = dialect;
        this.programmes = programmes;
    }

    public Milestone plan(Connection c, String tenant, String programmeId, String orgUnit, String name, int seq, LocalDate plannedStart,
                          LocalDate plannedEnd, BigDecimal weight, List<String> dependsOn, String actor) {
        String t = Db.tenant(tenant);
        programmes.get(c, t, programmeId);
        Db.requireText(name, "milestone name");
        if (plannedEnd == null) {
            throw ProgrammeException.invalid("planned end date is required");
        }
        if (plannedStart != null && plannedEnd.isBefore(plannedStart)) {
            throw ProgrammeException.invalid("planned end before planned start");
        }
        BigDecimal w = weight == null ? BigDecimal.ONE : weight;
        if (w.signum() < 0) {
            throw ProgrammeException.invalid("weight must not be negative");
        }
        for (String d : dependsOn == null ? List.<String>of() : dependsOn) {
            Milestone dep = get(c, t, d);
            if (!dep.programmeId().equals(programmeId)) {
                throw ProgrammeException.invalid("dependency " + d + " belongs to another programme");
            }
        }
        String id = Db.newId();
        Jdbc.update(c, "INSERT INTO PG_MILESTONE(tenant_id, milestone_id, programme_id, org_unit, name, seq, planned_start, planned_end, weight,"
                        + " percent_complete, status, depends_on_json, evidence_json, row_version, updated_by, updated_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,1,?," + dialect.now() + ")",
                t, id, programmeId, orgUnit, name, seq, Db.sqlDate(plannedStart), Db.sqlDate(plannedEnd), w, BigDecimal.ZERO,
                Status.NOT_STARTED.name(), Db.json(dependsOn == null ? List.of() : dependsOn), "[]", actor);
        return get(c, t, id);
    }

    /** Records progress. DONE requires 100 percent, an actual end date and every dependency DONE. */
    public Milestone update(Connection c, String tenant, Progress p, String actor) {
        String t = Db.tenant(tenant);
        Milestone m = get(c, t, p.milestoneId());
        if (m.status() == Status.DONE || m.status() == Status.DROPPED) {
            throw ProgrammeException.state("milestone is " + m.status());
        }
        Status status;
        BigDecimal pct = p.percentComplete() == null ? m.percentComplete() : p.percentComplete();
        if (pct.signum() < 0 || pct.compareTo(HUNDRED) > 0) {
            throw ProgrammeException.invalid("percent complete must be 0..100");
        }
        LocalDate as = p.actualStart() == null ? m.actualStart() : p.actualStart();
        LocalDate ae = p.actualEnd() == null ? m.actualEnd() : p.actualEnd();
        if (p.drop()) {
            Db.requireText(p.reason(), "reason for dropping a milestone");
            status = Status.DROPPED;
        } else if (pct.compareTo(HUNDRED) == 0 && ae != null) {
            for (Object d : m.dependsOn()) {
                Milestone dep = get(c, t, String.valueOf(d));
                if (dep.status() != Status.DONE && dep.status() != Status.DROPPED) {
                    throw ProgrammeException.state("dependency " + dep.name() + " is not done");
                }
            }
            status = Status.DONE;
        } else if (pct.signum() > 0 || as != null) {
            status = m.status() == Status.DELAYED ? Status.DELAYED : Status.IN_PROGRESS;
        } else {
            status = m.status();
        }
        if (as != null && ae != null && ae.isBefore(as)) {
            throw ProgrammeException.invalid("actual end before actual start");
        }
        List<Object> evidence = new ArrayList<>(m.evidence());
        if (p.addEvidence() != null) {
            evidence.addAll(p.addEvidence());
        }
        Integer delay = status == Status.DONE && ae.isAfter(m.plannedEnd()) ? (int) (ae.toEpochDay() - m.plannedEnd().toEpochDay()) : m.delayDays();
        Db.requireOne(Jdbc.update(c, "UPDATE PG_MILESTONE SET percent_complete=?, actual_start=?, actual_end=?, status=?, evidence_json=?, delay_days=?,"
                        + " row_version=row_version+1, updated_by=?, updated_at=" + dialect.now() + " WHERE tenant_id=? AND milestone_id=? AND row_version=?",
                pct, Db.sqlDate(as), Db.sqlDate(ae), status.name(), Db.json(evidence), delay, actor, t, m.id(), p.expectedVersion()), "milestone");
        return get(c, t, m.id());
    }

    /** Marks open milestones whose planned end is before {@code asOf} as DELAYED; returns the newly delayed ones. */
    public List<Milestone> detectDelays(Connection c, String tenant, LocalDate asOf, int limit) {
        String t = Db.tenant(tenant);
        List<Milestone> late = Jdbc.query(c, "SELECT * FROM PG_MILESTONE WHERE tenant_id=? AND status IN ('NOT_STARTED','IN_PROGRESS','DELAYED')"
                + " AND planned_end<? ORDER BY planned_end", MilestoneService::map, t, Db.sqlDate(asOf));
        List<Milestone> newly = new ArrayList<>();
        for (Milestone m : late) {
            if (newly.size() >= limit) {
                break;
            }
            int days = (int) (asOf.toEpochDay() - m.plannedEnd().toEpochDay());
            Jdbc.update(c, "UPDATE PG_MILESTONE SET status='DELAYED', delay_days=?, row_version=row_version+1, updated_at=" + dialect.now()
                    + " WHERE tenant_id=? AND milestone_id=?", days, t, m.id());
            if (m.status() != Status.DELAYED) {
                newly.add(get(c, t, m.id()));
            }
        }
        return newly;
    }

    /** Weighted physical progress (percent) of a programme, optionally for one org unit; null when nothing is planned. */
    public BigDecimal physicalProgress(Connection c, String tenant, String programmeId, String orgUnitOrNull) {
        List<Milestone> ms = list(c, tenant, programmeId, orgUnitOrNull);
        BigDecimal num = BigDecimal.ZERO;
        BigDecimal den = BigDecimal.ZERO;
        for (Milestone m : ms) {
            if (m.status() == Status.DROPPED) {
                continue;
            }
            num = num.add(m.weight().multiply(m.percentComplete()));
            den = den.add(m.weight());
        }
        return den.signum() == 0 ? null : num.divide(den, MathContext.DECIMAL64).setScale(2, RoundingMode.HALF_UP);
    }

    /** Planned progress at {@code asOf}: weight share of milestones whose planned end has passed (percent). */
    public BigDecimal plannedProgress(Connection c, String tenant, String programmeId, String orgUnitOrNull, LocalDate asOf) {
        List<Milestone> ms = list(c, tenant, programmeId, orgUnitOrNull);
        BigDecimal num = BigDecimal.ZERO;
        BigDecimal den = BigDecimal.ZERO;
        for (Milestone m : ms) {
            if (m.status() == Status.DROPPED) {
                continue;
            }
            den = den.add(m.weight());
            if (!m.plannedEnd().isAfter(asOf)) {
                num = num.add(m.weight().multiply(HUNDRED));
            }
        }
        return den.signum() == 0 ? null : num.divide(den, MathContext.DECIMAL64).setScale(2, RoundingMode.HALF_UP);
    }

    public Milestone get(Connection c, String tenant, String id) {
        return Jdbc.one(c, "SELECT * FROM PG_MILESTONE WHERE tenant_id=? AND milestone_id=?", MilestoneService::map, Db.tenant(tenant), id)
                .orElseThrow(() -> ProgrammeException.notFound("milestone", id));
    }

    public List<Milestone> list(Connection c, String tenant, String programmeId, String orgUnitOrNull) {
        return orgUnitOrNull == null
                ? Jdbc.query(c, "SELECT * FROM PG_MILESTONE WHERE tenant_id=? AND programme_id=? ORDER BY seq, planned_end", MilestoneService::map,
                Db.tenant(tenant), programmeId)
                : Jdbc.query(c, "SELECT * FROM PG_MILESTONE WHERE tenant_id=? AND programme_id=? AND org_unit=? ORDER BY seq, planned_end",
                MilestoneService::map, Db.tenant(tenant), programmeId, orgUnitOrNull);
    }

    public Map<Status, Long> summary(Connection c, String tenant, String programmeId) {
        Map<Status, Long> out = new java.util.EnumMap<>(Status.class);
        for (Status s : Status.values()) {
            out.put(s, 0L);
        }
        Jdbc.query(c, "SELECT status, COUNT(*) AS n FROM PG_MILESTONE WHERE tenant_id=? AND programme_id=? GROUP BY status",
                rs -> out.put(Status.valueOf(rs.getString("status")), rs.getLong("n")), Db.tenant(tenant), programmeId);
        return out;
    }

    static Milestone map(ResultSet rs) throws SQLException {
        int delay = rs.getInt("delay_days");
        Integer d = rs.wasNull() ? null : delay;
        return new Milestone(rs.getString("milestone_id"), rs.getString("programme_id"), rs.getString("org_unit"), rs.getString("name"),
                rs.getInt("seq"), Db.date(rs, "planned_start"), Db.date(rs, "planned_end"), Db.date(rs, "actual_start"), Db.date(rs, "actual_end"),
                rs.getBigDecimal("weight"), rs.getBigDecimal("percent_complete"), Status.valueOf(rs.getString("status")),
                Db.list(rs.getString("depends_on_json")), Db.list(rs.getString("evidence_json")), d, rs.getInt("row_version"),
                rs.getString("updated_by"), Jdbc.instant(rs, "updated_at"));
    }
}
