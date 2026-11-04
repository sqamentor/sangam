// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.policy;

import org.sangam.core.layer.Jdbc;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.security.dialect.SecurityDialect;
import org.sangam.security.support.Sql;

import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The materialised membership table {@code AUTH_MEMBERSHIP(member_id → principal_id)} (VI.4 rule 2, D-55): which
 * groups, roles and seats a user is a member of, effective-dated. It serves two purposes:
 * <ul>
 *   <li>identity enrichment: the principal ids added to a caller from Sangam groups, role bindings and postings;</li>
 *   <li>principals with more than 200 ids: the VI.4 predicate joins this table instead of binding an {@code IN} list.
 *       IdP groups are synchronised into it at login (source {@code idp}) so the join sees them too.</li>
 * </ul>
 * Every change bumps {@code policy_version} in the same transaction (VI.4 rule 5).
 */
public final class MembershipIndex {

    public static final String SOURCE_IDP = "idp";

    private final SecurityDialect dialect;
    private final AuthEffectiveMaintainer maintainer;

    public MembershipIndex(SecurityDialect dialect, AuthEffectiveMaintainer maintainer) {
        this.dialect = dialect;
        this.maintainer = maintainer;
    }

    /** Adds a membership; {@code validTo} null means open-ended. Returns the new policy version. */
    public long put(Connection c, String tenant, String memberId, String principalId, String sourceId, Instant validFrom,
                    Instant validTo) {
        long v = maintainer.bumpPolicyVersion(c, tenant);
        Jdbc.update(c, "DELETE FROM AUTH_MEMBERSHIP WHERE tenant_id=? AND member_id=? AND principal_id=? AND source_id=?",
                tenant, memberId, principalId, sourceId);
        Jdbc.update(c, "INSERT INTO AUTH_MEMBERSHIP(tenant_id, member_id, principal_id, source_id, valid_from, valid_to)"
                + " VALUES (?,?,?,?,?,?)", tenant, memberId, principalId, sourceId,
                validFrom == null ? Instant.EPOCH : validFrom, validTo);
        return v;
    }

    public long remove(Connection c, String tenant, String memberId, String principalId, String sourceId) {
        long v = maintainer.bumpPolicyVersion(c, tenant);
        Jdbc.update(c, "DELETE FROM AUTH_MEMBERSHIP WHERE tenant_id=? AND member_id=? AND principal_id=? AND source_id=?",
                tenant, memberId, principalId, sourceId);
        return v;
    }

    public long removeSource(Connection c, String tenant, String sourceId) {
        long v = maintainer.bumpPolicyVersion(c, tenant);
        Jdbc.update(c, "DELETE FROM AUTH_MEMBERSHIP WHERE tenant_id=? AND source_id=?", tenant, sourceId);
        return v;
    }

    /** Ends a membership at {@code at} (effective-dated, keeps history). */
    public long end(Connection c, String tenant, String sourceId, Instant at) {
        long v = maintainer.bumpPolicyVersion(c, tenant);
        Jdbc.update(c, "UPDATE AUTH_MEMBERSHIP SET valid_to=? WHERE tenant_id=? AND source_id=? AND (valid_to IS NULL OR valid_to > ?)",
                at, tenant, sourceId, at);
        return v;
    }

    /** Bumps {@code policy_version} for a change that affects memberships indirectly (e.g. leave). */
    public long touch(Connection c, String tenant) {
        return maintainer.bumpPolicyVersion(c, tenant);
    }

    /** Principal ids (prefixed) the member belongs to at {@code at}. */
    public Set<String> principalsOf(Connection c, String tenant, String memberId, Instant at) {
        return new LinkedHashSet<>(Jdbc.query(c, "SELECT principal_id FROM AUTH_MEMBERSHIP WHERE tenant_id=? AND member_id=? AND "
                + Sql.validAt(null), rs -> rs.getString(1), tenant, memberId, at, at));
    }

    /**
     * Synchronises the IdP groups and roles of a user into the index (source {@code idp}) when they changed, so the
     * large-principal join path sees exactly the token's memberships. Bumps the policy version only on change.
     */
    public void syncIdp(Connection c, String tenant, String personId, Set<String> groups, Set<String> roles) {
        String member = SecurityIdentity.USER + personId;
        Set<String> wanted = new LinkedHashSet<>();
        groups.forEach(g -> wanted.add(SecurityIdentity.GROUP + g));
        roles.forEach(r -> wanted.add(SecurityIdentity.ROLE + r));
        Set<String> current = new LinkedHashSet<>(Jdbc.query(c, "SELECT principal_id FROM AUTH_MEMBERSHIP WHERE tenant_id=?"
                + " AND member_id=? AND source_id=?", rs -> rs.getString(1), tenant, member, SOURCE_IDP));
        if (current.equals(wanted)) {
            return;
        }
        maintainer.bumpPolicyVersion(c, tenant);
        List<String> removed = new ArrayList<>(current);
        removed.removeAll(wanted);
        for (String p : removed) {
            Jdbc.update(c, "DELETE FROM AUTH_MEMBERSHIP WHERE tenant_id=? AND member_id=? AND principal_id=? AND source_id=?",
                    tenant, member, p, SOURCE_IDP);
        }
        for (String p : wanted) {
            if (!current.contains(p)) {
                Sql.insertIfAbsent(c, dialect, "INSERT INTO AUTH_MEMBERSHIP(tenant_id, member_id, principal_id, source_id,"
                        + " valid_from, valid_to) VALUES (?,?,?,?,?,NULL)", tenant, member, p, SOURCE_IDP, Instant.EPOCH);
            }
        }
    }
}
