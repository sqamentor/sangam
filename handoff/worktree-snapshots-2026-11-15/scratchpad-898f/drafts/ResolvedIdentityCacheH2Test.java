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

    private static void sleepUntil(Instant t) throws InterruptedException {
        long ms = t.toEpochMilli() - System.currentTimeMillis();
        if (ms > 0) {
            Thread.sleep(ms);
        }
    }
}
