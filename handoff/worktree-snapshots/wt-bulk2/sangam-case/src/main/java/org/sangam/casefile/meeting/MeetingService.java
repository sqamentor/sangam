// SPDX-License-Identifier: Apache-2.0
package org.sangam.casefile.meeting;

import org.sangam.casefile.CaseFileException;
import org.sangam.casefile.common.CaseEvents;
import org.sangam.casefile.common.NumberService;
import org.sangam.casefile.config.CaseConfig;
import org.sangam.casefile.config.CaseConfigRegistry;
import org.sangam.casefile.ports.CaseFilePorts;
import org.sangam.casefile.support.Db;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Meetings of a committee (N16): scheduling with venue booking (N01 port), agenda items linked to files, cases and
 * applications, notices with the committee's notice period (urgency needs a reason), attendance and quorum (voting
 * members present, proxies only when the committee's terms allow them, chair rule), start / adjourn / end, decisions
 * per agenda item with votes and dissent notes, and minutes: DRAFT → CIRCULATED_FOR_COMMENT → APPROVED (approver ≠
 * drafter; the chair when the kind says so; optional eSign reference) → ISSUED.
 */
public class MeetingService {

    public enum Status { SCHEDULED, NOTICED, IN_PROGRESS, HELD, ADJOURNED, CANCELLED, MINUTED }

    public enum MinutesStatus { DRAFT, CIRCULATED_FOR_COMMENT, APPROVED, ISSUED, SUPERSEDED }

    public static final Set<String> MODES = Set.of("IN_PERSON", "VIRTUAL", "HYBRID", "CIRCULATION");
    public static final Set<String> ATTENDANCE = Set.of("PRESENT", "ABSENT", "LEAVE", "PROXY");
    public static final Set<String> OUTCOMES = Set.of("APPROVED", "REJECTED", "DEFERRED", "NOTED", "MODIFIED", "WITHDRAWN");

    public record Meeting(String tenantId, String meetingId, String committeeId, String meetingNumber, String title,
                          Instant scheduledAt, Instant endsAt, String venue, String venueBookingRef, String mode, Status status,
                          int quorumRequired, Integer quorumPresent, String chairMemberId, String urgentReason, String caseId,
                          String adjournedTo, Instant startedAt, Instant endedAt, String createdBy, Instant createdAt, long rowVersion) {
    }

    public record ScheduleRequest(String committeeId, String title, Instant scheduledAt, Instant endsAt, String venue, String mode,
                                  String urgentReason, String caseId) {
    }

    public record AgendaItem(String tenantId, String itemId, String meetingId, int seq, String title, String description,
                             String linkedType, String linkedRef, String presenter, String status, String carriedFrom,
                             String addedBy, Instant addedAt) {
    }

    public record Attendance(String meetingId, String attendeeRef, String memberId, String attendance, boolean voting,
                             String proxyFor, String markedBy, Instant markedAt) {
    }

    public record Notice(String noticeId, String meetingId, String kind, String recipientRef, String channel, String contentHash,
                         String deliveryRef, String sentBy, Instant sentAt, Instant acknowledgedAt) {
    }

    public record Minutes(String meetingId, int version, String body, MinutesStatus status, String contentHash, String draftedBy,
                          Instant draftedAt, List<Object> comments, String approvedBy, Instant approvedAt, String esignRef,
                          Instant circulatedAt) {
    }

    public record Decision(String tenantId, String decisionId, String meetingId, String itemId, String text, String outcome,
                           int votesFor, int votesAgainst, int abstentions, List<Object> dissent, String recordedBy,
                           Instant recordedAt) {
    }

    public record DecisionInput(String itemId, String text, String outcome, int votesFor, int votesAgainst, int abstentions,
                                List<Map<String, Object>> dissent) {
    }

    private final Dialect dialect;
    private final CaseConfigRegistry config;
    private final CommitteeService committees;
    private final NumberService numbers;
    private final CaseEvents events;
    private final CaseFilePorts.Notifier notifier;
    private final CaseFilePorts.VenueBooking venues;

    public MeetingService(Dialect dialect, CaseConfigRegistry config, CommitteeService committees, NumberService numbers,
                          CaseEvents events, CaseFilePorts.Notifier notifier, CaseFilePorts.VenueBooking venues) {
        this.dialect = dialect;
        this.config = config;
        this.committees = committees;
        this.numbers = numbers;
        this.events = events;
        this.notifier = notifier == null ? CaseFilePorts.Notifier.NONE : notifier;
        this.venues = venues == null ? CaseFilePorts.VenueBooking.NONE : venues;
    }

    // ------------------------------------------------------------------ scheduling

