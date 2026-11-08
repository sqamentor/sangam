// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.identity;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.security.dialect.SecurityDialect;
import org.sangam.security.support.Sql;

/**
 * Per-token cache of the identity {@link PrincipalResolver#resolve} computes (SG-15 T-SCALE). Without it every request
 * re-resolved the caller: tenant status, person by subject, IdP membership sync, database time, seats (postings and
 * leave per seat) and one {@code AUTH_MEMBERSHIP} lookup per member, about ten statements and a commit per request.
 *
 * <p><b>Exact, not time-to-live based.</b> A cached identity is used only while one validation read, made on every
 * request in the same database the resolution reads, still returns exactly what it returned when the identity was
 * resolved:
 * <ul>
 *   <li>the tenant {@code policy_version}: every change of memberships, role bindings, postings, leave and IdP
 *       sources bumps it in the same transaction (VI.4 rule 5, {@link org.sangam.security.policy.MembershipIndex});</li>
 *   <li>the tenant status (suspension is refused at once) and the person the subject maps to and its status
 *       (disabling or relinking a person is seen at once; neither bumps the policy version);</li>
 *   <li>the database time is before {@code validUntil}: the first effective-date boundary after resolution of any
 *       row that can change the result (the members' {@code AUTH_MEMBERSHIP} rows, the postings of the person and of
 *       every seat it has a posting on, the approved leave of those seats' holders). A membership, posting or leave
 *       that starts or ends is therefore seen at the first request after the boundary, as without the cache.</li>
 * </ul>
 * Anything else misses and resolves in full. The key is the whole token identity (subject, IdP groups and roles,
 * attributes, client IP), so a token with other claims never reuses an entry. Entries are bounded in number and in
 * age ({@link #MAX_AGE}, defence in depth only; correctness does not depend on it). Each node keeps its own cache and
 * validates against the shared database, so the cache is correct with any number of nodes.
 */
public final class ResolvedIdentityCache {

    /** Upper bound on the age of an entry; not needed for correctness (see the class comment). */
    public static final Duration MAX_AGE = Duration.ofMinutes(5);
    static final int MAX_ENTRIES = 10_000;

