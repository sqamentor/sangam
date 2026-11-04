// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.admin;

import org.sangam.core.layer.Jdbc;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.security.delegation.DelegationOfPowersService;
import org.sangam.security.dialect.SecurityDialect;
import org.sangam.security.org.OrgModel;
import org.sangam.security.policy.AuthorizationService;
import org.sangam.security.policy.AuthorizedQueryBuilder.CappedCount;
import org.sangam.security.policy.AuthorizedQueryBuilder.Page;
import org.sangam.security.policy.Permission;
import org.sangam.security.policy.ResourceRegistry;
import org.sangam.security.support.Sql;

import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Paged, tenant-scoped read services for the admin screens (A01–A08) and the server bridge. Every list:
 * <ul>
 *   <li>checks {@code READ} on the virtual admin resource type with the VI.4 object check (deny wins, B-11);</li>
 *   <li>binds the caller's tenant in SQL (VI.4 rule 6, K1) — no request tenant is accepted;</li>
 *   <li>filters, orders and pages in SQL per dialect (no in-memory slicing), page size ≤ 1,000 (R28);</li>
 *   <li>returns a capped total ("more than N", A-16).</li>
 * </ul>
 * Text filters ({@code q}) are case-insensitive substring matches on id and name.
 */
public final class SecurityDirectoryService {

    public record OrgUnitFilter(String parentId, String levelType, String q, boolean includeInactive, Instant at) {
    }

    public record SeatFilter(String unitId, String designation, String q, boolean includeInactive, Instant at) {
    }

    /** {@code kind}: REGULAR, ADDITIONAL_CHARGE, LEAVE_SUBSTITUTE; {@code actingOnly} selects delegations of a person. */
    public record PostingFilter(String seatId, String personId, String kind, boolean actingOnly, Instant activeAt,
                                boolean includeRevoked) {
    }

    public record PostingRow(OrgModel.Posting posting, String createdBy, Instant createdAt, boolean revoked) {
    }

    public record PowerTableFilter(String tableKey, String status, Instant effectiveAt) {
    }

    /** Power-table version without the DMN XML (fetch it with {@code DelegationOfPowersService.table}). */
    public record PowerTableRow(String tableKey, int version, String status, Instant effectiveFrom, Instant effectiveTo,
                                String dmnHash, String decisionId, String authorityRef, String author, String approver,
                                Instant publishedAt) {
    }

    public record PolicyFilter(String resourceType, String effect, String q) {
    }

    public record PolicyRow(String policyId, String name, String effect, String resourceType, String resourceId,
                            long version, String updatedBy, Instant updatedAt, PolicyAdminService.PolicyDefinition definition) {
    }

    public record RoleRow(String roleId, String name, String rulesJson, long activeBindings, Instant updatedAt) {
    }

    public record GroupRow(String groupId, String name, String externalRef, String source, long members) {
    }

    public record UserFilter(String q, String status, String groupId) {
    }

    public record SodExceptionFilter(String status, String caseId, String personId) {
    }

    public record SodExceptionRow(String exceptionId, String caseId, String duty, String personId, String seatId,
                                  String reason, String requestedBy, String status, String approvedBy,
                                  String approverSeatId, String approvalReason, Instant expiresAt, Instant decidedAt,
                                  Instant usedAt, Instant createdAt) {
    }

    private final SecurityDialect dialect;
    private final AuthorizationService authz;

    public SecurityDirectoryService(SecurityDialect dialect, AuthorizationService authz) {
        this.dialect = dialect;
        this.authz = authz;
    }

    // ------------------------------------------------------------------ org units