    public Meeting schedule(Connection c, String tenant, ScheduleRequest r, String actor) {
        String t = Db.tenant(tenant);
        CommitteeService.Committee cm = committees.requireActive(c, t, Db.requireText(r.committeeId(), "committeeId"));
        CaseConfig.CommitteeKind kind = config.committeeKind(c, t, cm.kind());
        Instant now = Db.now(c, dialect);
        Db.require(r.scheduledAt(), "scheduledAt");
        if (!r.scheduledAt().isAfter(now)) {
            throw CaseFileException.invalid("a meeting must be scheduled in the future");
        }
        String mode = r.mode() == null ? "IN_PERSON" : r.mode();
        if (!MODES.contains(mode)) {
            throw CaseFileException.invalid("mode must be one of " + MODES);
        }
        Instant ends = r.endsAt() == null ? r.scheduledAt().plus(Duration.ofHours(2)) : r.endsAt();
        String booking = null;
        if (r.venue() != null && !"VIRTUAL".equals(mode) && !"CIRCULATION".equals(mode)) {
            booking = venues.book(c, t, r.venue(), r.scheduledAt(), ends, "committee:" + cm.committeeId())
                    .orElseThrow(() -> CaseFileException.state("venue " + r.venue() + " is not available"));
        }
        String id = Db.newId();
        String number = numbers.next(c, t, kind.meetingNumbering(), cm.orgUnit(), r.scheduledAt());
        String chair = committees.chair(c, t, cm.committeeId()).map(CommitteeService.Member::memberId).orElse(null);
        Jdbc.update(c, "INSERT INTO CF_MEETING(tenant_id, meeting_id, committee_id, meeting_number, title, scheduled_at, ends_at, venue,"
                        + " venue_booking_ref, meeting_mode, status, quorum_required, chair_member_id, urgent_reason, case_id, created_by, created_at,"
                        + " row_version) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)",
                t, id, cm.committeeId(), number, Db.requireText(r.title(), "title"), r.scheduledAt(), ends, r.venue(), booking, mode,
                Status.SCHEDULED, committees.quorumRequired(c, t, cm.committeeId()), chair, r.urgentReason(), r.caseId(), actor, now);
        events.record(c, t, CaseEvents.MEETING, id, "MEETING_SCHEDULED", actor, number + " " + r.title(),
                Db.kv("committeeId", cm.committeeId(), "scheduledAt", r.scheduledAt().toString(), "venue", r.venue(), "mode", mode));
        events.record(c, t, CaseEvents.COMMITTEE, cm.committeeId(), "MEETING_SCHEDULED", actor, number, Db.kv("meetingId", id));
        return get(c, t, id);
    }

    public Meeting reschedule(Connection c, String tenant, String meetingId, Instant newAt, Instant newEnds, String venue, String reason,
                              String actor) {
        String t = Db.tenant(tenant);
        Meeting m = requireStatus(c, t, meetingId, Status.SCHEDULED, Status.NOTICED);
        Db.requireText(reason, "reason");
        if (m.venueBookingRef() != null) {
            venues.release(c, t, m.venueBookingRef());
        }
        Instant ends = newEnds == null ? newAt.plus(Duration.between(m.scheduledAt(), m.endsAt())) : newEnds;
        String v = venue == null ? m.venue() : venue;
        String booking = null;
        if (v != null && !"VIRTUAL".equals(m.mode()) && !"CIRCULATION".equals(m.mode())) {
            booking = venues.book(c, t, v, newAt, ends, "committee:" + m.committeeId())
                    .orElseThrow(() -> CaseFileException.state("venue " + v + " is not available"));
        }
        Jdbc.update(c, "UPDATE CF_MEETING SET scheduled_at=?, ends_at=?, venue=?, venue_booking_ref=?, row_version=row_version+1"
                + " WHERE tenant_id=? AND meeting_id=?", newAt, ends, v, booking, t, meetingId);
        events.record(c, t, CaseEvents.MEETING, meetingId, "MEETING_RESCHEDULED", actor, reason,
                Db.kv("from", m.scheduledAt().toString(), "to", newAt.toString(), "venue", v));
        if (m.status() == Status.NOTICED) {
            sendNotices(c, t, meetingId, "CORRIGENDUM", actor);
        }
        return get(c, t, meetingId);
    }

    public Meeting cancel(Connection c, String tenant, String meetingId, String reason, String actor) {
        String t = Db.tenant(tenant);
        Meeting m = requireStatus(c, t, meetingId, Status.SCHEDULED, Status.NOTICED);
        Db.requireText(reason, "reason");
        if (m.venueBookingRef() != null) {
            venues.release(c, t, m.venueBookingRef());
        }
        setStatus(c, t, meetingId, Status.CANCELLED);
        events.record(c, t, CaseEvents.MEETING, meetingId, "MEETING_CANCELLED", actor, reason, Db.kv("reason", reason));
        if (m.status() == Status.NOTICED) {
            sendNotices(c, t, meetingId, "CANCELLATION", actor);
        }
        return get(c, t, meetingId);
    }

    public Meeting get(Connection c, String tenant, String meetingId) {
        return Jdbc.one(c, "SELECT * FROM CF_MEETING WHERE tenant_id=? AND meeting_id=?", MeetingService::row, Db.tenant(tenant), meetingId)
                .orElseThrow(() -> CaseFileException.notFound("meeting", meetingId));
    }

    public List<Meeting> list(Connection c, String tenant, String committeeIdOrNull, Instant from, Instant to) {
        StringBuilder sql = new StringBuilder("SELECT * FROM CF_MEETING WHERE tenant_id=?");
        List<Object> p = new ArrayList<>(List.of(Db.tenant(tenant)));
        if (committeeIdOrNull != null) {
            sql.append(" AND committee_id=?");
            p.add(committeeIdOrNull);
        }
        if (from != null) {
            sql.append(" AND scheduled_at >= ?");
            p.add(from);
        }
        if (to != null) {
            sql.append(" AND scheduled_at < ?");
            p.add(to);
        }
        sql.append(" ORDER BY scheduled_at DESC");
        return Jdbc.query(c, sql.toString(), MeetingService::row, p.toArray());
    }

