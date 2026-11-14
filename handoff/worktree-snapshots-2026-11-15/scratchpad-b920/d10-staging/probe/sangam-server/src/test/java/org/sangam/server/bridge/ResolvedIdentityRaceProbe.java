// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.engine.clock.OperatonClock;
import org.sangam.persistence.SangamSchema;
import org.sangam.security.SangamSecurity;
import org.sangam.security.dialect.SecurityDialect;
import org.sangam.security.identity.ResolvedIdentityCache;
import org.sangam.security.support.Sql;

/**
 * SL-67 probe (scratch in wt-d10, NOT for apply). The scenario of
 * {@code ResolvedIdentityCacheH2Test.membershipEndAndSourceRemovalAreSeenAtOnce} (membership resolved and cached, ended
 * "now", next request), repeated in one warm JVM. "Now" is either the untruncated database time (what the test used:
 * {@code Jdbc.dbNow}) or the product's database time ({@code Sql.dbNow}: A-1, truncated to milliseconds, the time the
 * resolver compares). Each iteration uses a fresh tenant on one shared H2 schema. Prints one PROBE line per variant.
 */
@TestMethodOrder(MethodOrderer.MethodName.class)
class ResolvedIdentityRaceProbe {

    static final int N = 3000;
    static JdbcDataSource h2;
    static SangamSecurity security;
    static Transactions tx;
    static ResolvedIdentityCache cache;
    static final AtomicInteger SEQ = new AtomicInteger();

    record Outcome(boolean stillMember, Instant endedAt, Instant nextRequestDbMs) {
    }

    @BeforeAll
    static void schema() throws Exception {
        h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:ricprobe" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamSchema.apply(h2);
        try (Connection c = h2.getConnection()) {
            security = new SangamSecurity(SecurityDialect.detect(c), null);
        }
        tx = Transactions.of(h2);
        cache = new ResolvedIdentityCache(security.dialect(), security.principals());
    }

    private static Outcome once(Function<Connection, Instant> now) {
        String t = "T-P" + SEQ.incrementAndGet();
        tx.inTransaction(c -> {
            Instant created = Instant.now();
            Jdbc.update(c, "INSERT INTO SEC_TENANT(tenant_id, name_en, status, config, quotas, four_eyes, created_at)"
                    + " VALUES (?,?,?,?,?,?,?)", t, "t", "ACTIVE", "{}", "{}", 0, created);
            Jdbc.update(c, "INSERT INTO SEC_PERSON(tenant_id, person_id, display_name, external_subject, status, created_at)"
                    + " VALUES (?,?,?,?,?,?)", t, "p1", "P One", "sub-1", "ACTIVE", created);
            return null;
        });
        SecurityIdentity raw = new SecurityIdentity(t, "sub-1", "sub-1", Set.of(), Set.of(), Set.of(), Set.of(), "OIDC",
                "10.0.0.1", null, false, Map.of());
        tx.inTransaction(c -> security.memberships().put(c, t, SecurityIdentity.USER + "p1", SecurityIdentity.GROUP + "g1",
                "src-1", Instant.EPOCH, null));
        assertTrue(cache.resolve(tx, raw, false, null, null).groups().contains("g1"));
        cache.resolve(tx, raw, false, null, null);   // cached
        Instant[] at = new Instant[1];
        tx.inTransaction(c -> security.memberships().end(c, t, "src-1", at[0] = now.apply(c)));
        boolean still = cache.resolve(tx, raw, false, null, null).groups().contains("g1");
        Instant after = tx.inTransaction(c -> Sql.dbNow(c, security.dialect()));
        return new Outcome(still, at[0], after);
    }

    /** Runs the scenario N times; returns {still member on the next request, end and next request in one millisecond}. */
    private static int[] run(String label, Function<Connection, Instant> now) {
        int still = 0;
        int sameMs = 0;
        int sameMsAndStill = 0;
        List<String> samples = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            Outcome o = once(now);
            boolean same = o.nextRequestDbMs().equals(o.endedAt().truncatedTo(ChronoUnit.MILLIS))
                    && !o.endedAt().equals(o.endedAt().truncatedTo(ChronoUnit.MILLIS));
            if (o.stillMember()) {
                still++;
                if (samples.size() < 3) {
                    samples.add("iteration " + i + ": ended at " + o.endedAt() + ", next request in db ms " + o.nextRequestDbMs());
                }
            }
            if (same) {
                sameMs++;
                if (o.stillMember()) {
                    sameMsAndStill++;
                }
            }
        }
        System.out.println("PROBE " + label + ": iterations=" + N + " stillMemberOnTheNextRequest=" + still
                + " endAndNextRequestInOneMillisecond(sub-ms end)=" + sameMs + " of which stillMember=" + sameMsAndStill
                + " samples=" + samples);
        return new int[] {still, sameMs, sameMsAndStill};
    }

    @Test
    void a_warmUp() {
        run("warm-up (product time)", c -> Sql.dbNow(c, security.dialect()));
    }

    @Test
    void b_untruncatedDatabaseTime_asTheTestDid() {
        int[] r = run("untruncated Jdbc.dbNow", c -> Jdbc.dbNow(c, security.dialect().core()));
        assertEquals(r[1], r[2], "every end that shares the next request's millisecond is still seen");
    }

    @Test
    void c_productDatabaseTime_asTheFixDoes() {
        int[] r = run("product Sql.dbNow", c -> Sql.dbNow(c, security.dialect()));
        assertEquals(0, r[0], "with the product's time an ended membership is never seen on the next request");
    }

    @Test
    void d_productDatabaseTime_withTheStaticEngineClockMoved() {
        OperatonClock.INSTANCE.set(Instant.parse("2001-01-01T00:00:00Z"));
        try {
            int[] r = run("product Sql.dbNow, ClockUtil at 2001", c -> Sql.dbNow(c, security.dialect()));
            assertEquals(0, r[0]);
        } finally {
            OperatonClock.INSTANCE.reset();
        }
    }

    @Test
    void e_untruncatedDatabaseTime_withTheStaticEngineClockMoved() {
        OperatonClock.INSTANCE.set(Instant.parse("2001-01-01T00:00:00Z"));
        try {
            int[] r = run("untruncated Jdbc.dbNow, ClockUtil at 2001", c -> Jdbc.dbNow(c, security.dialect().core()));
            assertEquals(r[1], r[2]);
        } finally {
            OperatonClock.INSTANCE.reset();
        }
    }
}
