// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.security.oidc.JdbcReplayGuard;

/**
 * SL-138 (M-006): the CDS client jti replay guard is cluster-wide. Two guards on one database stand for two nodes: a
 * jti used on one is refused on the other until it expires; concurrent first uses admit exactly one; long issuers and
 * ids fit the hashed key; expired rows are released and purged. (Lives in sangam-server because sangam-security's test
 * tree belongs to another lane; the DDL is the changelog's 0038-02 in the H2 dialect.)
 */
class JdbcReplayGuardH2Test {

    static final Instant T0 = Instant.parse("2026-11-12T10:00:00Z");

    static final class MutableClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(T0);

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    static Transactions db() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE SEC_CLIENT_JTI (replay_key VARBINARY(32) NOT NULL, issuer VARCHAR(1000) NOT NULL,"
                    + " expires_at TIMESTAMP WITH TIME ZONE NOT NULL, first_used_at TIMESTAMP WITH TIME ZONE NOT NULL,"
                    + " CONSTRAINT PK_SEC_CLIENT_JTI PRIMARY KEY (replay_key))");
            s.execute("CREATE INDEX SEC_CLIENT_JTI_EXPIRY ON SEC_CLIENT_JTI (expires_at)");
        }
        return Transactions.of(ds);
    }

    static long rows(Transactions tx) {
        return tx.inTransaction(c -> Jdbc.one(c, "SELECT COUNT(*) FROM SEC_CLIENT_JTI", rs -> rs.getLong(1)).orElse(0L));
    }

    @Test
    void aJtiUsedOnOneNodeIsRefusedOnAnotherUntilItExpires() throws Exception {
        Transactions tx = db();
        MutableClock clock = new MutableClock();
        JdbcReplayGuard node1 = new JdbcReplayGuard(tx, Dialect.POSTGRESQL, clock, Duration.ofHours(1));
        JdbcReplayGuard node2 = new JdbcReplayGuard(tx, Dialect.POSTGRESQL, clock, Duration.ofHours(1));
        Instant exp = T0.plusSeconds(300);

        assertTrue(node1.firstUse("https://ehr.example", "jti-1", exp));
        assertFalse(node2.firstUse("https://ehr.example", "jti-1", exp), "replay on another node");
        assertFalse(node1.firstUse("https://ehr.example", "jti-1", exp), "replay on the same node");
        assertTrue(node2.firstUse("https://other.example", "jti-1", exp), "the same id of another issuer is distinct");

        clock.now.set(exp.plusSeconds(1));
        assertTrue(node2.firstUse("https://ehr.example", "jti-1", exp.plusSeconds(600)), "an expired row is released");
        assertFalse(node1.firstUse("https://ehr.example", "jti-1", exp.plusSeconds(600)));
    }

    @Test
    void concurrentFirstUsesAdmitExactlyOne() throws Exception {
        Transactions tx = db();
        Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
        List<JdbcReplayGuard> nodes = List.of(new JdbcReplayGuard(tx, Dialect.POSTGRESQL, clock),
                new JdbcReplayGuard(tx, Dialect.POSTGRESQL, clock), new JdbcReplayGuard(tx, Dialect.POSTGRESQL, clock));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> calls = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                JdbcReplayGuard g = nodes.get(i % nodes.size());
                calls.add(() -> g.firstUse("https://ehr.example", "race", T0.plusSeconds(60)));
            }
            int admitted = 0;
            for (Future<Boolean> f : pool.invokeAll(calls)) {
                admitted += f.get() ? 1 : 0;
            }
            assertEquals(1, admitted);
            assertEquals(1, rows(tx));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void longIssuersAndIdsFitAndExpiredRowsArePurged() throws Exception {
        Transactions tx = db();
        MutableClock clock = new MutableClock();
        JdbcReplayGuard guard = new JdbcReplayGuard(tx, Dialect.POSTGRESQL, clock, Duration.ZERO);
        String issuer = "https://ehr.example/" + "i".repeat(1200);
        String jti = "j".repeat(2000);
        assertTrue(guard.firstUse(issuer, jti, T0.plusSeconds(60)));
        assertFalse(guard.firstUse(issuer, jti, T0.plusSeconds(60)));
        assertTrue(guard.firstUse(issuer, jti + "x", T0.plusSeconds(60)), "ids differing in the last character are distinct");
        assertTrue(guard.firstUse("https://a.example", "short", T0.plusSeconds(600)));
        assertEquals(3, rows(tx));

        clock.now.set(T0.plusSeconds(120));
        assertTrue(guard.firstUse("https://b.example", "new", T0.plusSeconds(700)), "purge runs before this use");
        assertEquals(2, rows(tx), "the two expired rows were purged");
        assertEquals(0, guard.purgeExpired());
    }
}