    // ------------------------------------------------------------------ agenda

    public AgendaItem addAgendaItem(Connection c, String tenant, String meetingId, String title, String description, String linkedType,
                                    String linkedRef, String presenter, String actor) {
        String t = Db.tenant(tenant);
        requireStatus(c, t, meetingId, Status.SCHEDULED, Status.NOTICED, Status.IN_PROGRESS);
        int seq = Jdbc.one(c, "SELECT MAX(seq) AS s FROM CF_AGENDA_ITEM WHERE tenant_id=? AND meeting_id=?", rs -> rs.getInt("s"), t, meetingId)
                .orElse(0) + 1;
        String id = insertItem(c, t, meetingId, seq, title, description, linkedType, linkedRef, presenter, null, actor);
        events.record(c, t, CaseEvents.MEETING, meetingId, "AGENDA_ITEM_ADDED", actor, title,
                Db.kv("itemId", id, "seq", seq, "linkedType", linkedType, "linkedRef", linkedRef));
        if ("case".equals(linkedType) && linkedRef != null) {
            events.record(c, t, CaseEvents.CASE, linkedRef, "PLACED_ON_AGENDA", actor, title, Db.kv("meetingId", meetingId, "itemId", id));
        }
        return item(c, t, id);
    }

    public void withdrawAgendaItem(Connection c, String tenant, String itemId, String reason, String actor) {
        AgendaItem i = item(c, tenant, itemId);
        if (!"PENDING".equals(i.status())) {
            throw CaseFileException.state("agenda item is " + i.status());
        }
        Jdbc.update(c, "UPDATE CF_AGENDA_ITEM SET status='WITHDRAWN' WHERE tenant_id=? AND item_id=?", Db.tenant(tenant), itemId);
        events.record(c, tenant, CaseEvents.MEETING, i.meetingId(), "AGENDA_ITEM_WITHDRAWN", actor, reason, Db.kv("itemId", itemId));
    }

    /** Sets the order of the agenda (all item ids of the meeting, in the new order). */
    public void reorder(Connection c, String tenant, String meetingId, List<String> itemIds, String actor) {
        String t = Db.tenant(tenant);
        List<AgendaItem> items = agenda(c, t, meetingId);
        if (items.size() != itemIds.size() || !items.stream().map(AgendaItem::itemId).toList().containsAll(itemIds)) {
            throw CaseFileException.invalid("reorder needs every agenda item of the meeting exactly once");
        }
        for (int i = 0; i < itemIds.size(); i++) {
            Jdbc.update(c, "UPDATE CF_AGENDA_ITEM SET seq=? WHERE tenant_id=? AND item_id=?", i + 1, t, itemIds.get(i));
        }
        events.record(c, t, CaseEvents.MEETING, meetingId, "AGENDA_REORDERED", actor, null, Db.kv("items", itemIds));
    }

    public List<AgendaItem> agenda(Connection c, String tenant, String meetingId) {
        return Jdbc.query(c, "SELECT * FROM CF_AGENDA_ITEM WHERE tenant_id=? AND meeting_id=? ORDER BY seq", MeetingService::itemRow,
                Db.tenant(tenant), meetingId);
    }

    public AgendaItem item(Connection c, String tenant, String itemId) {
        return Jdbc.one(c, "SELECT * FROM CF_AGENDA_ITEM WHERE tenant_id=? AND item_id=?", MeetingService::itemRow, Db.tenant(tenant), itemId)
                .orElseThrow(() -> CaseFileException.notFound("agenda item", itemId));
    }

    /** Carries undecided and deferred items to a later meeting of the same committee. */
    public int carryForward(Connection c, String tenant, String fromMeetingId, String toMeetingId, String actor) {
        String t = Db.tenant(tenant);
        Meeting from = get(c, t, fromMeetingId);
        Meeting to = requireStatus(c, t, toMeetingId, Status.SCHEDULED, Status.NOTICED);
        if (!from.committeeId().equals(to.committeeId())) {
            throw CaseFileException.invalid("items can only be carried to a meeting of the same committee");
        }
        int seq = Jdbc.one(c, "SELECT MAX(seq) AS s FROM CF_AGENDA_ITEM WHERE tenant_id=? AND meeting_id=?", rs -> rs.getInt("s"), t, toMeetingId)
                .orElse(0);
        int n = 0;
        for (AgendaItem i : agenda(c, t, fromMeetingId)) {
            if ("PENDING".equals(i.status()) || "DEFERRED".equals(i.status())) {
                insertItem(c, t, toMeetingId, ++seq, i.title(), i.description(), i.linkedType(), i.linkedRef(), i.presenter(), i.itemId(), actor);
                Jdbc.update(c, "UPDATE CF_AGENDA_ITEM SET status='CARRIED_FORWARD' WHERE tenant_id=? AND item_id=?", t, i.itemId());
                n++;
            }
        }
        events.record(c, t, CaseEvents.MEETING, toMeetingId, "AGENDA_CARRIED_FORWARD", actor, n + " items",
                Db.kv("fromMeetingId", fromMeetingId, "items", n));
        return n;
    }

