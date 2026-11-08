// SPDX-License-Identifier: Apache-2.0
package org.sangam.casefile.meeting;

import org.sangam.casefile.CaseFileException;
import org.sangam.casefile.common.CaseEvents;
import org.sangam.casefile.config.CaseConfig;
import org.sangam.casefile.config.CaseConfigRegistry;
import org.sangam.casefile.support.Db;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Committees (N16): constitution by an order (reference kept), members by person or seat (a seat member follows the
 * posting, IX.5), member roles from the committee kind, voting and non-voting members, quorum rule, chair rule and
 * notice period (defaults from the kind, overridable per committee), effective-dated membership, reconstitution and
 * dissolution. Committee kinds (selection, purchase, appraisal, review, board ...) are configuration data.
 */
public class CommitteeService {

    public enum Status { ACTIVE, DISSOLVED }

    public record Committee(String tenantId, String committeeId, String kind, String name, String orgUnit, String constitutionRef,
                            String constitutedBy, Instant constitutedAt, Instant validFrom, Instant validTo, String quorumRule,
                            boolean chairRequired, int minNoticeHours, Map<String, Object> terms, Status status, long rowVersion) {
    }

    public record Member(String tenantId, String memberId, String committeeId, String personRef, String seatRef, String displayName,
                         String role, boolean voting, Instant validFrom, Instant validTo) {

        /** The address used for notices and attendance: {@code seat:<id>} when seat-based, else {@code user:<id>}. */
        public String ref() {
            return seatRef != null ? "seat:" + seatRef : "user:" + personRef;
        }
    }

    public record MemberInput(String personRef, String seatRef, String displayName, String role, Boolean voting) {
    }

    public record ConstituteRequest(String kind, String name, String orgUnit, String constitutionRef, Instant validFrom,
                                    Instant validTo, String quorumRule, Boolean chairRequired, Integer minNoticeHours,
                                    Map<String, Object> terms, List<MemberInput> members) {
    }

    private final Dialect dialect;
    private final CaseConfigRegistry config;
    private final CaseEvents events;

    public CommitteeService(Dialect dialect, CaseConfigRegistry config, CaseEvents events) {
        this.dialect = dialect;
        this.config = config;
        this.events = events;
    }

    public Committee constitute(Connection c, String tenant, ConstituteRequest r, String actor) {
        String t = Db.tenant(tenant);
        CaseConfig.CommitteeKind kind = config.committeeKind(c, t, Db.requireText(r.kind(), "kind"));
        String quorum = r.quorumRule() == null ? kind.quorumRule() : r.quorumRule();
        CaseConfig.quorumRequired(quorum, 1);
        boolean chairRequired = r.chairRequired() == null ? kind.chairRequired() : r.chairRequired();
        Instant now = Db.now(c, dialect);
        String id = Db.newId();
        Jdbc.update(c, "INSERT INTO CF_COMMITTEE(tenant_id, committee_id, committee_kind, name, org_unit, constitution_ref, constituted_by,"
                        + " constituted_at, valid_from, valid_to, quorum_rule, chair_required, min_notice_hours, terms_json, status, row_version)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)",
                t, id, kind.key(), Db.requireText(r.name(), "name"), r.orgUnit(), r.constitutionRef(), actor, now,
                r.validFrom() == null ? now : r.validFrom(), r.validTo(), quorum, Db.small(chairRequired),
                r.minNoticeHours() == null ? kind.minNoticeHours() : r.minNoticeHours(), Db.json(r.terms()), Status.ACTIVE);
        if (r.members() != null) {
            for (MemberInput m : r.members()) {
                addMember(c, t, id, m, now, actor, kind);
            }
        }
        requireChair(c, t, id, chairRequired, now);
        events.record(c, t, CaseEvents.COMMITTEE, id, "COMMITTEE_CONSTITUTED", actor, r.name(),
                Db.kv("kind", kind.key(), "constitutionRef", r.constitutionRef(), "members", r.members() == null ? 0 : r.members().size()));
        return get(c, t, id);
    }

    public Member addMember(Connection c, String tenant, String committeeId, MemberInput m, String actor) {
        Committee cm = requireActive(c, tenant, committeeId);
        CaseConfig.CommitteeKind kind = config.committeeKind(c, tenant, cm.kind());
        Member added = addMember(c, Db.tenant(tenant), committeeId, m, Db.now(c, dialect), actor, kind);
        events.record(c, tenant, CaseEvents.COMMITTEE, committeeId, "MEMBER_ADDED", actor, m.role(),
                Db.kv("memberId", added.memberId(), "role", m.role(), "ref", added.ref()));
        return added;
    }

