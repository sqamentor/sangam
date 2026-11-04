// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.org;

import org.sangam.core.layer.Jdbc;
import org.sangam.core.security.SangamSecurityException;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.security.audit.SecurityAuditService;
import org.sangam.security.audit.SecurityEventType;
import org.sangam.security.dialect.SecurityDialect;
import org.sangam.security.policy.AuthorizationService;
import org.sangam.security.policy.Permission;
import org.sangam.security.policy.ResourceRegistry;
import org.sangam.security.support.Sql;

import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Organisation hierarchy (IX.5 rule 2, AJ-02). Level types are fixed per tenant ({@code SEC_ORG_LEVEL}) so routing
 * rules refer to levels, not people. A unit's parent is effective-dated ({@code SEC_ORG_UNIT_PARENT}): a
 * re-organisation from a date changes routing for new tasks from that date while open tasks stay with their seats
 * (AJ-02 step 3).
 */
public final class OrgUnitService {

    private final SecurityDialect dialect;
    private final AuthorizationService authz;
    private final SecurityAuditService audit;

    public OrgUnitService(SecurityDialect dialect, AuthorizationService authz, SecurityAuditService audit) {
        this.dialect = dialect;
        this.authz = authz;
        this.audit = audit;
    }

    /** Defines the tenant's level types in order (1 = top). Replaces an earlier definition only while no unit uses it. */
    public void defineLevels(Connection c, SecurityIdentity actor, List<String> levelTypes) {
        authz.require(c, actor, ResourceRegistry.ORG_UNIT, Permission.ADMIN, ResourceRegistry.ANY_ID);
        String t = actor.tenantId();
        long used = Jdbc.one(c, "SELECT COUNT(*) FROM SEC_ORG_UNIT WHERE tenant_id=?", rs -> rs.getLong(1), t).orElse(0L);
        if (used > 0) {
            throw new SangamSecurityException.AdminConflict("LEVELS_IN_USE", "level types are fixed once units exist");
        }
        Jdbc.update(c, "DELETE FROM SEC_ORG_LEVEL WHERE tenant_id=?", t);
        for (int i = 0; i < levelTypes.size(); i++) {
            Jdbc.update(c, "INSERT INTO SEC_ORG_LEVEL(tenant_id, level_no, level_type) VALUES (?,?,?)", t, i + 1, levelTypes.get(i));
        }
        audit.security(c, actor, SecurityEventType.TENANT_CONFIGURED, Map.of("org_levels", levelTypes));
    }

    public OrgModel.OrgUnit createUnit(Connection c, SecurityIdentity actor, String unitId, String levelType, String parentId,
                                       String nameEn, String nameHi, Instant validFrom) {
        authz.require(c, actor, ResourceRegistry.ORG_UNIT, Permission.ADMIN, parentId == null ? ResourceRegistry.ANY_ID : parentId);
        String t = actor.tenantId();
        int levelNo = Jdbc.one(c, "SELECT level_no FROM SEC_ORG_LEVEL WHERE tenant_id=? AND level_type=?", rs -> rs.getInt(1), t, levelType)
                .orElseThrow(() -> new SangamSecurityException.AdminConflict("UNKNOWN_LEVEL", "unknown level type " + levelType));
        Instant from = validFrom == null ? Sql.dbNow(c, dialect) : validFrom;
        if (parentId != null) {
            OrgModel.OrgUnit parent = unitAt(c, t, parentId, from).orElseThrow(() ->
                    new SangamSecurityException.AdminConflict("UNKNOWN_PARENT", "parent unit " + parentId + " not found"));
            if (parent.levelNo() >= levelNo) {
                throw new SangamSecurityException.AdminConflict("LEVEL_ORDER", "a child must be below its parent's level");
            }
        }
        if (!Sql.insertIfAbsent(c, dialect, "INSERT INTO SEC_ORG_UNIT(tenant_id, unit_id, level_type, level_no, name_en, name_hi,"
                + " head_seat_id, status, created_at) VALUES (?,?,?,?,?,?,NULL,'ACTIVE',?)", t, unitId, levelType, levelNo,
                nameEn, nameHi, Sql.dbNow(c, dialect))) {
            throw new SangamSecurityException.AdminConflict("UNIT_EXISTS", "unit id " + unitId + " already exists");
        }
        Jdbc.update(c, "INSERT INTO SEC_ORG_UNIT_PARENT(tenant_id, unit_id, parent_id, valid_from, valid_to) VALUES (?,?,?,?,NULL)",
                t, unitId, parentId, from);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("unit_id", unitId);
        p.put("level_type", levelType);
        p.put("parent_id", parentId);
        p.put("valid_from", from);
        audit.security(c, actor, SecurityEventType.ORG_UNIT_CREATED, p);
        return unitAt(c, t, unitId, from).orElseThrow();
    }