    private String insertItem(Connection c, String t, String meetingId, int seq, String title, String description, String linkedType,
                              String linkedRef, String presenter, String carriedFrom, String actor) {
        String id = Db.newId();
        Jdbc.update(c, "INSERT INTO CF_AGENDA_ITEM(tenant_id, item_id, meeting_id, seq, title, description, linked_type, linked_ref, presenter,"
                        + " status, carried_from, added_by, added_at) VALUES (?,?,?,?,?,?,?,?,?,'PENDING',?,?," + dialect.now() + ")",
                t, id, meetingId, seq, Db.requireText(title, "title"), description, linkedType, linkedRef, presenter, carriedFrom, actor);
        return id;
    }

    // ------------------------------------------------------------------ notices

    /**
     * Sends a notice of {@code kind} (NOTICE, AGENDA, CORRIGENDUM, CANCELLATION, MINUTES) to every current member. The
     * first NOTICE checks the committee's notice period unless the meeting carries an urgency reason.
     */
    public List<Notice> sendNotices(Connection c, String tenant, String meetingId, String kind, String actor) {
        String t = Db.tenant(tenant);
        Meeting m = get(c, t, meetingId);
        CommitteeService.Committee cm = committees.get(c, t, m.committeeId());
        Instant now = Db.now(c, dialect);
        if ("NOTICE".equals(kind)) {
            if (m.status() != Status.SCHEDULED && m.status() != Status.NOTICED) {
                throw CaseFileException.state("meeting is " + m.status());
            }
            long hours = Duration.between(now, m.scheduledAt()).toHours();
            if (hours < cm.minNoticeHours() && (m.urgentReason() == null || m.urgentReason().isBlank())) {
                throw new CaseFileException(CaseFileException.Code.NOTICE_PERIOD,
                        "notice period is " + cm.minNoticeHours() + " hours; record an urgency reason to call the meeting earlier");
            }
        }
        List<AgendaItem> agenda = agenda(c, t, meetingId);
        Map<String, Object> content = Db.kv("kind", kind, "meetingNumber", m.meetingNumber(), "title", m.title(),
                "scheduledAt", m.scheduledAt().toString(), "venue", m.venue(), "mode", m.mode(),
                "agenda", agenda.stream().filter(i -> !"WITHDRAWN".equals(i.status())).map(i -> i.seq() + ". " + i.title()).toList());
        String hash = Db.hash(content);
        List<Notice> out = new ArrayList<>();
        for (CommitteeService.Member mem : committees.members(c, t, m.committeeId(), null)) {
            String delivery = notifier.send(c, new CaseFilePorts.Message(t, mem.ref(), "MEETING_" + kind,
                    kind + ": " + m.title(), m.title() + " on " + m.scheduledAt(), content));
            String id = Db.newId();
            Jdbc.update(c, "INSERT INTO CF_NOTICE(tenant_id, notice_id, meeting_id, notice_kind, recipient_ref, channel, content_hash,"
                    + " delivery_ref, sent_by, sent_at) VALUES (?,?,?,?,?,?,?,?,?,?)", t, id, meetingId, kind, mem.ref(), null, hash, delivery, actor, now);
            out.add(new Notice(id, meetingId, kind, mem.ref(), null, hash, delivery, actor, now, null));
        }
        if ("NOTICE".equals(kind) && m.status() == Status.SCHEDULED) {
            setStatus(c, t, meetingId, Status.NOTICED);
        }
        events.record(c, t, CaseEvents.MEETING, meetingId, "NOTICES_SENT", actor, kind + " to " + out.size(),
                Db.kv("kind", kind, "recipients", out.size(), "contentHash", hash));
        return out;
    }

    public void acknowledgeNotice(Connection c, String tenant, String noticeId) {
        Jdbc.update(c, "UPDATE CF_NOTICE SET acknowledged_at=" + dialect.now() + " WHERE tenant_id=? AND notice_id=? AND acknowledged_at IS NULL",
                Db.tenant(tenant), noticeId);
    }

    public List<Notice> notices(Connection c, String tenant, String meetingId) {
        return Jdbc.query(c, "SELECT * FROM CF_NOTICE WHERE tenant_id=? AND meeting_id=? ORDER BY sent_at", rs -> new Notice(
                rs.getString("notice_id"), rs.getString("meeting_id"), rs.getString("notice_kind"), rs.getString("recipient_ref"),
                rs.getString("channel"), rs.getString("content_hash"), rs.getString("delivery_ref"), rs.getString("sent_by"),
                Jdbc.instant(rs, "sent_at"), Jdbc.instant(rs, "acknowledged_at")), Db.tenant(tenant), meetingId);
    }

    // ------------------------------------------------------------------ attendance, quorum, conduct

