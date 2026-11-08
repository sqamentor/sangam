// SPDX-License-Identifier: Apache-2.0
package org.sangam.casefile.meeting;

import org.sangam.casefile.CaseFileException;
import org.sangam.casefile.common.CaseEvents;
import org.sangam.casefile.config.CaseConfig;
import org.sangam.casefile.config.CaseConfigRegistry;
import org.sangam.casefile.deadline.DeadlineService;
import org.sangam.casefile.ports.CaseFilePorts;
import org.sangam.casefile.support.Db;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Action items and action-taken reports (N16 {@code ActionItem}): an owner seat, a due date with the committee kind's
 * SLA ladder (deadline scope ACTION), optional one process per action item (GP-09), ATR submission by the owner and
 * verification by someone else (approve → DONE, return → OPEN again with the reason), drop with reason. The
 * pending / ATR reports feed the next meeting's agenda ("action taken on the previous decisions").
 */
public class ActionItemService {

    public enum Status { OPEN, IN_PROGRESS, ATR_SUBMITTED, DONE, DROPPED }

    public record ActionItem(String tenantId, String actionId, String meetingId, String decisionId, String committeeId, String title,
                             String ownerSeat, String ownerPerson, Instant dueAt, Status status, String atrText, String atrBy,
                             Instant atrAt, String verifiedBy, Instant verifiedAt, String returnReason, String deadlineId,
                             String createdBy, Instant createdAt, long rowVersion) {
    }

    public record CreateRequest(String meetingId, String decisionId, String committeeId, String title, String ownerSeat,
                                String ownerPerson, Instant dueAt, String period) {
    }

    /** Counts per owner seat for the pendency report. */
    public record Pendency(String ownerSeat, int open, int atrSubmitted, int overdue) {
    }

    private final Dialect dialect;
    private final CaseConfigRegistry config;
    private final CommitteeService committees;
    private final MeetingService meetings;
    private final DeadlineService deadlines;
    private final CaseEvents events;
    private final CaseFilePorts.ProcessStarter processes;

    public ActionItemService(Dialect dialect, CaseConfigRegistry config, CommitteeService committees, MeetingService meetings,
                             DeadlineService deadlines, CaseEvents events, CaseFilePorts.ProcessStarter processes) {
        this.dialect = dialect;
        this.config = config;
        this.committees = committees;
        this.meetings = meetings;
        this.deadlines = deadlines;
        this.events = events;
        this.processes = processes == null ? CaseFilePorts.ProcessStarter.NONE : processes;
    }

    public ActionItem create(Connection c, String tenant, CreateRequest r, String actor) {
        String t = Db.tenant(tenant);
        Db.requireText(r.title(), "title");
        if (r.ownerSeat() == null && r.ownerPerson() == null) {
            throw CaseFileException.invalid("an action item needs an owner seat or person");
        }
        String committeeId = r.committeeId();
        if (r.meetingId() != null) {
            MeetingService.Meeting m = meetings.get(c, t, r.meetingId());
            committeeId = m.committeeId();
        }
        if (r.decisionId() != null) {
            MeetingService.Decision d = meetings.decision(c, t, r.decisionId());
            if (r.meetingId() != null && !d.meetingId().equals(r.meetingId())) {
                throw CaseFileException.invalid("decision belongs to another meeting");
            }
        }
        CaseConfig.CommitteeKind kind = committeeId == null ? null : config.committeeKind(c, t, committees.get(c, t, committeeId).kind());
        String id = Db.newId();
        DeadlineService.Deadline dl = r.dueAt() != null
                ? deadlines.startAt(c, t, CaseEvents.ACTION, id, "ACTION_ITEM", r.ownerSeat(), r.ownerPerson(), r.dueAt(),
                kind == null ? null : kind.actionCalendar(), null, kind == null ? List.of() : kind.actionLadder())
                : deadlines.start(c, t, CaseEvents.ACTION, id, "ACTION_ITEM", r.ownerSeat(), r.ownerPerson(),
                r.period() != null ? r.period() : kind == null ? "P30D" : kind.actionPeriod(), kind == null ? null : kind.actionCalendar(),
                null, kind == null ? List.of() : kind.actionLadder());
        Jdbc.update(c, "INSERT INTO CF_ACTION_ITEM(tenant_id, action_id, meeting_id, decision_id, committee_id, title, owner_seat, owner_person,"
                        + " due_at, status, deadline_id, created_by, created_at, row_version) VALUES (?,?,?,?,?,?,?,?,?,?,?,?," + dialect.now() + ",0)",
                t, id, r.meetingId(), r.decisionId(), committeeId, r.title(), r.ownerSeat(), r.ownerPerson(), dl.dueAt(), Status.OPEN,
                dl.id(), actor);
        Map<String, Object> payload = Db.kv("actionId", id, "title", r.title(), "ownerSeat", r.ownerSeat(), "dueAt", dl.dueAt().toString(),
                "meetingId", r.meetingId(), "decisionId", r.decisionId());
        events.record(c, t, CaseEvents.ACTION, id, "ACTION_CREATED", actor, r.title(), payload);
        if (r.meetingId() != null) {
            events.record(c, t, CaseEvents.MEETING, r.meetingId(), "ACTION_CREATED", actor, r.title(), payload);
        }
        if (kind != null && kind.actionProcessKey() != null) {
            processes.start(c, t, kind.actionProcessKey(), "action:" + id, Db.kv("actionId", id, "ownerSeat", r.ownerSeat(),
                    "dueAt", dl.dueAt().toString(), "title", r.title()));
        }
        return get(c, t, id);
    }

