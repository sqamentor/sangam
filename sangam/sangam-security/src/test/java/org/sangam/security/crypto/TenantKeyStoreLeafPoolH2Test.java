// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.crypto;

import org.sangam.core.api.ReadWork;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.security.dialect.SecurityDialect;

/**
 * SL-161: the tenant key store is read from inside business transactions (an encrypted idempotency response or document
 * is written in the command's transaction). On the pool that the caller holds, the key read waits for a second
 * connection of that pool: with every connection held by such a caller, the pool locks up (the SL-43 pattern). The
 * key store therefore runs on a leaf pool, and one refresh per tenant serves every concurrent caller.
 * <p>
 * The "main pool" here is a bounded pool of one connection (a semaphore with a 1 s wait, as Hikari's
 * {@code connectionTimeout}); the caller holds its only connection while it encrypts.
 */
class TenantKeyStoreLeafPoolH2Test {

    static final String T = "t1";

    String url;
    Connection keepAlive;
    final Semaphore mainPool = new Semaphore(1);
    final AtomicInteger mainPoolRequests = new AtomicInteger();
    final AtomicInteger leafTransactions = new AtomicInteger();

    @BeforeEach
    void db() throws Exception {
        url = "jdbc:h2:mem:keys" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        keepAlive = DriverManager.getConnection(url, "sa", "");
        Jdbc.update(keepAlive, "CREATE TABLE SEC_TENANT_KEY (tenant_id VARCHAR(64) NOT NULL, key_id VARCHAR(80) NOT NULL,"
                + " version INTEGER NOT NULL, status VARCHAR(16) NOT NULL, kek_id VARCHAR(64) NOT NULL,"
                + " wrapped_key VARCHAR(255), created_at TIMESTAMP WITH TIME ZONE NOT NULL,"
                + " retired_at TIMESTAMP WITH TIME ZONE, destroyed_at TIMESTAMP WITH TIME ZONE,"
                + " CONSTRAINT PK_SEC_TENANT_KEY PRIMARY KEY (tenant_id, version))");
        Jdbc.update(keepAlive, "CREATE UNIQUE INDEX SEC_TENANT_KEY_ID ON SEC_TENANT_KEY (tenant_id, key_id)");
    }

    @AfterEach
    void close() throws Exception {
        keepAlive.close();
    }

    /** The bounded main pool: waits at most 1 s for its single connection, then fails like a pool timeout. */
    Transactions mainPool() {
        return new Transactions() {
            @Override
            public <R> R inTransaction(Function<Connection, R> work) {
                mainPoolRequests.incrementAndGet();
                try {
                    if (!mainPool.tryAcquire(1, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("main pool exhausted: connection is not available");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                try {
                    return transaction(work);
                } finally {
                    mainPool.release();
                }
            }

            @Override
            public <R> R read(ReadWork<R> work) {
                mainPoolRequests.incrementAndGet();
                try {
                    if (!mainPool.tryAcquire(1, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("main pool exhausted: connection is not available");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                try {
                    return readTransaction(work);
                } finally {
                    mainPool.release();
                }
            }
        };
    }

    Transactions leafPool() {
        return new Transactions() {
            @Override
            public <R> R inTransaction(Function<Connection, R> work) {
                leafTransactions.incrementAndGet();
                return transaction(work);
            }

            @Override
            public <R> R read(ReadWork<R> work) {
                leafTransactions.incrementAndGet();
                return readTransaction(work);
            }
        };
    }

    <R> R transaction(Function<Connection, R> work) {
        try (Connection c = DriverManager.getConnection(url, "sa", "")) {
            c.setAutoCommit(false);
            try {
                R r = work.apply(c);
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

    <R> R readTransaction(ReadWork<R> work) {
        try (Connection c = DriverManager.getConnection(url, "sa", "")) {
            c.setAutoCommit(false);
            try {
                R r = work.read(c);
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

    final MasterKeyProvider kek = MasterKeyProvider.ephemeralForDevelopment();

    AesGcmFieldEncryptor encryptor(Transactions keyTx, Duration ttl) {
        return new AesGcmFieldEncryptor(new JdbcTenantKeyStore(keyTx, SecurityDialect.H2, kek, ttl));
    }

    @Test
    void onTheCallersPoolTheKeyReadWaitsForASecondConnectionAndTimesOut() {
        AesGcmFieldEncryptor onMain = encryptor(mainPool(), Duration.ofSeconds(60));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> mainPool().inTransaction(c ->
                onMain.encrypt(T, "idempotency", "{\"ok\":true}")));
        assertTrue(e.getMessage().contains("main pool exhausted"), "the SL-161 hazard: " + e.getMessage());
    }

    @Test
    void onTheLeafPoolTheCallerHoldingTheOnlyMainConnectionEncryptsAndDecrypts() {
        AesGcmFieldEncryptor onLeaf = encryptor(leafPool(), Duration.ofSeconds(60));
        String plain = mainPool().inTransaction(c -> {
            String envelope = onLeaf.encrypt(T, "idempotency", "{\"ok\":true}");
            return onLeaf.decrypt(T, "idempotency", envelope);
        });
        assertEquals("{\"ok\":true}", plain);
        assertEquals(1, mainPoolRequests.get(), "only the caller's own connection was taken from the main pool");
        assertTrue(leafTransactions.get() >= 1);
    }

    @Test
    void concurrentCallersShareOneRefreshPerTenant() throws Exception {
        AesGcmFieldEncryptor onLeaf = encryptor(leafPool(), Duration.ofSeconds(60));
        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> keys = new ArrayList<>();
        try {
            for (int i = 0; i < n; i++) {
                keys.add(pool.submit(() -> {
                    start.await();
                    return org.sangam.core.security.FieldEncryptor.keyIdOf(onLeaf.encrypt(T, "doc", "x"));
                }));
            }
            start.countDown();
            for (Future<String> f : keys) {
                assertEquals(T + "/k1", f.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, leafTransactions.get(), "one key-store transaction for eight concurrent first uses");
        assertEquals(1L, Jdbc.one(keepAlive, "SELECT COUNT(*) FROM SEC_TENANT_KEY WHERE tenant_id=?",
                rs -> rs.getLong(1), T).orElse(0L));
    }

    @Test
    void anExpiredActiveKeyIsReReadOnceAndARotationIsSeen() {
        AesGcmFieldEncryptor onLeaf = encryptor(leafPool(), Duration.ZERO);
        JdbcTenantKeyStore other = new JdbcTenantKeyStore(leafPool(), SecurityDialect.H2, kek, Duration.ofSeconds(60));
        String first = org.sangam.core.security.FieldEncryptor.keyIdOf(onLeaf.encrypt(T, "doc", "x"));
        assertEquals(T + "/k1", first);
        other.rotate(T);   // another node rotates
        String second = org.sangam.core.security.FieldEncryptor.keyIdOf(onLeaf.encrypt(T, "doc", "y"));
        assertEquals(T + "/k2", second, "with a zero TTL the next use reads the active key again");
    }
}