    /** Marks a member (by member id) or a guest (attendeeRef only, never voting). PROXY needs {@code proxyFor}. */
    public Attendance markAttendance(Connection c, String tenant, String meetingId, String memberId, String attendeeRef,
                                     String attendance, String proxyFor, String actor) {
        String t = Db.tenant(tenant);
        Meeting m = requireStatus(c, t, meetingId, Status.SCHEDULED, Status.NOTICED, Status.IN_PROGRESS);
        if (!ATTENDANCE.contains(attendance)) {
            throw CaseFileException.invalid("attendance must be one of " + ATTENDANCE);
        }
        String ref = attendeeRef;
        boolean voting = false;
        if (memberId != null) {
            CommitteeService.Member mem = committees.member(c, t, memberId);
            if (!mem.committeeId().equals(m.committeeId()) || mem.validTo() != null) {
                throw CaseFileException.invalid("not a current member of the committee");
            }
            ref = mem.ref();
            voting = mem.voting();
        }
        Db.requireText(ref, "attendeeRef");
        if ("PROXY".equals(attendance)) {
            CommitteeService.Committee cm = committees.get(c, t, m.committeeId());
            if (!Db.boolVal(cm.terms(), "proxyAllowed", false)) {
                throw CaseFileException.invalid("the committee's terms do not allow proxies");
            }
            CommitteeService.Member principal = committees.member(c, t, Db.requireText(proxyFor, "proxyFor"));
            voting = principal.voting();
        }
        Jdbc.update(c, "DELETE FROM CF_ATTENDANCE WHERE tenant_id=? AND meeting_id=? AND attendee_ref=?", t, meetingId, ref);
        Jdbc.update(c, "INSERT INTO CF_ATTENDANCE(tenant_id, meeting_id, attendee_ref, member_id, attendance, voting, proxy_for, marked_by, marked_at)"
                + " VALUES (?,?,?,?,?,?,?,?," + dialect.now() + ")", t, meetingId, ref, memberId, attendance, Db.small(voting), proxyFor, actor);
        String marked = ref;
        return attendance(c, t, meetingId).stream().filter(a -> a.attendeeRef().equals(marked)).findFirst().orElseThrow();
    }

    public List<Attendance> attendance(Connection c, String tenant, String meetingId) {
        return Jdbc.query(c, "SELECT * FROM CF_ATTENDANCE WHERE tenant_id=? AND meeting_id=? ORDER BY attendee_ref", rs -> new Attendance(
                rs.getString("meeting_id"), rs.getString("attendee_ref"), rs.getString("member_id"), rs.getString("attendance"),
                rs.getInt("voting") != 0, rs.getString("proxy_for"), rs.getString("marked_by"), Jdbc.instant(rs, "marked_at")),
                Db.tenant(tenant), meetingId);
    }

    /** Present voting members (members and proxies) and whether quorum and the chair rule are met. */
    public record QuorumStatus(int required, int presentVoting, boolean chairPresent, boolean chairRequired, boolean met) {
    }

    public QuorumStatus quorum(Connection c, String tenant, String meetingId) {
        String t = Db.tenant(tenant);
        Meeting m = get(c, t, meetingId);
        CommitteeService.Committee cm = committees.get(c, t, m.committeeId());
        List<Attendance> att = attendance(c, t, meetingId);
        int present = (int) att.stream().filter(a -> a.voting() && ("PRESENT".equals(a.attendance()) || "PROXY".equals(a.attendance()))).count();
        boolean chair = m.chairMemberId() != null && att.stream().anyMatch(a -> m.chairMemberId().equals(a.memberId())
                && "PRESENT".equals(a.attendance()));
        int required = committees.quorumRequired(c, t, m.committeeId());
        return new QuorumStatus(required, present, chair, cm.chairRequired(), present >= required && (chair || !cm.chairRequired()));
    }

    /** Opens the meeting; refuses without quorum (a CIRCULATION meeting counts the members who have responded). */
    public Meeting start(Connection c, String tenant, String meetingId, String actor) {
        String t = Db.tenant(tenant);
        requireStatus(c, t, meetingId, Status.SCHEDULED, Status.NOTICED);
        QuorumStatus q = quorum(c, t, meetingId);
        if (!q.met()) {
            throw new CaseFileException(CaseFileException.Code.QUORUM_NOT_MET, "quorum not met: " + q.presentVoting() + " of " + q.required()
                    + (q.chairRequired() && !q.chairPresent() ? ", chair absent" : ""));
        }
        Jdbc.update(c, "UPDATE CF_MEETING SET status=?, quorum_present=?, started_at=" + dialect.now() + ", row_version=row_version+1"
                + " WHERE tenant_id=? AND meeting_id=?", Status.IN_PROGRESS, q.presentVoting(), t, meetingId);
        events.record(c, t, CaseEvents.MEETING, meetingId, "MEETING_STARTED", actor, q.presentVoting() + " present",
                Db.kv("quorumRequired", q.required(), "present", q.presentVoting()));
        return get(c, t, meetingId);
    }