    public Page<OrgModel.OrgUnit> orgUnits(Connection c, SecurityIdentity actor, OrgUnitFilter f, int offset, int limit, int cap) {
        authz.require(c, actor, ResourceRegistry.ORG_UNIT, Permission.READ, ResourceRegistry.ANY_ID);
        OrgUnitFilter flt = f == null ? new OrgUnitFilter(null, null, null, false, null) : f;
        Instant at = flt.at() == null ? Sql.dbNow(c, dialect) : flt.at();
        String t = actor.tenantId();
        Where w = new Where("u.tenant_id = ?", t);
        String parent = "(SELECT p.parent_id FROM SEC_ORG_UNIT_PARENT p WHERE p.tenant_id=u.tenant_id AND p.unit_id=u.unit_id"
                + " AND p.valid_from <= ? AND (p.valid_to IS NULL OR p.valid_to > ?))";
        if (flt.parentId() != null) {
            w.and(parent + " = ?", at, at, flt.parentId());
        }
        if (flt.levelType() != null) {
            w.and("u.level_type = ?", flt.levelType());
        }
        if (!flt.includeInactive()) {
            w.and("u.status = 'ACTIVE'");
        }
        if (flt.q() != null && !flt.q().isBlank()) {
            w.and("(LOWER(u.unit_id) LIKE ? ESCAPE '!' OR LOWER(" + nameExpr("en") + ") LIKE ? ESCAPE '!'"
                    + " OR LOWER(" + nameExpr("hi") + ") LIKE ? ESCAPE '!')", like(flt.q()), at, at, like(flt.q()), at, at, like(flt.q()));
        }
        String select = "SELECT u.unit_id, u.level_type, u.level_no, " + nameExpr("en") + " AS name_en, " + nameExpr("hi")
                + " AS name_hi, u.head_seat_id, u.status, " + parent + " AS parent_id";
        List<Object> sp = List.of(at, at, at, at, at, at);
        return page(c, select, sp, "FROM SEC_ORG_UNIT u", w, "u.level_no, u.unit_id", offset, limit, cap,
                rs -> new OrgModel.OrgUnit(t, rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(8), rs.getString(7)));
    }

    // ------------------------------------------------------------------ seats

    public Page<OrgModel.Seat> seats(Connection c, SecurityIdentity actor, SeatFilter f, int offset, int limit, int cap) {
        authz.require(c, actor, ResourceRegistry.SEAT, Permission.READ, ResourceRegistry.ANY_ID);
        SeatFilter flt = f == null ? new SeatFilter(null, null, null, false, null) : f;
        Instant at = flt.at() == null ? Sql.dbNow(c, dialect) : flt.at();
        String t = actor.tenantId();
        String unit = "(SELECT p.unit_id FROM SEC_SEAT_PLACEMENT p WHERE p.tenant_id=s.tenant_id AND p.seat_id=s.seat_id"
                + " AND p.valid_from <= ? AND (p.valid_to IS NULL OR p.valid_to > ?))";
        Where w = new Where("s.tenant_id = ?", t);
        if (flt.unitId() != null) {
            w.and(unit + " = ?", at, at, flt.unitId());
        }
        if (flt.designation() != null) {
            w.and("s.designation = ?", flt.designation());
        }
        if (!flt.includeInactive()) {
            w.and("s.status = 'ACTIVE'");
        }
        if (flt.q() != null && !flt.q().isBlank()) {
            w.and("(LOWER(s.seat_id) LIKE ? ESCAPE '!' OR LOWER(s.designation) LIKE ? ESCAPE '!')", like(flt.q()), like(flt.q()));
        }
        String select = "SELECT s.seat_id, s.designation, s.rank_no, s.pool_flag, s.escalation_seat_id, s.status, " + unit + " AS unit_id";
        return page(c, select, List.of(at, at), "FROM SEC_SEAT s", w, "s.rank_no DESC, s.seat_id", offset, limit, cap,
                rs -> new OrgModel.Seat(t, rs.getString(1), rs.getString(2), rs.getInt(3), rs.getInt(4) == 1,
                        rs.getString(5), rs.getString(7), rs.getString(6)));
    }

    // ------------------------------------------------------------------ postings and person delegations

    public Page<PostingRow> postings(Connection c, SecurityIdentity actor, PostingFilter f, int offset, int limit, int cap) {
        authz.require(c, actor, ResourceRegistry.POSTING, Permission.READ, ResourceRegistry.ANY_ID);
        PostingFilter flt = f == null ? new PostingFilter(null, null, null, false, null, false) : f;
        String t = actor.tenantId();
        Where w = new Where("tenant_id = ?", t);
        if (flt.seatId() != null) {
            w.and("seat_id = ?", flt.seatId());
        }
        if (flt.personId() != null) {
            w.and("person_id = ?", flt.personId());
        }
        if (flt.kind() != null) {
            w.and("kind = ?", flt.kind().toUpperCase(Locale.ROOT));
        }
        if (flt.actingOnly()) {
            w.and("kind <> 'REGULAR'");
        }
        if (flt.activeAt() != null) {
            w.and("valid_from <= ? AND (valid_to IS NULL OR valid_to > ?)", flt.activeAt(), flt.activeAt());
        }
        if (!flt.includeRevoked()) {
            w.and("revoked_at IS NULL");
        }
        String select = "SELECT posting_id, seat_id, person_id, kind, valid_from, valid_to, order_ref, power_cap, created_by,"
                + " created_at, revoked_at";
        return page(c, select, List.of(), "FROM SEC_POSTING", w, "valid_from DESC, posting_id", offset, limit, cap,
                rs -> new PostingRow(new OrgModel.Posting(t, rs.getString(1), rs.getString(2), rs.getString(3),
                        OrgModel.PostingKind.valueOf(rs.getString(4)), Jdbc.instant(rs, "valid_from"), Jdbc.instant(rs, "valid_to"),
                        rs.getString(7), Sql.decimal(rs.getString(8))), rs.getString(9), Jdbc.instant(rs, "created_at"),
                        Jdbc.instant(rs, "revoked_at") != null));
    }

