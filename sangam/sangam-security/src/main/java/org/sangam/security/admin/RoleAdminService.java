// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.admin;

import org.sangam.core.layer.Jdbc;
import org.sangam.core.security.SangamSecurityException;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.security.audit.SecurityAuditService;
import org.sangam.security.audit.SecurityEventType;
import org.sangam.security.dialect.SecurityDialect;
import org.sangam.security.policy.AuthEffectiveMaintainer;
import org.sangam.security.policy.AuthorizationService;
import org.sangam.security.policy.MembershipIndex;
import org.sangam.security.policy.Permission;
import org.sangam.security.policy.PolicyCompiler;
import org.sangam.security.policy.ResourceRegistry;
import org.sangam.security.support.Sql;

import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Roles: named permission sets (A08 "roles → permissions", B-11, EC-20). A role's rules are materialised as the grant
 * source {@code role:<id>} with principal {@code role:<id>}; a binding makes a user, group or seat a member of the role
 * for a date range (effective-dated, via {@code AUTH_MEMBERSHIP}). Roles may also arrive from the IdP (Keycloak realm or
 * client roles); both kinds are the same principal id. Deny rules are allowed in roles (deny wins, A-3).
 */
public final class RoleAdminService {

    public record RoleRule(String resourceType, String resourceId, Set<Permission> permissions, boolean deny) {
    }

    public record RoleBinding(String bindingId, String roleId, String memberId, Instant validFrom, Instant validTo) {
    }

    /** Bootstrap administrator of a new tenant (AJ-01 step 2). */
    public record AdminSeed(String personId, String externalSubject, String displayName) {
    }

    private final SecurityDialect dialect;
    private final AuthorizationService authz;
    private final AuthEffectiveMaintainer maintainer;
    private final MembershipIndex memberships;
    private final SecurityAuditService audit;
    private final PolicyCompiler compiler;

    public RoleAdminService(SecurityDialect dialect, AuthorizationService authz, AuthEffectiveMaintainer maintainer,
                            MembershipIndex memberships, SecurityAuditService audit) {
        this.dialect = dialect;
        this.authz = authz;
        this.maintainer = maintainer;
        this.memberships = memberships;
        this.audit = audit;
        this.compiler = authz.compiler();
    }

    public void defineRole(Connection c, SecurityIdentity actor, String roleId, String name, List<RoleRule> rules) {
        authz.require(c, actor, ResourceRegistry.ROLE, Permission.CHANGE_GRANT, roleId);
        String t = actor.tenantId();
        for (RoleRule r : rules) {
            compiler.validate(r.resourceType(), Permission.mask(r.permissions()), null);
        }
        Jdbc.update(c, "DELETE FROM SEC_ROLE WHERE tenant_id=? AND role_id=?", t, roleId);
        Jdbc.update(c, "INSERT INTO SEC_ROLE(tenant_id, role_id, name, rules, updated_by, updated_at) VALUES (?,?,?,?,?,?)",
                t, roleId, name, Sql.toJson(rules.stream().map(RoleAdminService::ruleMap).toList()), actor.personId(),
                Sql.dbNow(c, dialect));
        List<AuthEffectiveMaintainer.GrantRow> rows = new ArrayList<>();
        for (RoleRule r : rules) {
            rows.add(new AuthEffectiveMaintainer.GrantRow(SecurityIdentity.ROLE + roleId, r.resourceType(), r.resourceId(),
                    r.deny(), Permission.mask(r.permissions()), null));
        }
        long v = maintainer.replaceSource(c, t, "role:" + roleId, rows);
        compiler.invalidate(t);
        audit.security(c, actor, SecurityEventType.GRANT_CHANGED, Map.of("role_id", roleId, "rules",
                rules.stream().map(RoleAdminService::ruleMap).toList(), "new_policy_version", v));
    }

    public void deleteRole(Connection c, SecurityIdentity actor, String roleId) {
        authz.require(c, actor, ResourceRegistry.ROLE, Permission.CHANGE_GRANT, roleId);
        String t = actor.tenantId();
        long v = maintainer.removeSource(c, t, "role:" + roleId);
        for (String b : Jdbc.query(c, "SELECT binding_id FROM SEC_ROLE_BINDING WHERE tenant_id=? AND role_id=?",
                rs -> rs.getString(1), t, roleId)) {
            memberships.removeSource(c, t, "rolebinding:" + b);
        }
        Jdbc.update(c, "DELETE FROM SEC_ROLE_BINDING WHERE tenant_id=? AND role_id=?", t, roleId);
        Jdbc.update(c, "DELETE FROM SEC_ROLE WHERE tenant_id=? AND role_id=?", t, roleId);
        audit.security(c, actor, SecurityEventType.GRANT_CHANGED, Map.of("role_id", roleId, "deleted", true, "new_policy_version", v));
    }

