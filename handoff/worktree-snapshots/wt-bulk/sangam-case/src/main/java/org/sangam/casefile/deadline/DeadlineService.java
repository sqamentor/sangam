// SPDX-License-Identifier: Apache-2.0
package org.sangam.casefile.deadline;

import org.sangam.casefile.CaseFileException;
import org.sangam.casefile.common.CaseEvents;
import org.sangam.casefile.config.CaseConfig.EscalationStep;
import org.sangam.casefile.ports.CaseFilePorts;
import org.sangam.casefile.support.Db;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Deadlines with business calendars, warnings and an escalation ladder (C12, R79), shared by cases, references,
 * action items and meetings. A deadline is RUNNING → WARNED → BREACHED (escalating step by step) or MET / CANCELLED.
 * The sweeper claims due rows with the dialect's skip-locked select, so several nodes can sweep at once; each row
 * carries {@code next_check_at}, so a sweep never scans settled rows.
 */
public class DeadlineService {

    public enum State { RUNNING, WARNED, BREACHED, MET, CANCELLED }

    public record Deadline(String tenantId, String id, String scopeType, String scopeId, String kind, String ownerSeat,
                           String ownerPerson, Instant startedAt, Instant dueAt, Instant warnAt, State state,
                           int escalationLevel, List<EscalationStep> ladder, Instant lastEscalatedAt, Instant metAt,
                           int extensionCount, String calendar, Instant nextCheckAt) {

        public boolean late() {
            return state == State.BREACHED || (metAt != null && metAt.isAfter(dueAt));
        }
    }

    /** In-process reaction to deadline transitions (runs inside the sweeper's transaction). */
    @FunctionalInterface
    public interface DeadlineListener {
        /** transition: WARNED, BREACHED, ESCALATED */
        void onTransition(Connection c, Deadline deadline, String transition, String escalatedToSeat);
    }

    private final Dialect dialect;
    private final Transactions tx;
    private final CaseEvents events;
    private final CaseFilePorts.Calendars calendars;
    private final CaseFilePorts.Notifier notifier;
    private final CaseFilePorts.EscalationSeats escalation;
    private final ZoneId defaultZone;
    private final List<DeadlineListener> listeners = new CopyOnWriteArrayList<>();

    public DeadlineService(Dialect dialect, Transactions tx, CaseEvents events, CaseFilePorts.Calendars calendars,
                           CaseFilePorts.Notifier notifier, CaseFilePorts.EscalationSeats escalation, ZoneId defaultZone) {
        this.dialect = dialect;
        this.tx = tx;
        this.events = events;
        this.calendars = calendars == null ? CaseFilePorts.Calendars.PLAIN : calendars;
        this.notifier = notifier == null ? CaseFilePorts.Notifier.NONE : notifier;
        this.escalation = escalation == null ? CaseFilePorts.EscalationSeats.NONE : escalation;
        this.defaultZone = defaultZone == null ? ZoneId.of("UTC") : defaultZone;
    }

    public void addListener(DeadlineListener l) {
        listeners.add(l);
    }

    /** Due = now + {@code period} counted on {@code calendar} (working time only when the calendar says so). */
    public Deadline start(Connection c, String tenant, String scopeType, String scopeId, String kind, String ownerSeat,
                          String ownerPerson, String period, String calendar, String warnBefore, List<EscalationStep> ladder) {
        Instant now = Db.now(c, dialect);
        Instant due = add(calendar, now, period);
        return insert(c, tenant, scopeType, scopeId, kind, ownerSeat, ownerPerson, now, due, warnBefore, ladder, calendar);
    }

    /** A due date fixed by an outside body (a sitting date, a hearing date, a statutory date). */
    public Deadline startAt(Connection c, String tenant, String scopeType, String scopeId, String kind, String ownerSeat,
                            String ownerPerson, Instant dueAt, String calendar, String warnBefore, List<EscalationStep> ladder) {
        Db.require(dueAt, "dueAt");
        Instant now = Db.now(c, dialect);
        return insert(c, tenant, scopeType, scopeId, kind, ownerSeat, ownerPerson, now, dueAt, warnBefore, ladder, calendar);
    }