    // ------------------------------------------------------------------ delegation-of-powers tables

    public Page<PowerTableRow> powerTables(Connection c, SecurityIdentity actor, PowerTableFilter f, int offset, int limit, int cap) {
        authz.require(c, actor, ResourceRegistry.POWER_TABLE, Permission.READ, ResourceRegistry.ANY_ID);
        PowerTableFilter flt = f == null ? new PowerTableFilter(null, null, null) : f;
        Where w = new Where("tenant_id = ?", actor.tenantId());
        if (flt.tableKey() != null) {
            w.and("table_key = ?", flt.tableKey());
        }
        if (flt.status() != null) {
            w.and("status = ?", flt.status().toUpperCase(Locale.ROOT));
        }
        if (flt.effectiveAt() != null) {
            w.and("status = 'PUBLISHED' AND effective_from <= ? AND (effective_to IS NULL OR effective_to > ?)",
                    flt.effectiveAt(), flt.effectiveAt());
        }
        String select = "SELECT table_key, version, status, effective_from, effective_to, dmn_hash, decision_id, authority_ref,"
                + " author, approver, published_at";
        return page(c, select, List.of(), "FROM SEC_POWER_TABLE", w, "table_key, version DESC", offset, limit, cap,
                rs -> new PowerTableRow(rs.getString(1), rs.getInt(2), rs.getString(3), Jdbc.instant(rs, "effective_from"),
                        Jdbc.instant(rs, "effective_to"), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
                        rs.getString(10), Jdbc.instant(rs, "published_at")));
    }

    /** One power-table version including its DMN XML (READ on POWER_TABLE). */
    public java.util.Optional<DelegationOfPowersService.PowerTable> powerTable(Connection c, SecurityIdentity actor,
                                                                              DelegationOfPowersService powers,
                                                                              String tableKey, int version) {
        authz.require(c, actor, ResourceRegistry.POWER_TABLE, Permission.READ, tableKey);
        return powers.table(c, actor.tenantId(), tableKey, version);
    }

    // ------------------------------------------------------------------ policies

