// SPDX-License-Identifier: Apache-2.0
package org.sangam.programme.returns;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Outbox;
import org.sangam.core.layer.SqlPredicate;
import org.sangam.ports.support.Paging;
import org.sangam.programme.ProgrammeException;
import org.sangam.programme.config.IndicatorDefinition;
import org.sangam.programme.config.ProgrammeConfig;
import org.sangam.programme.config.ReturnDefinition;
import org.sangam.programme.indicator.IndicatorRegistry;
import org.sangam.programme.period.PeriodService;
import org.sangam.programme.ports.ProgrammePorts.CampaignStarter;
import org.sangam.programme.ports.ProgrammePorts.HierarchyPort;
import org.sangam.programme.ports.ProgrammePorts.Notifier;
import org.sangam.programme.ports.ProgrammePorts.Reminder;
import org.sangam.programme.rollup.RollupService;
import org.sangam.programme.support.Db;
import org.sangam.programme.target.TargetService;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * N15 {@code ReturnSubmission}: per office per period, DUE → DRAFT → SUBMITTED → VALIDATED → ACCEPTED (after
 * {@code approvalLevels} distinct checkers) or RETURNED (for correction) → FROZEN; re-open with a reason.
 *
 * <ul>
 *   <li>{@link #startCampaign}: GP-30 campaign start per period, one DUE return per reporting office (GP-09: one
 *       process instance per office through {@link CampaignStarter}, never one large multi-instance); idempotent.</li>
 *   <li>{@link #defaulters} / {@link #sendReminders}: defaulter lists and reminders (C15) through {@link Notifier}.</li>
 *   <li>Accepted values feed {@link RollupService} (leaf rows); re-opening removes them.</li>
 *   <li>Every transition is recorded in {@code PG_RETURN_EVENT} and emitted on the outbox ({@code programme.return.*}).</li>
 * </ul>
 * Maker-checker: the submitter cannot accept, and each approval level needs a different person.
 */
public final class ReturnService {

    public enum State { DUE, DRAFT, SUBMITTED, VALIDATED, ACCEPTED, RETURNED, FROZEN }

    public record ReturnRow(String id, String returnKey, int returnVersion, String periodId, String orgUnit, String programmeId, State state,
                            int approvalLevel, LocalDate dueOn, int remindersSent, Instant lastReminderAt, String submittedBy,
                            Instant submittedAt, Instant validatedAt, String acceptedBy, Instant acceptedAt, String returnedBy,
                            String returnedReason, Instant frozenAt, String reopenedReason, boolean late, String processInstanceId,
                            String contentHash, int rowVersion, Instant createdAt, Instant updatedAt) {
    }

    public record Value(String indicatorKey, BigDecimal numValue, String textValue, String remark, String updatedBy, Instant updatedAt) {
    }

    public record StoredFinding(int seq, String indicatorKey, String severity, String code, String message, String acknowledgedBy,
                                Instant acknowledgedAt) {
    }

    public record Event(int seq, String fromState, String toState, int approvalLevel, String actor, String reason, Instant at) {
    }

    public record Outcome(ReturnRow returnRow, List<ValidationEngine.Finding> findings, boolean advanced) {
    }

    public record CampaignResult(String returnKey, String periodId, int offices, int created, int processesStarted) {
    }

    public static final Outbox.Destination EVENTS = Outbox.Destination.unordered("programme-events");

    private static final Set<State> SUBMITTED_OR_LATER = Set.of(State.SUBMITTED, State.VALIDATED, State.ACCEPTED, State.FROZEN);

    private final Dialect dialect;
    private final IndicatorRegistry registry;
    private final PeriodService periods;
    private final TargetService targets;
    private final RollupService rollup;
    private final ValidationEngine validation;
    private final HierarchyPort hierarchy;
    private final CampaignStarter starter;
    private final Notifier notifier;
    private final Outbox outbox;

    public ReturnService(Dialect dialect, IndicatorRegistry registry, PeriodService periods, TargetService targets, RollupService rollup,
                         ValidationEngine validation, HierarchyPort hierarchy, CampaignStarter starter, Notifier notifier, Outbox outbox) {
        this.dialect = dialect;
        this.registry = registry;
        this.periods = periods;
        this.targets = targets;
        this.rollup = rollup;
        this.validation = validation;
        this.hierarchy = hierarchy;
        this.starter = starter == null ? CampaignStarter.NONE : starter;
        this.notifier = notifier == null ? Notifier.NONE : notifier;
        this.outbox = outbox;
    }

    // ------------------------------------------------------------------------------------------------ campaign

    public CampaignResult startCampaign(Connection c, String tenant, String returnKey, String periodId, String rootNode, String programmeId,
                                        String actor) {
        String t = Db.tenant(tenant);
        ReturnDefinition def = registry.returnDefinition(c, t, returnKey);
        if (!def.active()) {
            throw ProgrammeException.state("return definition " + returnKey + " is not active");
        }
        PeriodService.Period p = periods.get(c, t, periodId);
        if (p.frequency() != def.frequency()) {
            throw ProgrammeException.invalid("period " + p.label() + " does not match the return frequency " + def.frequency());
        }
        periods.requireWritable(c, t, periodId, false);
        String prog = programmeId != null ? programmeId : def.programmeId();
        Db.requireText(rootNode, "root node");
        List<String> offices = def.reportingLevel() == null ? List.of(rootNode) : hierarchy.nodesAtLevel(c, t, rootNode, def.reportingLevel());
        LocalDate due = p.endsOn().plusDays(def.dueOffsetDays());
        int created = 0;
        int started = 0;
        for (String office : offices) {
            String id = Db.newId();
            boolean inserted = Jdbc.insertIfAbsent(c, dialect, "INSERT INTO PG_RETURN(tenant_id, return_id, return_key, return_version, period_id,"
                            + " org_unit, programme_id, state, approval_level, due_on, reminders_sent, late, row_version, created_at, updated_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,0,?,0,0,1," + dialect.now() + "," + dialect.now() + ")",
                    t, id, def.key(), def.version(), periodId, office, prog, State.DUE.name(), Db.sqlDate(due));
            if (!inserted) {
                continue;
            }
            created++;
            event(c, t, id, null, State.DUE, 0, actor, "campaign");
            if (def.processKey() != null) {
                String pid = starter.start(c, t, def.processKey(), businessKey(def.key(), periodId, office),
                        Db.kv("returnId", id, "returnKey", def.key(), "periodId", periodId, "orgUnit", office, "programmeId", prog,
                                "dueOn", due.toString()));
                if (pid != null) {
                    Jdbc.update(c, "UPDATE PG_RETURN SET process_instance_id=? WHERE tenant_id=? AND return_id=?", pid, t, id);
                    started++;
                }
            }
        }
        emit(c, t, "campaign:" + def.key() + ":" + periodId, "programme.return.campaign-started",
                Db.kv("returnKey", def.key(), "periodId", periodId, "rootNode", rootNode, "offices", offices.size(), "created", created));
        return new CampaignResult(def.key(), periodId, offices.size(), created, started);
    }

    public static String businessKey(String returnKey, String periodId, String orgUnit) {
        return returnKey + "/" + periodId + "/" + orgUnit;
    }

    // ------------------------------------------------------------------------------------------------ data entry

    /** Saves values; {@code values} maps indicator key → number or text. Allowed in DUE, DRAFT and RETURNED. */
    public ReturnRow saveDraft(Connection c, String tenant, String returnId, int expectedVersion, Map<String, Object> values,
                               Map<String, String> remarks, String actor) {
        String t = Db.tenant(tenant);
        ReturnRow r = get(c, t, returnId);
        if (r.state() != State.DUE && r.state() != State.DRAFT && r.state() != State.RETURNED) {
            throw ProgrammeException.state("return is " + r.state() + ": values can be changed only before submission");
        }
        periods.requireWritable(c, t, r.periodId(), true);
        ReturnDefinition def = registry.returnDefinition(c, t, r.returnKey(), r.returnVersion());
        Set<String> allowed = new HashSet<>();
        def.items().forEach(i -> allowed.add(i.indicatorKey()));
        for (Map.Entry<String, Object> e : values.entrySet()) {
            if (!allowed.contains(e.getKey())) {
                throw ProgrammeException.invalid("indicator " + e.getKey() + " is not part of return " + def.key());
            }
            Object v = e.getValue();
            BigDecimal num = null;
            String text = null;
            if (v instanceof Number || (v instanceof String s && s.trim().matches("-?\\d+(\\.\\d+)?"))) {
                num = Db.dec(v);
            } else if (v != null) {
                text = v.toString();
            }
            Jdbc.update(c, "DELETE FROM PG_RETURN_VALUE WHERE tenant_id=? AND return_id=? AND indicator_key=?", t, returnId, e.getKey());
            if (num != null || text != null) {
                Jdbc.update(c, "INSERT INTO PG_RETURN_VALUE(tenant_id, return_id, indicator_key, num_value, text_value, remark, updated_by, updated_at)"
                                + " VALUES (?,?,?,?,?,?,?," + dialect.now() + ")", t, returnId, e.getKey(), num, text,
                        remarks == null ? null : remarks.get(e.getKey()), actor);
            }
        }
        State to = State.DRAFT;
        Db.requireOne(Jdbc.update(c, "UPDATE PG_RETURN SET state=?, row_version=row_version+1, updated_at=" + dialect.now()
                + " WHERE tenant_id=? AND return_id=? AND row_version=?", to.name(), t, returnId, expectedVersion), "return");
        if (r.state() != to) {
            event(c, t, returnId, r.state(), to, r.approvalLevel(), actor, null);
        }
        return get(c, t, returnId);
    }

    // ------------------------------------------------------------------------------------------------ workflow

    /** Submits: built-in validation; ERROR findings keep the return in DRAFT (findings stored). */
    public Outcome submit(Connection c, String tenant, String returnId, int expectedVersion, String actor) {
        String t = Db.tenant(tenant);
        ReturnRow r = get(c, t, returnId);
        if (r.state() != State.DRAFT && r.state() != State.RETURNED && r.state() != State.DUE) {
            throw ProgrammeException.state("return is " + r.state());
        }
        periods.requireWritable(c, t, r.periodId(), true);
        List<ValidationEngine.Finding> findings = runValidation(c, t, r, false);
        storeFindings(c, t, returnId, findings);
        if (ValidationEngine.hasErrors(findings)) {
            return new Outcome(r, findings, false);
        }
        Map<String, Object> material = materialValues(c, t, returnId);
        boolean late = r.dueOn() != null && Db.today(c, dialect).isAfter(r.dueOn());
        Db.requireOne(Jdbc.update(c, "UPDATE PG_RETURN SET state=?, approval_level=0, submitted_by=?, submitted_at=" + dialect.now()
                        + ", late=?, content_hash=?, row_version=row_version+1, updated_at=" + dialect.now()
                        + " WHERE tenant_id=? AND return_id=? AND row_version=?",
                State.SUBMITTED.name(), actor, Db.small(late), Db.hashOf(material), t, returnId, expectedVersion), "return");
        event(c, t, returnId, r.state(), State.SUBMITTED, 0, actor, late ? "late" : null);
        emit(c, t, returnId, "programme.return.submitted", Db.kv("returnId", returnId, "orgUnit", r.orgUnit(), "periodId", r.periodId(), "late", late));
        return new Outcome(get(c, t, returnId), findings, true);
    }

    /** Full validation (built-in + DMN). Passing → VALIDATED; ERROR findings → RETURNED with the findings. */
    public Outcome validate(Connection c, String tenant, String returnId, String actor) {
        String t = Db.tenant(tenant);
        ReturnRow r = get(c, t, returnId);
        if (r.state() != State.SUBMITTED) {
            throw ProgrammeException.state("only a SUBMITTED return can be validated");
        }
        List<ValidationEngine.Finding> findings = runValidation(c, t, r, true);
        storeFindings(c, t, returnId, findings);
        if (ValidationEngine.hasErrors(findings)) {
            transition(c, t, r, State.RETURNED, 0, actor, "validation failed", "returned_by=?, returned_reason=?", actor, "validation failed");
            emit(c, t, returnId, "programme.return.returned", Db.kv("returnId", returnId, "reason", "validation failed"));
            return new Outcome(get(c, t, returnId), findings, false);
        }
        transition(c, t, r, State.VALIDATED, 0, actor, null, "validated_at=" + dialect.now());
        return new Outcome(get(c, t, returnId), findings, true);
    }

    /**
     * One approval level. The last level ({@code approvalLevels}) accepts the return and writes its values to the
     * consolidation (leaf rows). The submitter and earlier approvers of the same submission cannot approve.
     */
    public ReturnRow accept(Connection c, String tenant, String returnId, String actor) {
        String t = Db.tenant(tenant);
        ReturnRow r = get(c, t, returnId);
        if (r.state() != State.VALIDATED) {
            throw ProgrammeException.state("only a VALIDATED return can be accepted");
        }
        if (actor == null || actor.equals(r.submittedBy()) || approversSinceSubmit(c, t, returnId).contains(actor)) {
            throw new ProgrammeException(ProgrammeException.Code.FOUR_EYES_REQUIRED, "a different person must approve this level");
        }
        ReturnDefinition def = registry.returnDefinition(c, t, r.returnKey(), r.returnVersion());
        int level = r.approvalLevel() + 1;
        if (level < def.approvalLevels()) {
            Db.requireOne(Jdbc.update(c, "UPDATE PG_RETURN SET approval_level=?, row_version=row_version+1, updated_at=" + dialect.now()
                    + " WHERE tenant_id=? AND return_id=? AND row_version=?", level, t, returnId, r.rowVersion()), "return");
            event(c, t, returnId, State.VALIDATED, State.VALIDATED, level, actor, "approved level " + level);
            return get(c, t, returnId);
        }
        transition(c, t, r, State.ACCEPTED, level, actor, null, "accepted_by=?, accepted_at=" + dialect.now(), actor);
        if (r.programmeId() != null) {
            for (Value v : values(c, t, returnId)) {
                if (v.numValue() != null) {
                    rollup.recordLeaf(c, t, r.programmeId(), v.indicatorKey(), r.periodId(), r.orgUnit(), v.numValue(), RollupService.Source.RETURN, returnId);
                }
            }
        }
        emit(c, t, returnId, "programme.return.accepted", Db.kv("returnId", returnId, "orgUnit", r.orgUnit(), "periodId", r.periodId()));
        return get(c, t, returnId);
    }

    /** Sends a SUBMITTED or VALIDATED return back for correction (reason required). */
    public ReturnRow returnForCorrection(Connection c, String tenant, String returnId, String reason, String actor) {
        String t = Db.tenant(tenant);
        Db.requireText(reason, "reason");
        ReturnRow r = get(c, t, returnId);
        if (r.state() != State.SUBMITTED && r.state() != State.VALIDATED) {
            throw ProgrammeException.state("only a SUBMITTED or VALIDATED return can be returned");
        }
        transition(c, t, r, State.RETURNED, 0, actor, reason, "returned_by=?, returned_reason=?", actor, reason);
        Optional<String> to = hierarchy.responsible(c, t, r.orgUnit());
        notifier.send(c, new Reminder(t, to.orElse(r.orgUnit()), r.orgUnit(), "RETURNED", returnId, Db.kv("reason", reason, "periodId", r.periodId())));
        emit(c, t, returnId, "programme.return.returned", Db.kv("returnId", returnId, "reason", reason));
        return get(c, t, returnId);
    }

    public ReturnRow freeze(Connection c, String tenant, String returnId, String actor) {
        String t = Db.tenant(tenant);
        ReturnRow r = get(c, t, returnId);
        if (r.state() != State.ACCEPTED) {
            throw ProgrammeException.state("only an ACCEPTED return can be frozen");
        }
        transition(c, t, r, State.FROZEN, r.approvalLevel(), actor, null, "frozen_at=" + dialect.now());
        return get(c, t, returnId);
    }

    /** Freezes every ACCEPTED return of a period (period close). */
    public int freezePeriod(Connection c, String tenant, String returnKey, String periodId, String actor) {
        String t = Db.tenant(tenant);
        int n = 0;
        for (ReturnRow r : Jdbc.query(c, "SELECT * FROM PG_RETURN WHERE tenant_id=? AND return_key=? AND period_id=? AND state='ACCEPTED'",
                ReturnService::map, t, returnKey, periodId)) {
            freeze(c, t, r.id(), actor);
            n++;
        }
        return n;
    }

    /** Re-opens an ACCEPTED or FROZEN return for correction (reason required); its consolidated values are withdrawn. */
    public ReturnRow reopen(Connection c, String tenant, String returnId, String reason, String actor) {
        String t = Db.tenant(tenant);
        Db.requireText(reason, "reason for re-opening");
        ReturnRow r = get(c, t, returnId);
        if (r.state() != State.ACCEPTED && r.state() != State.FROZEN) {
            throw ProgrammeException.state("only an ACCEPTED or FROZEN return can be re-opened");
        }
        periods.requireWritable(c, t, r.periodId(), true);
        transition(c, t, r, State.DRAFT, 0, actor, reason, "reopened_reason=?, frozen_at=NULL, accepted_by=NULL, accepted_at=NULL", reason);
        rollup.removeSource(c, t, returnId);
        emit(c, t, returnId, "programme.return.reopened", Db.kv("returnId", returnId, "reason", reason));
        return get(c, t, returnId);
    }

    public void acknowledgeFinding(Connection c, String tenant, String returnId, int seq, String actor) {
        Db.requireOne(Jdbc.update(c, "UPDATE PG_RETURN_FINDING SET acknowledged_by=?, acknowledged_at=" + dialect.now()
                        + " WHERE tenant_id=? AND return_id=? AND seq=? AND severity='WARNING' AND acknowledged_by IS NULL",
                actor, Db.tenant(tenant), returnId, seq), "finding");
    }

    // ------------------------------------------------------------------------------------------------ defaulters

    /** Returns not yet submitted whose due date is before {@code asOf}. */
    public List<ReturnRow> defaulters(Connection c, String tenant, String returnKeyOrNull, String periodIdOrNull, LocalDate asOf, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM PG_RETURN WHERE tenant_id=? AND state IN ('DUE','DRAFT','RETURNED') AND due_on<?");
        List<Object> params = new ArrayList<>(List.of(Db.tenant(tenant), Db.sqlDate(asOf)));
        if (returnKeyOrNull != null) {
            sql.append(" AND return_key=?");
            params.add(returnKeyOrNull);
        }
        if (periodIdOrNull != null) {
            sql.append(" AND period_id=?");
            params.add(periodIdOrNull);
        }
        sql.append(" ORDER BY due_on, org_unit");
        Paging.PageRequest page = new Paging.PageRequest(0, Math.min(Math.max(limit, 1), Paging.MAX_PAGE));
        return Jdbc.query(c, sql + Paging.window(dialect, page), ReturnService::map, params.toArray());
    }

    /**
     * Sends reminders on the configured offsets ({@code reminderDays} relative to the due date) for returns not yet
     * submitted; at most one per return and day. Returns the number sent.
     */
    public int sendReminders(Connection c, String tenant, LocalDate asOf, int limit) {
        String t = Db.tenant(tenant);
        int sent = 0;
        List<ReturnRow> open = Jdbc.query(c, "SELECT * FROM PG_RETURN WHERE tenant_id=? AND state IN ('DUE','DRAFT','RETURNED') ORDER BY due_on",
                ReturnService::map, t);
        Map<String, ReturnDefinition> defs = new LinkedHashMap<>();
        for (ReturnRow r : open) {
            if (sent >= limit || r.dueOn() == null) {
                break;
            }
            ReturnDefinition def = defs.computeIfAbsent(r.returnKey() + "@" + r.returnVersion(),
                    k -> registry.returnDefinition(c, t, r.returnKey(), r.returnVersion()));
            int offset = (int) (asOf.toEpochDay() - r.dueOn().toEpochDay());
            boolean scheduled = def.reminderDays().contains(offset) || (offset > 0 && def.reminderDays().stream().anyMatch(d -> d > 0 && offset % d == 0));
            boolean alreadyToday = r.lastReminderAt() != null && r.lastReminderAt().atZone(java.time.ZoneOffset.UTC).toLocalDate().equals(asOf);
            if (!scheduled || alreadyToday) {
                continue;
            }
            String to = hierarchy.responsible(c, t, r.orgUnit()).orElse(r.orgUnit());
            notifier.send(c, new Reminder(t, to, r.orgUnit(), offset > 0 ? "OVERDUE" : "DUE_SOON", r.id(),
                    Db.kv("returnKey", r.returnKey(), "periodId", r.periodId(), "dueOn", r.dueOn().toString(), "daysOverdue", Math.max(offset, 0))));
            Jdbc.update(c, "UPDATE PG_RETURN SET reminders_sent=reminders_sent+1, last_reminder_at=" + dialect.now()
                    + " WHERE tenant_id=? AND return_id=?", t, r.id());
            sent++;
        }
        return sent;
    }

    // ------------------------------------------------------------------------------------------------ queries

    public ReturnRow get(Connection c, String tenant, String returnId) {
        return Jdbc.one(c, "SELECT * FROM PG_RETURN WHERE tenant_id=? AND return_id=?", ReturnService::map, Db.tenant(tenant), returnId)
                .orElseThrow(() -> ProgrammeException.notFound("return", returnId));
    }

    public Optional<ReturnRow> find(Connection c, String tenant, String returnKey, String periodId, String orgUnit) {
        return Jdbc.one(c, "SELECT * FROM PG_RETURN WHERE tenant_id=? AND return_key=? AND period_id=? AND org_unit=?", ReturnService::map,
                Db.tenant(tenant), returnKey, periodId, orgUnit);
    }

    public List<Value> values(Connection c, String tenant, String returnId) {
        return Jdbc.query(c, "SELECT * FROM PG_RETURN_VALUE WHERE tenant_id=? AND return_id=? ORDER BY indicator_key",
                rs -> new Value(rs.getString("indicator_key"), rs.getBigDecimal("num_value"), rs.getString("text_value"), rs.getString("remark"),
                        rs.getString("updated_by"), Jdbc.instant(rs, "updated_at")), Db.tenant(tenant), returnId);
    }

    public List<StoredFinding> findings(Connection c, String tenant, String returnId) {
        return Jdbc.query(c, "SELECT * FROM PG_RETURN_FINDING WHERE tenant_id=? AND return_id=? ORDER BY seq",
                rs -> new StoredFinding(rs.getInt("seq"), rs.getString("indicator_key"), rs.getString("severity"), rs.getString("rule_code"),
                        rs.getString("message"), rs.getString("acknowledged_by"), Jdbc.instant(rs, "acknowledged_at")), Db.tenant(tenant), returnId);
    }

    public List<Event> events(Connection c, String tenant, String returnId) {
        return Jdbc.query(c, "SELECT * FROM PG_RETURN_EVENT WHERE tenant_id=? AND return_id=? ORDER BY seq",
                rs -> new Event(rs.getInt("seq"), rs.getString("from_state"), rs.getString("to_state"), rs.getInt("approval_level"),
                        rs.getString("actor"), rs.getString("reason"), Jdbc.instant(rs, "at_time")), Db.tenant(tenant), returnId);
    }

    /** Paged list under the caller's VI.4 predicate (alias {@code r}); capped count (A-16). */
    public Paging.PageResult<ReturnRow> list(Connection c, String tenant, String returnKey, String periodId, String orgUnit, State state,
                                             SqlPredicate visible, Paging.PageRequest page) {
        StringBuilder where = new StringBuilder(" FROM PG_RETURN r WHERE r.tenant_id=?");
        List<Object> params = new ArrayList<>(List.of(Db.tenant(tenant)));
        if (returnKey != null) {
            where.append(" AND r.return_key=?");
            params.add(returnKey);
        }
        if (periodId != null) {
            where.append(" AND r.period_id=?");
            params.add(periodId);
        }
        if (orgUnit != null) {
            where.append(" AND r.org_unit=?");
            params.add(orgUnit);
        }
        if (state != null) {
            where.append(" AND r.state=?");
            params.add(state.name());
        }
        if (visible != null) {
            where.append(" AND (").append(visible.sql()).append(')');
            params.addAll(visible.params());
        }
        long total = Jdbc.one(c, Paging.cappedCount(dialect, where.toString()), rs -> rs.getLong(1), params.toArray()).orElse(0L);
        List<ReturnRow> items = Jdbc.query(c, "SELECT r.*" + where + " ORDER BY r.due_on, r.org_unit" + Paging.window(dialect, page),
                ReturnService::map, params.toArray());
        return Paging.of(items, total, page);
    }

    /** Counts per state for one return key and period (dashboard tiles). */
    public Map<State, Long> stateCounts(Connection c, String tenant, String returnKeyOrNull, String periodId) {
        Map<State, Long> out = new java.util.EnumMap<>(State.class);
        for (State s : State.values()) {
            out.put(s, 0L);
        }
        String sql = "SELECT state, COUNT(*) AS n FROM PG_RETURN WHERE tenant_id=? AND period_id=?" + (returnKeyOrNull == null ? "" : " AND return_key=?")
                + " GROUP BY state";
        Object[] params = returnKeyOrNull == null ? new Object[]{Db.tenant(tenant), periodId} : new Object[]{Db.tenant(tenant), periodId, returnKeyOrNull};
        Jdbc.query(c, sql, rs -> out.put(State.valueOf(rs.getString("state")), rs.getLong("n")), params);
        return out;
    }

    // ------------------------------------------------------------------------------------------------ internals

    private List<ValidationEngine.Finding> runValidation(Connection c, String t, ReturnRow r, boolean withExternal) {
        ProgrammeConfig cfg = registry.config(c, t);
        ReturnDefinition def = registry.returnDefinition(c, t, r.returnKey(), r.returnVersion());
        Map<String, BigDecimal> nums = new LinkedHashMap<>();
        Map<String, String> texts = new LinkedHashMap<>();
        for (Value v : values(c, t, r.id())) {
            if (v.numValue() != null) {
                nums.put(v.indicatorKey(), v.numValue());
            }
            if (v.textValue() != null) {
                texts.put(v.indicatorKey(), v.textValue());
            }
        }
        Map<String, BigDecimal> prev = new LinkedHashMap<>();
        PeriodService.Period p = periods.get(c, t, r.periodId());
        Optional<PeriodService.Period> pp = periods.previous(c, t, p);
        if (pp.isPresent()) {
            find(c, t, r.returnKey(), pp.get().id(), r.orgUnit()).ifPresent(prevRet -> {
                for (Value v : values(c, t, prevRet.id())) {
                    if (v.numValue() != null) {
                        prev.put(v.indicatorKey(), v.numValue());
                    }
                }
            });
        }
        Map<String, BigDecimal> tg = new LinkedHashMap<>();
        if (r.programmeId() != null) {
            for (ReturnDefinition.Item i : def.items()) {
                IndicatorDefinition ind = cfg.indicators().get(i.indicatorKey());
                if (ind != null) {
                    targets.approvedValue(c, t, r.programmeId(), i.indicatorKey(), r.periodId(), r.orgUnit()).ifPresent(v -> tg.put(i.indicatorKey(), v));
                }
            }
        }
        Map<String, Object> ctx = Db.kv("returnKey", r.returnKey(), "periodId", r.periodId(), "orgUnit", r.orgUnit(),
                "programmeId", r.programmeId(), "periodSeq", p.seqInYear(), "fiscalYear", p.fiscalYear());
        return validation.validate(c, t, cfg, def, nums, texts, prev, tg, ctx, withExternal);
    }

    private void storeFindings(Connection c, String t, String returnId, List<ValidationEngine.Finding> findings) {
        Jdbc.update(c, "DELETE FROM PG_RETURN_FINDING WHERE tenant_id=? AND return_id=?", t, returnId);
        int seq = 1;
        for (ValidationEngine.Finding f : findings) {
            String msg = f.message() == null ? f.code() : f.message();
            Jdbc.update(c, "INSERT INTO PG_RETURN_FINDING(tenant_id, return_id, seq, indicator_key, severity, rule_code, message, created_at)"
                            + " VALUES (?,?,?,?,?,?,?," + dialect.now() + ")", t, returnId, seq++, f.indicatorKey(), f.severity().name(), f.code(),
                    msg.length() > 255 ? msg.substring(0, 255) : msg);
        }
    }

    private Map<String, Object> materialValues(Connection c, String t, String returnId) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Value v : values(c, t, returnId)) {
            m.put(v.indicatorKey(), v.numValue() != null ? v.numValue().stripTrailingZeros().toPlainString() : v.textValue());
        }
        return m;
    }

    private Set<String> approversSinceSubmit(Connection c, String t, String returnId) {
        Set<String> out = new HashSet<>();
        for (Event e : events(c, t, returnId)) {
            if (State.SUBMITTED.name().equals(e.toState())) {
                out.clear();
            } else if (State.VALIDATED.name().equals(e.fromState()) && e.actor() != null) {
                out.add(e.actor());
            }
        }
        return out;
    }

    private void transition(Connection c, String t, ReturnRow r, State to, int level, String actor, String reason, String extraSet,
                            Object... extraParams) {
        List<Object> params = new ArrayList<>();
        params.add(to.name());
        params.add(level);
        params.addAll(List.of(extraParams));
        params.add(t);
        params.add(r.id());
        params.add(r.rowVersion());
        Db.requireOne(Jdbc.update(c, "UPDATE PG_RETURN SET state=?, approval_level=?" + (extraSet == null ? "" : ", " + extraSet)
                + ", row_version=row_version+1, updated_at=" + dialect.now() + " WHERE tenant_id=? AND return_id=? AND row_version=?",
                params.toArray()), "return");
        event(c, t, r.id(), r.state(), to, level, actor, reason);
    }

    private void event(Connection c, String t, String returnId, State from, State to, int level, String actor, String reason) {
        int seq = Jdbc.one(c, "SELECT COALESCE(MAX(seq),0) FROM PG_RETURN_EVENT WHERE tenant_id=? AND return_id=?", rs -> rs.getInt(1), t, returnId)
                .orElse(0) + 1;
        Jdbc.update(c, "INSERT INTO PG_RETURN_EVENT(tenant_id, return_id, seq, from_state, to_state, approval_level, actor, reason, at_time)"
                + " VALUES (?,?,?,?,?,?,?,?," + dialect.now() + ")", t, returnId, seq, from == null ? null : from.name(), to.name(), level, actor, reason);
    }

    private void emit(Connection c, String t, String rootId, String type, Map<String, Object> payload) {
        if (outbox != null) {
            outbox.append(c, t, rootId, type, Db.json(payload), List.of(EVENTS));
        }
    }

    static ReturnRow map(ResultSet rs) throws SQLException {
        return new ReturnRow(rs.getString("return_id"), rs.getString("return_key"), rs.getInt("return_version"), rs.getString("period_id"),
                rs.getString("org_unit"), rs.getString("programme_id"), State.valueOf(rs.getString("state")), rs.getInt("approval_level"),
                Db.date(rs, "due_on"), rs.getInt("reminders_sent"), Jdbc.instant(rs, "last_reminder_at"), rs.getString("submitted_by"),
                Jdbc.instant(rs, "submitted_at"), Jdbc.instant(rs, "validated_at"), rs.getString("accepted_by"), Jdbc.instant(rs, "accepted_at"),
                rs.getString("returned_by"), rs.getString("returned_reason"), Jdbc.instant(rs, "frozen_at"), rs.getString("reopened_reason"),
                rs.getInt("late") != 0, rs.getString("process_instance_id"), rs.getString("content_hash"), rs.getInt("row_version"),
                Jdbc.instant(rs, "created_at"), Jdbc.instant(rs, "updated_at"));
    }

    static boolean submittedOrLater(State s) {
        return SUBMITTED_OR_LATER.contains(s);
    }
}
