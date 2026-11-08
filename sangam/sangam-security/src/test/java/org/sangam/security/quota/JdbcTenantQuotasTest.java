// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.quota;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;
import org.sangam.core.quota.QuotaLimit;
import org.sangam.core.quota.TenantQuotaExceededException;

/** SL-43: database-counted per-tenant quotas (H2 in PostgreSQL mode; the PostgreSQL IT runs in sangam-server). */
class JdbcTenantQuotasTest {

    static final Dialect D = Dialect.POSTGRESQL;
    String url;
    Connection keepAlive;
    final AtomicLong now = new AtomicLong(Instant.parse("2026-11-08T10:00:00.250Z").toEpochMilli());
    final Clock clock = new Clock() {
        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(now.get());
        }
    };
    final Map<String, Map<String, Object>> overrides = new ConcurrentHashMap<>();

    @BeforeEach
    void db() throws Exception {
        url = "jdbc:h2:mem:quota" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        keepAlive = DriverManager.getConnection(url, "sa", "");
        Schema.create(keepAlive, D);
        Jdbc.update(keepAlive, "CREATE TABLE SEC_TENANT_QUOTA_WINDOW (tenant_id VARCHAR(64) NOT NULL, quota_name VARCHAR(64)"
                + " NOT NULL, window_start BIGINT NOT NULL, used BIGINT NOT NULL, refused SMALLINT NOT NULL,"
                + " PRIMARY KEY (tenant_id, quota_name, window_start))");
    }

    @AfterEach
    void close() throws Exception {
        keepAlive.close();
    }

    Transactions tx() {
        return new Transactions() {
            @Override
            public <T> T inTransaction(Function<Connection, T> work) {
                try (Connection c = DriverManager.getConnection(url, "sa", "")) {
                    c.setAutoCommit(false);
                    try {
                        T r = work.apply(c);
                        c.commit();
                        return r;
                    } catch (RuntimeException e) {
                        c.rollback();
                        throw e;
                    }
                } catch (java.sql.SQLException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
    }

    JdbcTenantQuotas quotas(Map<QuotaLimit, Long> defaults) {
        return new JdbcTenantQuotas(D, tx(), overrides::get, defaults, new AuditChain(D), clock, Duration.ZERO);
    }

    long audits(String tenant) {
        return tx().inTransaction(c -> Jdbc.one(c, "SELECT COUNT(*) FROM AUDIT_EVENT WHERE chain_id=? AND event_type=?",
                rs -> rs.getLong(1), "quota:" + tenant, JdbcTenantQuotas.AUDIT_EVENT).orElse(0L));
    }

    @Test
    void effectiveLimitIsTenantOverrideThenDeploymentDefaultThenSafeDefault() {
        JdbcTenantQuotas q = quotas(Map.of(QuotaLimit.API_CALLS_PER_MINUTE, 100L));
        overrides.put("t1", Map.of("apiCallsPerMinute", 7, "maxBatchSize", "-1"));
        assertEquals(7, q.limit("t1", QuotaLimit.API_CALLS_PER_MINUTE));
        assertEquals(QuotaLimit.UNLIMITED, q.limit("t1", QuotaLimit.BATCH_SIZE));
        assertEquals(100, q.limit("t2", QuotaLimit.API_CALLS_PER_MINUTE));
        assertEquals(QuotaLimit.STARTS_PER_SECOND.safeDefault(), q.limit("t2", QuotaLimit.STARTS_PER_SECOND));
    }

    @Test
    void rateLimitRefusesAboveTheLimitWithRetryAfterAndResetsInTheNextWindow() {
        overrides.put("t1", Map.of("startsPerSecond", 3));
        JdbcTenantQuotas q = quotas(Map.of());
        for (int i = 0; i < 3; i++) {
            q.acquire("t1", QuotaLimit.STARTS_PER_SECOND, 1);
        }
        TenantQuotaExceededException e = assertThrows(TenantQuotaExceededException.class,
                () -> q.acquire("t1", QuotaLimit.STARTS_PER_SECOND, 1));
        assertEquals(429, e.httpStatus());
        assertEquals(QuotaLimit.STARTS_PER_SECOND, e.limit());
        assertEquals(3, e.allowed());
        assertEquals(1, e.retryAfterSeconds(), "window ends within the second");
        assertEquals("TENANT_QUOTA_EXCEEDED", TenantQuotaExceededException.CODE);
        assertEquals("startsPerSecond", e.details().get("limit"));
        // other tenants are not affected
        assertDoesNotThrow(() -> q.acquire("t2", QuotaLimit.STARTS_PER_SECOND, 1));
        now.addAndGet(1000);
        assertDoesNotThrow(() -> q.acquire("t1", QuotaLimit.STARTS_PER_SECOND, 1), "new window");
    }

    @Test
    void retryAfterIsTheRestOfTheMinuteWindow() {
        overrides.put("t1", Map.of("apiCallsPerMinute", 1));
        JdbcTenantQuotas q = quotas(Map.of());
        now.set(Instant.parse("2026-11-08T10:00:15Z").toEpochMilli());
        q.acquire("t1", QuotaLimit.API_CALLS_PER_MINUTE, 1);
        TenantQuotaExceededException e = assertThrows(TenantQuotaExceededException.class,
                () -> q.acquire("t1", QuotaLimit.API_CALLS_PER_MINUTE, 1));
        assertEquals(45, e.retryAfterSeconds());
    }

    @Test
    void firstRefusalPerWindowIsAuditedOnce() {
        overrides.put("t1", Map.of("apiCallsPerMinute", 1));
        JdbcTenantQuotas q = quotas(Map.of());
        q.acquire("t1", QuotaLimit.API_CALLS_PER_MINUTE, 1);
        for (int i = 0; i < 5; i++) {
            assertThrows(TenantQuotaExceededException.class, () -> q.acquire("t1", QuotaLimit.API_CALLS_PER_MINUTE, 1));
        }
        assertEquals(1, audits("t1"), "one audit event per tenant, limit and window, not per refused call");
        now.addAndGet(60_000);
        q.acquire("t1", QuotaLimit.API_CALLS_PER_MINUTE, 1);
        assertThrows(TenantQuotaExceededException.class, () -> q.acquire("t1", QuotaLimit.API_CALLS_PER_MINUTE, 1));
        assertEquals(2, audits("t1"));
        assertTrue(tx().inTransaction(c -> new AuditChain(D).verify(c, "t1", "quota:t1")).ok(), "chain intact");
    }

    @Test
    void sizeLimitAnswers413WithoutRetryAfterAndUnlimitedDisablesTheCheck() {
        overrides.put("t1", Map.of("maxBatchSize", 10));
        overrides.put("t2", Map.of("maxBatchSize", -1));
        JdbcTenantQuotas q = quotas(Map.of());
        assertDoesNotThrow(() -> q.checkSize("t1", QuotaLimit.BATCH_SIZE, 10));
        TenantQuotaExceededException e = assertThrows(TenantQuotaExceededException.class,
                () -> q.checkSize("t1", QuotaLimit.BATCH_SIZE, 11));
        assertEquals(413, e.httpStatus());
        assertNull(e.retryAfterSeconds());
        assertEquals(11L, e.details().get("requested"));
        assertEquals(1, audits("t1"));
        assertDoesNotThrow(() -> q.checkSize("t2", QuotaLimit.BATCH_SIZE, 1_000_000));
    }

    @Test
    void invalidQuotaValuesAreRejectedAtConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> JdbcTenantQuotas.validate(Map.of("startsPerSecond", "fast")));
        assertThrows(IllegalArgumentException.class, () -> JdbcTenantQuotas.validate(Map.of("maxBatchSize", 2.5)));
        assertDoesNotThrow(() -> JdbcTenantQuotas.validate(Map.of("startsPerSecond", 5, "somethingElse", "x")));
    }

    /** Counts the units of work run through it (the leaf pool). */
    final java.util.concurrent.atomic.AtomicInteger leafUnits = new java.util.concurrent.atomic.AtomicInteger();

    Transactions countingTx() {
        Transactions t = tx();
        return new Transactions() {
            @Override
            public <T> T inTransaction(Function<Connection, T> work) {
                leafUnits.incrementAndGet();
                return t.inTransaction(work);
            }
        };
    }

    @Test
    void overridesAreReadOnTheCallersOwnConnectionWhenItHasOne() throws Exception {
        try (Connection callers = DriverManager.getConnection(url, "sa", "")) {
            List<Connection> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
            JdbcTenantQuotas q = new JdbcTenantQuotas(D, countingTx(), (c, t) -> {
                seen.add(c);
                return Map.of("maxBatchSize", 5);
            }, () -> callers, Map.of(), new AuditChain(D), clock, Duration.ZERO);
            assertEquals(5, q.limit("t1", QuotaLimit.BATCH_SIZE));
            assertEquals(5, q.limit("t1", QuotaLimit.BATCH_SIZE));
            assertEquals(List.of(callers, callers), seen, "read on the ambient connection every time (ttl 0)");
            assertEquals(0, leafUnits.get(), "no other connection taken for the override read");
        }
    }

    @Test
    void callersWithoutAConnectionShareOneOverrideReadAndAnInvalidatedReadIsNotCached() throws Exception {
        CountDownLatch inRead = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger value = new java.util.concurrent.atomic.AtomicInteger(5);
        JdbcTenantQuotas q = new JdbcTenantQuotas(D, countingTx(), (c, t) -> {
            int v = value.get();
            if (reads.incrementAndGet() == 1) {
                inRead.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return Map.of("maxBatchSize", v);
        }, null, Map.of(), new AuditChain(D), clock, Duration.ofHours(1));
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            Future<Long> first = pool.submit(() -> q.limit("t1", QuotaLimit.BATCH_SIZE));
            assertTrue(inRead.await(30, TimeUnit.SECONDS));
            List<Future<Long>> joiners = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                joiners.add(pool.submit(() -> q.limit("t1", QuotaLimit.BATCH_SIZE)));
            }
            Thread.sleep(200);
            assertEquals(1, reads.get(), "callers arriving during a read wait for it instead of reading again");
            // a configure commits while the first read is still running: that read must not be cached
            value.set(9);
            q.invalidate("t1");
            Future<Long> afterChange = pool.submit(() -> q.limit("t1", QuotaLimit.BATCH_SIZE));
            release.countDown();
            assertEquals(5L, first.get(30, TimeUnit.SECONDS));
            for (Future<Long> j : joiners) {
                assertEquals(5L, j.get(30, TimeUnit.SECONDS));
            }
            assertEquals(9L, afterChange.get(30, TimeUnit.SECONDS), "a caller after the change reads the new value");
            assertEquals(9L, q.limit("t1", QuotaLimit.BATCH_SIZE), "the cache holds the new value, not the old read");
            assertEquals(2, reads.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void twoNodesSharingTheDatabaseNeverAdmitMoreThanTheLimit() throws Exception {
        overrides.put("t1", Map.of("apiCallsPerMinute", 40));
        // two independent instances = two nodes; only the database is shared
        List<JdbcTenantQuotas> nodes = List.of(quotas(Map.of()), quotas(Map.of()));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> calls = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            JdbcTenantQuotas node = nodes.get(i % 2);
            calls.add(pool.submit(() -> {
                go.await();
                try {
                    node.acquire("t1", QuotaLimit.API_CALLS_PER_MINUTE, 1);
                    return true;
                } catch (TenantQuotaExceededException e) {
                    return false;
                }
            }));
        }
        go.countDown();
        int admitted = 0;
        for (Future<Boolean> f : calls) {
            if (f.get(60, TimeUnit.SECONDS)) {
                admitted++;
            }
        }
        pool.shutdown();
        assertEquals(40, admitted, "exactly the limit, across both nodes");
        long used = tx().inTransaction(c -> Jdbc.one(c, "SELECT SUM(used) FROM SEC_TENANT_QUOTA_WINDOW WHERE tenant_id='t1'",
                rs -> rs.getLong(1)).orElseThrow());
        assertEquals(40L, used);
    }
}