    public Page<PolicyRow> policies(Connection c, SecurityIdentity actor, PolicyFilter f, int offset, int limit, int cap) {
        authz.require(c, actor, ResourceRegistry.POLICY, Permission.READ, ResourceRegistry.ANY_ID);
        PolicyFilter flt = f == null ? new PolicyFilter(null, null, null) : f;
        Where w = new Where("tenant_id = ? AND status = 'ACTIVE'", actor.tenantId());
        if (flt.resourceType() != null) {
            w.and("resource_type = ?", flt.resourceType());
        }
        if (flt.effect() != null) {
            w.and("effect = ?", flt.effect().toUpperCase(Locale.ROOT));
        }
        if (flt.q() != null && !flt.q().isBlank()) {
            w.and("(LOWER(policy_id) LIKE ? ESCAPE '!' OR LOWER(name) LIKE ? ESCAPE '!')", like(flt.q()), like(flt.q()));
        }
        String select = "SELECT policy_id, name, effect, resource_type, resource_id, version, updated_by, updated_at, definition";
        return page(c, select, List.of(), "FROM SEC_POLICY", w, "policy_id", offset, limit, cap,
                rs -> new PolicyRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getLong(6), rs.getString(7), Jdbc.instant(rs, "updated_at"),
                        PolicyAdminService.fromJson(rs.getString(1), rs.getString(9))));
    }

    public Page<PolicyAdminService.PolicyChange> policyChanges(Connection c, SecurityIdentity actor, String status,
                                                              int offset, int limit, int cap) {
        authz.require(c, actor, ResourceRegistry.POLICY, Permission.READ, ResourceRegistry.ANY_ID);
        Where w = new Where("tenant_id = ?", actor.tenantId());
        if (status != null) {
            w.and("status = ?", status.toUpperCase(Locale.ROOT));
        }
        return page(c, "SELECT change_id, policy_id, operation, status, requested_by", List.of(), "FROM SEC_POLICY_CHANGE", w,
                "requested_at DESC, change_id", offset, limit, cap,
                rs -> new PolicyAdminService.PolicyChange(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5)));
    }

    // ------------------------------------------------------------------ roles and groups

    public Page<RoleRow> roles(Connection c, SecurityIdentity actor, String q, int offset, int limit, int cap) {
        authz.require(c, actor, ResourceRegistry.ROLE, Permission.READ, ResourceRegistry.ANY_ID);
        Instant now = Sql.dbNow(c, dialect);
        Where w = new Where("r.tenant_id = ?", actor.tenantId());
        if (q != null && !q.isBlank()) {
            w.and("(LOWER(r.role_id) LIKE ? ESCAPE '!' OR LOWER(r.name) LIKE ? ESCAPE '!')", like(q), like(q));
        }
        String select = "SELECT r.role_id, r.name, r.rules, (SELECT COUNT(*) FROM SEC_ROLE_BINDING b WHERE b.tenant_id=r.tenant_id"
                + " AND b.role_id=r.role_id AND b.valid_from <= ? AND (b.valid_to IS NULL OR b.valid_to > ?)) AS bindings, r.updated_at";
        return page(c, select, List.of(now, now), "FROM SEC_ROLE r", w, "r.role_id", offset, limit, cap,
                rs -> new RoleRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4), Jdbc.instant(rs, "updated_at")));
    }

    public Page<RoleAdminService.RoleBinding> roleBindings(Connection c, SecurityIdentity actor, String roleId, String memberId,
                                                          int offset, int limit, int cap) {
        authz.require(c, actor, ResourceRegistry.ROLE, Permission.READ, roleId == null ? ResourceRegistry.ANY_ID : roleId);
        Where w = new Where("tenant_id = ?", actor.tenantId());
        if (roleId != null) {
            w.and("role_id = ?", roleId);
        }
        if (memberId != null) {
            w.and("member_id = ?", memberId);
        }
        return page(c, "SELECT binding_id, role_id, member_id, valid_from, valid_to", List.of(), "FROM SEC_ROLE_BINDING", w,
                "role_id, valid_from, binding_id", offset, limit, cap,
                rs -> new RoleAdminService.RoleBinding(rs.getString(1), rs.getString(2), rs.getString(3),
                        Jdbc.instant(rs, "valid_from"), Jdbc.instant(rs, "valid_to")));
    }

    public Page<GroupRow> groups(Connection c, SecurityIdentity actor, String q, String source, int offset, int limit, int cap) {
        authz.require(c, actor, ResourceRegistry.GROUP, Permission.READ, ResourceRegistry.ANY_ID);
        Where w = new Where("g.tenant_id = ?", actor.tenantId());
        if (source != null) {
            w.and("g.source = ?", source.toUpperCase(Locale.ROOT));
        }
        if (q != null && !q.isBlank()) {
            w.and("(LOWER(g.group_id) LIKE ? ESCAPE '!' OR LOWER(g.name) LIKE ? ESCAPE '!')", like(q), like(q));
        }
        String select = "SELECT g.group_id, g.name, g.external_ref, g.source, (SELECT COUNT(*) FROM SEC_GROUP_MEMBER m"
                + " WHERE m.tenant_id=g.tenant_id AND m.group_id=g.group_id) AS members";
        return page(c, select, List.of(), "FROM SEC_GROUP g", w, "g.group_id", offset, limit, cap,
                rs -> new GroupRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5)));
    }

    public Page<String> groupMembers(Connection c, SecurityIdentity actor, String groupId, int offset, int limit, int cap) {
        authz.require(c, actor, ResourceRegistry.GROUP, Permission.READ, groupId);
        Where w = new Where("tenant_id = ? AND group_id = ?", actor.tenantId(), groupId);
        return page(c, "SELECT person_id", List.of(), "FROM SEC_GROUP_MEMBER", w, "person_id", offset, limit, cap,
                rs -> rs.getString(1));
    }

    // ------------------------------------------------------------------ users

    public Page<OrgModel.Person> users(Connection c, SecurityIdentity actor, UserFilter f, int offset, int limit, int cap) {
        authz.require(c, actor, ResourceRegistry.USER, Permission.READ, ResourceRegistry.ANY_ID);
        UserFilter flt = f == null ? new UserFilter(null, null, null) : f;
        String t = actor.tenantId();
        Where w = new Where("p.tenant_id = ?", t);
        if (flt.status() != null) {
            w.and("p.status = ?", flt.status().toUpperCase(Locale.ROOT));
        }
        if (flt.groupId() != null) {
            w.and("EXISTS (SELECT 1 FROM SEC_GROUP_MEMBER m WHERE m.tenant_id=p.tenant_id AND m.person_id=p.person_id AND m.group_id=?)",
                    flt.groupId());
        }
        if (flt.q() != null && !flt.q().isBlank()) {
            w.and("(LOWER(p.person_id) LIKE ? ESCAPE '!' OR LOWER(p.display_name) LIKE ? ESCAPE '!' OR LOWER(p.email) LIKE ? ESCAPE '!')",
                    like(flt.q()), like(flt.q()), like(flt.q()));
        }
        return page(c, "SELECT p.person_id, p.display_name, p.external_subject, p.email, p.status", List.of(), "FROM SEC_PERSON p",
                w, "p.person_id", offset, limit, cap,
                rs -> new OrgModel.Person(t, rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)));
    }

    // ------------------------------------------------------------------ SoD exceptions

    public Page<SodExceptionRow> sodExceptions(Connection c, SecurityIdentity actor, SodExceptionFilter f, int offset, int limit,
                                               int cap) {
        authz.require(c, actor, ResourceRegistry.SOD_EXCEPTION, Permission.READ, ResourceRegistry.ANY_ID);
        SodExceptionFilter flt = f == null ? new SodExceptionFilter(null, null, null) : f;
        Where w = new Where("tenant_id = ?", actor.tenantId());
        if (flt.status() != null) {
            w.and("status = ?", flt.status().toUpperCase(Locale.ROOT));
        }
        if (flt.caseId() != null) {
            w.and("case_id = ?", flt.caseId());
        }
        if (flt.personId() != null) {
            w.and("person_id = ?", flt.personId());
        }
        String select = "SELECT exception_id, case_id, duty, person_id, seat_id, reason, requested_by, status, approved_by,"
                + " approver_seat_id, approval_reason, expires_at, decided_at, used_at, created_at";
        return page(c, select, List.of(), "FROM SEC_SOD_EXCEPTION", w, "created_at DESC, exception_id", offset, limit, cap,
                rs -> new SodExceptionRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11),
                        Jdbc.instant(rs, "expires_at"), Jdbc.instant(rs, "decided_at"), Jdbc.instant(rs, "used_at"),
                        Jdbc.instant(rs, "created_at")));
    }

    // ------------------------------------------------------------------ internals

    /** A WHERE clause with its bind parameters in text order. */
    private static final class Where {
        private final StringBuilder sql;
        private final List<Object> params = new ArrayList<>();

        Where(String first, Object... p) {
            sql = new StringBuilder(first);
            params.addAll(List.of(p));
        }

        Where and(String clause, Object... p) {
            sql.append(" AND ").append(clause);
            params.addAll(List.of(p));
            return this;
        }
    }

    private <T> Page<T> page(Connection c, String select, List<Object> selectParams, String from, Where w, String orderBy,
                             int offset, int limit, int cap, Jdbc.RowMapper<T> mapper) {
        int size = Math.max(1, Math.min(limit, 1000));
        int off = Math.max(0, offset);
        String fromWhere = from + " WHERE " + w.sql;
        List<Object> listParams = new ArrayList<>(selectParams);
        listParams.addAll(w.params);
        List<T> items = Jdbc.query(c, dialect.page(select + " " + fromWhere + " ORDER BY " + orderBy, off, size), mapper,
                listParams.toArray());
        int countCap = cap <= 0 ? 1000 : cap;
        long n = Jdbc.one(c, dialect.cappedCount(fromWhere, countCap), rs -> rs.getLong(1), w.params.toArray()).orElse(0L);
        CappedCount total = n > countCap ? new CappedCount(countCap, true) : new CappedCount(n, false);
        return new Page<>(items, off, size, total);
    }

    /** Unit name at a bound instant (two {@code ?}), alias {@code u}; same rule as {@code OrgUnitService.unitAt}. */
    private static String nameExpr(String lang) {
        return "COALESCE((SELECT n.name_" + lang + " FROM SEC_ORG_UNIT_NAME n WHERE n.tenant_id=u.tenant_id AND n.unit_id=u.unit_id"
                + " AND n.valid_from <= ? AND (n.valid_to IS NULL OR n.valid_to > ?)), u.name_" + lang + ")";
    }

    /** {@code %q%}, lower-cased, with {@code ! % _} escaped by {@code !}. */
    private static String like(String q) {
        String e = q.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return "%" + e + "%";
    }
}