    /** Adjourns (for want of quorum or by decision) and, when {@code newAt} is given, schedules the adjourned sitting. */
    public Meeting adjourn(Connection c, String tenant, String meetingId, String reason, Instant newAt, String actor) {
        String t = Db.tenant(tenant);
        Meeting m = requireStatus(c, t, meetingId, Status.SCHEDULED, Status.NOTICED, Status.IN_PROGRESS);
        Db.requireText(reason, "reason");
        String next = null;
        if (newAt != null) {
            Meeting n = schedule(c, t, new ScheduleRequest(m.committeeId(), m.title() + " (adjourned)", newAt, null, m.venue(), m.mode(),
                    m.urgentReason() == null ? "adjourned sitting of " + m.meetingNumber() : m.urgentReason(), m.caseId()), actor);
            next = n.meetingId();
            carryForward(c, t, meetingId, next, actor);
        }
        if (m.venueBookingRef() != null) {
            venues.release(c, t, m.venueBookingRef());
        }
        Jdbc.update(c, "UPDATE CF_MEETING SET status=?, adjourned_to=?, ended_at=" + dialect.now() + ", row_version=row_version+1"
                + " WHERE tenant_id=? AND meeting_id=?", Status.ADJOURNED, next, t, meetingId);
        events.record(c, t, CaseEvents.MEETING, meetingId, "MEETING_ADJOURNED", actor, reason, Db.kv("reason", reason, "adjournedTo", next));
        return get(c, t, meetingId);
    }

    public Meeting end(Connection c, String tenant, String meetingId, String actor) {
        String t = Db.tenant(tenant);
        Meeting m = requireStatus(c, t, meetingId, Status.IN_PROGRESS);
        Jdbc.update(c, "UPDATE CF_MEETING SET status=?, ended_at=" + dialect.now() + ", row_version=row_version+1 WHERE tenant_id=? AND meeting_id=?",
                Status.HELD, t, meetingId);
        if (m.venueBookingRef() != null) {
            venues.release(c, t, m.venueBookingRef());
        }
        events.record(c, t, CaseEvents.MEETING, meetingId, "MEETING_HELD", actor, null, Db.kv());
        return get(c, t, meetingId);
    }

    // ------------------------------------------------------------------ decisions

    public Decision recordDecision(Connection c, String tenant, String meetingId, DecisionInput in, String actor) {
        String t = Db.tenant(tenant);
        Meeting m = requireStatus(c, t, meetingId, Status.IN_PROGRESS, Status.HELD);
        String outcome = Db.requireText(in.outcome(), "outcome");
        if (!OUTCOMES.contains(outcome)) {
            throw CaseFileException.invalid("outcome must be one of " + OUTCOMES);
        }
        if (in.votesFor() < 0 || in.votesAgainst() < 0 || in.abstentions() < 0) {
            throw CaseFileException.invalid("vote counts cannot be negative");
        }
        int cast = in.votesFor() + in.votesAgainst() + in.abstentions();
        if (m.quorumPresent() != null && cast > m.quorumPresent()) {
            throw CaseFileException.invalid("more votes (" + cast + ") than voting members present (" + m.quorumPresent() + ")");
        }
        AgendaItem item = null;
        if (in.itemId() != null) {
            item = item(c, t, in.itemId());
            if (!item.meetingId().equals(meetingId)) {
                throw CaseFileException.invalid("agenda item belongs to another meeting");
            }
        }
        String id = Db.newId();
        Jdbc.update(c, "INSERT INTO CF_DECISION(tenant_id, decision_id, meeting_id, item_id, decision_text, outcome, votes_for, votes_against,"
                        + " abstentions, dissent_json, recorded_by, recorded_at) VALUES (?,?,?,?,?,?,?,?,?,?,?," + dialect.now() + ")",
                t, id, meetingId, in.itemId(), Db.requireText(in.text(), "text"), outcome, in.votesFor(), in.votesAgainst(), in.abstentions(),
                Db.json(in.dissent() == null ? List.of() : in.dissent()), actor);
        if (item != null) {
            String st = "DEFERRED".equals(outcome) ? "DEFERRED" : "WITHDRAWN".equals(outcome) ? "WITHDRAWN" : "DECIDED";
            Jdbc.update(c, "UPDATE CF_AGENDA_ITEM SET status=? WHERE tenant_id=? AND item_id=?", st, t, in.itemId());
            if ("case".equals(item.linkedType()) && item.linkedRef() != null) {
                events.record(c, t, CaseEvents.CASE, item.linkedRef(), "COMMITTEE_DECISION", actor, outcome,
                        Db.kv("meetingId", meetingId, "decisionId", id, "outcome", outcome, "text", in.text()));
            }
        }
        events.record(c, t, CaseEvents.MEETING, meetingId, "DECISION_RECORDED", actor, outcome,
                Db.kv("decisionId", id, "itemId", in.itemId(), "outcome", outcome, "for", in.votesFor(), "against", in.votesAgainst(),
                        "abstain", in.abstentions()));
        return decision(c, t, id);
    }

    public Decision decision(Connection c, String tenant, String decisionId) {
        return Jdbc.one(c, "SELECT * FROM CF_DECISION WHERE tenant_id=? AND decision_id=?", MeetingService::decisionRow, Db.tenant(tenant), decisionId)
                .orElseThrow(() -> CaseFileException.notFound("decision", decisionId));
    }

    public List<Decision> decisions(Connection c, String tenant, String meetingId) {
        return Jdbc.query(c, "SELECT * FROM CF_DECISION WHERE tenant_id=? AND meeting_id=? ORDER BY recorded_at", MeetingService::decisionRow,
                Db.tenant(tenant), meetingId);
    }