    private final SecurityDialect dialect;
    private final PrincipalResolver resolver;
    private final Map<SecurityIdentity, Entry> entries = new LinkedHashMap<>(1024, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<SecurityIdentity, Entry> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    /** What the validation read returns; an entry is valid while it is unchanged and {@code now} is before the bound. */
    record Stamp(long policyVersion, String tenantStatus, String personId, String personStatus, Instant now) {
        boolean sameAs(Stamp o) {
            return policyVersion == o.policyVersion && Objects.equals(tenantStatus, o.tenantStatus)
                    && Objects.equals(personId, o.personId) && Objects.equals(personStatus, o.personStatus);
        }
    }

    private record Entry(SecurityIdentity identity, Stamp stamp, Instant validUntil) {
    }

    public ResolvedIdentityCache(SecurityDialect dialect, PrincipalResolver resolver) {
        this.dialect = dialect;
        this.resolver = resolver;
    }

    /** Same contract as {@link PrincipalResolver#resolve}, served from the cache while the entry is provably current. */
    public SecurityIdentity resolve(Transactions tx, SecurityIdentity raw, boolean jit, String displayName, String email) {
        if ("API_KEY".equals(raw.authMethod()) || raw.subject() == null) {
            return tx.inTransaction(c -> resolver.resolve(c, raw, jit, displayName, email));
        }
        Entry cached;
        synchronized (entries) {
            cached = entries.get(raw);
        }
        if (cached != null) {
            Stamp now = tx.inTransaction(c -> stamp(c, raw));
            if (now.sameAs(cached.stamp()) && "ACTIVE".equals(now.personStatus()) && now.now().isBefore(cached.validUntil())) {
                return cached.identity();
            }
        }
        Entry fresh = tx.inTransaction(c -> {
            // read the stamp before resolving: a change committed in between makes the next request miss (never a stale hit)
            Stamp before = stamp(c, raw);
            SecurityIdentity resolved = resolver.resolve(c, raw, jit, displayName, email);
            Instant bound = nextBoundary(c, resolved, before.now());
            Instant cap = before.now().plus(MAX_AGE);
            return new Entry(resolved, before, bound == null || bound.isAfter(cap) ? cap : bound);
        });
        synchronized (entries) {
            entries.put(raw, fresh);
        }
        return fresh.identity();
    }

    /** Drops every entry (tests, or an operator after a direct database change outside the services). */
    public void clear() {
        synchronized (entries) {
            entries.clear();
        }
    }

    int size() {
        synchronized (entries) {
            return entries.size();
        }
    }

    Stamp stamp(Connection c, SecurityIdentity raw) {
        String t = raw.tenantId();
        String s = raw.subject();
        return Jdbc.one(c, "SELECT (SELECT policy_version FROM AUTH_POLICY_VERSION WHERE tenant_id=?) AS pv,"
                        + " (SELECT status FROM SEC_TENANT WHERE tenant_id=?) AS ts,"
                        + " (SELECT person_id FROM SEC_PERSON WHERE tenant_id=? AND external_subject=?) AS pid,"
                        + " (SELECT status FROM SEC_PERSON WHERE tenant_id=? AND external_subject=?) AS ps,"
                        + " " + dialect.now() + " AS n" + dialect.fromDual(),
                rs -> {
                    long pv = rs.getLong(1);
                    if (rs.wasNull()) {
                        pv = -1;
                    }
                    return new Stamp(pv, rs.getString(2), rs.getString(3), rs.getString(4),
                            Jdbc.instant(rs, "n").truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
                }, t, t, t, s, t, s).orElseThrow();
    }

    /**
     * The first effective-date boundary after {@code at} of any row the resolution of {@code id} depends on, or null.
     * A superset is harmless (an earlier refresh); missing a row would not be, so every source the resolver reads is
     * covered: memberships of the user, its groups and its seats; postings of the person and of every seat it was ever
     * posted to (a co-holder's posting changes who acts for a seat); approved leave of the person and of those holders.
     */
    Instant nextBoundary(Connection c, SecurityIdentity id, Instant at) {
        String t = id.tenantId();
        List<String> members = new ArrayList<>();
        members.add(SecurityIdentity.USER + id.personId());
        id.groups().forEach(g -> members.add(SecurityIdentity.GROUP + g));
        id.seats().forEach(s -> members.add(SecurityIdentity.SEAT + s));
        String in = Sql.placeholders(members.size());
        String seatsOfPerson = "SELECT seat_id FROM SEC_POSTING WHERE tenant_id=? AND person_id=?";
        String holders = "SELECT person_id FROM SEC_POSTING WHERE tenant_id=? AND seat_id IN (" + seatsOfPerson + ")";
        String postings = "FROM SEC_POSTING WHERE tenant_id=? AND (person_id=? OR seat_id IN (" + seatsOfPerson + "))";
        String leave = "FROM SEC_LEAVE WHERE tenant_id=? AND status='APPROVED' AND (person_id=? OR person_id IN (" + holders + "))";
        String sql = "SELECT MIN(b) AS b FROM ("
                + "SELECT valid_from AS b FROM AUTH_MEMBERSHIP WHERE tenant_id=? AND member_id IN (" + in + ") AND valid_from > ?"
                + " UNION ALL SELECT valid_to AS b FROM AUTH_MEMBERSHIP WHERE tenant_id=? AND member_id IN (" + in + ") AND valid_to > ?"
                + " UNION ALL SELECT valid_from AS b " + postings + " AND valid_from > ?"
                + " UNION ALL SELECT valid_to AS b " + postings + " AND valid_to > ?"
                + " UNION ALL SELECT valid_from AS b " + leave + " AND valid_from > ?"
                + " UNION ALL SELECT valid_to AS b " + leave + " AND valid_to > ?"
                + ") x";
        String p = id.personId();
        List<Object> args = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            args.add(t);
            args.addAll(members);
            args.add(at);
        }
        for (int i = 0; i < 2; i++) {
            args.addAll(List.of(t, p, t, p, at));
        }
        for (int i = 0; i < 2; i++) {
            args.addAll(List.of(t, p, t, t, p, at));
        }
        List<Instant> min = Jdbc.query(c, sql, rs -> Jdbc.instant(rs, "b"), args.toArray());   // one row; MIN may be NULL
        return min.isEmpty() ? null : min.get(0);
    }
}