    /** Re-organisation: moves {@code unitId} under {@code newParentId} from {@code effectiveFrom} (AJ-02 step 3). */
    public void moveUnit(Connection c, SecurityIdentity actor, String unitId, String newParentId, Instant effectiveFrom) {
        authz.require(c, actor, ResourceRegistry.ORG_UNIT, Permission.ADMIN, unitId);
        String t = actor.tenantId();
        if (newParentId != null && (newParentId.equals(unitId) || ancestorsAt(c, t, newParentId, effectiveFrom).contains(unitId))) {
            throw new SangamSecurityException.AdminConflict("HIERARCHY_CYCLE", "the move would create a cycle");
        }
        Jdbc.update(c, "UPDATE SEC_ORG_UNIT_PARENT SET valid_to=? WHERE tenant_id=? AND unit_id=? AND valid_from < ?"
                + " AND (valid_to IS NULL OR valid_to > ?)", effectiveFrom, t, unitId, effectiveFrom, effectiveFrom);
        Jdbc.update(c, "DELETE FROM SEC_ORG_UNIT_PARENT WHERE tenant_id=? AND unit_id=? AND valid_from >= ?", t, unitId, effectiveFrom);
        Jdbc.update(c, "INSERT INTO SEC_ORG_UNIT_PARENT(tenant_id, unit_id, parent_id, valid_from, valid_to) VALUES (?,?,?,?,NULL)",
                t, unitId, newParentId, effectiveFrom);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("unit_id", unitId);
        p.put("new_parent_id", newParentId);
        p.put("effective_from", effectiveFrom);
        audit.security(c, actor, SecurityEventType.ORG_UNIT_MOVED, p);
    }

    public void setHeadSeat(Connection c, SecurityIdentity actor, String unitId, String seatId) {
        authz.require(c, actor, ResourceRegistry.ORG_UNIT, Permission.ADMIN, unitId);
        Jdbc.update(c, "UPDATE SEC_ORG_UNIT SET head_seat_id=? WHERE tenant_id=? AND unit_id=?", seatId, actor.tenantId(), unitId);
    }

    public Optional<OrgModel.OrgUnit> unitAt(Connection c, String tenant, String unitId, Instant at) {
        return Jdbc.one(c, "SELECT u.unit_id, u.level_type, u.level_no, " + nameAt("en") + ", " + nameAt("hi")
                        + ", u.head_seat_id, u.status,"
                        + " (SELECT p.parent_id FROM SEC_ORG_UNIT_PARENT p WHERE p.tenant_id=u.tenant_id AND p.unit_id=u.unit_id AND "
                        + Sql.validAt("p") + ") AS parent_id FROM SEC_ORG_UNIT u WHERE u.tenant_id=? AND u.unit_id=?",
                rs -> new OrgModel.OrgUnit(tenant, rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(8), rs.getString(7)),
                at, at, at, at, at, at, tenant, unitId);
    }

    /**
     * SQL for the unit name ({@code en} or {@code hi}) effective at a bound instant (two {@code ?}): the latest rename in
     * {@code SEC_ORG_UNIT_NAME} covering it, else the name the unit was created with. Uses alias {@code u}.
     */
    static String nameAt(String lang) {
        return "COALESCE((SELECT n.name_" + lang + " FROM SEC_ORG_UNIT_NAME n WHERE n.tenant_id=u.tenant_id AND n.unit_id=u.unit_id"
                + " AND " + Sql.validAt("n") + "), u.name_" + lang + ") AS name_" + lang;
    }