    public ActionItem startWork(Connection c, String tenant, String actionId, String actor) {
        ActionItem a = require(c, tenant, actionId, Status.OPEN);
        update(c, tenant, a, "status=?", Status.IN_PROGRESS);
        events.record(c, tenant, CaseEvents.ACTION, actionId, "ACTION_STARTED", actor, null, Db.kv());
        return get(c, tenant, actionId);
    }

    /** The owner's action-taken report. */
    public ActionItem submitAtr(Connection c, String tenant, String actionId, String atrText, String actor) {
        ActionItem a = require(c, tenant, actionId, Status.OPEN, Status.IN_PROGRESS);
        Db.requireText(atrText, "atrText");
        update(c, tenant, a, "status=?, atr_text=?, atr_by=?, atr_at=" + dialect.now() + ", return_reason=NULL", Status.ATR_SUBMITTED, atrText, actor);
        events.record(c, tenant, CaseEvents.ACTION, actionId, "ATR_SUBMITTED", actor, null, Db.kv("actionId", actionId));
        if (a.meetingId() != null) {
            events.record(c, tenant, CaseEvents.MEETING, a.meetingId(), "ATR_SUBMITTED", actor, a.title(), Db.kv("actionId", actionId));
        }
        return get(c, tenant, actionId);
    }

    /** Verification of the ATR by someone other than its author: accept closes the item, return reopens it. */
    public ActionItem verify(Connection c, String tenant, String actionId, boolean accept, String reason, String actor) {
        ActionItem a = require(c, tenant, actionId, Status.ATR_SUBMITTED);
        if (actor.equals(a.atrBy())) {
            throw new CaseFileException(CaseFileException.Code.FOUR_EYES_REQUIRED, "the ATR author cannot verify it");
        }
        if (accept) {
            update(c, tenant, a, "status=?, verified_by=?, verified_at=" + dialect.now(), Status.DONE, actor);
            if (a.deadlineId() != null) {
                deadlines.get(c, tenant, a.deadlineId()).filter(d -> d.metAt() == null && d.state() != DeadlineService.State.CANCELLED)
                        .ifPresent(d -> deadlines.meet(c, tenant, d.id(), actor));
            }
            events.record(c, tenant, CaseEvents.ACTION, actionId, "ACTION_DONE", actor, reason, Db.kv("actionId", actionId));
        } else {
            Db.requireText(reason, "reason");
            update(c, tenant, a, "status=?, return_reason=?", Status.OPEN, reason);
            events.record(c, tenant, CaseEvents.ACTION, actionId, "ATR_RETURNED", actor, reason, Db.kv("actionId", actionId));
        }
        return get(c, tenant, actionId);
    }

    public ActionItem drop(Connection c, String tenant, String actionId, String reason, String actor) {
        ActionItem a = require(c, tenant, actionId, Status.OPEN, Status.IN_PROGRESS, Status.ATR_SUBMITTED);
        Db.requireText(reason, "reason");
        update(c, tenant, a, "status=?, return_reason=?", Status.DROPPED, reason);
        if (a.deadlineId() != null) {
            deadlines.cancelScope(c, tenant, CaseEvents.ACTION, actionId, actor, reason);
        }
        events.record(c, tenant, CaseEvents.ACTION, actionId, "ACTION_DROPPED", actor, reason, Db.kv("actionId", actionId));
        return get(c, tenant, actionId);
    }

    public ActionItem reassign(Connection c, String tenant, String actionId, String ownerSeat, String ownerPerson, String actor) {
        ActionItem a = require(c, tenant, actionId, Status.OPEN, Status.IN_PROGRESS);
        update(c, tenant, a, "owner_seat=?, owner_person=?", ownerSeat, ownerPerson);
        if (a.deadlineId() != null) {
            deadlines.reassign(c, tenant, a.deadlineId(), ownerSeat, ownerPerson);
        }
        events.record(c, tenant, CaseEvents.ACTION, actionId, "ACTION_REASSIGNED", actor, a.ownerSeat() + " -> " + ownerSeat,
                Db.kv("from", a.ownerSeat(), "to", ownerSeat));
        return get(c, tenant, actionId);
    }

    public ActionItem get(Connection c, String tenant, String actionId) {
        return Jdbc.one(c, "SELECT * FROM CF_ACTION_ITEM WHERE tenant_id=? AND action_id=?", ActionItemService::row, Db.tenant(tenant), actionId)
                .orElseThrow(() -> CaseFileException.notFound("action item", actionId));
    }

