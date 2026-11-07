// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.policy;

import org.sangam.core.layer.Hashes;
import org.sangam.core.layer.Jdbc;
import org.sangam.security.dialect.SecurityDialect;
import org.sangam.security.support.Sql;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Materialises and incrementally maintains {@code AUTH_EFFECTIVE} (VI.4 rule 2) in the caller's transaction.
 *
 * <p>Every grant or deny has a <b>source</b> (a policy, a role rule, a seat-task assignment, a privilege). Source rows
 * live in {@code AUTH_GRANT_SOURCE}; {@code AUTH_EFFECTIVE.perms} is the bitwise OR of all sources of one key
 * {@code (tenant, principal, resource_type, resource_id, deny_flag, cond_key)}. Changing a source recomputes only the
 * keys that source touched (incremental), so a grant change and its effective rows commit together. Each change bumps
 * the tenant {@code policy_version} first, which also serialises concurrent grant changes of one tenant.
 *
 * <p>{@link #rebuild} re-materialises a whole tenant from the sources; {@link #verify} reports drift.
 */
public final class AuthEffectiveMaintainer {

    /** One grant or deny row produced by a source. */
    public record GrantRow(String principalId, String resourceType, String resourceId, boolean deny, long perms,
                           String conditionId) {
        public GrantRow {
            Objects.requireNonNull(principalId);
            Objects.requireNonNull(resourceType);
            Objects.requireNonNull(resourceId);
        }

        Key key() {
            return new Key(principalId, resourceType, resourceId, deny ? 1 : 0, condKey(conditionId));
        }
    }

    record Key(String principalId, String resourceType, String resourceId, int deny, String condKey) {
    }

    /** {@code cond_key} is {@code condition_id} or {@code '-'} (a NOT NULL primary-key column; see README). */
    public static final String NO_CONDITION = "-";

    private final SecurityDialect dialect;

    public AuthEffectiveMaintainer(SecurityDialect dialect) {
        this.dialect = dialect;
    }

    static String condKey(String conditionId) {
        return conditionId == null ? NO_CONDITION : conditionId;
    }

    /** Replaces every row of {@code sourceId} with {@code rows}; returns the new policy version. */
    public long replaceSource(Connection c, String tenant, String sourceId, List<GrantRow> rows) {
        long version = bumpPolicyVersion(c, tenant);
        Set<Key> touched = new LinkedHashSet<>(sourceKeys(c, tenant, sourceId));
        Jdbc.update(c, "DELETE FROM AUTH_GRANT_SOURCE WHERE tenant_id=? AND source_id=?", tenant, sourceId);
        // the primary key holds this fixed-width hash, not source_id: SQL Server (1,700 bytes) and MySQL (3,072 bytes) limits
        String sourceKey = Hashes.hex(Hashes.sha256(sourceId));
        Map<Key, Long> merged = new LinkedHashMap<>();
        for (GrantRow r : rows) {
            if (r.perms() == 0) {
                continue;
            }
            merged.merge(r.key(), r.perms(), (a, b) -> a | b);
        }
        for (Map.Entry<Key, Long> e : merged.entrySet()) {
            Key k = e.getKey();
            Jdbc.update(c, "INSERT INTO AUTH_GRANT_SOURCE(tenant_id, principal_id, resource_type, resource_id, deny_flag,"
                            + " cond_key, source_id, source_key, perms) VALUES (?,?,?,?,?,?,?,?,?)",
                    tenant, k.principalId(), k.resourceType(), k.resourceId(), k.deny(), k.condKey(), sourceId, sourceKey, e.getValue());
            touched.add(k);
        }
        for (Key k : touched) {
            recompute(c, tenant, k, version);
        }
        return version;
    }

    public long removeSource(Connection c, String tenant, String sourceId) {
        return replaceSource(c, tenant, sourceId, List.of());
    }

    /** Full re-materialisation of one tenant from {@code AUTH_GRANT_SOURCE}. */
    public long rebuild(Connection c, String tenant) {
        long version = bumpPolicyVersion(c, tenant);
        Jdbc.update(c, "DELETE FROM AUTH_EFFECTIVE WHERE tenant_id=?", tenant);
        Map<Key, Long> agg = aggregate(c, tenant);
        for (Map.Entry<Key, Long> e : agg.entrySet()) {
            insertEffective(c, tenant, e.getKey(), e.getValue(), version);
        }
        return version;
    }

    /** Keys whose effective perms differ from the OR of their sources (should be empty). */
    public List<String> verify(Connection c, String tenant) {
        Map<Key, Long> agg = aggregate(c, tenant);
        Map<Key, Long> eff = new LinkedHashMap<>();
        Jdbc.query(c, "SELECT principal_id, resource_type, resource_id, deny_flag, cond_key, perms FROM AUTH_EFFECTIVE WHERE tenant_id=?",
                rs -> {
                    eff.put(new Key(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getString(5)), rs.getLong(6));
                    return null;
                }, tenant);
        List<String> drift = new ArrayList<>();
        Set<Key> all = new LinkedHashSet<>(agg.keySet());
        all.addAll(eff.keySet());
        for (Key k : all) {
            if (!Objects.equals(agg.get(k), eff.get(k))) {
                drift.add(k + " sources=" + agg.get(k) + " effective=" + eff.get(k));
            }
        }
        return drift;
    }

    /** Bumps and returns the tenant policy version (VI.4 rule 5: every grant or membership change increments it). */
    public long bumpPolicyVersion(Connection c, String tenant) {
        Sql.insertIfAbsent(c, dialect, "INSERT INTO AUTH_POLICY_VERSION(tenant_id, policy_version) VALUES (?, 0)", tenant);
        Jdbc.update(c, "UPDATE AUTH_POLICY_VERSION SET policy_version = policy_version + 1 WHERE tenant_id=?", tenant);
        return policyVersion(c, tenant);
    }

    public static long policyVersion(Connection c, String tenant) {
        return Jdbc.one(c, "SELECT policy_version FROM AUTH_POLICY_VERSION WHERE tenant_id=?", rs -> rs.getLong(1), tenant)
                .orElse(0L);
    }

    /** Registers (or replaces) a compiled condition referenced by {@code condition_id}. */
    public void putCondition(Connection c, String tenant, String conditionId, String resourceType, Condition condition) {
        Jdbc.update(c, "DELETE FROM AUTH_CONDITION WHERE tenant_id=? AND condition_id=?", tenant, conditionId);
        Jdbc.update(c, "INSERT INTO AUTH_CONDITION(tenant_id, condition_id, resource_type, expression) VALUES (?,?,?,?)",
                tenant, conditionId, resourceType, Sql.toJson(Condition.toMap(condition)));
    }

    public void deleteCondition(Connection c, String tenant, String conditionId) {
        Jdbc.update(c, "DELETE FROM AUTH_CONDITION WHERE tenant_id=? AND condition_id=?", tenant, conditionId);
    }

    // ------------------------------------------------------------------ internals

    private List<Key> sourceKeys(Connection c, String tenant, String sourceId) {
        return Jdbc.query(c, "SELECT principal_id, resource_type, resource_id, deny_flag, cond_key FROM AUTH_GRANT_SOURCE"
                        + " WHERE tenant_id=? AND source_id=?",
                rs -> new Key(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getString(5)), tenant, sourceId);
    }

    private void recompute(Connection c, String tenant, Key k, long version) {
        List<Long> perms = Jdbc.query(c, "SELECT perms FROM AUTH_GRANT_SOURCE WHERE tenant_id=? AND principal_id=? AND resource_type=?"
                        + " AND resource_id=? AND deny_flag=? AND cond_key=?",
                rs -> rs.getLong(1), tenant, k.principalId(), k.resourceType(), k.resourceId(), k.deny(), k.condKey());
        long or = 0;
        for (long p : perms) {
            or |= p;
        }
        if (or == 0) {
            Jdbc.update(c, "DELETE FROM AUTH_EFFECTIVE WHERE tenant_id=? AND principal_id=? AND resource_type=? AND resource_id=?"
                            + " AND deny_flag=? AND cond_key=?",
                    tenant, k.principalId(), k.resourceType(), k.resourceId(), k.deny(), k.condKey());
            return;
        }
        int n = Jdbc.update(c, "UPDATE AUTH_EFFECTIVE SET perms=?, policy_version=? WHERE tenant_id=? AND principal_id=?"
                        + " AND resource_type=? AND resource_id=? AND deny_flag=? AND cond_key=?",
                or, version, tenant, k.principalId(), k.resourceType(), k.resourceId(), k.deny(), k.condKey());
        if (n == 0) {
            insertEffective(c, tenant, k, or, version);
        }
    }

    private void insertEffective(Connection c, String tenant, Key k, long perms, long version) {
        Jdbc.update(c, "INSERT INTO AUTH_EFFECTIVE(tenant_id, principal_id, resource_type, resource_id, deny_flag, perms,"
                        + " condition_id, cond_key, policy_version) VALUES (?,?,?,?,?,?,?,?,?)",
                tenant, k.principalId(), k.resourceType(), k.resourceId(), k.deny(), perms,
                NO_CONDITION.equals(k.condKey()) ? null : k.condKey(), k.condKey(), version);
    }

    private Map<Key, Long> aggregate(Connection c, String tenant) {
        Map<Key, Long> agg = new LinkedHashMap<>();
        Jdbc.query(c, "SELECT principal_id, resource_type, resource_id, deny_flag, cond_key, perms FROM AUTH_GRANT_SOURCE WHERE tenant_id=?",
                rs -> {
                    agg.merge(new Key(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getString(5)),
                            rs.getLong(6), (a, b) -> a | b);
                    return null;
                }, tenant);
        return agg;
    }
}
