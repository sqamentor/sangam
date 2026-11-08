// SPDX-License-Identifier: Apache-2.0
package org.sangam.programme.target;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.programme.ProgrammeException;
import org.sangam.programme.config.IndicatorDefinition;
import org.sangam.programme.indicator.IndicatorRegistry;
import org.sangam.programme.period.PeriodService;
import org.sangam.programme.ports.ProgrammePorts.HierarchyPort;
import org.sangam.programme.programme.ProgrammeService;
import org.sangam.programme.support.Db;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * N15 {@code Target}: by programme, indicator, period and org unit, allocated down the hierarchy with an approval.
 * Lifecycle DRAFT → PROPOSED → APPROVED (maker ≠ checker) or REJECTED; a revision of an approved target is a new
 * version (PROPOSED, reason required) and the previous version becomes REVISED when the revision is approved.
 * Allocation splits an approved parent target over child nodes of the {@link HierarchyPort}, by explicit values or by
 * weights; the children never exceed the parent unless over-allocation is explicitly allowed.
 */
public final class TargetService {

    public enum State { DRAFT, PROPOSED, APPROVED, REJECTED, REVISED }

    public record Target(String id, String programmeId, String indicatorKey, String periodId, String orgUnit, BigDecimal value,
                         String parentTargetId, State state, int version, String proposedBy, Instant proposedAt, String approvedBy,
                         Instant approvedAt, String reason, int rowVersion, Instant createdAt) {
    }

    private final Dialect dialect;
    private final IndicatorRegistry registry;
    private final ProgrammeService programmes;
    private final PeriodService periods;
    private final HierarchyPort hierarchy;

    public TargetService(Dialect dialect, IndicatorRegistry registry, ProgrammeService programmes, PeriodService periods, HierarchyPort hierarchy) {
        this.dialect = dialect;
        this.registry = registry;
        this.programmes = programmes;
        this.periods = periods;
        this.hierarchy = hierarchy;
    }

    /** Creates a DRAFT target (or updates the open DRAFT/PROPOSED one for the same key). */
    public Target set(Connection c, String tenant, String programmeId, String indicatorKey, String periodId, String orgUnit, BigDecimal value,
                      String actor) {
        String t = Db.tenant(tenant);
        check(c, t, programmeId, indicatorKey, periodId, orgUnit, value);
        Optional<Target> open = current(c, t, programmeId, indicatorKey, periodId, orgUnit, List.of(State.DRAFT, State.PROPOSED));
        if (open.isPresent()) {
            Target o = open.get();
            Db.requireOne(Jdbc.update(c, "UPDATE PG_TARGET SET target_value=?, state=?, row_version=row_version+1 WHERE tenant_id=? AND target_id=? AND row_version=?",
                    value, State.DRAFT.name(), t, o.id(), o.rowVersion()), "target");
            return get(c, t, o.id());
        }
        int version = Jdbc.one(c, "SELECT COALESCE(MAX(version),0) FROM PG_TARGET WHERE tenant_id=? AND programme_id=? AND indicator_key=?"
                + " AND period_id=? AND org_unit=?", rs -> rs.getInt(1), t, programmeId, indicatorKey, periodId, orgUnit).orElse(0) + 1;
        return insert(c, t, programmeId, indicatorKey, periodId, orgUnit, value, null, version == 1 ? State.DRAFT : State.PROPOSED, version,
                actor, version == 1 ? null : "new version");
    }

    public Target propose(Connection c, String tenant, String targetId, String actor) {
        Target tg = get(c, tenant, targetId);
        if (tg.state() != State.DRAFT) {
            throw ProgrammeException.state("only a DRAFT target can be proposed");
        }
        Db.requireOne(Jdbc.update(c, "UPDATE PG_TARGET SET state=?, proposed_by=?, proposed_at=" + dialect.now()
                        + ", row_version=row_version+1 WHERE tenant_id=? AND target_id=? AND row_version=?",
                State.PROPOSED.name(), actor, Db.tenant(tenant), targetId, tg.rowVersion()), "target");
        return get(c, tenant, targetId);
    }

