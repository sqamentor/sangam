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
 * Admin service for policies (A08, R19/VI.4, A-3, B-11, EC-20). A policy names exactly one subject (a prefixed
 * principal id; decision 392: one policy id per principal, older multi-principal policies are split at start by
 * {@link #splitMultiPrincipalOfEveryTenant}),
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

    /**
     * Second administrator approves a pending change (four-eyes), without a decision reason. Used by start-up steps
     * (the dev bootstrap approves as the system principal); the API decides through {@link #decide}, which refuses a
     * system identity.
     */
    public void approve(Connection c, SecurityIdentity approver, String changeId) {
        authz.require(c, approver, ResourceRegistry.POLICY, Permission.CHANGE_GRANT, ResourceRegistry.ANY_ID);
        String t = approver.tenantId();
        String[] row = pendingRow(c, t, changeId);
        if (approver.personId().equals(row[3])) {
            throw new SangamSecurityException.SodViolation("the requester cannot approve their own policy change");
        }
        mark(c, approver, changeId, true, null);
        apply(c, approver, row[0], row[1], fromJson(row[0], row[2]), decision(changeId, row[3], approver, null));
    }

    /** Rejects a pending change; the same rules as {@link #decide} with {@code approve=false}. */
    public void reject(Connection c, SecurityIdentity approver, String changeId, String reason) {
        decide(c, approver, changeId, false, reason);
    }

    /**
     * The four-eyes decision of the API (E-33 follow-up 3, IFMS EV-M-0968): approve applies the change in the caller's
     * transaction (GRANT_CHANGED / DENY_CHANGED with change_id, requested_by, decided_by and reason); reject applies
     * nothing (POLICY_CHANGE_PENDING, operation REJECT). The decision reason is stored in {@code decision_reason}; the
     * request reason is never overwritten.
     *
     * <ul>
     *   <li>A system identity never decides: SOD_VIOLATION, audited SOD_VIOLATION_BLOCKED.</li>
     *   <li>The decider needs {@code CHANGE_GRANT} on policies.</li>
     *   <li>{@code UNKNOWN_CHANGE} for no change of the decider's tenant; {@code CHANGE_NOT_PENDING} once decided.</li>
     *   <li>The requester decides neither way (no self-approve, no self-reject, no withdraw): SOD_VIOLATION, audited
     *       SOD_VIOLATION_BLOCKED (a leaf transaction, so the event survives the rollback of the refused request).</li>
     * </ul>
     */
    public void decide(Connection c, SecurityIdentity decider, String changeId, boolean approve, String reason) {
        String verb = approve ? "approve" : "reject";
        if (decider.system()) {
            refuse(c, decider, changeId, approve, null, "a system identity never decides a policy change; a person who"
                    + " holds the permission does");
        }
        authz.require(c, decider, ResourceRegistry.POLICY, Permission.CHANGE_GRANT, ResourceRegistry.ANY_ID);
        String[] row = pendingRow(c, decider.tenantId(), changeId);
        if (decider.personId().equals(row[3])) {
            refuse(c, decider, changeId, approve, row[3], "the requester cannot " + verb + " their own policy change");
        }
        mark(c, decider, changeId, approve, reason);
        Map<String, Object> about = decision(changeId, row[3], decider, reason);
        if (approve) {
            apply(c, decider, row[0], row[1], fromJson(row[0], row[2]), about);
            return;
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("change_id", changeId);
        p.put("policy_id", row[0]);
        p.put("operation", "REJECT");
        p.put("change_operation", row[1]);
        p.putAll(about);
        audit.security(c, decider, SecurityEventType.POLICY_CHANGE_PENDING, p);
    }

    /** policy_id, operation, definition, requested_by of a PENDING change of the tenant. */
    private static String[] pendingRow(Connection c, String tenant, String changeId) {
        String[] row = Jdbc.one(c, "SELECT policy_id, operation, definition, requested_by, status FROM SEC_POLICY_CHANGE"
                        + " WHERE tenant_id=? AND change_id=?",
                rs -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)},
                tenant, changeId)
                .orElseThrow(() -> new SangamSecurityException.AdminConflict("UNKNOWN_CHANGE", "change not found"));
        if (!"PENDING".equals(row[4])) {
            throw new SangamSecurityException.AdminConflict("CHANGE_NOT_PENDING", "change is " + row[4]);
        }
        return row;
    }

    /** Marks a PENDING change decided; a concurrent decision leaves nothing to mark (CHANGE_NOT_PENDING). */
    private void mark(Connection c, SecurityIdentity decider, String changeId, boolean approve, String reason) {
        int n = Jdbc.update(c, "UPDATE SEC_POLICY_CHANGE SET status=?, decided_by=?, decided_at=?, decision_reason=?"
                        + " WHERE tenant_id=? AND change_id=? AND status='PENDING'", approve ? "APPLIED" : "REJECTED",
                decider.personId(), Sql.dbNow(c, dialect), reason, decider.tenantId(), changeId);
        if (n != 1) {
            throw new SangamSecurityException.AdminConflict("CHANGE_NOT_PENDING", "no pending change " + changeId);
        }
    }

    private static Map<String, Object> decision(String changeId, String requestedBy, SecurityIdentity decider, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("change_id", changeId);
        m.put("requested_by", requestedBy);
        m.put("decided_by", decider.personId());
        m.put("reason", reason == null ? "" : reason);
        return m;
    }

    private void refuse(Connection c, SecurityIdentity decider, String changeId, boolean approve, String requestedBy,
                        String why) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("code", "SOD_VIOLATION");
        p.put("change_id", changeId);
        p.put("decision", approve ? "APPROVE" : "REJECT");
        p.put("decided_by", decider.personId());
        if (requestedBy != null) {
            p.put("requested_by", requestedBy);
        }
        p.put("system", decider.system());
        p.put("reason", why);
        audit.denial(c, decider, SecurityEventType.SOD_VIOLATION_BLOCKED, null, p);
        throw new SangamSecurityException.SodViolation(why);
    }

    public Optional<PolicyDefinition> find(Connection c, String tenant, String policyId) {
        return Jdbc.one(c, "SELECT definition FROM SEC_POLICY WHERE tenant_id=? AND policy_id=? AND status='ACTIVE'",
                rs -> fromJson(policyId, rs.getString(1)), tenant, policyId);
    }

    /** Whether an ACTIVE policy of the tenant names {@code subject} on exactly this resource type and id. */
    public boolean hasPolicyFor(Connection c, String tenant, String subject, String resourceType, String resourceId) {
        return Jdbc.query(c, "SELECT policy_id, definition FROM SEC_POLICY WHERE tenant_id=? AND status='ACTIVE'"
                        + " AND resource_type=? AND resource_id=?", rs -> fromJson(rs.getString(1), rs.getString(2)),
                tenant, resourceType, resourceId).stream().anyMatch(d -> d.subjects().contains(subject));
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
        apply(c, actor, policyId, op, def, Map.of());
        return new PolicyChange(id, policyId, op, "APPLIED", actor.personId());
    }

    /** {@code about}: the four-eyes decision (change_id, requested_by, decided_by, reason) when a change is approved. */
    private void apply(Connection c, SecurityIdentity actor, String policyId, String op, PolicyDefinition def,
                       Map<String, Object> about) {
        String t = actor.tenantId();
        long version;
        if ("DELETE".equals(op)) {
            version = maintainer.removeSource(c, t, "policy:" + policyId);
            maintainer.deleteCondition(c, t, "pol:" + policyId);
            Jdbc.update(c, "UPDATE SEC_POLICY SET status='RETIRED', updated_at=? WHERE tenant_id=? AND policy_id=?",
                    Sql.dbNow(c, dialect), t, policyId);
        } else {
            if (def.subjects().size() != 1) {
                // decision 392: a change stored before the split rule (four-eyes PENDING) is never applied as several
                // principals under one id; the start-time split marks such changes SUPERSEDED
                throw new SangamSecurityException.PolicyRejected(ONE_PRINCIPAL);
            }
            version = store(c, actor, policyId, def);
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
        p.putAll(about);
        audit.security(c, actor, def.effect() == Effect.DENY ? SecurityEventType.DENY_CHANGED : SecurityEventType.GRANT_CHANGED, p);
    }

    /** Writes the grant source, condition and SEC_POLICY row of a one-principal definition; returns the policy version. */
    private long store(Connection c, SecurityIdentity actor, String policyId, PolicyDefinition def) {
        String t = actor.tenantId();
        String condId = "pol:" + policyId;
        if (def.condition() != null) {
            maintainer.putCondition(c, t, condId, def.resourceType(), def.condition());
        } else {
            maintainer.deleteCondition(c, t, condId);
        }
        List<AuthEffectiveMaintainer.GrantRow> rows = List.of(new AuthEffectiveMaintainer.GrantRow(def.subjects().get(0),
                def.resourceType(), def.resourceId(), def.effect() == Effect.DENY, Permission.mask(def.permissions()),
                def.condition() == null ? null : condId));
        long version = maintainer.replaceSource(c, t, "policy:" + policyId, rows);
        Jdbc.update(c, "DELETE FROM SEC_POLICY WHERE tenant_id=? AND policy_id=?", t, policyId);
        Jdbc.update(c, "INSERT INTO SEC_POLICY(tenant_id, policy_id, name, effect, resource_type, resource_id, definition,"
                        + " status, version, updated_by, updated_at) VALUES (?,?,?,?,?,?,?,'ACTIVE',?,?,?)",
                t, policyId, def.name(), def.effect().name(), def.resourceType(), def.resourceId(), toJson(def), version,
                actor.personId(), Sql.dbNow(c, dialect));
        return version;
    }

    // ------------------------------------------------------------------ decision 392: split of multi-principal policies

    static final String ONE_PRINCIPAL = "a policy names exactly one principal (decision 392); create one policy per principal";

    /** Reason stored on a pending change that the split made obsolete. */
    public static final String SUPERSEDED_REASON = "decision 392: superseded by the one-principal policy split; resubmit"
            + " one change per principal";

    /** Result of one tenant's split: original policy id to the ids of its principals, and the superseded change ids. */
    public record SplitResult(Map<String, List<String>> splitPolicies, List<String> supersededChanges) {
        public SplitResult {
            splitPolicies = Map.copyOf(splitPolicies);
            supersededChanges = List.copyOf(supersededChanges);
        }

        public boolean isEmpty() {
            return splitPolicies.isEmpty() && supersededChanges.isEmpty();
        }
    }

    /**
     * Decision 392 data migration for every tenant, one transaction per tenant (decision 409: Java start-time step on
     * the decision 370 runner precedent). Any failure propagates, so start-up stops and the failing tenant's
     * transaction rolls back whole. Returns the tenants that had something to split.
     */
    public Map<String, SplitResult> splitMultiPrincipalOfEveryTenant(org.sangam.core.layer.Transactions tx, String actorId) {
        List<String> tenants = tx.inTransaction(c -> Jdbc.query(c, "SELECT tenant_id FROM SEC_TENANT ORDER BY tenant_id",
                rs -> rs.getString(1)));
        Map<String, SplitResult> out = new LinkedHashMap<>();
        for (String tenant : tenants) {
            SplitResult r = splitWithLockRetry(tx, SecurityIdentity.system(tenant, actorId));
            if (!r.isEmpty()) {
                out.put(tenant, r);
            }
        }
        return out;
    }

    private SplitResult splitWithLockRetry(org.sangam.core.layer.Transactions tx, SecurityIdentity system) {
        for (int attempt = 1; ; attempt++) {
            try {
                return tx.inTransaction(c -> splitMultiPrincipal(c, system));
            } catch (RuntimeException e) {
                // Oracle's FOR UPDATE WAIT 2 gives up while another node holds the tenant lock: wait again (the
                // other node's split then leaves nothing to do). Any other failure stops the start (fail closed).
                if (attempt >= 60 || !lockWaitTimedOut(e)) {
                    throw e;
                }
            }
        }
    }

    private static boolean lockWaitTimedOut(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLException s && (s.getErrorCode() == 30006 || s.getErrorCode() == 54)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Splits, in the caller's transaction, every ACTIVE policy of the actor's tenant that names several principals:
     * the first principal keeps the policy id, each other principal gets a new id with the same name, effect,
     * resource, permissions and condition. Grant sources and conditions are rewritten per id and one
     * {@code GRANT_CHANGED}/{@code DENY_CHANGED} event (operation {@code SPLIT}) is written per split policy. A PENDING
     * four-eyes change that names several principals or targets a split policy is marked {@code SUPERSEDED}, with a
     * {@code POLICY_CHANGE_PENDING} event (operation {@code SUPERSEDE}), so approving it cannot restore a grant.
     *
     * <p>Cluster-safe and idempotent: the tenant's SEC_TENANT row is locked first, so a second node waits and then
     * finds nothing left to split.
     */
    public SplitResult splitMultiPrincipal(Connection c, SecurityIdentity actor) {
        String t = actor.tenantId();
        Jdbc.query(c, dialect.lockingSelect("tenant_id", "SEC_TENANT", "tenant_id=?"), rs -> rs.getString(1), t);
        List<PolicyDefinition> multi = Jdbc.query(c, "SELECT policy_id, definition FROM SEC_POLICY WHERE tenant_id=?"
                        + " AND status='ACTIVE' ORDER BY policy_id", rs -> fromJson(rs.getString(1), rs.getString(2)), t)
                .stream().filter(d -> d.subjects().size() > 1).toList();
        Map<String, List<String>> split = new LinkedHashMap<>();
        for (PolicyDefinition d : multi) {
            Map<String, String> into = new LinkedHashMap<>();
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < d.subjects().size(); i++) {
                String subject = d.subjects().get(i);
                if (into.containsKey(subject)) {
                    continue; // a subject named twice gets one policy
                }
                String id = i == 0 ? d.policyId() : Sql.newId();
                store(c, actor, id, new PolicyDefinition(id, d.name(), d.effect(), List.of(subject), d.resourceType(),
                        d.resourceId(), d.permissions(), d.condition()));
                into.put(subject, id);
                ids.add(id);
            }
            split.put(d.policyId(), List.copyOf(ids));
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("policy_id", d.policyId());
            p.put("operation", "SPLIT");
            p.put("decision", "392");
            p.put("effect", d.effect().name());
            p.put("subjects", d.subjects());
            p.put("split_into", into);
            p.put("resource_type", d.resourceType());
            p.put("resource_id", d.resourceId());
            p.put("permissions", d.permissions().stream().map(Enum::name).sorted().toList());
            audit.security(c, actor, d.effect() == Effect.DENY ? SecurityEventType.DENY_CHANGED : SecurityEventType.GRANT_CHANGED, p);
        }
        List<String[]> pending = Jdbc.query(c, "SELECT change_id, policy_id, definition FROM SEC_POLICY_CHANGE WHERE tenant_id=?"
                + " AND status='PENDING' ORDER BY change_id", rs -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3)}, t);
        List<String> superseded = new ArrayList<>();
        for (String[] row : pending) {
            if (!split.containsKey(row[1]) && fromJson(row[1], row[2]).subjects().size() <= 1) {
                continue;
            }
            // E-33 follow-up 3 (b): the request reason stays; why the change was superseded is its decision reason
            int n = Jdbc.update(c, "UPDATE SEC_POLICY_CHANGE SET status='SUPERSEDED', decided_by=?, decided_at=?, decision_reason=?"
                    + " WHERE tenant_id=? AND change_id=? AND status='PENDING'", actor.personId(), Sql.dbNow(c, dialect),
                    SUPERSEDED_REASON, t, row[0]);
            if (n == 1) {
                superseded.add(row[0]);
                audit.security(c, actor, SecurityEventType.POLICY_CHANGE_PENDING, Map.of("change_id", row[0],
                        "policy_id", row[1], "operation", "SUPERSEDE", "decision", "392", "reason", SUPERSEDED_REASON));
            }
        }
        if (!split.isEmpty()) {
            compiler.invalidate(t);
        }
        return new SplitResult(split, superseded);
    }

    private void validate(PolicyDefinition def) {
        if (def.policyId() == null || def.policyId().isBlank() || def.subjects().isEmpty() || def.permissions().isEmpty()) {
            throw new SangamSecurityException.PolicyRejected("a policy needs an id, subjects and permissions");
        }
        if (def.subjects().size() != 1) {
            throw new SangamSecurityException.PolicyRejected(ONE_PRINCIPAL);
        }
        for (String s : def.subjects()) {
            if (PREFIXES.stream().noneMatch(s::startsWith) || s.indexOf(':') == s.length() - 1) {
                throw new SangamSecurityException.PolicyRejected("subject '" + s + "' must be user:, group:, role: or seat: prefixed");
            }
        }
        if (def.resourceId() == null || def.resourceId().isBlank()) {
            throw new SangamSecurityException.PolicyRejected("a policy needs a resource id or '*'");
        }
        compiler.validate(def.resourceType(), def.resourceId(), Permission.mask(def.permissions()), def.condition(),
                def.effect() == Effect.DENY);
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