    /**
     * Renames a unit from {@code effectiveFrom} (effective-dated, audited): earlier dates keep the old name, so records
     * and outputs issued before the order keep showing it. A later pending rename is replaced.
     */
    public void renameUnit(Connection c, SecurityIdentity actor, String unitId, String nameEn, String nameHi,
                           Instant effectiveFrom, String orderRef) {
        authz.require(c, actor, ResourceRegistry.ORG_UNIT, Permission.ADMIN, unitId);
        String t = actor.tenantId();
        if (nameEn == null || nameEn.isBlank()) {
            throw new SangamSecurityException.AdminConflict("NAME_REQUIRED", "a unit needs an English name");
        }
        Instant from = effectiveFrom == null ? Sql.dbNow(c, dialect) : effectiveFrom;
        OrgModel.OrgUnit before = unitAt(c, t, unitId, from).orElseThrow(() ->
                new SangamSecurityException.AdminConflict("UNKNOWN_UNIT", "unit " + unitId + " not found"));
        String hi = nameHi != null ? nameHi : before.nameHi();
        Jdbc.update(c, "UPDATE SEC_ORG_UNIT_NAME SET valid_to=? WHERE tenant_id=? AND unit_id=? AND valid_from < ?"
                + " AND (valid_to IS NULL OR valid_to > ?)", from, t, unitId, from, from);
        Jdbc.update(c, "DELETE FROM SEC_ORG_UNIT_NAME WHERE tenant_id=? AND unit_id=? AND valid_from >= ?", t, unitId, from);
        Jdbc.update(c, "INSERT INTO SEC_ORG_UNIT_NAME(tenant_id, unit_id, valid_from, valid_to, name_en, name_hi, order_ref,"
                + " created_by, created_at) VALUES (?,?,?,NULL,?,?,?,?,?)", t, unitId, from, nameEn, hi, orderRef,
                actor.personId(), Sql.dbNow(c, dialect));
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("unit_id", unitId);
        p.put("old_name_en", before.nameEn());
        p.put("old_name_hi", before.nameHi());
        p.put("name_en", nameEn);
        p.put("name_hi", hi);
        p.put("effective_from", from);
        p.put("order_ref", orderRef);
        audit.security(c, actor, SecurityEventType.ORG_UNIT_RENAMED, p);
    }

    /** Name history of a unit, oldest first: {@code [validFrom, validTo, nameEn, nameHi, orderRef]}. */
    public List<Object[]> nameHistory(Connection c, String tenant, String unitId) {
        return Jdbc.query(c, "SELECT valid_from, valid_to, name_en, name_hi, order_ref FROM SEC_ORG_UNIT_NAME"
                        + " WHERE tenant_id=? AND unit_id=? ORDER BY valid_from",
                rs -> new Object[]{Jdbc.instant(rs, "valid_from"), Jdbc.instant(rs, "valid_to"), rs.getString(3),
                        rs.getString(4), rs.getString(5)}, tenant, unitId);
    }

    public Optional<String> parentAt(Connection c, String tenant, String unitId, Instant at) {
        // parent_id IS NOT NULL: Jdbc.one cannot carry a null row value (a root unit has no parent)
        return Jdbc.one(c, "SELECT parent_id FROM SEC_ORG_UNIT_PARENT WHERE tenant_id=? AND unit_id=? AND parent_id IS NOT NULL AND "
                + Sql.validAt(null), rs -> rs.getString(1), tenant, unitId, at, at);
    }

    /** Ancestors of a unit at {@code at}, nearest first (cycle-safe). */
    public List<String> ancestorsAt(Connection c, String tenant, String unitId, Instant at) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        seen.add(unitId);
        Optional<String> p = parentAt(c, tenant, unitId, at);
        while (p.isPresent() && seen.add(p.get())) {
            out.add(p.get());
            p = parentAt(c, tenant, p.get(), at);
        }
        return out;
    }

    public boolean isAncestor(Connection c, String tenant, String ancestorId, String unitId, Instant at) {
        return ancestorsAt(c, tenant, unitId, at).contains(ancestorId);
    }

    /** The unit itself or its nearest ancestor of the given level type (routing by level, IX.5 rule 2). */
    public Optional<String> ancestorAtLevel(Connection c, String tenant, String unitId, String levelType, Instant at) {
        List<String> chain = new ArrayList<>();
        chain.add(unitId);
        chain.addAll(ancestorsAt(c, tenant, unitId, at));
        for (String u : chain) {
            Optional<OrgModel.OrgUnit> unit = unitAt(c, tenant, u, at);
            if (unit.isPresent() && unit.get().levelType().equals(levelType)) {
                return Optional.of(u);
            }
        }
        return Optional.empty();
    }

    public List<String> childrenAt(Connection c, String tenant, String unitId, Instant at) {
        return Jdbc.query(c, "SELECT unit_id FROM SEC_ORG_UNIT_PARENT WHERE tenant_id=? AND parent_id=? AND " + Sql.validAt(null)
                + " ORDER BY unit_id", rs -> rs.getString(1), tenant, unitId, at, at);
    }
}