    /** Approves a PROPOSED target; the approver must not be the proposer (maker-checker). */
    public Target approve(Connection c, String tenant, String targetId, String actor) {
        String t = Db.tenant(tenant);
        Target tg = get(c, t, targetId);
        if (tg.state() != State.PROPOSED) {
            throw ProgrammeException.state("only a PROPOSED target can be approved");
        }
        if (actor != null && actor.equals(tg.proposedBy())) {
            throw new ProgrammeException(ProgrammeException.Code.FOUR_EYES_REQUIRED, "the proposer cannot approve the target");
        }
        if (tg.parentTargetId() != null) {
            requireWithinParent(c, t, get(c, t, tg.parentTargetId()), tg.id(), tg.value(), false);
        }
        Jdbc.update(c, "UPDATE PG_TARGET SET state=?, row_version=row_version+1 WHERE tenant_id=? AND programme_id=? AND indicator_key=?"
                        + " AND period_id=? AND org_unit=? AND state=?", State.REVISED.name(), t, tg.programmeId(), tg.indicatorKey(),
                tg.periodId(), tg.orgUnit(), State.APPROVED.name());
        Db.requireOne(Jdbc.update(c, "UPDATE PG_TARGET SET state=?, approved_by=?, approved_at=" + dialect.now()
                        + ", row_version=row_version+1 WHERE tenant_id=? AND target_id=? AND row_version=?",
                State.APPROVED.name(), actor, t, targetId, tg.rowVersion()), "target");
        return get(c, t, targetId);
    }

    public Target reject(Connection c, String tenant, String targetId, String reason, String actor) {
        Db.requireText(reason, "reason");
        Target tg = get(c, tenant, targetId);
        if (tg.state() != State.PROPOSED) {
            throw ProgrammeException.state("only a PROPOSED target can be rejected");
        }
        Db.requireOne(Jdbc.update(c, "UPDATE PG_TARGET SET state=?, reason=?, approved_by=?, approved_at=" + dialect.now()
                        + ", row_version=row_version+1 WHERE tenant_id=? AND target_id=? AND row_version=?",
                State.REJECTED.name(), reason, actor, Db.tenant(tenant), targetId, tg.rowVersion()), "target");
        return get(c, tenant, targetId);
    }

    /** Proposes a new version of an APPROVED target (reason required); it replaces the old one when approved. */
    public Target revise(Connection c, String tenant, String targetId, BigDecimal newValue, String reason, String actor) {
        String t = Db.tenant(tenant);
        Db.requireText(reason, "reason for the revision");
        Target tg = get(c, t, targetId);
        if (tg.state() != State.APPROVED) {
            throw ProgrammeException.state("only an APPROVED target can be revised");
        }
        periods.requireWritable(c, t, tg.periodId(), true);
        if (current(c, t, tg.programmeId(), tg.indicatorKey(), tg.periodId(), tg.orgUnit(), List.of(State.DRAFT, State.PROPOSED)).isPresent()) {
            throw ProgrammeException.state("a revision is already pending for this target");
        }
        return insert(c, t, tg.programmeId(), tg.indicatorKey(), tg.periodId(), tg.orgUnit(), newValue, tg.parentTargetId(), State.PROPOSED,
                tg.version() + 1, actor, reason);
    }