    private Deadline insert(Connection c, String tenant, String scopeType, String scopeId, String kind, String ownerSeat,
                            String ownerPerson, Instant now, Instant due, String warnBefore, List<EscalationStep> ladder,
                            String calendar) {
        String id = Db.newId();
        Instant warn = warnBefore == null ? null : minus(due, warnBefore);
        if (warn != null && warn.isBefore(now)) {
            warn = now;
        }
        List<EscalationStep> steps = ladder == null ? List.of() : ladder;
        Instant next = warn != null ? warn : due;
        List<Map<String, Object>> ladderJson = new ArrayList<>();
        steps.forEach(s -> ladderJson.add(s.toMap()));
        Jdbc.update(c, "INSERT INTO CF_DEADLINE(tenant_id, deadline_id, scope_type, scope_id, kind, owner_seat, owner_person, started_at,"
                        + " due_at, warn_at, state, escalation_level, ladder_json, extension_count, calendar, zone_id, next_check_at, row_version)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,0,?,0,?,?,?,0)",
                Db.tenant(tenant), id, scopeType, scopeId, kind, ownerSeat, ownerPerson, now, due, warn, State.RUNNING,
                Db.json(ladderJson), calendar, defaultZone.getId(), next);
        events.record(c, tenant, scopeType, scopeId, "DEADLINE_STARTED", ownerPerson, kind + " due " + due,
                Db.kv("deadlineId", id, "kind", kind, "dueAt", due.toString(), "ownerSeat", ownerSeat));
        return get(c, tenant, id).orElseThrow();
    }

    public Optional<Deadline> get(Connection c, String tenant, String id) {
        return Jdbc.one(c, "SELECT * FROM CF_DEADLINE WHERE tenant_id=? AND deadline_id=?", DeadlineService::row, Db.tenant(tenant), id);
    }

    public List<Deadline> forScope(Connection c, String tenant, String scopeType, String scopeId) {
        return Jdbc.query(c, "SELECT * FROM CF_DEADLINE WHERE tenant_id=? AND scope_type=? AND scope_id=? ORDER BY due_at",
                DeadlineService::row, Db.tenant(tenant), scopeType, scopeId);
    }

    /** Marks the deadline met (reply sent, action taken); late when after the due time. */
    public Deadline meet(Connection c, String tenant, String id, String actor) {
        Deadline d = open(c, tenant, id);
        Instant now = Db.now(c, dialect);
        Jdbc.update(c, "UPDATE CF_DEADLINE SET state=?, met_at=?, next_check_at=NULL, row_version=row_version+1 WHERE tenant_id=? AND deadline_id=?",
                State.MET, now, Db.tenant(tenant), id);
        events.record(c, tenant, d.scopeType(), d.scopeId(), "DEADLINE_MET", actor, d.kind() + (now.isAfter(d.dueAt()) ? " met late" : " met"),
                Db.kv("deadlineId", id, "late", now.isAfter(d.dueAt())));
        return get(c, tenant, id).orElseThrow();
    }

    /** Meets every open deadline of the scope (optionally only of one kind); returns how many. */
    public int meetScope(Connection c, String tenant, String scopeType, String scopeId, String kindOrNull, String actor) {
        int n = 0;
        for (Deadline d : forScope(c, tenant, scopeType, scopeId)) {
            if (isOpen(d) && (kindOrNull == null || kindOrNull.equals(d.kind()))) {
                meet(c, tenant, d.id(), actor);
                n++;
            }
        }
        return n;
    }

    public void cancel(Connection c, String tenant, String id, String actor, String reason) {
        Deadline d = open(c, tenant, id);
        Jdbc.update(c, "UPDATE CF_DEADLINE SET state=?, next_check_at=NULL, row_version=row_version+1 WHERE tenant_id=? AND deadline_id=?",
                State.CANCELLED, Db.tenant(tenant), id);
        events.record(c, tenant, d.scopeType(), d.scopeId(), "DEADLINE_CANCELLED", actor, reason, Db.kv("deadlineId", id));
    }

    public int cancelScope(Connection c, String tenant, String scopeType, String scopeId, String actor, String reason) {
        int n = 0;
        for (Deadline d : forScope(c, tenant, scopeType, scopeId)) {
            if (isOpen(d)) {
                cancel(c, tenant, d.id(), actor, reason);
                n++;
            }
        }
        return n;
    }

    /** Extension with a reason; a breached deadline runs again when the new due time is in the future. */
    public Deadline extend(Connection c, String tenant, String id, Instant newDue, String reason, String actor, int maxExtensions) {
        Deadline d = open(c, tenant, id);
        Db.requireText(reason, "reason");
        if (maxExtensions >= 0 && d.extensionCount() >= maxExtensions) {
            throw CaseFileException.state("no further extension allowed (" + maxExtensions + ")");
        }
        if (!newDue.isAfter(d.dueAt())) {
            throw CaseFileException.invalid("the new due time must be later than the current one");
        }
        Instant now = Db.now(c, dialect);
        State s = newDue.isAfter(now) ? State.RUNNING : State.BREACHED;
        Instant warn = d.warnAt() == null ? null : newDue.minus(Duration.between(d.warnAt(), d.dueAt()));
        Instant next = s == State.BREACHED ? now : (warn != null && warn.isAfter(now) ? warn : newDue);
        Jdbc.update(c, "UPDATE CF_DEADLINE SET due_at=?, warn_at=?, state=?, escalation_level=0, extension_count=extension_count+1,"
                        + " next_check_at=?, row_version=row_version+1 WHERE tenant_id=? AND deadline_id=?",
                newDue, warn, s, next, Db.tenant(tenant), id);
        events.record(c, tenant, d.scopeType(), d.scopeId(), "DEADLINE_EXTENDED", actor, reason,
                Db.kv("deadlineId", id, "from", d.dueAt().toString(), "to", newDue.toString()));
        return get(c, tenant, id).orElseThrow();
    }

