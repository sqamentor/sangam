// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.policy;

import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.SqlPredicate;
import org.sangam.core.security.SangamSecurityException;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.security.dialect.SecurityDialect;
import org.sangam.security.support.Sql;

import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The VI.4 policy compiler: one policy definition (the {@code AUTH_EFFECTIVE} rows plus their compiled
 * {@code AUTH_CONDITION}s), two compiled forms.
 *
 * <h2>List predicate ({@link #visible})</h2>
 * <pre>
 * r.tenant = :tenant
 * AND ( EXISTS(grant, exact id) OR EXISTS(grant, '*') OR EXISTS(grant, scope_1) ... )
 * AND NOT EXISTS(deny, exact id) AND NOT EXISTS(deny, '*') AND NOT EXISTS(deny, scope_1) ...
 * [AND base filter]
 * </pre>
 * <ul>
 *   <li><b>Deny wins at any scope</b> (A-3, A-13): any deny row that matches the principal, the resource type or
 *       {@code '*'}, and the resource by id, by {@code '*'} or by any broader scope (process instance, definition,
 *       business key ...) removes the resource, whatever grants exist. Compiled as {@code NOT EXISTS}.</li>
 *   <li><b>Conditional rows</b>: a grant matches only when its compiled condition is true on the row; a deny applies
 *       when its condition is true, and <i>always</i> when its condition is unknown to the compiler (fail closed).</li>
 *   <li><b>Index plan</b> (A-16, FLOWABLE-INTO-OPERATON D-55): the wildcard and each scope are separate {@code EXISTS} branches instead of one
 *       {@code OR} inside one subquery, so each branch is an index seek on
 *       {@code (tenant_id, resource_type, resource_id, principal_id)}; the tenant is bound, not correlated.</li>
 *   <li><b>Large principals</b>: above {@value #MAX_IN_LIST} principal ids the predicate joins
 *       {@code AUTH_MEMBERSHIP} instead of binding an {@code IN} list (VI.4 rule 2).</li>
 *   <li>Pages are never filtered after the query (A-3): callers AND this predicate into their {@code WHERE}.</li>
 * </ul>
 *
 * <h2>Object check ({@link #allow})</h2>
 * For a table-backed type the object check runs <b>the same predicate</b> against the single row
 * ({@code SELECT COUNT(*) FROM t r WHERE r.id = ? AND <visible>}), so list and object can never disagree (VI.4 rule 3,
 * T37). Virtual (admin) types without a table are checked from their grant rows with the Java form of the conditions.
 */
public final class PolicyCompiler {

    public static final int MAX_IN_LIST = 200;

    private final SecurityDialect dialect;
    private final ResourceRegistry registry;
    private final Duration maxStaleness;
    private final Map<String, CacheEntry> conditionCache = new ConcurrentHashMap<>();

    private record CacheEntry(long policyVersion, long loadedAtNanos, List<CompiledCondition> conditions) {
    }

    /** A condition known to the compiler for one resource type. */
    public record CompiledCondition(String conditionId, Condition condition) {
    }

    /**
     * @param maxStaleness how long cached condition definitions may be used for list predicates without re-reading
     *                     {@code policy_version} (VI.4 rule 5: at most 5 s; zero means no cache). Sensitive actions
     *                     always re-read.
     */
    public PolicyCompiler(SecurityDialect dialect, ResourceRegistry registry, Duration maxStaleness) {
        this.dialect = dialect;
        this.registry = registry;
        this.maxStaleness = maxStaleness == null ? Duration.ofSeconds(5) : maxStaleness;
    }

    public SecurityDialect dialect() {
        return dialect;
    }

    public ResourceRegistry registry() {
        return registry;
    }

    // ------------------------------------------------------------------ list predicate

    public SqlPredicate visible(Connection c, SecurityIdentity who, String resourceType, Permission permission, String alias) {
        ResourceDescriptor d = registry.get(resourceType);
        if (!d.hasTable()) {
            throw new IllegalArgumentException(resourceType + " is a virtual resource type; use allow()");
        }
        List<CompiledCondition> conds = conditions(c, who.tenantId(), resourceType, permission.sensitive());
        StringBuilder sql = new StringBuilder();
        List<Object> params = new ArrayList<>();
        String tenantCol = alias + "." + d.tenantColumn();
        sql.append(tenantCol).append(" = ?");
        params.add(who.tenantId());

        List<Branch> branches = branches(d, alias);
        sql.append(" AND (");
        for (int i = 0; i < branches.size(); i++) {
            if (i > 0) {
                sql.append(" OR ");
            }
            appendExists(sql, params, "g", false, who, resourceType, permission, branches.get(i), conds, d, alias);
        }
        sql.append(")");
        for (Branch b : branches) {
            sql.append(" AND NOT ");
            appendExists(sql, params, "x", true, who, resourceType, permission, b, conds, d, alias);
        }
        if (d.baseFilter() != null) {
            sql.append(" AND (").append(d.baseFilter().replace("r.", alias + ".")).append(")");
        }
        return new SqlPredicate(sql.toString(), params);
    }

    // ------------------------------------------------------------------ object check

    /** Object check by id, through the same predicate (table-backed) or the grant rows (virtual types). */
    public boolean allow(Connection c, SecurityIdentity who, String resourceType, Permission permission, String resourceId) {
        return allow(c, who, resourceType, permission, resourceId, Map.of());
    }

    /**
     * Object check. {@code attributes} are used only for virtual resource types (conditions evaluated in Java);
     * table-backed types read their attributes from the row through the SQL predicate.
     */
    public boolean allow(Connection c, SecurityIdentity who, String resourceType, Permission permission, String resourceId,
                         Map<String, ?> attributes) {
        ResourceDescriptor d = registry.get(resourceType);
        if (d.hasTable()) {
            SqlPredicate p = visible(c, who, resourceType, permission, "r");
            List<Object> params = new ArrayList<>();
            params.add(resourceId);
            params.addAll(p.params());
            long n = Jdbc.one(c, "SELECT COUNT(*) FROM " + d.table() + " r WHERE r." + d.idColumn() + " = ? AND " + p.sql(),
                    rs -> rs.getLong(1), params.toArray()).orElse(0L);
            return n > 0;
        }
        return allowVirtual(c, who, resourceType, permission, resourceId, attributes);
    }

    private boolean allowVirtual(Connection c, SecurityIdentity who, String resourceType, Permission permission,
                                 String resourceId, Map<String, ?> attributes) {
        Map<String, Condition> conds = new java.util.HashMap<>();
        for (CompiledCondition cc : conditions(c, who.tenantId(), resourceType, permission.sensitive())) {
            conds.put(cc.conditionId(), cc.condition());
        }
        StringBuilder sql = new StringBuilder("SELECT a.deny_flag, a.perms, a.condition_id FROM AUTH_EFFECTIVE a WHERE a.tenant_id = ? AND ");
        List<Object> params = new ArrayList<>();
        params.add(who.tenantId());
        appendPrincipal(sql, params, "a", who);
        sql.append(" AND a.resource_type IN (?, ?) AND a.resource_id IN (?, ?)");
        params.add(resourceType);
        params.add(ResourceRegistry.ANY_TYPE);
        params.add(resourceId);
        params.add(ResourceRegistry.ANY_ID);
        boolean granted = false;
        for (Object[] row : Jdbc.query(c, sql.toString(), rs -> new Object[]{rs.getInt(1), rs.getLong(2), rs.getString(3)},
                params.toArray())) {
            int deny = (Integer) row[0];
            long perms = (Long) row[1];
            String condId = (String) row[2];
            if ((perms & permission.bit()) == 0) {
                continue;
            }
            Condition cond = condId == null ? null : conds.get(condId);
            if (deny == 1) {
                if (condId == null || cond == null || ConditionCompiler.evaluate(cond, attributes, who)) {
                    return false;                  // deny wins; unknown condition fails closed
                }
            } else if (condId == null || (cond != null && ConditionCompiler.evaluate(cond, attributes, who))) {
                granted = true;
            }
        }
        return granted;
    }

    // ------------------------------------------------------------------ deployment validation (VI.4 rule 1)

    /** Rejects a conditional policy on a list-visible permission whose condition does not compile to SQL. */
    public void validate(String resourceType, long perms, Condition condition) {
        if (!registry.known(resourceType)) {
            throw new SangamSecurityException.PolicyRejected("unknown resource type " + resourceType);
        }
        java.util.Set<Permission> allowed = ResourceRegistry.ANY_TYPE.equals(resourceType) ? java.util.Set.of()
                : registry.permissionsOf(resourceType);
        if (!allowed.isEmpty()) {
            for (Permission p : Permission.fromMask(perms)) {
                if (!allowed.contains(p)) {
                    throw new SangamSecurityException.PolicyRejected("permission " + p + " is not defined on " + resourceType
                            + " (allowed: " + allowed + ")");
                }
            }
        }
        if (condition == null || ResourceRegistry.ANY_TYPE.equals(resourceType)) {
            if (condition != null) {
                throw new SangamSecurityException.PolicyRejected("a condition needs a concrete resource type");
            }
            return;
        }
        ResourceDescriptor d = registry.get(resourceType);
        boolean listVisible = Permission.fromMask(perms).stream().anyMatch(Permission::listVisible);
        if (listVisible) {
            ConditionCompiler.requireCompilable(condition, d);
        }
    }

    /** Drops cached condition definitions (after an admin change in this JVM). */
    public void invalidate(String tenant) {
        conditionCache.keySet().removeIf(k -> k.startsWith(tenant + "|"));
    }

    // ------------------------------------------------------------------ internals

    private record Branch(String matchSql, List<Object> params) {
    }

    private List<Branch> branches(ResourceDescriptor d, String alias) {
        List<Branch> out = new ArrayList<>();
        out.add(new Branch("{X}.resource_id = " + alias + "." + d.idColumn(), List.of()));
        out.add(new Branch("{X}.resource_id = ?", List.of(ResourceRegistry.ANY_ID)));
        for (ResourceDescriptor.Scope s : d.scopes()) {
            out.add(new Branch("{X}.resource_id = " + dialect.concat("?", alias + "." + s.column()), List.of(s.prefix())));
        }
        return out;
    }

    private void appendExists(StringBuilder sql, List<Object> params, String x, boolean deny, SecurityIdentity who,
                              String resourceType, Permission permission, Branch branch, List<CompiledCondition> conds,
                              ResourceDescriptor d, String alias) {
        sql.append("EXISTS (SELECT 1 FROM AUTH_EFFECTIVE ").append(x).append(" WHERE ").append(x).append(".tenant_id = ? AND ");
        params.add(who.tenantId());
        appendPrincipal(sql, params, x, who);
        sql.append(" AND ").append(x).append(".resource_type IN (?, ?) AND ");
        params.add(resourceType);
        params.add(ResourceRegistry.ANY_TYPE);
        sql.append(branch.matchSql().replace("{X}", x));
        params.addAll(branch.params());
        sql.append(" AND ").append(x).append(".deny_flag = ").append(deny ? 1 : 0)
                .append(" AND ").append(dialect.bitAnd(x + ".perms", "?")).append(" <> 0");
        params.add(permission.bit());
        if (!deny) {
            sql.append(" AND (").append(x).append(".condition_id IS NULL");
            for (CompiledCondition cc : conds) {
                SqlPredicate p = ConditionCompiler.compile(cc.condition(), d, alias, who);
                sql.append(" OR (").append(x).append(".condition_id = ? AND (").append(p.sql()).append("))");
                params.add(cc.conditionId());
                params.addAll(p.params());
            }
            sql.append(")");
        } else if (!conds.isEmpty()) {
            // a conditional deny applies when its condition holds, and always when the condition is unknown (fail closed)
            sql.append(" AND (").append(x).append(".condition_id IS NULL OR ").append(x).append(".condition_id NOT IN (")
                    .append(Sql.placeholders(conds.size())).append(")");
            conds.forEach(cc -> params.add(cc.conditionId()));
            for (CompiledCondition cc : conds) {
                SqlPredicate p = ConditionCompiler.compile(cc.condition(), d, alias, who);
                sql.append(" OR (").append(x).append(".condition_id = ? AND (").append(p.sql()).append("))");
                params.add(cc.conditionId());
                params.addAll(p.params());
            }
            sql.append(")");
        }
        sql.append(")");
    }

    private void appendPrincipal(StringBuilder sql, List<Object> params, String x, SecurityIdentity who) {
        List<String> ids = who.principalIds();
        if (ids.size() <= MAX_IN_LIST) {
            sql.append(x).append(".principal_id IN (").append(Sql.placeholders(ids.size())).append(")");
            params.addAll(ids);
            return;
        }
        // VI.4 rule 2: join the materialised membership table; seats held now are bound explicitly
        List<String> direct = new ArrayList<>();
        direct.add(ids.get(0));
        who.seats().forEach(s -> direct.add(SecurityIdentity.SEAT + s));
        String now = dialect.now();
        sql.append("(").append(x).append(".principal_id IN (").append(Sql.placeholders(direct.size())).append(")")
                .append(" OR ").append(x).append(".principal_id IN (SELECT m.principal_id FROM AUTH_MEMBERSHIP m")
                .append(" WHERE m.tenant_id = ? AND m.member_id = ? AND m.valid_from <= ").append(now)
                .append(" AND (m.valid_to IS NULL OR m.valid_to > ").append(now).append(")))");
        params.addAll(direct);
        params.add(who.tenantId());
        params.add(ids.get(0));
    }

    private List<CompiledCondition> conditions(Connection c, String tenant, String resourceType, boolean sensitive) {
        String key = tenant + "|" + resourceType;
        CacheEntry e = conditionCache.get(key);
        long now = System.nanoTime();
        if (e != null && !sensitive && !maxStaleness.isZero() && now - e.loadedAtNanos() < maxStaleness.toNanos()) {
            return e.conditions();
        }
        long version = AuthEffectiveMaintainer.policyVersion(c, tenant);
        if (e != null && e.policyVersion() == version) {
            conditionCache.put(key, new CacheEntry(version, now, e.conditions()));
            return e.conditions();
        }
        ResourceDescriptor d = registry.get(resourceType);
        List<CompiledCondition> loaded = new ArrayList<>();
        for (String[] row : Jdbc.query(c, "SELECT condition_id, expression FROM AUTH_CONDITION WHERE tenant_id=? AND resource_type=?"
                        + " ORDER BY condition_id",
                rs -> new String[]{rs.getString(1), rs.getString(2)}, tenant, resourceType)) {
            Condition cond = Condition.fromMap(Sql.jsonMap(row[1]));
            if (!d.hasTable() || ConditionCompiler.compilable(cond, d)) {
                loaded.add(new CompiledCondition(row[0], cond));
            }
            // a stored condition that no longer compiles is left out: its grants never match, its denies always apply
        }
        List<CompiledCondition> immutable = List.copyOf(loaded);
        conditionCache.put(key, new CacheEntry(version, now, immutable));
        return immutable;
    }
}