    /**
     * Allocates an APPROVED parent target over child nodes. {@code explicit} gives child → value; otherwise
     * {@code weights} gives child → weight (missing children of the hierarchy get weight 0; null weights = equal split).
     * Child targets are created PROPOSED (an approver confirms them). Rounding uses the indicator's decimals and the
     * last child takes the remainder so the split sums exactly to the parent.
     */
    public List<Target> allocate(Connection c, String tenant, String parentTargetId, Map<String, BigDecimal> explicit,
                                 Map<String, BigDecimal> weights, boolean allowOverAllocation, String actor) {
        String t = Db.tenant(tenant);
        Target parent = get(c, t, parentTargetId);
        if (parent.state() != State.APPROVED) {
            throw ProgrammeException.state("allocate from an APPROVED target only");
        }
        IndicatorDefinition ind = registry.indicator(c, t, parent.indicatorKey());
        Map<String, BigDecimal> split = new LinkedHashMap<>();
        if (explicit != null && !explicit.isEmpty()) {
            split.putAll(explicit);
        } else {
            List<String> kids = hierarchy.children(c, t, parent.orgUnit());
            if (kids.isEmpty()) {
                throw ProgrammeException.invalid("node " + parent.orgUnit() + " has no children to allocate to");
            }
            BigDecimal totalW = BigDecimal.ZERO;
            Map<String, BigDecimal> w = new LinkedHashMap<>();
            for (String k : kids) {
                BigDecimal wk = weights == null ? BigDecimal.ONE : weights.getOrDefault(k, BigDecimal.ZERO);
                if (wk.signum() < 0) {
                    throw ProgrammeException.invalid("negative weight for " + k);
                }
                w.put(k, wk);
                totalW = totalW.add(wk);
            }
            if (totalW.signum() == 0) {
                throw ProgrammeException.invalid("weights sum to zero");
            }
            BigDecimal rest = parent.value();
            int i = 0;
            for (Map.Entry<String, BigDecimal> e : w.entrySet()) {
                i++;
                BigDecimal share = i == w.size() ? rest
                        : parent.value().multiply(e.getValue()).divide(totalW, ind.decimals(), RoundingMode.HALF_UP);
                rest = rest.subtract(share);
                split.put(e.getKey(), share);
            }
        }
        BigDecimal sum = split.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (!allowOverAllocation && sum.compareTo(parent.value()) > 0) {
            throw new ProgrammeException(ProgrammeException.Code.ALLOCATION_EXCEEDS_PARENT,
                    "allocation " + sum.toPlainString() + " exceeds parent target " + parent.value().toPlainString());
        }
        List<Target> out = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : split.entrySet()) {
            if (explicit != null && !explicit.isEmpty()) {
                Optional<String> p = hierarchy.parent(c, t, e.getKey());
                if (p.isPresent() && !p.get().equals(parent.orgUnit())) {
                    throw ProgrammeException.invalid(e.getKey() + " is not a child of " + parent.orgUnit());
                }
            }
            check(c, t, parent.programmeId(), parent.indicatorKey(), parent.periodId(), e.getKey(), e.getValue());
            Optional<Target> open = current(c, t, parent.programmeId(), parent.indicatorKey(), parent.periodId(), e.getKey(),
                    List.of(State.DRAFT, State.PROPOSED));
            open.ifPresent(o -> Jdbc.update(c, "UPDATE PG_TARGET SET state=?, reason=?, row_version=row_version+1 WHERE tenant_id=? AND target_id=?",
                    State.REJECTED.name(), "superseded by allocation", t, o.id()));
            int version = Jdbc.one(c, "SELECT COALESCE(MAX(version),0) FROM PG_TARGET WHERE tenant_id=? AND programme_id=? AND indicator_key=?"
                            + " AND period_id=? AND org_unit=?", rs -> rs.getInt(1), t, parent.programmeId(), parent.indicatorKey(),
                    parent.periodId(), e.getKey()).orElse(0) + 1;
            out.add(insert(c, t, parent.programmeId(), parent.indicatorKey(), parent.periodId(), e.getKey(), e.getValue(), parent.id(),
                    State.PROPOSED, version, actor, "allocated from " + parent.orgUnit()));
        }
        return out;
    }

    private void requireWithinParent(Connection c, String t, Target parent, String excludeId, BigDecimal value, boolean allowOver) {
        if (allowOver) {
            return;
        }
        BigDecimal siblings = Jdbc.one(c, "SELECT COALESCE(SUM(target_value),0) FROM PG_TARGET WHERE tenant_id=? AND parent_target_id=?"
                        + " AND state=? AND target_id<>?", rs -> rs.getBigDecimal(1), t, parent.id(), State.APPROVED.name(), excludeId)
                .orElse(BigDecimal.ZERO);
        Target self = get(c, t, excludeId);
        BigDecimal replaced = current(c, t, self.programmeId(), self.indicatorKey(), self.periodId(), self.orgUnit(), List.of(State.APPROVED))
                .filter(a -> parent.id().equals(a.parentTargetId())).map(Target::value).orElse(BigDecimal.ZERO);
        if (siblings.subtract(replaced).add(value).compareTo(parent.value()) > 0) {
            throw new ProgrammeException(ProgrammeException.Code.ALLOCATION_EXCEEDS_PARENT, "approved child targets would exceed the parent target");
        }
    }

    /** The APPROVED target value for the key, if any. */
    public Optional<BigDecimal> approvedValue(Connection c, String tenant, String programmeId, String indicatorKey, String periodId, String orgUnit) {
        return current(c, Db.tenant(tenant), programmeId, indicatorKey, periodId, orgUnit, List.of(State.APPROVED)).map(Target::value);
    }

    public Optional<Target> current(Connection c, String t, String programmeId, String indicatorKey, String periodId, String orgUnit,
                                    List<State> states) {
        List<Object> params = new ArrayList<>(List.of(t, programmeId, indicatorKey, periodId, orgUnit));
        states.forEach(s -> params.add(s.name()));
        List<Target> rows = Jdbc.query(c, "SELECT * FROM PG_TARGET WHERE tenant_id=? AND programme_id=? AND indicator_key=? AND period_id=?"
                + " AND org_unit=? AND state IN " + Db.in(states.size()) + " ORDER BY version DESC", TargetService::map, params.toArray());
        return rows.stream().findFirst();
    }

    public Target get(Connection c, String tenant, String targetId) {
        return Jdbc.one(c, "SELECT * FROM PG_TARGET WHERE tenant_id=? AND target_id=?", TargetService::map, Db.tenant(tenant), targetId)
                .orElseThrow(() -> ProgrammeException.notFound("target", targetId));
    }

    public List<Target> list(Connection c, String tenant, String programmeId, String periodId, String orgUnitOrNull, boolean approvedOnly) {
        StringBuilder sql = new StringBuilder("SELECT * FROM PG_TARGET WHERE tenant_id=? AND programme_id=? AND period_id=?");
        List<Object> params = new ArrayList<>(List.of(Db.tenant(tenant), programmeId, periodId));
        if (orgUnitOrNull != null) {
            sql.append(" AND org_unit=?");
            params.add(orgUnitOrNull);
        }
        if (approvedOnly) {
            sql.append(" AND state='APPROVED'");
        } else {
            sql.append(" AND state<>'REVISED'");
        }
        return Jdbc.query(c, sql + " ORDER BY indicator_key, org_unit, version", TargetService::map, params.toArray());
    }

    public List<Target> children(Connection c, String tenant, String parentTargetId) {
        return Jdbc.query(c, "SELECT * FROM PG_TARGET WHERE tenant_id=? AND parent_target_id=? AND state<>'REVISED' ORDER BY org_unit",
                TargetService::map, Db.tenant(tenant), parentTargetId);
    }

    private Target insert(Connection c, String t, String programmeId, String indicatorKey, String periodId, String orgUnit, BigDecimal value,
                          String parentId, State state, int version, String actor, String reason) {
        String id = Db.newId();
        boolean proposed = state == State.PROPOSED;
        Jdbc.update(c, "INSERT INTO PG_TARGET(tenant_id, target_id, programme_id, indicator_key, period_id, org_unit, target_value, parent_target_id,"
                        + " state, version, proposed_by, proposed_at, reason, row_version, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,"
                        + (proposed ? dialect.now() : "NULL") + ",?,1," + dialect.now() + ")",
                t, id, programmeId, indicatorKey, periodId, orgUnit, value, parentId, state.name(), version, proposed ? actor : null, reason);
        if (!proposed) {
            Jdbc.update(c, "UPDATE PG_TARGET SET proposed_by=? WHERE tenant_id=? AND target_id=?", actor, t, id);
        }
        return get(c, t, id);
    }

    private void check(Connection c, String t, String programmeId, String indicatorKey, String periodId, String orgUnit, BigDecimal value) {
        Db.requireText(orgUnit, "org unit");
        if (value == null || value.signum() < 0) {
            throw ProgrammeException.invalid("target value must be zero or positive");
        }
        ProgrammeService.Programme p = programmes.get(c, t, programmeId);
        if (!p.indicators().isEmpty() && !p.indicators().contains(indicatorKey)) {
            throw ProgrammeException.invalid("programme " + p.code() + " does not track indicator " + indicatorKey);
        }
        IndicatorDefinition ind = registry.indicator(c, t, indicatorKey);
        PeriodService.Period per = periods.get(c, t, periodId);
        if (per.frequency() != ind.frequency() && per.frequency() != IndicatorDefinition.Frequency.ANNUAL) {
            throw ProgrammeException.invalid("period " + per.label() + " does not match the indicator frequency " + ind.frequency());
        }
        periods.requireWritable(c, t, periodId, true);
    }

    static Target map(ResultSet rs) throws SQLException {
        return new Target(rs.getString("target_id"), rs.getString("programme_id"), rs.getString("indicator_key"), rs.getString("period_id"),
                rs.getString("org_unit"), rs.getBigDecimal("target_value"), rs.getString("parent_target_id"), State.valueOf(rs.getString("state")),
                rs.getInt("version"), rs.getString("proposed_by"), Jdbc.instant(rs, "proposed_at"), rs.getString("approved_by"),
                Jdbc.instant(rs, "approved_at"), rs.getString("reason"), rs.getInt("row_version"), Jdbc.instant(rs, "created_at"));
    }
}
