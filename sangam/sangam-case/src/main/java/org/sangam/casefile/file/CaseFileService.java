// SPDX-License-Identifier: Apache-2.0
package org.sangam.casefile.file;

import org.sangam.casefile.CaseFileException;
import org.sangam.casefile.common.CaseEvents;
import org.sangam.casefile.common.NumberService;
import org.sangam.casefile.config.CaseConfig;
import org.sangam.casefile.config.CaseConfigRegistry;
import org.sangam.casefile.deadline.DeadlineService;
import org.sangam.casefile.support.Db;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.SqlPredicate;
import org.sangam.ports.links.InstanceLinkService;
import org.sangam.ports.support.Paging;

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
 * The shared case file (N04) that spans processes and CMMN cases: a typed, numbered record with a stage machine from
 * its case type, custom fields validated against the type, an owner seat, a confidentiality level and the CMMN case /
 * BPMN process instances that work on it. Every change is a timeline event ({@link CaseEvents}).
 */
public class CaseFileService {

    public enum Status { OPEN, CLOSED, MERGED }

    public record CaseFile(String tenantId, String caseId, String caseType, int typeVersion, String caseNumber, String title,
                           Status status, String stage, String jurisdiction, String orgUnit, String ownerSeat, String priority,
                           String confidentiality, String businessKey, String cmmnCaseInstanceId, String processInstanceId,
                           String mergedInto, String splitFrom, Map<String, Object> attributes, String openedBy,
                           Instant openedAt, Instant closedAt, String closedBy, String closeReason, long rowVersion) {
    }

    public record OpenRequest(String caseType, String title, String jurisdiction, String orgUnit, String orgCode,
                              String ownerSeat, String priority, String confidentiality, String businessKey,
                              Map<String, Object> attributes, String cmmnCaseInstanceId, String processInstanceId) {
    }

    public record Filter(String caseType, Status status, String stage, String orgUnit, String ownerSeat, String text,
                         String partyId, Instant openedFrom, Instant openedTo) {
    }

    private final Dialect dialect;
    private final CaseConfigRegistry config;
    private final NumberService numbers;
    private final CaseEvents events;
    private final DeadlineService deadlines;
    private final InstanceLinkService links;

    /** @param linksOrNull FC-31/32 entity links; {@code null} keeps the binding only in {@code CF_CASE_RECORD} */
    public CaseFileService(Dialect dialect, CaseConfigRegistry config, NumberService numbers, CaseEvents events,
                           DeadlineService deadlines, InstanceLinkService linksOrNull) {
        this.dialect = dialect;
        this.config = config;
        this.numbers = numbers;
        this.events = events;
        this.deadlines = deadlines;
        this.links = linksOrNull;
    }

    public CaseFile open(Connection c, String tenant, OpenRequest r, String actor) {
        String t = Db.tenant(tenant);
        Db.requireText(r.caseType(), "caseType");
        Db.requireText(r.title(), "title");
        CaseConfig.CaseType type = config.caseType(c, t, r.caseType());
        Map<String, Object> attrs = validated(type, r.attributes(), true);
        Instant now = Db.now(c, dialect);
        String id = Db.newId();
        String number = numbers.next(c, t, type.numbering(), r.orgCode(), now);
        String confidentiality = r.confidentiality() == null ? type.defaultConfidentiality() : r.confidentiality();
        Jdbc.update(c, "INSERT INTO CF_CASE(tenant_id, case_id, case_type, type_version, case_number, title, status, stage, jurisdiction,"
                        + " org_unit, owner_seat, priority, confidentiality, business_key, cmmn_case_instance_id, process_instance_id,"
                        + " attributes_json, opened_by, opened_at, row_version) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)",
                t, id, type.key(), type.version(), number, r.title(), Status.OPEN, type.initialStage(), r.jurisdiction(), r.orgUnit(),
                r.ownerSeat(), r.priority(), confidentiality, r.businessKey() == null ? number : r.businessKey(),
                r.cmmnCaseInstanceId(), r.processInstanceId(), Db.json(attrs), actor, now);
        for (String folder : type.folders()) {
            Jdbc.update(c, "INSERT INTO CF_FOLDER(tenant_id, folder_id, case_id, parent_folder_id, name, created_by, created_at)"
                    + " VALUES (?,?,?,NULL,?,?,?)", t, Db.newId(), id, folder, actor, now);
        }
        events.record(c, t, CaseEvents.CASE, id, "CASE_OPENED", actor, number + " " + r.title(),
                Db.kv("caseType", type.key(), "typeVersion", type.version(), "caseNumber", number, "stage", type.initialStage()));
        if (r.cmmnCaseInstanceId() != null) {
            bindInstance(c, t, id, "cmmn", r.cmmnCaseInstanceId(), actor);
        }
        if (r.processInstanceId() != null) {
            bindInstance(c, t, id, "bpmn", r.processInstanceId(), actor);
        }
        for (CaseConfig.DeadlineRule rule : type.deadlines()) {
            if (rule.onStage() == null || rule.onStage().equals(type.initialStage())) {
                deadlines.start(c, t, CaseEvents.CASE, id, rule.kind(), r.ownerSeat(), actor, rule.period(), rule.calendar(),
                        rule.warnBefore(), rule.ladder());
            }
        }
        return get(c, t, id);
    }