    /** Binds a member ({@code user:}, {@code group:} or {@code seat:}) to a role for a date range. */
    public RoleBinding bind(Connection c, SecurityIdentity actor, String roleId, String memberId, Instant from, Instant to) {
        authz.require(c, actor, ResourceRegistry.ROLE, Permission.CHANGE_GRANT, roleId);
        if (!(memberId.startsWith(SecurityIdentity.USER) || memberId.startsWith(SecurityIdentity.GROUP)
                || memberId.startsWith(SecurityIdentity.SEAT))) {
            throw new SangamSecurityException.PolicyRejected("a role member must be user:, group: or seat: prefixed");
        }
        String t = actor.tenantId();
        long exists = Jdbc.one(c, "SELECT COUNT(*) FROM SEC_ROLE WHERE tenant_id=? AND role_id=?", rs -> rs.getLong(1), t, roleId).orElse(0L);
        if (exists == 0) {
            throw new SangamSecurityException.AdminConflict("UNKNOWN_ROLE", "role " + roleId + " not found");
        }
        Instant start = from == null ? Sql.dbNow(c, dialect) : from;
        String id = Sql.newId();
        Jdbc.update(c, "INSERT INTO SEC_ROLE_BINDING(tenant_id, binding_id, role_id, member_id, valid_from, valid_to, created_by)"
                + " VALUES (?,?,?,?,?,?,?)", t, id, roleId, memberId, start, to, actor.personId());
        long v = memberships.put(c, t, memberId, SecurityIdentity.ROLE + roleId, "rolebinding:" + id, start, to);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("binding_id", id);
        p.put("role_id", roleId);
        p.put("member_id", memberId);
        p.put("valid_from", start);
        p.put("valid_to", to);
        p.put("new_policy_version", v);
        audit.security(c, actor, SecurityEventType.MEMBERSHIP_CHANGED, p);
        return new RoleBinding(id, roleId, memberId, start, to);
    }

    public void unbind(Connection c, SecurityIdentity actor, String bindingId) {
        String t = actor.tenantId();
        String role = Jdbc.one(c, "SELECT role_id FROM SEC_ROLE_BINDING WHERE tenant_id=? AND binding_id=?", rs -> rs.getString(1), t, bindingId)
                .orElseThrow(() -> new SangamSecurityException.AdminConflict("UNKNOWN_BINDING", "binding not found"));
        authz.require(c, actor, ResourceRegistry.ROLE, Permission.CHANGE_GRANT, role);
        Jdbc.update(c, "DELETE FROM SEC_ROLE_BINDING WHERE tenant_id=? AND binding_id=?", t, bindingId);
        long v = memberships.removeSource(c, t, "rolebinding:" + bindingId);
        audit.security(c, actor, SecurityEventType.MEMBERSHIP_CHANGED, Map.of("binding_id", bindingId, "role_id", role,
                "removed", true, "new_policy_version", v));
    }

    public List<RoleBinding> bindings(Connection c, SecurityIdentity actor, String roleId) {
        authz.require(c, actor, ResourceRegistry.ROLE, Permission.READ, roleId);
        return Jdbc.query(c, "SELECT binding_id, role_id, member_id, valid_from, valid_to FROM SEC_ROLE_BINDING WHERE tenant_id=?"
                        + " AND role_id=? ORDER BY valid_from, binding_id",
                rs -> new RoleBinding(rs.getString(1), rs.getString(2), rs.getString(3), Jdbc.instant(rs, "valid_from"),
                        Jdbc.instant(rs, "valid_to")), actor.tenantId(), roleId);
    }

    /**
     * Bootstraps a new tenant (AJ-01 step 2): creates the {@code tenant-admin} role and appoints <b>two</b>
     * administrators (two-person rule). Runs as the tenant's system principal on behalf of the platform admin.
     */
    public void bootstrapTenantAdmins(Connection c, SecurityIdentity platformAdmin, String tenantId, List<AdminSeed> admins) {
        TenantAdminService.requirePlatformAdmin(platformAdmin);
        if (admins.size() < 2 || admins.stream().map(AdminSeed::personId).distinct().count() < 2) {
            throw new SangamSecurityException.AdminConflict("TWO_ADMINS_REQUIRED", "appoint two different department administrators");
        }
        SecurityIdentity system = SecurityIdentity.system(tenantId, platformAdmin.personId());
        Instant now = Sql.dbNow(c, dialect);
        for (AdminSeed a : admins) {
            Sql.insertIfAbsent(c, dialect, "INSERT INTO SEC_PERSON(tenant_id, person_id, display_name, external_subject, email,"
                    + " status, created_at) VALUES (?,?,?,?,NULL,'ACTIVE',?)", tenantId, a.personId(), a.displayName(),
                    a.externalSubject(), now);
        }
        defineRole(c, system, TenantAdminService.TENANT_ADMIN_ROLE, "Department administrator", List.of(
                new RoleRule(ResourceRegistry.ANY_TYPE, ResourceRegistry.ANY_ID, EnumSet.of(Permission.READ, Permission.ADMIN,
                        Permission.CHANGE_GRANT, Permission.READ_AUDIT, Permission.REVIEW_PACKAGE), false)));
        for (AdminSeed a : admins) {
            bind(c, system, TenantAdminService.TENANT_ADMIN_ROLE, SecurityIdentity.USER + a.personId(), now, null);
        }
    }

    private static Map<String, Object> ruleMap(RoleRule r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("resourceType", r.resourceType());
        m.put("resourceId", r.resourceId());
        m.put("permissions", r.permissions().stream().map(Enum::name).sorted().toList());
        m.put("deny", r.deny());
        return m;
    }
}
