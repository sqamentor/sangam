package org.sangam.spike.layer;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One authorization policy with two compiled forms (SANGAM.md VI.4 rule 1, A-3), both generated from the
 * same {@code AUTH_EFFECTIVE} grant data:
 * <ul>
 *   <li>{@link #allow} — the object check, evaluated from the grant rows of one resource;</li>
 *   <li>{@link #visible} — a SQL predicate on a caller's table alias, AND-ed into list, count, search and
 *       export queries. Pages are never filtered after the query (A-3).</li>
 * </ul>
 *
 * <p>Semantics: a resource {@code r} of this policy's type is visible to principal {@code p} for action {@code a} iff
 * <ol>
 *   <li>{@code r.tenant == p.tenant} (VI.4 rule 6: the tenant comes from the identity, never from a request), and</li>
 *   <li>some grant row exists for one of {@code p.ids()} on {@code r.id} or {@code '*'} whose perms contain
 *       {@code a} and whose {@code condition_id} is null, and</li>
 *   <li>no deny row exists for one of {@code p.ids()} on {@code r.id} or {@code '*'} whose perms contain
 *       {@code a} (explicit deny wins, compiled as {@code NOT EXISTS}).</li>
 * </ol>
 * Conditions ({@code condition_id}) are not compiled in this spike: a conditional grant never matches and a
 * conditional deny always applies (fail closed). A production policy compiler would emit
 * {@code <compiled condition on r>} for both forms or reject the policy at deployment (VI.4 rule 1).
 *
 * <p>Above 200 principal ids the predicate should join a materialised membership table (VI.4 rule 2);
 * this spike always binds an {@code IN} list.
 */
public final class AuthorizationPolicy {

    public static final String ANY = "*";

    /** Permission bits stored in {@code AUTH_EFFECTIVE.perms}. */
    public enum Action {
        READ, READ_TASK, READ_HISTORY, READ_VARIABLE, CLAIM, COMPLETE, APPROVE, CANCEL, MODIFY, CHANGE_GRANT, READ_AUDIT;

        public long bit() {
            return 1L << ordinal();
        }

        public static long mask(Action... actions) {
            long m = 0;
            for (Action a : actions) {
                m |= a.bit();
            }
            return m;
        }
    }

    /** An authenticated principal: the user plus their groups, bound to one tenant. Ids are opaque. */
    public record Principal(String tenantId, String userId, Set<String> groups) {
        public Principal {
            Objects.requireNonNull(tenantId);
            Objects.requireNonNull(userId);
            groups = Collections.unmodifiableSet(new LinkedHashSet<>(groups));
        }

        public List<String> ids() {
            List<String> ids = new ArrayList<>(1 + groups.size());
            ids.add(userId);
            ids.addAll(groups);
            return ids;
        }
    }

    public record Resource(String tenantId, String id) {
    }

    /** Column names of the protected table, e.g. {@code ("TENANT_ID_", "ID_")} for an engine task table. */
    public record ResourceColumns(String tenantColumn, String idColumn) {
        public static final ResourceColumns DEFAULT = new ResourceColumns("tenant_id", "id");
    }

    private final Dialect dialect;
    private final String resourceType;
    private final ResourceColumns columns;

    public AuthorizationPolicy(Dialect dialect, String resourceType, ResourceColumns columns) {
        this.dialect = dialect;
        this.resourceType = resourceType;
        this.columns = columns;
    }

    public String resourceType() {
        return resourceType;
    }

    // ------------------------------------------------------------------ compiled form 1: object check

    /** Object check. Sensitive actions call this inside the command transaction, with no cache (VI.4 rule 5). */
    public boolean allow(Connection c, Principal principal, Action action, Resource resource) {
        if (!principal.tenantId().equals(resource.tenantId())) {
            return false;
        }
        List<String> ids = principal.ids();
        List<Object> params = new ArrayList<>();
        params.add(principal.tenantId());
        params.addAll(ids);
        params.add(resourceType);
        params.add(resource.id());
        params.add(ANY);
        List<long[]> rows = Jdbc.query(c,
                "SELECT deny_flag, perms, CASE WHEN condition_id IS NULL THEN 0 ELSE 1 END AS conditional FROM AUTH_EFFECTIVE"
                        + " WHERE tenant_id = ? AND principal_id IN (" + placeholders(ids.size()) + ")"
                        + " AND resource_type = ? AND resource_id IN (?, ?)",
                rs -> new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3)}, params.toArray());
        boolean granted = false;
        for (long[] row : rows) {
            boolean matches = (row[1] & action.bit()) != 0;
            if (!matches) {
                continue;
            }
            if (row[0] == 1) {
                return false;               // explicit deny wins (A-3), conditional or not (fail closed)
            }
            if (row[2] == 0) {
                granted = true;             // conditional grants never match in this spike (fail closed)
            }
        }
        return granted;
    }

    // ------------------------------------------------------------------ compiled form 2: SQL predicate

    /**
     * SQL predicate over {@code tableAlias} (a table holding resources of this policy's type).
     * Use it for every list, count, search and export: {@code SELECT .. FROM T r WHERE <pred> [AND filters]}.
     */
    public SqlPredicate visible(Principal principal, Action action, String tableAlias) {
        String t = tableAlias + "." + columns.tenantColumn();
        String id = tableAlias + "." + columns.idColumn();
        List<String> ids = principal.ids();
        String in = placeholders(ids.size());
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder();
        sql.append(t).append(" = ?");
        params.add(principal.tenantId());
        sql.append(" AND EXISTS (SELECT 1 FROM AUTH_EFFECTIVE g WHERE g.tenant_id = ").append(t)
                .append(" AND g.principal_id IN (").append(in).append(")")
                .append(" AND g.resource_type = ? AND (g.resource_id = ").append(id).append(" OR g.resource_id = ?)")
                .append(" AND g.deny_flag = 0 AND g.condition_id IS NULL AND ")
                .append(dialect.bitAnd("g.perms", "?")).append(" <> 0)");
        params.addAll(ids);
        params.add(resourceType);
        params.add(ANY);
        params.add(action.bit());
        sql.append(" AND NOT EXISTS (SELECT 1 FROM AUTH_EFFECTIVE d WHERE d.tenant_id = ").append(t)
                .append(" AND d.principal_id IN (").append(in).append(")")
                .append(" AND d.resource_type = ? AND (d.resource_id = ").append(id).append(" OR d.resource_id = ?)")
                .append(" AND d.deny_flag = 1 AND ").append(dialect.bitAnd("d.perms", "?")).append(" <> 0)");
        params.addAll(ids);
        params.add(resourceType);
        params.add(ANY);
        params.add(action.bit());
        return new SqlPredicate(sql.toString(), params);
    }

    // ------------------------------------------------------------------ grant maintenance (same transaction, VI.4 rule 2)

    /** Adds permissions to a grant row; bumps the tenant policy version (VI.4 rule 5). Runs in the caller's transaction. */
    public static long grant(Connection c, Dialect d, String tenant, String principalId, String resourceType,
                             String resourceId, long perms) {
        return addPerms(c, d, tenant, principalId, resourceType, resourceId, false, perms);
    }

    /** Adds an explicit deny for the given permissions. */
    public static long deny(Connection c, Dialect d, String tenant, String principalId, String resourceType,
                            String resourceId, long perms) {
        return addPerms(c, d, tenant, principalId, resourceType, resourceId, true, perms);
    }

    /** Removes a grant or deny row entirely. */
    public static long revoke(Connection c, Dialect d, String tenant, String principalId, String resourceType,
                              String resourceId, boolean deny) {
        long v = bumpPolicyVersion(c, d, tenant);
        Jdbc.update(c, "DELETE FROM AUTH_EFFECTIVE WHERE tenant_id=? AND principal_id=? AND resource_type=? AND resource_id=? AND deny_flag=?",
                tenant, principalId, resourceType, resourceId, deny ? 1 : 0);
        return v;
    }

    public static long policyVersion(Connection c, String tenant) {
        return Jdbc.one(c, "SELECT policy_version FROM AUTH_POLICY_VERSION WHERE tenant_id=?", rs -> rs.getLong(1), tenant)
                .orElse(0L);
    }

    private static long addPerms(Connection c, Dialect d, String tenant, String principalId, String resourceType,
                                 String resourceId, boolean deny, long perms) {
        long v = bumpPolicyVersion(c, d, tenant);
        int flag = deny ? 1 : 0;
        Jdbc.insertIfAbsent(c, d,
                "INSERT INTO AUTH_EFFECTIVE(tenant_id, principal_id, resource_type, resource_id, deny_flag, perms, condition_id, policy_version)"
                        + " VALUES (?,?,?,?,?,0,NULL,?)",
                tenant, principalId, resourceType, resourceId, flag, v);
        long current = Jdbc.one(c, d.lockingSelect("perms", "AUTH_EFFECTIVE",
                                "tenant_id=? AND principal_id=? AND resource_type=? AND resource_id=? AND deny_flag=?"),
                        rs -> rs.getLong(1), tenant, principalId, resourceType, resourceId, flag)
                .orElseThrow();
        Jdbc.update(c, "UPDATE AUTH_EFFECTIVE SET perms=?, policy_version=? WHERE tenant_id=? AND principal_id=? AND resource_type=? AND resource_id=? AND deny_flag=?",
                current | perms, v, tenant, principalId, resourceType, resourceId, flag);
        return v;
    }

    private static long bumpPolicyVersion(Connection c, Dialect d, String tenant) {
        Jdbc.insertIfAbsent(c, d, "INSERT INTO AUTH_POLICY_VERSION(tenant_id, policy_version) VALUES (?, 0)", tenant);
        Jdbc.update(c, "UPDATE AUTH_POLICY_VERSION SET policy_version = policy_version + 1 WHERE tenant_id=?", tenant);
        return policyVersion(c, tenant);
    }

    private static String placeholders(int n) {
        if (n <= 0) {
            throw new IllegalArgumentException("principal must have at least one id");
        }
        return String.join(",", Collections.nCopies(n, "?"));
    }
}