    /** Action items filtered by meeting, committee, owner seat and openness. */
    public List<ActionItem> list(Connection c, String tenant, String meetingId, String committeeId, String ownerSeat, boolean openOnly) {
        StringBuilder sql = new StringBuilder("SELECT * FROM CF_ACTION_ITEM WHERE tenant_id=?");
        List<Object> p = new ArrayList<>(List.of(Db.tenant(tenant)));
        if (meetingId != null) {
            sql.append(" AND meeting_id=?");
            p.add(meetingId);
        }
        if (committeeId != null) {
            sql.append(" AND committee_id=?");
            p.add(committeeId);
        }
        if (ownerSeat != null) {
            sql.append(" AND owner_seat=?");
            p.add(ownerSeat);
        }
        if (openOnly) {
            sql.append(" AND status IN ('OPEN','IN_PROGRESS','ATR_SUBMITTED')");
        }
        sql.append(" ORDER BY due_at");
        return Jdbc.query(c, sql.toString(), ActionItemService::row, p.toArray());
    }

    /** Pendency per owner seat for a committee (or all committees). */
    public List<Pendency> pendency(Connection c, String tenant, String committeeIdOrNull) {
        Instant now = Db.now(c, dialect);
        Map<String, int[]> acc = new LinkedHashMap<>();
        for (ActionItem a : list(c, tenant, null, committeeIdOrNull, null, true)) {
            int[] v = acc.computeIfAbsent(a.ownerSeat() == null ? "user:" + a.ownerPerson() : a.ownerSeat(), k -> new int[3]);
            if (a.status() == Status.ATR_SUBMITTED) {
                v[1]++;
            } else {
                v[0]++;
            }
            if (a.dueAt() != null && a.dueAt().isBefore(now) && a.status() != Status.ATR_SUBMITTED) {
                v[2]++;
            }
        }
        List<Pendency> out = new ArrayList<>();
        acc.forEach((k, v) -> out.add(new Pendency(k, v[0], v[1], v[2])));
        return out;
    }

    /**
     * The action-taken report of a meeting: every action item from its decisions with status and ATR text, ready to be
     * placed on the next meeting's agenda.
     */
    public List<ActionItem> atrReport(Connection c, String tenant, String meetingId) {
        return list(c, tenant, meetingId, null, null, false);
    }

    /** Places the ATR of {@code previousMeetingId} as an agenda item of {@code nextMeetingId}. */
    public MeetingService.AgendaItem placeAtrOnAgenda(Connection c, String tenant, String previousMeetingId, String nextMeetingId,
                                                      String actor) {
        MeetingService.Meeting prev = meetings.get(c, tenant, previousMeetingId);
        List<ActionItem> items = atrReport(c, tenant, previousMeetingId);
        StringBuilder sb = new StringBuilder();
        for (ActionItem a : items) {
            sb.append("- ").append(a.title()).append(" [").append(a.status()).append("]");
            if (a.atrText() != null) {
                sb.append(": ").append(a.atrText());
            }
            sb.append('\n');
        }
        return meetings.addAgendaItem(c, tenant, nextMeetingId, "Action taken on the decisions of " + prev.meetingNumber(), sb.toString(),
                "meeting", previousMeetingId, null, actor);
    }

    private ActionItem require(Connection c, String tenant, String actionId, Status... allowed) {
        ActionItem a = get(c, tenant, actionId);
        for (Status s : allowed) {
            if (a.status() == s) {
                return a;
            }
        }
        throw CaseFileException.state("action item is " + a.status());
    }

    private void update(Connection c, String tenant, ActionItem a, String set, Object... values) {
        List<Object> p = new ArrayList<>(List.of(values));
        p.add(Db.tenant(tenant));
        p.add(a.actionId());
        p.add(a.rowVersion());
        if (Jdbc.update(c, "UPDATE CF_ACTION_ITEM SET " + set + ", row_version=row_version+1 WHERE tenant_id=? AND action_id=? AND row_version=?",
                p.toArray()) != 1) {
            throw new CaseFileException(CaseFileException.Code.CONCURRENT_UPDATE, "action item changed since it was read");
        }
    }

    static ActionItem row(ResultSet rs) throws SQLException {
        return new ActionItem(rs.getString("tenant_id"), rs.getString("action_id"), rs.getString("meeting_id"), rs.getString("decision_id"),
                rs.getString("committee_id"), rs.getString("title"), rs.getString("owner_seat"), rs.getString("owner_person"),
                Jdbc.instant(rs, "due_at"), Status.valueOf(rs.getString("status")), rs.getString("atr_text"), rs.getString("atr_by"),
                Jdbc.instant(rs, "atr_at"), rs.getString("verified_by"), Jdbc.instant(rs, "verified_at"), rs.getString("return_reason"),
                rs.getString("deadline_id"), rs.getString("created_by"), Jdbc.instant(rs, "created_at"), rs.getLong("row_version"));
    }
}
