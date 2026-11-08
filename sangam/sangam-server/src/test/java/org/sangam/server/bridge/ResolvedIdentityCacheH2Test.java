// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.core.security.SangamSecurityException;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.persistence.SangamSchema;
import org.sangam.security.SangamSecurity;
import org.sangam.security.dialect.SecurityDialect;
import org.sangam.security.identity.ResolvedIdentityCache;

/**
 * SG-15 T-SCALE: the per-token identity cache on a real H2 security schema. A hit costs one validation statement
 * instead of the full resolution (~10 statements); every change that can alter the resolved identity is seen on the
 * next request, exactly as without the cache: a membership change (policy version), a membership that starts later
 * (effective-date boundary), a disabled person, a suspended tenant, a relinked subject.
 */
class ResolvedIdentityCacheH2Test {

    private static final String T = "T-RIC";

    private SangamSecurity security;
    private Transactions tx;
    private ResolvedIdentityCache cache;
    private final AtomicInteger statements = new AtomicInteger();

    @BeforeEach
    void schema() throws Exception {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:ric" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamSchema.apply(h2);
        try (Connection c = h2.getConnection()) {
            security = new SangamSecurity(SecurityDialect.detect(c), null);
        }
        tx = Transactions.of(counting(h2));
        cache = new ResolvedIdentityCache(security.dialect(), security.principals());
        tx.inTransaction(c -> {
            Instant now = Instant.now();
            Jdbc.update(c, "INSERT INTO SEC_TENANT(tenant_id, name_en, status, config, quotas, four_eyes, created_at)"
                    + " VALUES (?,?,?,?,?,?,?)", T, "t", "ACTIVE", "{}", "{}", 0, now);
            Jdbc.update(c, "INSERT INTO SEC_PERSON(tenant_id, person_id, display_name, external_subject, status, created_at)"
                    + " VALUES (?,?,?,?,?,?)", T, "p1", "P One", "sub-1", "ACTIVE", now);
            return null;
        });
    }