    public CaseFile get(Connection c, String tenant, String caseId) {
        return find(c, tenant, caseId).orElseThrow(() -> CaseFileException.notFound("case", caseId));
    }

    public Optional<CaseFile> find(Connection c, String tenant, String caseId) {
        return Jdbc.one(c, "SELECT * FROM CF_CASE WHERE tenant_id=? AND case_id=?", CaseFileService::row, Db.tenant(tenant), caseId);
    }

    public Optional<CaseFile> byNumber(Connection c, String tenant, String caseNumber) {
        return Jdbc.one(c, "SELECT * FROM CF_CASE WHERE tenant_id=? AND case_number=?", CaseFileService::row, Db.tenant(tenant), caseNumber);
    }

    public Optional<CaseFile> byCmmnInstance(Connection c, String tenant, String cmmnCaseInstanceId) {
        return Jdbc.query(c, "SELECT * FROM CF_CASE WHERE tenant_id=? AND cmmn_case_instance_id=?", CaseFileService::row,
                Db.tenant(tenant), cmmnCaseInstanceId).stream().findFirst();
    }

    public List<CaseFile> byBusinessKey(Connection c, String tenant, String businessKey) {
        return Jdbc.query(c, "SELECT * FROM CF_CASE WHERE tenant_id=? AND business_key=? ORDER BY opened_at", CaseFileService::row,
                Db.tenant(tenant), businessKey);
    }

    /**
     * Cross-case search under VI.4 (R19): {@code auth} is the compiled predicate over alias {@code cf}; there is no
     * post-filtering, and the count is capped (A-16).
     */
    public Paging.PageResult<CaseFile> list(Connection c, String tenant, Filter f, SqlPredicate auth, Paging.PageRequest page) {
        StringBuilder where = new StringBuilder(" FROM CF_CASE cf WHERE cf.tenant_id=?");
        List<Object> params = new ArrayList<>();
        params.add(Db.tenant(tenant));
        Filter q = f == null ? new Filter(null, null, null, null, null, null, null, null, null) : f;
        if (q.caseType() != null) {
            where.append(" AND cf.case_type=?");
            params.add(q.caseType());
        }
        if (q.status() != null) {
            where.append(" AND cf.status=?");
            params.add(q.status());
        }
        if (q.stage() != null) {
            where.append(" AND cf.stage=?");
            params.add(q.stage());
        }
        if (q.orgUnit() != null) {
            where.append(" AND cf.org_unit=?");
            params.add(q.orgUnit());
        }
        if (q.ownerSeat() != null) {
            where.append(" AND cf.owner_seat=?");
            params.add(q.ownerSeat());
        }
        if (q.text() != null && !q.text().isBlank()) {
            where.append(" AND (LOWER(cf.title) LIKE ? ESCAPE '!' OR cf.case_number LIKE ? ESCAPE '!')");
            String like = Db.likeContains(q.text().toLowerCase());
            params.add(like);
            params.add(Db.likeContains(q.text()));
        }
        if (q.partyId() != null) {
            where.append(" AND EXISTS (SELECT 1 FROM CF_CASE_PARTY cp WHERE cp.tenant_id=cf.tenant_id AND cp.case_id=cf.case_id"
                    + " AND cp.party_id=? AND cp.valid_to IS NULL)");
            params.add(q.partyId());
        }
        if (q.openedFrom() != null) {
            where.append(" AND cf.opened_at >= ?");
            params.add(q.openedFrom());
        }
        if (q.openedTo() != null) {
            where.append(" AND cf.opened_at < ?");
            params.add(q.openedTo());
        }
        SqlPredicate a = Db.orTrue(auth);
        where.append(" AND (").append(a.sql()).append(")");
        params.addAll(a.params());
        Paging.PageRequest p = page == null ? new Paging.PageRequest(0, 50) : page;
        long total = Jdbc.one(c, Paging.cappedCount(dialect, where.toString()), rs -> rs.getLong(1), params.toArray()).orElse(0L);
        List<CaseFile> items = Jdbc.query(c, "SELECT cf.*" + where + " ORDER BY cf.opened_at DESC, cf.case_id" + Paging.window(dialect, p),
                CaseFileService::row, params.toArray());
        return Paging.of(items, total, p);
    }

