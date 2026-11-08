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
import org.sangam.security.policy.Condition;
import org.sangam.security.policy.Permission;
import org.sangam.security.policy.PolicyCompiler;
import org.sangam.security.policy.ResourceRegistry;
import org.sangam.security.support.Sql;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Admin service for policies (A08, R19/VI.4, A-3, B-11, EC-20). A policy names subjects (prefixed principal ids),
 * one resource type and id (or {@code '*'} / a scope such as {@code procinst:<id>}), an effect (ALLOW or DENY), the
 * permissions and an optional condition.
 *
 * <ul>
 *   <li>Deployment validation (VI.4 rule 1): a list-visible conditional policy that cannot compile to SQL is
 *       rejected.</li>
 *   <li>Grant changes are sensitive (K3): the caller's {@code CHANGE_GRANT} is re-checked in the transaction.</li>
 *   <li>Four-eyes (AJ-01 step 2): when the tenant enables it, a change is stored as {@code PENDING} and applied only
 *       when a different administrator approves it.</li>
 *   <li>Applying a policy rewrites only its own grant source in {@code AUTH_EFFECTIVE} (incremental) and bumps
 *       {@code policy_version}; {@code GRANT_CHANGED}/{@code DENY_CHANGED} is written to the security chain.</li>
 * </ul>
 */
public final class PolicyAdminService {

    public enum Effect { ALLOW, DENY }