    private DataSource counting(DataSource target) {
        return (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {DataSource.class}, (p, m, a) -> {
            Object r = m.invoke(target, a);
            if (!(r instanceof Connection c)) {
                return r;
            }
            return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class}, (p2, m2, a2) -> {
                Object r2 = m2.invoke(c, a2);
                if (r2 instanceof PreparedStatement) {
                    statements.incrementAndGet();
                }
                return r2;
            });
        });
    }

    private static SecurityIdentity token(String subject, Set<String> groups) {
        return new SecurityIdentity(T, subject, subject, groups, Set.of(), Set.of(), Set.of(), "OIDC", "10.0.0.1", null, false, Map.of());
    }

    private SecurityIdentity resolve(SecurityIdentity raw) {
        return cache.resolve(tx, raw, false, null, null);
    }

    private int statementsOf(Runnable r) {
        int before = statements.get();
        r.run();
        return statements.get() - before;
    }

    @Test
    void aRepeatedTokenCostsOneValidationStatementAndReturnsTheSameIdentity() {
        SecurityIdentity raw = token("sub-1", Set.of("clerks"));
        SecurityIdentity first = resolve(raw);   // first sight also mirrors the IdP group (bumps the policy version)
        cache.clear();
        int miss = statementsOf(() -> resolve(raw));
        SecurityIdentity[] again = new SecurityIdentity[1];
        int hit = statementsOf(() -> again[0] = resolve(raw));
        assertEquals(1, hit, "a hit is one validation read");
        assertTrue(miss > 5, "a miss resolves in full (was " + miss + ")");
        assertEquals(first, again[0]);
        assertEquals("p1", again[0].personId());
        assertTrue(again[0].groups().contains("clerks"));
    }

    @Test
    void aMembershipChangeIsSeenOnTheNextRequest() {
        SecurityIdentity raw = token("sub-1", Set.of());
        assertFalse(resolve(raw).groups().contains("auditors"));
        tx.inTransaction(c -> security.memberships().put(c, T, SecurityIdentity.USER + "p1", SecurityIdentity.GROUP + "auditors",
                "LOCAL", Instant.EPOCH, null));
        assertTrue(resolve(raw).groups().contains("auditors"), "policy_version bump invalidates");
        tx.inTransaction(c -> security.memberships().remove(c, T, SecurityIdentity.USER + "p1", SecurityIdentity.GROUP + "auditors",
                "LOCAL"));
        assertFalse(resolve(raw).groups().contains("auditors"));
    }

    @Test
    void aMembershipThatStartsLaterIsSeenFromItsStartWithoutAnyVersionBump() throws Exception {
        SecurityIdentity raw = token("sub-1", Set.of());
        Instant start = tx.inTransaction(c -> Jdbc.dbNow(c, security.dialect().core())).plusMillis(1500);
        tx.inTransaction(c -> security.memberships().put(c, T, SecurityIdentity.USER + "p1", SecurityIdentity.GROUP + "night-shift",
                "LOCAL", start, start.plusMillis(1500)));
        SecurityIdentity before = resolve(raw);
        assertFalse(before.groups().contains("night-shift"));
        assertSame(before, resolve(raw), "cached before the boundary");
        sleepUntil(start.plusMillis(100));
        assertTrue(resolve(raw).groups().contains("night-shift"), "the start boundary ends the entry");
        sleepUntil(start.plusMillis(1600));
        assertFalse(resolve(raw).groups().contains("night-shift"), "the end boundary ends the entry");
    }

    @Test
    void aDisabledPersonOrSuspendedTenantIsRefusedOnTheNextRequest() {
        SecurityIdentity raw = token("sub-1", Set.of());
        resolve(raw);
        tx.inTransaction(c -> Jdbc.update(c, "UPDATE SEC_PERSON SET status='DISABLED' WHERE tenant_id=? AND person_id=?", T, "p1"));
        SangamSecurityException e = assertThrows(SangamSecurityException.class, () -> resolve(raw));
        assertEquals("ACCOUNT_DISABLED", e.code());
        tx.inTransaction(c -> Jdbc.update(c, "UPDATE SEC_PERSON SET status='ACTIVE' WHERE tenant_id=? AND person_id=?", T, "p1"));
        resolve(raw);
        tx.inTransaction(c -> Jdbc.update(c, "UPDATE SEC_TENANT SET status='SUSPENDED' WHERE tenant_id=?", T));
        e = assertThrows(SangamSecurityException.class, () -> resolve(raw));
        assertEquals("TENANT_UNAVAILABLE", e.code());
    }

    @Test
    void aRelinkedSubjectResolvesToTheNewPerson() {
        SecurityIdentity raw = token("sub-1", Set.of());
        assertEquals("p1", resolve(raw).personId());
        tx.inTransaction(c -> {
            Jdbc.update(c, "UPDATE SEC_PERSON SET external_subject=NULL WHERE tenant_id=? AND person_id=?", T, "p1");
            Jdbc.update(c, "INSERT INTO SEC_PERSON(tenant_id, person_id, display_name, external_subject, status, created_at)"
                    + " VALUES (?,?,?,?,?,?)", T, "p2", "P Two", "sub-1", "ACTIVE", Instant.now());
            return null;
        });
        assertEquals("p2", resolve(raw).personId());
    }

    @Test
    void otherTokenClaimsNeverShareAnEntry() {
        SecurityIdentity a = resolve(token("sub-1", Set.of("a")));
        SecurityIdentity b = resolve(token("sub-1", Set.of("b")));
        assertTrue(a.groups().contains("a") && !a.groups().contains("b"));
        assertTrue(b.groups().contains("b") && !b.groups().contains("a"));
        assertEquals(List.of("p1", "p1"), List.of(a.personId(), b.personId()));
    }

    // ------------------------------------------------------------------ every product write path revokes at once

    private static final SecurityIdentity SYSTEM = SecurityIdentity.system(T, "test-admin");

    /** Resolves, applies {@code change} through a service, and returns the identity of the very next request. */
    private SecurityIdentity nextAfter(SecurityIdentity raw, java.util.function.Consumer<Connection> change) {
        SecurityIdentity before = resolve(raw);
        assertSame(before, resolve(raw), "cached before the change");
        tx.inTransaction(c -> {
            change.accept(c);
            return null;
        });
        return resolve(raw);
    }

    @Test
    void revokedAdminRoleIsRefusedOnTheVeryNextRequest() {
        SecurityIdentity raw = token("sub-1", Set.of());
        tx.inTransaction(c -> {
            security.roles().defineRole(c, SYSTEM, "tenant-admin", "Tenant admin", List.of());
            return null;
        });
        String[] binding = new String[1];
        SecurityIdentity granted = nextAfter(raw, c -> binding[0] = security.roles()
                .bind(c, SYSTEM, "tenant-admin", SecurityIdentity.USER + "p1", null, null).bindingId());
        assertTrue(granted.roles().contains("tenant-admin"), "role binding is seen at once");
        SecurityIdentity revoked = nextAfter(raw, c -> security.roles().unbind(c, SYSTEM, binding[0]));
        assertFalse(revoked.roles().contains("tenant-admin"), "unbind is seen on the very next request");
        // role bound through a group, then the group membership removed
        tx.inTransaction(c -> {
            security.groups().createGroup(c, SYSTEM, "admins", "Admins", null, "LOCAL");
            security.roles().bind(c, SYSTEM, "tenant-admin", SecurityIdentity.GROUP + "admins", null, null);
            return null;
        });
        SecurityIdentity viaGroup = nextAfter(raw, c -> security.groups().addMember(c, SYSTEM, "admins", "p1", "LOCAL"));
        assertTrue(viaGroup.groups().contains("admins") && viaGroup.roles().contains("tenant-admin"));
        SecurityIdentity removed = nextAfter(raw, c -> security.groups().removeMember(c, SYSTEM, "admins", "p1"));
        assertFalse(removed.groups().contains("admins") || removed.roles().contains("tenant-admin"));
        tx.inTransaction(c -> {
            security.groups().addMember(c, SYSTEM, "admins", "p1", "LOCAL");
            return null;
        });
        assertTrue(resolve(raw).roles().contains("tenant-admin"));
        SecurityIdentity deleted = nextAfter(raw, c -> security.groups().deleteGroup(c, SYSTEM, "admins"));
        assertFalse(deleted.groups().contains("admins") || deleted.roles().contains("tenant-admin"), "group deletion");
        assertTrue(nextAfter(raw, c -> security.roles().bind(c, SYSTEM, "tenant-admin", SecurityIdentity.USER + "p1", null, null))
                .roles().contains("tenant-admin"));
        assertFalse(nextAfter(raw, c -> security.roles().deleteRole(c, SYSTEM, "tenant-admin")).roles().contains("tenant-admin"),
                "role deletion");
    }

    @Test
    void membershipEndAndSourceRemovalAreSeenAtOnce() {
        SecurityIdentity raw = token("sub-1", Set.of());
        tx.inTransaction(c -> security.memberships().put(c, T, SecurityIdentity.USER + "p1", SecurityIdentity.GROUP + "g1",
                "src-1", Instant.EPOCH, null));
        assertTrue(resolve(raw).groups().contains("g1"));
        assertFalse(nextAfter(raw, c -> security.memberships().end(c, T, "src-1", Jdbc.dbNow(c, security.dialect().core())))
                .groups().contains("g1"), "expire (end) now");
        tx.inTransaction(c -> security.memberships().put(c, T, SecurityIdentity.USER + "p1", SecurityIdentity.GROUP + "g2",
                "src-2", Instant.EPOCH, null));
        assertTrue(resolve(raw).groups().contains("g2"));
        assertFalse(nextAfter(raw, c -> security.memberships().removeSource(c, T, "src-2")).groups().contains("g2"),
                "directory source removed");
    }

    @Test
    void postingAndLeaveChangesAreSeenAtOnce() {
        SecurityIdentity raw = token("sub-1", Set.of());
        Instant past = Instant.parse("2020-01-01T00:00:00Z");
        tx.inTransaction(c -> {
            Jdbc.update(c, "INSERT INTO SEC_SEAT(tenant_id, seat_id, designation, rank_no, pool_flag, escalation_seat_id, status,"
                    + " created_at, abolished_at) VALUES (?,?,?,?,?,?,?,?,NULL)", T, "S1", "Clerk", 1, 0, null, "ACTIVE", past);
            Jdbc.update(c, "INSERT INTO SEC_PERSON(tenant_id, person_id, display_name, external_subject, status, created_at)"
                    + " VALUES (?,?,?,?,?,?)", T, "p3", "Approver", "sub-3", "ACTIVE", past);
            return null;
        });
        String[] posting = new String[1];
        SecurityIdentity posted = nextAfter(raw, c -> posting[0] = security.postings().post(c, SYSTEM, "S1", "p1",
                org.sangam.security.org.OrgModel.PostingKind.REGULAR, past, null, "order-1", null).postingId());
        assertTrue(posted.seats().contains("S1"), "posting is seen at once");
        SecurityIdentity onLeave = nextAfter(raw, c -> {
            Instant now = Jdbc.dbNow(c, security.dialect().core());
            security.postings().recordLeave(c, SecurityIdentity.system(T, "p3").withPerson("p3"), "p1", now.minusSeconds(60),
                    now.plusSeconds(3600), null, "p1");
        });
        assertFalse(onLeave.seats().contains("S1"), "leave is seen at once");
        tx.inTransaction(c -> {   // test cleanup of the leave row (the product has no cancel path), with the version bump
            Jdbc.update(c, "DELETE FROM SEC_LEAVE WHERE tenant_id=?", T);
            return security.memberships().touch(c, T);
        });
        assertTrue(resolve(raw).seats().contains("S1"));
        SecurityIdentity relieved = nextAfter(raw, c -> security.postings().endPosting(c, SYSTEM, posting[0],
                Jdbc.dbNow(c, security.dialect().core())));
        assertFalse(relieved.seats().contains("S1"), "relieving is seen at once");
    }

    private static void sleepUntil(Instant t) throws InterruptedException {
        long ms = t.toEpochMilli() - System.currentTimeMillis();
        if (ms > 0) {
            Thread.sleep(ms);
        }
    }
}