    /** Replaces or merges custom fields (validated against the pinned case-type version). */
    public CaseFile updateAttributes(Connection c, String tenant, String caseId, Map<String, Object> changes, boolean replace,
                                     long expectedRowVersion, String actor) {
        CaseFile cf = requireOpen(c, tenant, caseId);
        CaseConfig.CaseType type = config.caseType(c, tenant, cf.caseType(), cf.typeVersion());
        Map<String, Object> merged = replace ? new LinkedHashMap<>() : new LinkedHashMap<>(cf.attributes());
        if (changes != null) {
            merged.putAll(changes);
        }
        Map<String, Object> attrs = validated(type, merged, true);
        bump(c, tenant, caseId, expectedRowVersion, "attributes_json=?", Db.json(attrs));
        Map<String, Object> changed = new LinkedHashMap<>();
        if (changes != null) {
            changes.forEach((k, v) -> {
                CaseConfig.Field fd = type.field(k);
                changed.put(k, fd != null && fd.sensitive() ? "***" : v);
            });
        }
        events.record(c, tenant, CaseEvents.CASE, caseId, "CASE_UPDATED", actor, "fields changed: " + changed.keySet(), changed);
        return get(c, tenant, caseId);
    }

    public CaseFile retitle(Connection c, String tenant, String caseId, String title, String priority, String confidentiality,
                            long expectedRowVersion, String actor) {
        CaseFile cf = requireOpen(c, tenant, caseId);
        bump(c, tenant, caseId, expectedRowVersion, "title=?, priority=?, confidentiality=?",
                title == null ? cf.title() : title, priority == null ? cf.priority() : priority,
                confidentiality == null ? cf.confidentiality() : confidentiality);
        events.record(c, tenant, CaseEvents.CASE, caseId, "CASE_UPDATED", actor, "header changed",
                Db.kv("title", title, "priority", priority, "confidentiality", confidentiality));
        return get(c, tenant, caseId);
    }

    /**
     * Moves the case to {@code toStage}; the move must be allowed by the case type, and every role the target stage
     * requires must be filled. A terminal stage closes the case. Stage deadlines start on entry; open stage deadlines of
     * the left stage are met.
     */
    public CaseFile moveStage(Connection c, String tenant, String caseId, String toStage, String reason, long expectedRowVersion,
                              String actor) {
        CaseFile cf = requireOpen(c, tenant, caseId);
        CaseConfig.CaseType type = config.caseType(c, tenant, cf.caseType(), cf.typeVersion());
        CaseConfig.Stage target = type.stage(toStage);
        if (target == null) {
            throw CaseFileException.invalid("unknown stage " + toStage);
        }
        CaseConfig.Stage current = cf.stage() == null ? null : type.stage(cf.stage());
        if (current != null && !current.next().isEmpty() && !current.next().contains(toStage)) {
            throw CaseFileException.state("stage " + cf.stage() + " cannot move to " + toStage);
        }
        for (String role : target.requiredRoles()) {
            long n = Jdbc.one(c, "SELECT COUNT(*) FROM CF_CASE_PARTY WHERE tenant_id=? AND case_id=? AND party_role=? AND valid_to IS NULL",
                    rs -> rs.getLong(1), Db.tenant(tenant), caseId, role).orElse(0L);
            if (n == 0) {
                throw CaseFileException.state("stage " + toStage + " requires a party in role " + role);
            }
        }
        bump(c, tenant, caseId, expectedRowVersion, "stage=?", toStage);
        events.record(c, tenant, CaseEvents.CASE, caseId, "STAGE_CHANGED", actor, cf.stage() + " -> " + toStage,
                Db.kv("from", cf.stage(), "to", toStage, "reason", reason));
        for (DeadlineService.Deadline d : deadlines.forScope(c, tenant, CaseEvents.CASE, caseId)) {
            boolean stageBound = type.deadlines().stream().anyMatch(rule -> rule.kind().equals(d.kind()) && cf.stage() != null
                    && cf.stage().equals(rule.onStage()));
            if (stageBound && d.metAt() == null && d.state() != DeadlineService.State.CANCELLED) {
                deadlines.meet(c, tenant, d.id(), actor);
            }
        }
        for (CaseConfig.DeadlineRule rule : type.deadlines()) {
            if (toStage.equals(rule.onStage())) {
                deadlines.start(c, tenant, CaseEvents.CASE, caseId, rule.kind(), cf.ownerSeat(), actor, rule.period(), rule.calendar(),
                        rule.warnBefore(), rule.ladder());
            }
        }
        if (target.terminal()) {
            CaseFile now = get(c, tenant, caseId);
            return close(c, tenant, caseId, reason == null ? "terminal stage " + toStage : reason, now.rowVersion(), actor);
        }
        return get(c, tenant, caseId);
    }

