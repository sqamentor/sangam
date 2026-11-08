// SPDX-License-Identifier: Apache-2.0
package org.sangam.casefile.reference;

import org.sangam.casefile.CaseFileException;
import org.sangam.casefile.common.CaseEvents;
import org.sangam.casefile.common.NumberService;
import org.sangam.casefile.config.CaseConfig;
import org.sangam.casefile.config.CaseConfigRegistry;
import org.sangam.casefile.deadline.DeadlineService;
import org.sangam.casefile.file.CaseContentsService;
import org.sangam.casefile.file.CaseFileService;
import org.sangam.casefile.ports.CaseFilePorts;
import org.sangam.casefile.support.Db;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.SqlPredicate;
import org.sangam.ports.support.Paging;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Any "reference with a deadline" (N16): legislative questions, assurances, dignitary references, an
 * oversight body's letter, an outside forum's direction — the kind is configuration ({@link CaseConfig.ReferenceKind}): reply period
 * on a business calendar (or a fixed date set by the source, e.g. the sitting date), warning and escalation ladder,
 * custom fields, reply approval (maker-checker), duplicate window, optional case file and process per reference, and
 * the follow-up kind (a question's answer can create an assurance to fulfil). Lifecycle RECEIVED → ASSIGNED →
 * IN_PROGRESS → (REPLY_DRAFTED → REPLY_APPROVED) → REPLIED → CLOSED; TRANSFERRED is recorded as a move.
 */
public class ReferenceService {

    public enum Status { RECEIVED, ASSIGNED, IN_PROGRESS, REPLY_DRAFTED, REPLY_APPROVED, REPLIED, CLOSED }

    public record Reference(String tenantId, String referenceId, String kind, int kindVersion, String number, String sourceName,
                            String sourceCategory, String sourceRef, String subject, String body, Instant receivedAt,
                            String receivedVia, String priority, Status status, String assignedSeat, String assignedPerson,
                            String orgUnit, Instant dueAt, String deadlineId, String caseId, String parentReferenceId,
                            Map<String, Object> attributes, String replyDraft, String replyDraftedBy, String replyText,
                            String replyRef, String repliedBy, Instant repliedAt, String replyApprovedBy, boolean late,
                            Instant closedAt, String closeReason, String createdBy, long rowVersion) {
    }

    public record RegisterRequest(String kind, String sourceName, String sourceCategory, String sourceRef, String subject, String body,
                                  Instant receivedAt, String receivedVia, String priority, String orgUnit, String orgCode,
                                  String assignedSeat, String assignedPerson, Instant fixedDueAt, Map<String, Object> attributes,
                                  String parentReferenceId, boolean allowDuplicate) {
    }

    public record Move(String moveId, String referenceId, String fromSeat, String toSeat, String kind, String remarks, String movedBy,
                       Instant movedAt) {
    }

    public record Filter(String kind, Status status, String assignedSeat, String orgUnit, String sourceCategory, Boolean overdue,
                         String text) {
    }

    /** Pendency per kind and seat: open, overdue, due within the window, replied late (all time). */
    public record Pendency(String kind, String seat, int open, int overdue, int dueSoon, int repliedLate) {
    }

    private static final List<Status> OPEN = List.of(Status.RECEIVED, Status.ASSIGNED, Status.IN_PROGRESS, Status.REPLY_DRAFTED,
            Status.REPLY_APPROVED);

    private final Dialect dialect;
    private final CaseConfigRegistry config;
    private final NumberService numbers;
    private final DeadlineService deadlines;
    private final CaseEvents events;
    private final CaseFileService cases;
    private final CaseContentsService contents;
    private final CaseFilePorts.ProcessStarter processes;
    private final CaseFilePorts.Notifier notifier;

    public ReferenceService(Dialect dialect, CaseConfigRegistry config, NumberService numbers, DeadlineService deadlines, CaseEvents events,
                            CaseFileService cases, CaseContentsService contents, CaseFilePorts.ProcessStarter processes,
                            CaseFilePorts.Notifier notifier) {
        this.dialect = dialect;
        this.config = config;
        this.numbers = numbers;
        this.deadlines = deadlines;
        this.events = events;
        this.cases = cases;
        this.contents = contents;
        this.processes = processes == null ? CaseFilePorts.ProcessStarter.NONE : processes;
        this.notifier = notifier == null ? CaseFilePorts.Notifier.NONE : notifier;
    }

    // ------------------------------------------------------------------ intake

    public Reference register(Connection c, String tenant, RegisterRequest r, String actor) {
        String t = Db.tenant(tenant);
        CaseConfig.ReferenceKind kind = config.referenceKind(c, t, Db.requireText(r.kind(), "kind"));
        Db.requireText(r.subject(), "subject");
        if (!kind.sourceCategories().isEmpty() && r.sourceCategory() != null && !kind.sourceCategories().contains(r.sourceCategory())) {
            throw CaseFileException.invalid("source category must be one of " + kind.sourceCategories());
        }
        Map<String, Object> attrs = validated(kind, r.attributes());
        Instant now = Db.now(c, dialect);
        Instant received = r.receivedAt() == null ? now : r.receivedAt();
        String subjectKey = subjectKey(r.subject(), r.sourceName());
        if (!r.allowDuplicate() && kind.duplicateWindow() != null) {
            Object[] pd = Db.periodAndDuration(kind.duplicateWindow());
            Instant since = received.atZone(java.time.ZoneOffset.UTC).minus((Period) pd[0]).toInstant().minus((Duration) pd[1]);
            List<String> dup = Jdbc.query(c, "SELECT ref_number FROM CF_REFERENCE WHERE tenant_id=? AND subject_key=? AND ref_kind=? AND received_at >= ?",
                    rs -> rs.getString(1), t, subjectKey, kind.key(), since);
            if (!dup.isEmpty()) {
                throw new CaseFileException(CaseFileException.Code.INVALID_STATE, "possible duplicate of " + dup.get(0)
                        + "; register with allowDuplicate to proceed");
            }
        }
        if (r.parentReferenceId() != null) {
            get(c, t, r.parentReferenceId());
        }
        String id = Db.newId();
        String number = numbers.next(c, t, kind.numbering(), r.orgCode(), received);
        Instant due = r.fixedDueAt() != null ? r.fixedDueAt() : deadlines.add(kind.calendar(), received, kind.replyPeriod());
        DeadlineService.Deadline dl = deadlines.startAt(c, t, CaseEvents.REFERENCE, id, "REPLY", r.assignedSeat(), r.assignedPerson(), due,
                kind.calendar(), kind.warnBefore(), kind.ladder());
        Status status = r.assignedSeat() != null || r.assignedPerson() != null ? Status.ASSIGNED : Status.RECEIVED;
        Jdbc.update(c, "INSERT INTO CF_REFERENCE(tenant_id, reference_id, ref_kind, kind_version, ref_number, source_name, source_category,"
                        + " source_ref, subject, subject_key, body, received_at, received_via, priority, status, assigned_seat, assigned_person,"
                        + " org_unit, due_at, deadline_id, parent_reference_id, attributes_json, late, created_by, row_version)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,?,0)",
                t, id, kind.key(), kind.version(), number, r.sourceName(), r.sourceCategory(), r.sourceRef(), r.subject(), subjectKey, r.body(),
                received, r.receivedVia(), r.priority(), status, r.assignedSeat(), r.assignedPerson(), r.orgUnit(), due, dl.id(),
                r.parentReferenceId(), Db.json(attrs), actor);
        events.record(c, t, CaseEvents.REFERENCE, id, "REFERENCE_RECEIVED", actor, number + " " + r.subject(),
                Db.kv("kind", kind.key(), "number", number, "sourceCategory", r.sourceCategory(), "dueAt", due.toString(),
                        "parentReferenceId", r.parentReferenceId()), "CASEFILE", received);
        if (kind.caseType() != null) {
            CaseFileService.CaseFile cf = cases.open(c, t, new CaseFileService.OpenRequest(kind.caseType(), r.subject(), null, r.orgUnit(),
                    r.orgCode(), r.assignedSeat(), r.priority(), null, number, Map.of(), null, null), actor);
            contents.linkRecord(c, t, cf.caseId(), "reference", id, "ORIGINATES_FROM", number, actor);
            Jdbc.update(c, "UPDATE CF_REFERENCE SET case_id=? WHERE tenant_id=? AND reference_id=?", cf.caseId(), t, id);
        }
        if (kind.processKey() != null) {
            processes.start(c, t, kind.processKey(), number, Db.kv("referenceId", id, "referenceNumber", number, "kind", kind.key(),
                    "dueAt", due.toString(), "assignedSeat", r.assignedSeat()));
        }
        if (r.assignedSeat() != null) {
            notifySeat(c, t, r.assignedSeat(), "REFERENCE_ASSIGNED", number, r.subject(), due);
        }
        return get(c, t, id);
    }

    /** A follow-up reference of the kind's {@code followUpKind} (e.g. an assurance given in a reply). */
    public Reference spawnFollowUp(Connection c, String tenant, String referenceId, String subject, Instant fixedDueAt,
                                   Map<String, Object> attributes, String actor) {
        Reference parent = get(c, tenant, referenceId);
        CaseConfig.ReferenceKind kind = config.referenceKind(c, tenant, parent.kind(), parent.kindVersion());
        if (kind.followUpKind() == null) {
            throw CaseFileException.state("reference kind " + kind.key() + " has no follow-up kind");
        }
        return register(c, tenant, new RegisterRequest(kind.followUpKind(), parent.sourceName(), parent.sourceCategory(), parent.number(),
                subject == null ? parent.subject() : subject, null, null, "FOLLOW_UP", parent.priority(), parent.orgUnit(), null,
                parent.assignedSeat(), parent.assignedPerson(), fixedDueAt, attributes, referenceId, true), actor);
    }

    // ------------------------------------------------------------------ movement

    public Reference assign(Connection c, String tenant, String referenceId, String seat, String person, String remarks, String actor) {
        Reference r = requireOpen(c, tenant, referenceId);
        move(c, tenant, r, seat, "ASSIGN", remarks, actor);
        update(c, tenant, r, "status=?, assigned_seat=?, assigned_person=?",
                r.status() == Status.RECEIVED ? Status.ASSIGNED : r.status(), seat, person);
        if (r.deadlineId() != null) {
            deadlines.reassign(c, tenant, r.deadlineId(), seat, person);
        }
        notifySeat(c, tenant, seat, "REFERENCE_ASSIGNED", r.number(), r.subject(), r.dueAt());
        return get(c, tenant, referenceId);
    }

    /** Transfer to another office (another org unit and seat); the clock keeps running unless extended. */
    public Reference transfer(Connection c, String tenant, String referenceId, String toOrgUnit, String toSeat, String remarks, String actor) {
        Reference r = requireOpen(c, tenant, referenceId);
        Db.requireText(remarks, "remarks");
        move(c, tenant, r, toSeat, "TRANSFER", remarks, actor);
        update(c, tenant, r, "status=?, org_unit=?, assigned_seat=?, assigned_person=NULL", Status.ASSIGNED, toOrgUnit, toSeat);
        if (r.deadlineId() != null) {
            deadlines.reassign(c, tenant, r.deadlineId(), toSeat, null);
        }
        notifySeat(c, tenant, toSeat, "REFERENCE_TRANSFERRED", r.number(), r.subject(), r.dueAt());
        return get(c, tenant, referenceId);
    }

    public Reference startWork(Connection c, String tenant, String referenceId, String actor) {
        Reference r = requireOpen(c, tenant, referenceId);
        if (r.status() == Status.RECEIVED || r.status() == Status.ASSIGNED) {
            update(c, tenant, r, "status=?", Status.IN_PROGRESS);
            events.record(c, tenant, CaseEvents.REFERENCE, referenceId, "WORK_STARTED", actor, null, Db.kv());
        }
        return get(c, tenant, referenceId);
    }

    public List<Move> moves(Connection c, String tenant, String referenceId) {
        return Jdbc.query(c, "SELECT * FROM CF_REFERENCE_MOVE WHERE tenant_id=? AND reference_id=? ORDER BY moved_at", rs -> new Move(
                rs.getString("move_id"), rs.getString("reference_id"), rs.getString("from_seat"), rs.getString("to_seat"),
                rs.getString("move_kind"), rs.getString("remarks"), rs.getString("moved_by"), Jdbc.instant(rs, "moved_at")),
                Db.tenant(tenant), referenceId);
    }

    // ------------------------------------------------------------------ replies

    /** An interim reply (acknowledgement, part information); the reply clock keeps running. */
    public Reference interimReply(Connection c, String tenant, String referenceId, String text, String replyRef, String actor) {
        Reference r = requireOpen(c, tenant, referenceId);
        events.record(c, tenant, CaseEvents.REFERENCE, referenceId, "INTERIM_REPLY", actor, replyRef,
                Db.kv("text", Db.requireText(text, "text"), "replyRef", replyRef));
        if (r.status() == Status.RECEIVED || r.status() == Status.ASSIGNED) {
            update(c, tenant, r, "status=?", Status.IN_PROGRESS);
        }
        return get(c, tenant, referenceId);
    }

    public Reference draftReply(Connection c, String tenant, String referenceId, String text, String actor) {
        Reference r = requireOpen(c, tenant, referenceId);
        update(c, tenant, r, "status=?, reply_draft=?, reply_drafted_by=?, reply_approved_by=NULL", Status.REPLY_DRAFTED,
                Db.requireText(text, "text"), actor);
        events.record(c, tenant, CaseEvents.REFERENCE, referenceId, "REPLY_DRAFTED", actor, null, Db.kv("draftHash", Db.hashText(text)));
        return get(c, tenant, referenceId);
    }

    /** Maker-checker on the reply text: the approver is not the drafter; the approval binds the draft's hash. */
    public Reference approveReply(Connection c, String tenant, String referenceId, String expectedDraftHash, String actor) {
        Reference r = get(c, tenant, referenceId);
        if (r.status() != Status.REPLY_DRAFTED) {
            throw CaseFileException.state("reference is " + r.status());
        }
        if (actor.equals(r.replyDraftedBy())) {
            throw new CaseFileException(CaseFileException.Code.FOUR_EYES_REQUIRED, "the drafter cannot approve the reply");
        }
        if (expectedDraftHash != null && !expectedDraftHash.equals(Db.hashText(r.replyDraft()))) {
            throw CaseFileException.state("the draft changed since it was read");
        }
        update(c, tenant, r, "status=?, reply_approved_by=?", Status.REPLY_APPROVED, actor);
        events.record(c, tenant, CaseEvents.REFERENCE, referenceId, "REPLY_APPROVED", actor, null, Db.kv("draftHash", Db.hashText(r.replyDraft())));
        return get(c, tenant, referenceId);
    }

    /**
     * The final reply. When the kind requires approval, the approved draft is sent unchanged ({@code text} may be null).
     * Meets the reply deadline (late when after the due time).
     */
    public Reference reply(Connection c, String tenant, String referenceId, String text, String replyRef, String actor) {
        Reference r = requireOpen(c, tenant, referenceId);
        CaseConfig.ReferenceKind kind = config.referenceKind(c, tenant, r.kind(), r.kindVersion());
        String body = text;
        if (kind.replyApproval()) {
            if (r.status() != Status.REPLY_APPROVED) {
                throw CaseFileException.state("the reply must be approved first");
            }
            if (body != null && !body.equals(r.replyDraft())) {
                throw CaseFileException.state("the reply differs from the approved draft");
            }
            body = r.replyDraft();
        }
        Db.requireText(body, "text");
        Instant now = Db.now(c, dialect);
        boolean late = r.dueAt() != null && now.isAfter(r.dueAt());
        update(c, tenant, r, "status=?, reply_text=?, reply_ref=?, replied_by=?, replied_at=?, late=?", Status.REPLIED, body, replyRef, actor,
                now, Db.small(late));
        deadlines.meetScope(c, tenant, CaseEvents.REFERENCE, referenceId, null, actor);
        events.record(c, tenant, CaseEvents.REFERENCE, referenceId, "REPLIED", actor, replyRef, Db.kv("replyRef", replyRef, "late", late));
        if (r.caseId() != null) {
            events.record(c, tenant, CaseEvents.CASE, r.caseId(), "REFERENCE_REPLIED", actor, r.number(), Db.kv("referenceId", referenceId, "late", late));
        }
        return get(c, tenant, referenceId);
    }

    public Reference extend(Connection c, String tenant, String referenceId, Instant newDue, String reason, String actor) {
        Reference r = requireOpen(c, tenant, referenceId);
        CaseConfig.ReferenceKind kind = config.referenceKind(c, tenant, r.kind(), r.kindVersion());
        if (!kind.extensionAllowed()) {
            throw CaseFileException.state("reference kind " + kind.key() + " allows no extension");
        }
        DeadlineService.Deadline d = deadlines.extend(c, tenant, Db.require(r.deadlineId(), "deadline"), newDue, reason, actor, kind.maxExtensions());
        update(c, tenant, r, "due_at=?", d.dueAt());
        return get(c, tenant, referenceId);
    }

    /** Closes (after reply, or as withdrawn / lapsed / merged with another with a reason). */
    public Reference close(Connection c, String tenant, String referenceId, String reason, String actor) {
        Reference r = get(c, tenant, referenceId);
        if (r.status() == Status.CLOSED) {
            throw CaseFileException.state("reference already closed");
        }
        Db.requireText(reason, "reason");
        update(c, tenant, r, "status=?, closed_at=" + dialect.now() + ", close_reason=?", Status.CLOSED, reason);
        deadlines.cancelScope(c, tenant, CaseEvents.REFERENCE, referenceId, actor, reason);
        events.record(c, tenant, CaseEvents.REFERENCE, referenceId, "REFERENCE_CLOSED", actor, reason, Db.kv("reason", reason));
        return get(c, tenant, referenceId);
    }

    // ------------------------------------------------------------------ queries

    public Reference get(Connection c, String tenant, String referenceId) {
        return Jdbc.one(c, "SELECT * FROM CF_REFERENCE WHERE tenant_id=? AND reference_id=?", ReferenceService::row, Db.tenant(tenant), referenceId)
                .orElseThrow(() -> CaseFileException.notFound("reference", referenceId));
    }

    public List<Reference> children(Connection c, String tenant, String referenceId) {
        return Jdbc.query(c, "SELECT * FROM CF_REFERENCE WHERE tenant_id=? AND parent_reference_id=? ORDER BY received_at", ReferenceService::row,
                Db.tenant(tenant), referenceId);
    }

    /** Work queue / register search under VI.4 ({@code auth} over alias {@code r}), capped count (A-16). */
    public Paging.PageResult<Reference> list(Connection c, String tenant, Filter f, SqlPredicate auth, Paging.PageRequest page) {
        StringBuilder where = new StringBuilder(" FROM CF_REFERENCE r WHERE r.tenant_id=?");
        List<Object> p = new ArrayList<>(List.of(Db.tenant(tenant)));
        Filter q = f == null ? new Filter(null, null, null, null, null, null, null) : f;
        if (q.kind() != null) {
            where.append(" AND r.ref_kind=?");
            p.add(q.kind());
        }
        if (q.status() != null) {
            where.append(" AND r.status=?");
            p.add(q.status());
        }
        if (q.assignedSeat() != null) {
            where.append(" AND r.assigned_seat=?");
            p.add(q.assignedSeat());
        }
        if (q.orgUnit() != null) {
            where.append(" AND r.org_unit=?");
            p.add(q.orgUnit());
        }
        if (q.sourceCategory() != null) {
            where.append(" AND r.source_category=?");
            p.add(q.sourceCategory());
        }
        if (Boolean.TRUE.equals(q.overdue())) {
            where.append(" AND r.status IN ('RECEIVED','ASSIGNED','IN_PROGRESS','REPLY_DRAFTED','REPLY_APPROVED') AND r.due_at < ")
                    .append(dialect.now());
        }
        if (q.text() != null && !q.text().isBlank()) {
            where.append(" AND (LOWER(r.subject) LIKE ? ESCAPE '!' OR r.ref_number LIKE ? ESCAPE '!')");
            p.add(Db.likeContains(q.text().toLowerCase(Locale.ROOT)));
            p.add(Db.likeContains(q.text()));
        }
        SqlPredicate a = Db.orTrue(auth);
        where.append(" AND (").append(a.sql()).append(")");
        p.addAll(a.params());
        Paging.PageRequest pg = page == null ? new Paging.PageRequest(0, 50) : page;
        long total = Jdbc.one(c, Paging.cappedCount(dialect, where.toString()), rs -> rs.getLong(1), p.toArray()).orElse(0L);
        List<Reference> items = Jdbc.query(c, "SELECT r.*" + where + " ORDER BY r.due_at, r.reference_id" + Paging.window(dialect, pg),
                ReferenceService::row, p.toArray());
        return Paging.of(items, total, pg);
    }

    public List<Pendency> pendency(Connection c, String tenant, String kindOrNull, Duration dueSoonWindow) {
        String t = Db.tenant(tenant);
        Instant now = Db.now(c, dialect);
        Instant soon = now.plus(dueSoonWindow == null ? Duration.ofDays(7) : dueSoonWindow);
        Map<String, int[]> acc = new LinkedHashMap<>();
        Map<String, String[]> keys = new LinkedHashMap<>();
        List<Object[]> rows = Jdbc.query(c, "SELECT ref_kind, assigned_seat, status, due_at, late FROM CF_REFERENCE WHERE tenant_id=?"
                        + (kindOrNull == null ? "" : " AND ref_kind=?") + " AND status <> 'CLOSED'",
                rs -> new Object[]{rs.getString(1), rs.getString(2), rs.getString(3), Jdbc.instant(rs, "due_at"), rs.getInt(5)},
                kindOrNull == null ? new Object[]{t} : new Object[]{t, kindOrNull});
        for (Object[] r : rows) {
            String k = r[0] + "|" + r[1];
            keys.putIfAbsent(k, new String[]{(String) r[0], (String) r[1]});
            int[] v = acc.computeIfAbsent(k, x -> new int[4]);
            Status s = Status.valueOf((String) r[2]);
            Instant due = (Instant) r[3];
            if (OPEN.contains(s)) {
                v[0]++;
                if (due != null && due.isBefore(now)) {
                    v[1]++;
                } else if (due != null && due.isBefore(soon)) {
                    v[2]++;
                }
            } else if (s == Status.REPLIED && ((Integer) r[4]) != 0) {
                v[3]++;
            }
        }
        List<Pendency> out = new ArrayList<>();
        acc.forEach((k, v) -> out.add(new Pendency(keys.get(k)[0], keys.get(k)[1], v[0], v[1], v[2], v[3])));
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private Reference requireOpen(Connection c, String tenant, String referenceId) {
        Reference r = get(c, tenant, referenceId);
        if (!OPEN.contains(r.status())) {
            throw CaseFileException.state("reference is " + r.status());
        }
        return r;
    }

    private void move(Connection c, String tenant, Reference r, String toSeat, String kind, String remarks, String actor) {
        Jdbc.update(c, "INSERT INTO CF_REFERENCE_MOVE(tenant_id, move_id, reference_id, from_seat, to_seat, move_kind, remarks, moved_by, moved_at)"
                + " VALUES (?,?,?,?,?,?,?,?," + dialect.now() + ")", Db.tenant(tenant), Db.newId(), r.referenceId(), r.assignedSeat(), toSeat, kind,
                remarks, actor);
        events.record(c, tenant, CaseEvents.REFERENCE, r.referenceId(), "REFERENCE_" + kind, actor, r.assignedSeat() + " -> " + toSeat,
                Db.kv("from", r.assignedSeat(), "to", toSeat, "remarks", remarks));
    }

    private void update(Connection c, String tenant, Reference r, String set, Object... values) {
        List<Object> p = new ArrayList<>(List.of(values));
        p.add(Db.tenant(tenant));
        p.add(r.referenceId());
        p.add(r.rowVersion());
        if (Jdbc.update(c, "UPDATE CF_REFERENCE SET " + set + ", row_version=row_version+1 WHERE tenant_id=? AND reference_id=? AND row_version=?",
                p.toArray()) != 1) {
            throw new CaseFileException(CaseFileException.Code.CONCURRENT_UPDATE, "reference changed since it was read");
        }
    }

    private void notifySeat(Connection c, String t, String seat, String kind, String number, String subject, Instant due) {
        if (seat != null) {
            notifier.send(c, new CaseFilePorts.Message(t, "seat:" + seat, kind, number + ": " + subject, "Reply due " + due,
                    Db.kv("number", number, "dueAt", due == null ? null : due.toString())));
        }
    }

    private static Map<String, Object> validated(CaseConfig.ReferenceKind kind, Map<String, Object> attrs) {
        Map<String, Object> in = attrs == null ? Map.of() : attrs;
        List<String> errors = new ArrayList<>();
        for (String k : in.keySet()) {
            if (kind.field(k) == null) {
                errors.add("unknown field " + k);
            }
        }
        for (CaseConfig.Field f : kind.fields()) {
            String err = f.check(in.get(f.name()));
            if (err != null) {
                errors.add(err);
            }
        }
        if (!errors.isEmpty()) {
            throw CaseFileException.invalid(String.join("; ", errors));
        }
        return new LinkedHashMap<>(in);
    }

    static String subjectKey(String subject, String source) {
        String n = Normalizer.normalize((source == null ? "" : source) + "|" + subject, Normalizer.Form.NFKD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}|]", "");
        return Db.hashText(n).substring(0, 64);
    }

    static Reference row(ResultSet rs) throws SQLException {
        return new Reference(rs.getString("tenant_id"), rs.getString("reference_id"), rs.getString("ref_kind"), rs.getInt("kind_version"),
                rs.getString("ref_number"), rs.getString("source_name"), rs.getString("source_category"), rs.getString("source_ref"),
                rs.getString("subject"), rs.getString("body"), Jdbc.instant(rs, "received_at"), rs.getString("received_via"),
                rs.getString("priority"), Status.valueOf(rs.getString("status")), rs.getString("assigned_seat"), rs.getString("assigned_person"),
                rs.getString("org_unit"), Jdbc.instant(rs, "due_at"), rs.getString("deadline_id"), rs.getString("case_id"),
                rs.getString("parent_reference_id"), Db.map(rs.getString("attributes_json")), rs.getString("reply_draft"),
                rs.getString("reply_drafted_by"), rs.getString("reply_text"), rs.getString("reply_ref"), rs.getString("replied_by"),
                Jdbc.instant(rs, "replied_at"), rs.getString("reply_approved_by"), rs.getInt("late") != 0, Jdbc.instant(rs, "closed_at"),
                rs.getString("close_reason"), rs.getString("created_by"), rs.getLong("row_version"));
    }
}