    private Member addMember(Connection c, String t, String committeeId, MemberInput m, Instant from, String actor,
                             CaseConfig.CommitteeKind kind) {
        if (m.personRef() == null && m.seatRef() == null) {
            throw CaseFileException.invalid("a member needs a personRef or a seatRef");
        }
        String role = Db.requireText(m.role(), "role");
        if (!kind.memberRoles().contains(role)) {
            throw CaseFileException.invalid("committee kind " + kind.key() + " has no member role " + role);
        }
        for (Member x : members(c, t, committeeId, null)) {
            if (x.ref().equals(new Member(t, "", committeeId, m.personRef(), m.seatRef(), null, role, true, from, null).ref())) {
                throw CaseFileException.invalid("already a member");
            }
            if ("CHAIR".equals(role) && "CHAIR".equals(x.role())) {
                throw CaseFileException.invalid("the committee already has a chair");
            }
        }
        boolean voting = m.voting() == null ? !"INVITEE".equals(role) : m.voting();
        String id = Db.newId();
        Jdbc.update(c, "INSERT INTO CF_COMMITTEE_MEMBER(tenant_id, member_id, committee_id, person_ref, seat_ref, display_name, member_role,"
                        + " voting, valid_from, added_by) VALUES (?,?,?,?,?,?,?,?,?,?)",
                t, id, committeeId, m.personRef(), m.seatRef(), m.displayName(), role, Db.small(voting), from, actor);
        return member(c, t, id);
    }

    public void removeMember(Connection c, String tenant, String memberId, String reason, String actor) {
        Member m = member(c, tenant, memberId);
        if (m.validTo() != null) {
            throw CaseFileException.state("membership already ended");
        }
        Committee cm = requireActive(c, tenant, m.committeeId());
        Jdbc.update(c, "UPDATE CF_COMMITTEE_MEMBER SET valid_to=" + dialect.now() + " WHERE tenant_id=? AND member_id=?", Db.tenant(tenant), memberId);
        if ("CHAIR".equals(m.role()) && cm.chairRequired()) {
            events.record(c, tenant, CaseEvents.COMMITTEE, m.committeeId(), "CHAIR_VACANT", actor, reason, Db.kv("memberId", memberId));
        }
        events.record(c, tenant, CaseEvents.COMMITTEE, m.committeeId(), "MEMBER_REMOVED", actor, reason, Db.kv("memberId", memberId, "role", m.role()));
    }

    /** Replaces the membership under a new constitution order; the committee id (and its meeting history) stays. */
    public Committee reconstitute(Connection c, String tenant, String committeeId, String constitutionRef, List<MemberInput> members,
                                  String actor) {
        String t = Db.tenant(tenant);
        Committee cm = requireActive(c, t, committeeId);
        CaseConfig.CommitteeKind kind = config.committeeKind(c, t, cm.kind());
        Instant now = Db.now(c, dialect);
        Jdbc.update(c, "UPDATE CF_COMMITTEE_MEMBER SET valid_to=? WHERE tenant_id=? AND committee_id=? AND valid_to IS NULL", now, t, committeeId);
        for (MemberInput m : members == null ? List.<MemberInput>of() : members) {
            addMember(c, t, committeeId, m, now, actor, kind);
        }
        requireChair(c, t, committeeId, cm.chairRequired(), now);
        Jdbc.update(c, "UPDATE CF_COMMITTEE SET constitution_ref=?, constituted_by=?, constituted_at=?, row_version=row_version+1"
                + " WHERE tenant_id=? AND committee_id=?", constitutionRef, actor, now, t, committeeId);
        events.record(c, t, CaseEvents.COMMITTEE, committeeId, "COMMITTEE_RECONSTITUTED", actor, constitutionRef,
                Db.kv("constitutionRef", constitutionRef, "members", members == null ? 0 : members.size()));
        return get(c, t, committeeId);
    }

    public Committee dissolve(Connection c, String tenant, String committeeId, String reason, String actor) {
        requireActive(c, tenant, committeeId);
        Db.requireText(reason, "reason");
        Jdbc.update(c, "UPDATE CF_COMMITTEE SET status=?, valid_to=" + dialect.now() + ", row_version=row_version+1 WHERE tenant_id=? AND committee_id=?",
                Status.DISSOLVED, Db.tenant(tenant), committeeId);
        events.record(c, tenant, CaseEvents.COMMITTEE, committeeId, "COMMITTEE_DISSOLVED", actor, reason, Db.kv("reason", reason));
        return get(c, tenant, committeeId);
    }