    /** Milestone reached (a configured milestone name): a timeline event the dashboards and CMMN sentries can use. */
    public void milestone(Connection c, String tenant, String caseId, String milestone, Instant occurredAtOrNull, String actor) {
        CaseFile cf = get(c, tenant, caseId);
        CaseConfig.CaseType type = config.caseType(c, tenant, cf.caseType(), cf.typeVersion());
        if (!type.milestones().isEmpty() && !type.milestones().contains(milestone)) {
            throw CaseFileException.invalid("unknown milestone " + milestone);
        }
        events.record(c, tenant, CaseEvents.CASE, caseId, "MILESTONE_REACHED", actor, milestone, Db.kv("milestone", milestone),
                "CASEFILE", occurredAtOrNull);
    }

    public CaseFile assign(Connection c, String tenant, String caseId, String ownerSeat, String orgUnit, long expectedRowVersion,
                           String actor) {
        CaseFile cf = requireOpen(c, tenant, caseId);
        bump(c, tenant, caseId, expectedRowVersion, "owner_seat=?, org_unit=?", ownerSeat, orgUnit == null ? cf.orgUnit() : orgUnit);
        for (DeadlineService.Deadline d : deadlines.forScope(c, tenant, CaseEvents.CASE, caseId)) {
            if (d.metAt() == null && d.state() != DeadlineService.State.CANCELLED) {
                deadlines.reassign(c, tenant, d.id(), ownerSeat, null);
            }
        }
        events.record(c, tenant, CaseEvents.CASE, caseId, "CASE_ASSIGNED", actor, cf.ownerSeat() + " -> " + ownerSeat,
                Db.kv("from", cf.ownerSeat(), "to", ownerSeat, "orgUnit", orgUnit));
        return get(c, tenant, caseId);
    }

    public CaseFile close(Connection c, String tenant, String caseId, String reason, long expectedRowVersion, String actor) {
        requireOpen(c, tenant, caseId);
        Db.requireText(reason, "reason");
        Instant now = Db.now(c, dialect);
        bump(c, tenant, caseId, expectedRowVersion, "status=?, closed_at=?, closed_by=?, close_reason=?", Status.CLOSED, now, actor, reason);
        deadlines.meetScope(c, tenant, CaseEvents.CASE, caseId, null, actor);
        events.record(c, tenant, CaseEvents.CASE, caseId, "CASE_CLOSED", actor, reason, Db.kv("reason", reason));
        return get(c, tenant, caseId);
    }

    public CaseFile reopen(Connection c, String tenant, String caseId, String reason, String stageOrNull, long expectedRowVersion,
                           String actor) {
        CaseFile cf = get(c, tenant, caseId);
        if (cf.status() != Status.CLOSED) {
            throw CaseFileException.state("only a closed case can be reopened (status " + cf.status() + ")");
        }
        Db.requireText(reason, "reason");
        String stage = cf.stage();
        if (stageOrNull != null) {
            CaseConfig.CaseType type = config.caseType(c, tenant, cf.caseType(), cf.typeVersion());
            if (type.stage(stageOrNull) == null) {
                throw CaseFileException.invalid("unknown stage " + stageOrNull);
            }
            stage = stageOrNull;
        }
        bump(c, tenant, caseId, expectedRowVersion, "status=?, stage=?, closed_at=NULL, closed_by=NULL, close_reason=NULL",
                Status.OPEN, stage);
        events.record(c, tenant, CaseEvents.CASE, caseId, "CASE_REOPENED", actor, reason, Db.kv("reason", reason, "stage", stage));
        return get(c, tenant, caseId);
    }