    // ------------------------------------------------------------------ minutes

    public Minutes draftMinutes(Connection c, String tenant, String meetingId, String body, String actor) {
        String t = Db.tenant(tenant);
        requireStatus(c, t, meetingId, Status.HELD, Status.ADJOURNED, Status.MINUTED);
        Optional<Minutes> latest = latestMinutes(c, t, meetingId);
        if (latest.isPresent() && (latest.get().status() == MinutesStatus.APPROVED || latest.get().status() == MinutesStatus.ISSUED)) {
            throw CaseFileException.state("minutes are " + latest.get().status() + "; record a correction as a new meeting decision");
        }
        int version = latest.map(x -> x.version() + 1).orElse(1);
        latest.ifPresent(x -> Jdbc.update(c, "UPDATE CF_MINUTES SET status='SUPERSEDED' WHERE tenant_id=? AND meeting_id=? AND version=?",
                t, meetingId, x.version()));
        Jdbc.update(c, "INSERT INTO CF_MINUTES(tenant_id, meeting_id, version, body, status, content_hash, drafted_by, drafted_at, comments_json)"
                + " VALUES (?,?,?,?,?,?,?," + dialect.now() + ",'[]')", t, meetingId, version, Db.requireText(body, "body"), MinutesStatus.DRAFT,
                Db.hashText(body), actor);
        events.record(c, t, CaseEvents.MEETING, meetingId, "MINUTES_DRAFTED", actor, "version " + version, Db.kv("version", version));
        return minutes(c, t, meetingId, version);
    }

    public Minutes circulateForComment(Connection c, String tenant, String meetingId, String actor) {
        String t = Db.tenant(tenant);
        Minutes mn = latestMinutes(c, t, meetingId).orElseThrow(() -> CaseFileException.notFound("minutes", meetingId));
        if (mn.status() != MinutesStatus.DRAFT) {
            throw CaseFileException.state("minutes are " + mn.status());
        }
        Jdbc.update(c, "UPDATE CF_MINUTES SET status=? WHERE tenant_id=? AND meeting_id=? AND version=?", MinutesStatus.CIRCULATED_FOR_COMMENT,
                t, meetingId, mn.version());
        sendNotices(c, t, meetingId, "DRAFT_MINUTES", actor);
        return minutes(c, t, meetingId, mn.version());
    }

    public Minutes comment(Connection c, String tenant, String meetingId, String memberRef, String text, String actor) {
        String t = Db.tenant(tenant);
        Minutes mn = latestMinutes(c, t, meetingId).orElseThrow(() -> CaseFileException.notFound("minutes", meetingId));
        if (mn.status() != MinutesStatus.CIRCULATED_FOR_COMMENT && mn.status() != MinutesStatus.DRAFT) {
            throw CaseFileException.state("minutes are " + mn.status());
        }
        List<Object> comments = new ArrayList<>(mn.comments());
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("by", memberRef == null ? actor : memberRef);
        entry.put("text", Db.requireText(text, "text"));
        entry.put("at", Db.now(c, dialect).toString());
        comments.add(entry);
        Jdbc.update(c, "UPDATE CF_MINUTES SET comments_json=? WHERE tenant_id=? AND meeting_id=? AND version=?", Db.json(comments), t, meetingId,
                mn.version());
        return minutes(c, t, meetingId, mn.version());
    }

    /** Approval binds the content hash; the approver is not the drafter and, when the kind says so, is the chair. */
    public Minutes approveMinutes(Connection c, String tenant, String meetingId, int version, String expectedContentHash,
                                  String approverPerson, java.util.Set<String> approverSeats, String esignRef) {
        String t = Db.tenant(tenant);
        Minutes mn = minutes(c, t, meetingId, version);
        if (mn.status() != MinutesStatus.DRAFT && mn.status() != MinutesStatus.CIRCULATED_FOR_COMMENT) {
            throw CaseFileException.state("minutes are " + mn.status());
        }
        if (expectedContentHash != null && !expectedContentHash.equals(mn.contentHash())) {
            throw CaseFileException.state("the minutes changed since they were read");
        }
        if (mn.draftedBy().equals(approverPerson)) {
            throw new CaseFileException(CaseFileException.Code.FOUR_EYES_REQUIRED, "the drafter cannot approve the minutes");
        }
        Meeting m = get(c, t, meetingId);
        CommitteeService.Committee cm = committees.get(c, t, m.committeeId());
        CaseConfig.CommitteeKind kind = config.committeeKind(c, t, cm.kind());
        if (kind.minutesApprovalByChair() && m.chairMemberId() != null) {
            CommitteeService.Member chair = committees.member(c, t, m.chairMemberId());
            boolean isChair = approverPerson.equals(chair.personRef())
                    || (chair.seatRef() != null && approverSeats != null && approverSeats.contains(chair.seatRef()));
            if (!isChair) {
                throw new CaseFileException(CaseFileException.Code.ACCESS_DENIED, "minutes are approved by the chair");
            }
        }
        Jdbc.update(c, "UPDATE CF_MINUTES SET status=?, approved_by=?, approved_at=" + dialect.now() + ", esign_ref=? WHERE tenant_id=?"
                + " AND meeting_id=? AND version=?", MinutesStatus.APPROVED, approverPerson, esignRef, t, meetingId, version);
        events.record(c, t, CaseEvents.MEETING, meetingId, "MINUTES_APPROVED", approverPerson, "version " + version,
                Db.kv("version", version, "contentHash", mn.contentHash(), "esignRef", esignRef));
        return minutes(c, t, meetingId, version);
    }