    public void reassign(Connection c, String tenant, String id, String ownerSeat, String ownerPerson) {
        Jdbc.update(c, "UPDATE CF_DEADLINE SET owner_seat=?, owner_person=?, row_version=row_version+1 WHERE tenant_id=? AND deadline_id=?",
                ownerSeat, ownerPerson, Db.tenant(tenant), id);
    }

    /** Moves deadlines of one scope to another (case merge). */
    public int move(Connection c, String tenant, String scopeType, String fromId, String toId) {
        return Jdbc.update(c, "UPDATE CF_DEADLINE SET scope_id=? WHERE tenant_id=? AND scope_type=? AND scope_id=?",
                toId, Db.tenant(tenant), scopeType, fromId);
    }

    /** Open deadlines due before {@code until} (dashboards, "due this week"). */
    public List<Deadline> dueBefore(Connection c, String tenant, String scopeTypeOrNull, Instant until, int limit) {
        String sql = "SELECT * FROM CF_DEADLINE WHERE tenant_id=? AND state IN ('RUNNING','WARNED','BREACHED') AND due_at <= ?"
                + (scopeTypeOrNull == null ? "" : " AND scope_type=?") + " ORDER BY due_at";
        List<Deadline> rows = scopeTypeOrNull == null
                ? Jdbc.query(c, sql, DeadlineService::row, Db.tenant(tenant), until)
                : Jdbc.query(c, sql, DeadlineService::row, Db.tenant(tenant), until, scopeTypeOrNull);
        return rows.stream().limit(Math.max(1, Math.min(limit, 1000))).toList();
    }

    /**
     * One sweep round in its own transaction: warnings, breaches and the next escalation step of up to {@code batch}
     * deadlines. Returns how many rows were processed (call again while it returns {@code batch}).
     */
    public int sweep(int batch) {
        return tx.inTransaction(c -> {
            Instant now = Db.now(c, dialect);
            String sql = dialect.skipLockedSelect("d.*", "CF_DEADLINE", "d",
                    "d.next_check_at IS NOT NULL AND d.next_check_at <= ? AND d.state IN ('RUNNING','WARNED','BREACHED')",
                    "d.next_check_at", Math.max(1, batch));
            List<Deadline> due = Jdbc.query(c, sql, DeadlineService::row, now);
            for (Deadline d : due) {
                process(c, d, now);
            }
            return due.size();
        });
    }

    private void process(Connection c, Deadline d, Instant now) {
        State state = d.state();
        int level = d.escalationLevel();
        if (state == State.RUNNING && d.warnAt() != null && !now.isBefore(d.warnAt()) && now.isBefore(d.dueAt())) {
            state = State.WARNED;
            notify(c, d, d.ownerSeat(), "DEADLINE_WARNING");
            events.record(c, d.tenantId(), d.scopeType(), d.scopeId(), "DEADLINE_WARNED", null, d.kind() + " due " + d.dueAt(),
                    Db.kv("deadlineId", d.id()));
            fire(c, d, "WARNED", null);
        }
        if ((state == State.RUNNING || state == State.WARNED) && !now.isBefore(d.dueAt())) {
            state = State.BREACHED;
            notify(c, d, d.ownerSeat(), "DEADLINE_BREACHED");
            events.record(c, d.tenantId(), d.scopeType(), d.scopeId(), "DEADLINE_BREACHED", null, d.kind() + " overdue",
                    Db.kv("deadlineId", d.id(), "dueAt", d.dueAt().toString()));
            fire(c, d, "BREACHED", null);
        }
        Instant lastEscalated = d.lastEscalatedAt();
        while (level < d.ladder().size()) {
            EscalationStep step = d.ladder().get(level);
            Instant at = plus(d.dueAt(), step.after());
            if (now.isBefore(at)) {
                break;
            }
            String target = target(c, d, step, now);
            level++;
            lastEscalated = now;
            notify(c, d, target, step.messageKind());
            events.record(c, d.tenantId(), d.scopeType(), d.scopeId(), "DEADLINE_ESCALATED", null,
                    d.kind() + " escalated to level " + level, Db.kv("deadlineId", d.id(), "level", level, "toSeat", target));
            fire(c, d, "ESCALATED", target);
        }
        Instant next = nextCheck(d, state, level);
        Jdbc.update(c, "UPDATE CF_DEADLINE SET state=?, escalation_level=?, last_escalated_at=?, next_check_at=?, row_version=row_version+1"
                + " WHERE tenant_id=? AND deadline_id=?", state, level, lastEscalated, next, d.tenantId(), d.id());
    }