    public Committee get(Connection c, String tenant, String committeeId) {
        return Jdbc.one(c, "SELECT * FROM CF_COMMITTEE WHERE tenant_id=? AND committee_id=?", CommitteeService::row, Db.tenant(tenant), committeeId)
                .orElseThrow(() -> CaseFileException.notFound("committee", committeeId));
    }

    public List<Committee> list(Connection c, String tenant, String kindOrNull, boolean activeOnly) {
        String sql = "SELECT * FROM CF_COMMITTEE WHERE tenant_id=?" + (kindOrNull == null ? "" : " AND committee_kind=?")
                + (activeOnly ? " AND status='ACTIVE'" : "") + " ORDER BY name";
        return kindOrNull == null ? Jdbc.query(c, sql, CommitteeService::row, Db.tenant(tenant))
                : Jdbc.query(c, sql, CommitteeService::row, Db.tenant(tenant), kindOrNull);
    }

    public Member member(Connection c, String tenant, String memberId) {
        return Jdbc.one(c, "SELECT * FROM CF_COMMITTEE_MEMBER WHERE tenant_id=? AND member_id=?", CommitteeService::memberRow,
                Db.tenant(tenant), memberId).orElseThrow(() -> CaseFileException.notFound("member", memberId));
    }

    /** Members at {@code at} ({@code null} = current members). */
    public List<Member> members(Connection c, String tenant, String committeeId, Instant at) {
        if (at == null) {
            return Jdbc.query(c, "SELECT * FROM CF_COMMITTEE_MEMBER WHERE tenant_id=? AND committee_id=? AND valid_to IS NULL ORDER BY member_role, display_name",
                    CommitteeService::memberRow, Db.tenant(tenant), committeeId);
        }
        return Jdbc.query(c, "SELECT * FROM CF_COMMITTEE_MEMBER WHERE tenant_id=? AND committee_id=? AND valid_from <= ?"
                        + " AND (valid_to IS NULL OR valid_to > ?) ORDER BY member_role, display_name",
                CommitteeService::memberRow, Db.tenant(tenant), committeeId, at, at);
    }

    public Optional<Member> chair(Connection c, String tenant, String committeeId) {
        return members(c, tenant, committeeId, null).stream().filter(m -> "CHAIR".equals(m.role())).findFirst();
    }

    /** Voting members needed under the committee's rule, out of the current voting members. */
    public int quorumRequired(Connection c, String tenant, String committeeId) {
        Committee cm = get(c, tenant, committeeId);
        int voting = (int) members(c, tenant, committeeId, null).stream().filter(Member::voting).count();
        return CaseConfig.quorumRequired(cm.quorumRule(), voting);
    }

    Committee requireActive(Connection c, String tenant, String committeeId) {
        Committee cm = get(c, tenant, committeeId);
        if (cm.status() != Status.ACTIVE) {
            throw CaseFileException.state("committee is " + cm.status());
        }
        return cm;
    }

    private void requireChair(Connection c, String t, String committeeId, boolean chairRequired, Instant at) {
        if (chairRequired && members(c, t, committeeId, null).stream().noneMatch(m -> "CHAIR".equals(m.role()))) {
            throw CaseFileException.invalid("the committee kind requires a chair");
        }
    }

    static Committee row(ResultSet rs) throws SQLException {
        return new Committee(rs.getString("tenant_id"), rs.getString("committee_id"), rs.getString("committee_kind"), rs.getString("name"),
                rs.getString("org_unit"), rs.getString("constitution_ref"), rs.getString("constituted_by"), Jdbc.instant(rs, "constituted_at"),
                Jdbc.instant(rs, "valid_from"), Jdbc.instant(rs, "valid_to"), rs.getString("quorum_rule"), rs.getInt("chair_required") != 0,
                rs.getInt("min_notice_hours"), Db.map(rs.getString("terms_json")), Status.valueOf(rs.getString("status")),
                rs.getLong("row_version"));
    }

    static Member memberRow(ResultSet rs) throws SQLException {
        return new Member(rs.getString("tenant_id"), rs.getString("member_id"), rs.getString("committee_id"), rs.getString("person_ref"),
                rs.getString("seat_ref"), rs.getString("display_name"), rs.getString("member_role"), rs.getInt("voting") != 0,
                Jdbc.instant(rs, "valid_from"), Jdbc.instant(rs, "valid_to"));
    }
}