    /** Issues approved minutes to every member; the meeting becomes MINUTED. */
    public Minutes issueMinutes(Connection c, String tenant, String meetingId, String actor) {
        String t = Db.tenant(tenant);
        Minutes mn = latestMinutes(c, t, meetingId).orElseThrow(() -> CaseFileException.notFound("minutes", meetingId));
        if (mn.status() != MinutesStatus.APPROVED) {
            throw CaseFileException.state("only approved minutes can be issued (" + mn.status() + ")");
        }
        Jdbc.update(c, "UPDATE CF_MINUTES SET status=?, circulated_at=" + dialect.now() + " WHERE tenant_id=? AND meeting_id=? AND version=?",
                MinutesStatus.ISSUED, t, meetingId, mn.version());
        sendNotices(c, t, meetingId, "MINUTES", actor);
        setStatus(c, t, meetingId, Status.MINUTED);
        return minutes(c, t, meetingId, mn.version());
    }

    public Minutes minutes(Connection c, String tenant, String meetingId, int version) {
        return Jdbc.one(c, "SELECT * FROM CF_MINUTES WHERE tenant_id=? AND meeting_id=? AND version=?", MeetingService::minutesRow,
                Db.tenant(tenant), meetingId, version).orElseThrow(() -> CaseFileException.notFound("minutes", meetingId + "@" + version));
    }

    public Optional<Minutes> latestMinutes(Connection c, String tenant, String meetingId) {
        return Jdbc.query(c, "SELECT * FROM CF_MINUTES WHERE tenant_id=? AND meeting_id=? ORDER BY version DESC", MeetingService::minutesRow,
                Db.tenant(tenant), meetingId).stream().findFirst();
    }

    // ------------------------------------------------------------------ helpers

    Meeting requireStatus(Connection c, String t, String meetingId, Status... allowed) {
        Meeting m = get(c, t, meetingId);
        for (Status s : allowed) {
            if (m.status() == s) {
                return m;
            }
        }
        throw CaseFileException.state("meeting is " + m.status());
    }

    private void setStatus(Connection c, String t, String meetingId, Status s) {
        Jdbc.update(c, "UPDATE CF_MEETING SET status=?, row_version=row_version+1 WHERE tenant_id=? AND meeting_id=?", s, t, meetingId);
    }

    static Meeting row(ResultSet rs) throws SQLException {
        int qp = rs.getInt("quorum_present");
        Integer quorumPresent = rs.wasNull() ? null : qp;
        return new Meeting(rs.getString("tenant_id"), rs.getString("meeting_id"), rs.getString("committee_id"), rs.getString("meeting_number"),
                rs.getString("title"), Jdbc.instant(rs, "scheduled_at"), Jdbc.instant(rs, "ends_at"), rs.getString("venue"),
                rs.getString("venue_booking_ref"), rs.getString("meeting_mode"), Status.valueOf(rs.getString("status")), rs.getInt("quorum_required"),
                quorumPresent, rs.getString("chair_member_id"), rs.getString("urgent_reason"), rs.getString("case_id"),
                rs.getString("adjourned_to"), Jdbc.instant(rs, "started_at"), Jdbc.instant(rs, "ended_at"), rs.getString("created_by"),
                Jdbc.instant(rs, "created_at"), rs.getLong("row_version"));
    }

    static AgendaItem itemRow(ResultSet rs) throws SQLException {
        return new AgendaItem(rs.getString("tenant_id"), rs.getString("item_id"), rs.getString("meeting_id"), rs.getInt("seq"),
                rs.getString("title"), rs.getString("description"), rs.getString("linked_type"), rs.getString("linked_ref"),
                rs.getString("presenter"), rs.getString("status"), rs.getString("carried_from"), rs.getString("added_by"),
                Jdbc.instant(rs, "added_at"));
    }

    static Minutes minutesRow(ResultSet rs) throws SQLException {
        return new Minutes(rs.getString("meeting_id"), rs.getInt("version"), rs.getString("body"), MinutesStatus.valueOf(rs.getString("status")),
                rs.getString("content_hash"), rs.getString("drafted_by"), Jdbc.instant(rs, "drafted_at"), Db.list(rs.getString("comments_json")),
                rs.getString("approved_by"), Jdbc.instant(rs, "approved_at"), rs.getString("esign_ref"), Jdbc.instant(rs, "circulated_at"));
    }

    static Decision decisionRow(ResultSet rs) throws SQLException {
        return new Decision(rs.getString("tenant_id"), rs.getString("decision_id"), rs.getString("meeting_id"), rs.getString("item_id"),
                rs.getString("decision_text"), rs.getString("outcome"), rs.getInt("votes_for"), rs.getInt("votes_against"),
                rs.getInt("abstentions"), Db.list(rs.getString("dissent_json")), rs.getString("recorded_by"), Jdbc.instant(rs, "recorded_at"));
    }
}