    private Instant nextCheck(Deadline d, State state, int level) {
        if (state == State.RUNNING && d.warnAt() != null) {
            return d.warnAt().isBefore(d.dueAt()) ? d.warnAt() : d.dueAt();
        }
        if (state == State.RUNNING || state == State.WARNED) {
            return d.dueAt();
        }
        if (level < d.ladder().size()) {
            return plus(d.dueAt(), d.ladder().get(level).after());
        }
        return null;
    }

    private String target(Connection c, Deadline d, EscalationStep step, Instant now) {
        if (step.target().startsWith("SEAT:")) {
            return step.target().substring(5);
        }
        if (step.target().equals("OWNER") || d.ownerSeat() == null) {
            return d.ownerSeat();
        }
        return escalation.superior(c, d.tenantId(), d.ownerSeat(), now).orElse(d.ownerSeat());
    }

    private void notify(Connection c, Deadline d, String seat, String kind) {
        if (seat == null && d.ownerPerson() == null) {
            return;
        }
        String recipient = seat != null ? "seat:" + seat : "user:" + d.ownerPerson();
        notifier.send(c, new CaseFilePorts.Message(d.tenantId(), recipient, kind, d.kind() + " " + kind.toLowerCase().replace('_', ' '),
                d.kind() + " for " + d.scopeType().toLowerCase() + " " + d.scopeId() + " is due " + d.dueAt(),
                Db.kv("deadlineId", d.id(), "scopeType", d.scopeType(), "scopeId", d.scopeId(), "dueAt", d.dueAt().toString())));
    }

    private void fire(Connection c, Deadline d, String transition, String seat) {
        for (DeadlineListener l : listeners) {
            l.onTransition(c, d, transition, seat);
        }
    }

    private Deadline open(Connection c, String tenant, String id) {
        Deadline d = get(c, tenant, id).orElseThrow(() -> CaseFileException.notFound("deadline", id));
        if (!isOpen(d)) {
            throw CaseFileException.state("deadline is " + d.state());
        }
        return d;
    }

    static boolean isOpen(Deadline d) {
        return d.state() == State.RUNNING || d.state() == State.WARNED || d.state() == State.BREACHED;
    }

    public Instant add(String calendar, Instant from, String iso) {
        Object[] pd = Db.periodAndDuration(iso);
        return calendars.calendar(calendar).add(from, (Period) pd[0], (Duration) pd[1], defaultZone);
    }

    private Instant minus(Instant at, String iso) {
        Object[] pd = Db.periodAndDuration(iso);
        return at.atZone(defaultZone).minus((Period) pd[0]).toInstant().minus((Duration) pd[1]);
    }

    private Instant plus(Instant at, String iso) {
        Object[] pd = Db.periodAndDuration(iso);
        return at.atZone(defaultZone).plus((Period) pd[0]).toInstant().plus((Duration) pd[1]);
    }

    static Deadline row(ResultSet rs) throws SQLException {
        List<EscalationStep> ladder = new ArrayList<>();
        for (Object o : Db.list(rs.getString("ladder_json"))) {
            Map<String, Object> m = Db.obj(o);
            ladder.add(new EscalationStep(Db.str(m, "after", "PT0S"), Db.str(m, "target", "SUPERIOR"),
                    Db.str(m, "messageKind", "DEADLINE_ESCALATED")));
        }
        return new Deadline(rs.getString("tenant_id"), rs.getString("deadline_id"), rs.getString("scope_type"), rs.getString("scope_id"),
                rs.getString("kind"), rs.getString("owner_seat"), rs.getString("owner_person"), Jdbc.instant(rs, "started_at"),
                Jdbc.instant(rs, "due_at"), Jdbc.instant(rs, "warn_at"), State.valueOf(rs.getString("state")),
                rs.getInt("escalation_level"), ladder, Jdbc.instant(rs, "last_escalated_at"), Jdbc.instant(rs, "met_at"),
                rs.getInt("extension_count"), rs.getString("calendar"), Jdbc.instant(rs, "next_check_at"));
    }
}