    /** Binds a CMMN case or BPMN process instance working on this file (FC-32 entity link + {@code CF_CASE_RECORD}). */
    public void bindInstance(Connection c, String tenant, String caseId, String scopeType, String instanceId, String actor) {
        String t = Db.tenant(tenant);
        if ("cmmn".equals(scopeType)) {
            Jdbc.update(c, "UPDATE CF_CASE SET cmmn_case_instance_id=COALESCE(cmmn_case_instance_id, ?) WHERE tenant_id=? AND case_id=?",
                    instanceId, t, caseId);
        } else if ("bpmn".equals(scopeType)) {
            Jdbc.update(c, "UPDATE CF_CASE SET process_instance_id=COALESCE(process_instance_id, ?) WHERE tenant_id=? AND case_id=?",
                    instanceId, t, caseId);
        }
        boolean exists = Jdbc.one(c, "SELECT COUNT(*) FROM CF_CASE_RECORD WHERE tenant_id=? AND case_id=? AND record_type=? AND record_ref=?"
                + " AND removed_at IS NULL", rs -> rs.getLong(1), t, caseId, scopeType, instanceId).orElse(0L) > 0;
        if (!exists) {
            Jdbc.update(c, "INSERT INTO CF_CASE_RECORD(tenant_id, record_id, case_id, record_type, record_ref, relation, title, added_by, added_at)"
                    + " VALUES (?,?,?,?,?,'WORKED_BY',NULL,?," + dialect.now() + ")", t, Db.newId(), caseId, scopeType, instanceId, actor);
        }
        if (links != null) {
            links.link(c, t, "REFERENCE", null, "casefile", caseId, scopeType, instanceId, actor);
        }
        events.record(c, t, CaseEvents.CASE, caseId, "INSTANCE_BOUND", actor, scopeType + " " + instanceId,
                Db.kv("scopeType", scopeType, "instanceId", instanceId));
    }

    /** Validates custom fields; unknown fields are rejected so the file never holds data its type does not declare. */
    public static Map<String, Object> validated(CaseConfig.CaseType type, Map<String, Object> attrs, boolean checkRequired) {
        Map<String, Object> in = attrs == null ? Map.of() : attrs;
        List<String> errors = new ArrayList<>();
        for (String k : in.keySet()) {
            if (type.field(k) == null) {
                errors.add("unknown field " + k);
            }
        }
        for (CaseConfig.Field f : type.fields()) {
            Object v = in.get(f.name());
            if (v == null && !checkRequired) {
                continue;
            }
            String err = f.check(v);
            if (err != null) {
                errors.add(err);
            }
        }
        if (!errors.isEmpty()) {
            throw CaseFileException.invalid(String.join("; ", errors));
        }
        return new LinkedHashMap<>(in);
    }

    CaseFile requireOpen(Connection c, String tenant, String caseId) {
        CaseFile cf = get(c, tenant, caseId);
        if (cf.status() != Status.OPEN) {
            throw new CaseFileException(CaseFileException.Code.CASE_CLOSED, "case " + cf.caseNumber() + " is " + cf.status());
        }
        return cf;
    }

    /** Optimistic update guarded by {@code row_version}; {@code expected < 0} skips the check (system callers). */
    void bump(Connection c, String tenant, String caseId, long expected, String setClause, Object... values) {
        List<Object> params = new ArrayList<>(List.of(values));
        params.add(Db.tenant(tenant));
        params.add(caseId);
        String sql = "UPDATE CF_CASE SET " + setClause + ", row_version=row_version+1 WHERE tenant_id=? AND case_id=?";
        if (expected >= 0) {
            sql += " AND row_version=?";
            params.add(expected);
        }
        if (Jdbc.update(c, sql, params.toArray()) != 1) {
            throw new CaseFileException(CaseFileException.Code.CONCURRENT_UPDATE, "case changed since it was read");
        }
    }

    static CaseFile row(ResultSet rs) throws SQLException {
        return new CaseFile(rs.getString("tenant_id"), rs.getString("case_id"), rs.getString("case_type"), rs.getInt("type_version"),
                rs.getString("case_number"), rs.getString("title"), Status.valueOf(rs.getString("status")), rs.getString("stage"),
                rs.getString("jurisdiction"), rs.getString("org_unit"), rs.getString("owner_seat"), rs.getString("priority"),
                rs.getString("confidentiality"), rs.getString("business_key"), rs.getString("cmmn_case_instance_id"),
                rs.getString("process_instance_id"), rs.getString("merged_into"), rs.getString("split_from"),
                Db.map(rs.getString("attributes_json")), rs.getString("opened_by"), Jdbc.instant(rs, "opened_at"),
                Jdbc.instant(rs, "closed_at"), rs.getString("closed_by"), rs.getString("close_reason"), rs.getLong("row_version"));
    }
}