    public record PolicyDefinition(String policyId, String name, Effect effect, List<String> subjects, String resourceType,
                                   String resourceId, Set<Permission> permissions, Condition condition) {
        public PolicyDefinition {
            subjects = List.copyOf(subjects);
            permissions = permissions.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(permissions));
        }
    }

    public record PolicyChange(String changeId, String policyId, String operation, String status, String requestedBy) {
    }

    private static final List<String> PREFIXES = List.of(SecurityIdentity.USER, SecurityIdentity.GROUP, SecurityIdentity.ROLE,
            SecurityIdentity.SEAT);

    private final SecurityDialect dialect;
    private final AuthorizationService authz;
    private final PolicyCompiler compiler;
    private final AuthEffectiveMaintainer maintainer;
    private final SecurityAuditService audit;
    private final TenantAdminService tenants;

    public PolicyAdminService(SecurityDialect dialect, AuthorizationService authz, AuthEffectiveMaintainer maintainer,
                              SecurityAuditService audit, TenantAdminService tenants) {
        this.dialect = dialect;
        this.authz = authz;
        this.compiler = authz.compiler();
        this.maintainer = maintainer;
        this.audit = audit;
        this.tenants = tenants;
    }

    public PolicyChange create(Connection c, SecurityIdentity actor, PolicyDefinition def, String reason) {
        validate(def);
        if (find(c, actor.tenantId(), def.policyId()).isPresent()) {
            throw new SangamSecurityException.AdminConflict("POLICY_EXISTS", "policy " + def.policyId() + " exists");
        }
        return change(c, actor, def.policyId(), "CREATE", def, reason);
    }

    public PolicyChange update(Connection c, SecurityIdentity actor, PolicyDefinition def, String reason) {
        validate(def);
        find(c, actor.tenantId(), def.policyId()).orElseThrow(() -> unknown(def.policyId()));
        return change(c, actor, def.policyId(), "UPDATE", def, reason);
    }

    public PolicyChange delete(Connection c, SecurityIdentity actor, String policyId, String reason) {
        PolicyDefinition def = find(c, actor.tenantId(), policyId).orElseThrow(() -> unknown(policyId));
        return change(c, actor, policyId, "DELETE", def, reason);
    }

    /** Second administrator approves a pending change (four-eyes). */
    public void approve(Connection c, SecurityIdentity approver, String changeId) {
        authz.require(c, approver, ResourceRegistry.POLICY, Permission.CHANGE_GRANT, ResourceRegistry.ANY_ID);
        String t = approver.tenantId();
        String[] row = Jdbc.one(c, "SELECT policy_id, operation, definition, requested_by, status FROM SEC_POLICY_CHANGE"
                        + " WHERE tenant_id=? AND change_id=?",
                rs -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)}, t, changeId)
                .orElseThrow(() -> new SangamSecurityException.AdminConflict("UNKNOWN_CHANGE", "change not found"));
        if (!"PENDING".equals(row[4])) {
            throw new SangamSecurityException.AdminConflict("CHANGE_NOT_PENDING", "change is " + row[4]);
        }
        if (approver.personId().equals(row[3])) {
            throw new SangamSecurityException.SodViolation("the requester cannot approve their own policy change");
        }
        Jdbc.update(c, "UPDATE SEC_POLICY_CHANGE SET status='APPLIED', decided_by=?, decided_at=? WHERE tenant_id=? AND change_id=?",
                approver.personId(), Sql.dbNow(c, dialect), t, changeId);
        apply(c, approver, row[0], row[1], fromJson(row[0], row[2]));
    }

    public void reject(Connection c, SecurityIdentity approver, String changeId, String reason) {
        authz.require(c, approver, ResourceRegistry.POLICY, Permission.CHANGE_GRANT, ResourceRegistry.ANY_ID);
        int n = Jdbc.update(c, "UPDATE SEC_POLICY_CHANGE SET status='REJECTED', decided_by=?, decided_at=?, reason=?"
                        + " WHERE tenant_id=? AND change_id=? AND status='PENDING'",
                approver.personId(), Sql.dbNow(c, dialect), reason, approver.tenantId(), changeId);
        if (n != 1) {
            throw new SangamSecurityException.AdminConflict("CHANGE_NOT_PENDING", "no pending change " + changeId);
        }
    }

    public Optional<PolicyDefinition> find(Connection c, String tenant, String policyId) {
        return Jdbc.one(c, "SELECT definition FROM SEC_POLICY WHERE tenant_id=? AND policy_id=? AND status='ACTIVE'",
                rs -> fromJson(policyId, rs.getString(1)), tenant, policyId);
    }

    public List<PolicyDefinition> list(Connection c, SecurityIdentity actor, int offset, int limit) {
        authz.require(c, actor, ResourceRegistry.POLICY, Permission.READ, ResourceRegistry.ANY_ID);
        return Jdbc.query(c, dialect.page("SELECT policy_id, definition FROM SEC_POLICY WHERE tenant_id=? AND status='ACTIVE'"
                + " ORDER BY policy_id", offset, Math.min(limit, 1000)), rs -> fromJson(rs.getString(1), rs.getString(2)), actor.tenantId());
    }

    public List<PolicyChange> pending(Connection c, SecurityIdentity actor) {
        authz.require(c, actor, ResourceRegistry.POLICY, Permission.READ, ResourceRegistry.ANY_ID);
        return Jdbc.query(c, "SELECT change_id, policy_id, operation, status, requested_by FROM SEC_POLICY_CHANGE WHERE tenant_id=?"
                        + " AND status='PENDING' ORDER BY requested_at",
                rs -> new PolicyChange(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)),
                actor.tenantId());
    }

    // ------------------------------------------------------------------ internals

    private PolicyChange change(Connection c, SecurityIdentity actor, String policyId, String op, PolicyDefinition def,
                                String reason) {
        authz.require(c, actor, ResourceRegistry.POLICY, Permission.CHANGE_GRANT, policyId);
        String t = actor.tenantId();
        boolean fourEyes = tenants.fourEyes(c, t);
        String id = Sql.newId();
        Jdbc.update(c, "INSERT INTO SEC_POLICY_CHANGE(tenant_id, change_id, policy_id, operation, definition, requested_by,"
                        + " requested_at, status, decided_by, decided_at, reason) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                t, id, policyId, op, toJson(def), actor.personId(), Sql.dbNow(c, dialect), fourEyes ? "PENDING" : "APPLIED",
                fourEyes ? null : actor.personId(), fourEyes ? null : Sql.dbNow(c, dialect), reason);
        if (fourEyes) {
            audit.security(c, actor, SecurityEventType.POLICY_CHANGE_PENDING, Map.of("change_id", id, "policy_id", policyId,
                    "operation", op, "reason", reason == null ? "" : reason));
            return new PolicyChange(id, policyId, op, "PENDING", actor.personId());
        }
        apply(c, actor, policyId, op, def);
        return new PolicyChange(id, policyId, op, "APPLIED", actor.personId());
    }

    private void apply(Connection c, SecurityIdentity actor, String policyId, String op, PolicyDefinition def) {
        String t = actor.tenantId();
        String source = "policy:" + policyId;
        String condId = "pol:" + policyId;
        long version;
        if ("DELETE".equals(op)) {
            version = maintainer.removeSource(c, t, source);
            maintainer.deleteCondition(c, t, condId);
            Jdbc.update(c, "UPDATE SEC_POLICY SET status='RETIRED', updated_at=? WHERE tenant_id=? AND policy_id=?",
                    Sql.dbNow(c, dialect), t, policyId);
        } else {
            if (def.condition() != null) {
                maintainer.putCondition(c, t, condId, def.resourceType(), def.condition());
            } else {
                maintainer.deleteCondition(c, t, condId);
            }
            long mask = Permission.mask(def.permissions());
            List<AuthEffectiveMaintainer.GrantRow> rows = new ArrayList<>();
            for (String s : def.subjects()) {
                rows.add(new AuthEffectiveMaintainer.GrantRow(s, def.resourceType(), def.resourceId(), def.effect() == Effect.DENY,
                        mask, def.condition() == null ? null : condId));
            }
            version = maintainer.replaceSource(c, t, source, rows);
            Jdbc.update(c, "DELETE FROM SEC_POLICY WHERE tenant_id=? AND policy_id=?", t, policyId);
            Jdbc.update(c, "INSERT INTO SEC_POLICY(tenant_id, policy_id, name, effect, resource_type, resource_id, definition,"
                            + " status, version, updated_by, updated_at) VALUES (?,?,?,?,?,?,?,'ACTIVE',?,?,?)",
                    t, policyId, def.name(), def.effect().name(), def.resourceType(), def.resourceId(), toJson(def), version,
                    actor.personId(), Sql.dbNow(c, dialect));
        }
        compiler.invalidate(t);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("policy_id", policyId);
        p.put("operation", op);
        p.put("effect", def.effect().name());
        p.put("subjects", def.subjects());
        p.put("resource_type", def.resourceType());
        p.put("resource_id", def.resourceId());
        p.put("permissions", def.permissions().stream().map(Enum::name).sorted().toList());
        p.put("new_policy_version", version);
        audit.security(c, actor, def.effect() == Effect.DENY ? SecurityEventType.DENY_CHANGED : SecurityEventType.GRANT_CHANGED, p);
    }

    private void validate(PolicyDefinition def) {
        if (def.policyId() == null || def.policyId().isBlank() || def.subjects().isEmpty() || def.permissions().isEmpty()) {
            throw new SangamSecurityException.PolicyRejected("a policy needs an id, subjects and permissions");
        }
        for (String s : def.subjects()) {
            if (PREFIXES.stream().noneMatch(s::startsWith) || s.indexOf(':') == s.length() - 1) {
                throw new SangamSecurityException.PolicyRejected("subject '" + s + "' must be user:, group:, role: or seat: prefixed");
            }
        }
        if (def.resourceId() == null || def.resourceId().isBlank()) {
            throw new SangamSecurityException.PolicyRejected("a policy needs a resource id or '*'");
        }
        compiler.validate(def.resourceType(), Permission.mask(def.permissions()), def.condition());
    }

    static String toJson(PolicyDefinition d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", d.name());
        m.put("effect", d.effect().name());
        m.put("subjects", d.subjects());
        m.put("resourceType", d.resourceType());
        m.put("resourceId", d.resourceId());
        m.put("permissions", d.permissions().stream().map(Enum::name).sorted().toList());
        if (d.condition() != null) {
            m.put("condition", Condition.toMap(d.condition()));
        }
        return Sql.toJson(m);
    }

    @SuppressWarnings("unchecked")
    static PolicyDefinition fromJson(String policyId, String json) {
        Map<String, Object> m = Sql.jsonMap(json);
        Set<Permission> perms = EnumSet.noneOf(Permission.class);
        ((List<Object>) m.get("permissions")).forEach(p -> perms.add(Permission.parse(p.toString())));
        Condition cond = m.get("condition") == null ? null : Condition.fromMap((Map<String, Object>) m.get("condition"));
        return new PolicyDefinition(policyId, (String) m.get("name"), Effect.valueOf((String) m.get("effect")),
                ((List<Object>) m.get("subjects")).stream().map(Object::toString).toList(), (String) m.get("resourceType"),
                (String) m.get("resourceId"), perms, cond);
    }

    private static SangamSecurityException unknown(String id) {
        return new SangamSecurityException.AdminConflict("UNKNOWN_POLICY", "policy " + id + " not found");
    }
}
