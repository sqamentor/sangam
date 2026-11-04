// SPDX-License-Identifier: Apache-2.0
package org.sangam.casefile.file;

import org.sangam.casefile.CaseFileException;
import org.sangam.casefile.common.CaseEvents;
import org.sangam.casefile.support.Db;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

/**
 * Dated events set by an outside forum (N04 {@code Hearing}/{@code Event}, GP-51): a hearing, a site visit, a sitting,
 * a review date. Kinds, forums and outcomes are data. Recording an outcome with a next date schedules the next event;
 * the cause list is the forum's events for a day (the feed to the resource module N01).
 */
public class HearingService {

    public enum Status { SCHEDULED, HELD, ADJOURNED, CANCELLED }

    public record Hearing(String tenantId, String hearingId, String caseId, String kind, String forum, Instant scheduledAt,
                          Status status, String outcome, String outcomeText, Instant nextDate, String externalRef,
                          String recordedBy, Instant recordedAt) {
    }

    private final Dialect dialect;
    private final CaseEvents events;

    public HearingService(Dialect dialect, CaseEvents events) {
        this.dialect = dialect;
        this.events = events;
    }

    public Hearing schedule(Connection c, String tenant, String caseId, String kind, String forum, Instant at, String externalRef,
                            String actor) {
        String t = Db.tenant(tenant);
        if (Jdbc.one(c, "SELECT COUNT(*) FROM CF_CASE WHERE tenant_id=? AND case_id=?", rs -> rs.getLong(1), t, caseId).orElse(0L) == 0) {
            throw CaseFileException.notFound("case", caseId);
        }
        String id = Db.newId();
        Jdbc.update(c, "INSERT INTO CF_HEARING(tenant_id, hearing_id, case_id, kind, forum, scheduled_at, status, external_ref, recorded_by,"
                        + " recorded_at) VALUES (?,?,?,?,?,?,?,?,?," + dialect.now() + ")",
                t, id, caseId, Db.requireText(kind, "kind"), forum, Db.require(at, "scheduledAt"), Status.SCHEDULED, externalRef, actor);
        events.record(c, t, CaseEvents.CASE, caseId, "HEARING_SCHEDULED", actor, kind + " " + at,
                Db.kv("hearingId", id, "kind", kind, "forum", forum, "scheduledAt", at.toString()));
        return get(c, t, id);
    }

    /**
     * Records what happened. {@code adjourned=true} marks it ADJOURNED; a {@code nextDate} schedules the follow-up event
     * of the same kind and forum, which is returned (else the recorded hearing is returned).
     */
    public Hearing recordOutcome(Connection c, String tenant, String hearingId, String outcome, String outcomeText, boolean adjourned,
                                 Instant nextDate, String actor) {
        String t = Db.tenant(tenant);
        Hearing h = get(c, t, hearingId);
        if (h.status() != Status.SCHEDULED) {
            throw CaseFileException.state("hearing is " + h.status());
        }
        Status s = adjourned ? Status.ADJOURNED : Status.HELD;
        Jdbc.update(c, "UPDATE CF_HEARING SET status=?, outcome=?, outcome_text=?, next_date=?, recorded_by=?, recorded_at=" + dialect.now()
                + " WHERE tenant_id=? AND hearing_id=?", s, outcome, outcomeText, nextDate, actor, t, hearingId);
        events.record(c, t, CaseEvents.CASE, h.caseId(), adjourned ? "HEARING_ADJOURNED" : "HEARING_HELD", actor, outcome,
                Db.kv("hearingId", hearingId, "outcome", outcome, "nextDate", nextDate == null ? null : nextDate.toString()),
                "CASEFILE", h.scheduledAt());
        if (nextDate != null) {
            return schedule(c, t, h.caseId(), h.kind(), h.forum(), nextDate, h.externalRef(), actor);
        }
        return get(c, t, hearingId);
    }

    public Hearing cancel(Connection c, String tenant, String hearingId, String reason, String actor) {
        Hearing h = get(c, tenant, hearingId);
        if (h.status() != Status.SCHEDULED) {
            throw CaseFileException.state("hearing is " + h.status());
        }
        Jdbc.update(c, "UPDATE CF_HEARING SET status=?, outcome_text=? WHERE tenant_id=? AND hearing_id=?", Status.CANCELLED, reason,
                Db.tenant(tenant), hearingId);
        events.record(c, tenant, CaseEvents.CASE, h.caseId(), "HEARING_CANCELLED", actor, reason, Db.kv("hearingId", hearingId));
        return get(c, tenant, hearingId);
    }

    public Hearing get(Connection c, String tenant, String hearingId) {
        return Jdbc.one(c, "SELECT * FROM CF_HEARING WHERE tenant_id=? AND hearing_id=?", HearingService::row, Db.tenant(tenant), hearingId)
                .orElseThrow(() -> CaseFileException.notFound("hearing", hearingId));
    }

    public List<Hearing> forCase(Connection c, String tenant, String caseId) {
        return Jdbc.query(c, "SELECT * FROM CF_HEARING WHERE tenant_id=? AND case_id=? ORDER BY scheduled_at", HearingService::row,
                Db.tenant(tenant), caseId);
    }

    /** Cause list: scheduled events of a forum (or all forums) in {@code [from, to)}. */
    public List<Hearing> causeList(Connection c, String tenant, String forumOrNull, Instant from, Instant to) {
        String sql = "SELECT * FROM CF_HEARING WHERE tenant_id=? AND status='SCHEDULED' AND scheduled_at >= ? AND scheduled_at < ?"
                + (forumOrNull == null ? "" : " AND forum=?") + " ORDER BY forum, scheduled_at";
        return forumOrNull == null
                ? Jdbc.query(c, sql, HearingService::row, Db.tenant(tenant), from, to)
                : Jdbc.query(c, sql, HearingService::row, Db.tenant(tenant), from, to, forumOrNull);
    }

    static Hearing row(ResultSet rs) throws SQLException {
        return new Hearing(rs.getString("tenant_id"), rs.getString("hearing_id"), rs.getString("case_id"), rs.getString("kind"),
                rs.getString("forum"), Jdbc.instant(rs, "scheduled_at"), Status.valueOf(rs.getString("status")), rs.getString("outcome"),
                rs.getString("outcome_text"), Jdbc.instant(rs, "next_date"), rs.getString("external_ref"), rs.getString("recorded_by"),
                Jdbc.instant(rs, "recorded_at"));
    }
}
