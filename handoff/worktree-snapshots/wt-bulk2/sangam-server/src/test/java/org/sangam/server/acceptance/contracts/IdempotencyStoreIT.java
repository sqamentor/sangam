// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.contracts;

import org.sangam.core.layer.*;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.IdempotencyStore.BeginResult;
import org.sangam.core.layer.IdempotencyStore.Conflict;
import org.sangam.core.layer.IdempotencyStore.InProgress;
import org.sangam.core.layer.IdempotencyStore.New;
import org.sangam.core.layer.IdempotencyStore.Replay;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** X.2.2: claim, same-transaction completion, replay, 422 conflicts, takeover, outcome classification. */
class IdempotencyStoreIT extends LayerSpecSupport {

    final IdempotencyStore store = new IdempotencyStore(PG, TX, "node-a", Duration.ofSeconds(30), Duration.ofHours(24), Duration.ofDays(30));
    final byte[] fp = IdempotencyStore.fingerprint("process.start", "POST", "/process-definitions/{key}/start",
            Map.of("key", "bill"), Map.of(), Map.of("amount", "1250.50", "payee", "P-17"));

    @BeforeAll
    void businessTable() throws SQLException {
        ensureIso();
        try (Connection c = DS.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS BUSINESS_START (id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(64))");
        }
    }

    @BeforeEach
    void clearBusiness() {
        run(c -> Jdbc.update(c, "DELETE FROM BUSINESS_START"));
    }

    @Test
    void concurrentDuplicateBeginFromTwoThreadsYieldsExactlyOneNew() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 50; round++) {
                String key = "k-" + round;
                CyclicBarrier barrier = new CyclicBarrier(2);
                List<Future<BeginResult>> fs = new ArrayList<>();
                for (int t = 0; t < 2; t++) {
                    fs.add(pool.submit(() -> {
                        barrier.await();
                        return store.begin("t1", "process.start", key, "alice", fp);
                    }));
                }
                int news = 0, inProgress = 0;
                for (Future<BeginResult> f : fs) {
                    BeginResult r = f.get();
                    if (r instanceof New) news++;
                    if (r instanceof InProgress ip) {
                        inProgress++;
                        assertEquals(409, ip.httpStatus());
                    }
                }
                assertEquals(1, news, "round " + round);
                assertEquals(1, inProgress, "round " + round);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void fiftyConcurrentIdenticalRequestsExecuteOnceAndReplayByteIdentical() throws Exception {
        int n = 20;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        byte[] body = "{\"instanceId\":\"root-1\"}".getBytes(StandardCharsets.UTF_8);
        try {
            CyclicBarrier barrier = new CyclicBarrier(n);
            List<Future<BeginResult>> fs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                fs.add(pool.submit(() -> {
                    barrier.await();
                    BeginResult r = store.begin("t1", "process.start", "k-50", "alice", fp);
                    if (r instanceof New nw) {
                        run(c -> {      // the command transaction: engine work, then the L6 write last
                            Jdbc.update(c, "INSERT INTO BUSINESS_START VALUES ('root-1','t1')");
                            store.complete(c, nw.claim(), 201, body, "root-1");
                        });
                    }
                    return r;
                }));
            }
            int news = 0;
            for (Future<BeginResult> f : fs) {
                BeginResult r = f.get();
                if (r instanceof New) news++;
                else if (r instanceof Replay rp) assertArrayEquals(body, rp.body());
                else assertInstanceOf(InProgress.class, r);
            }
            assertEquals(1, news);
            assertEquals(1L, txLong(c -> Jdbc.one(c, "SELECT COUNT(*) FROM BUSINESS_START", rs -> rs.getLong(1)).orElseThrow()));
            Replay rp = (Replay) store.begin("t1", "process.start", "k-50", "alice", fp);
            assertEquals(201, rp.httpStatus());
            assertArrayEquals(body, rp.body());
            assertEquals("root-1", rp.resourceRef());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void completeCommitsOrRollsBackWithTheCommand() {
        New nw = (New) store.begin("t1", "process.start", "k-rb", "alice", fp);
        assertThrows(IllegalStateException.class, () -> run(c -> {
            Jdbc.update(c, "INSERT INTO BUSINESS_START VALUES ('root-x','t1')");
            store.complete(c, nw.claim(), 201, "{}".getBytes(), "root-x");
            throw new IllegalStateException("engine failure after the L6 write");
        }));
        // neither the engine row nor COMPLETED survived (P-1); the claim is still IN_PROGRESS
        assertEquals(0L, txLong(c -> Jdbc.one(c, "SELECT COUNT(*) FROM BUSINESS_START", rs -> rs.getLong(1)).orElseThrow()));
        assertInstanceOf(InProgress.class, store.begin("t1", "process.start", "k-rb", "alice", fp));
    }

    @Test
    void differentPayloadOrPrincipalIsA422WithoutDisclosure() {
        New nw = (New) store.begin("t1", "process.start", "k-c", "alice", fp);
        run(c -> store.complete(c, nw.claim(), 201, "{\"secret\":1}".getBytes(), "root-9"));
        byte[] other = IdempotencyStore.fingerprint("process.start", "POST", "/process-definitions/{key}/start",
                Map.of("key", "bill"), Map.of(), Map.of("amount", "9999.00", "payee", "P-17"));
        Conflict reused = (Conflict) store.begin("t1", "process.start", "k-c", "alice", other);
        assertEquals(422, reused.httpStatus());
        assertEquals("IDEMPOTENCY_KEY_REUSED", reused.code());
        Conflict principal = (Conflict) store.begin("t1", "process.start", "k-c", "mallory", fp);
        assertEquals("IDEMPOTENCY_KEY_PRINCIPAL_MISMATCH", principal.code());
        // same key in another scope (tenant or command) is independent
        assertInstanceOf(New.class, store.begin("t2", "process.start", "k-c", "alice", fp));
        assertInstanceOf(New.class, store.begin("t1", "task.complete", "k-c", "alice", fp));
    }

    @Test
    void expiredClaimIsTakenOverAndTheOldOwnerIsFencedOut() throws Exception {
        IdempotencyStore shortClaims = new IdempotencyStore(PG, TX, "node-b", Duration.ofMillis(150), Duration.ofHours(1), Duration.ofDays(1));
        New first = (New) shortClaims.begin("t1", "process.start", "k-t", "alice", fp);
        Thread.sleep(400);
        New second = (New) shortClaims.begin("t1", "process.start", "k-t", "alice", fp);
        assertEquals(first.claim().epoch() + 1, second.claim().epoch());
        assertThrows(IdempotencyStore.ClaimLostException.class,
                () -> run(c -> shortClaims.complete(c, first.claim(), 201, "{}".getBytes(), "r")));
        run(c -> shortClaims.complete(c, second.claim(), 201, "{\"ok\":2}".getBytes(), "r2"));
        assertEquals("r2", ((Replay) shortClaims.begin("t1", "process.start", "k-t", "alice", fp)).resourceRef());
    }

    @Test
    void outcomeClassificationAndCompaction() throws Exception {
        // deterministic 422 business error: stored, replayed
        New a = (New) store.begin("t1", "task.complete", "k-422", "alice", fp);
        store.failFinal(a.claim(), 422, "{\"error\":\"FORM_INVALID\"}".getBytes());
        Replay ra = (Replay) store.begin("t1", "task.complete", "k-422", "alice", fp);
        assertEquals(422, ra.httpStatus());

        // transient 503 / 403: not stored, an immediate retry executes
        New b = (New) store.begin("t1", "task.complete", "k-503", "alice", fp);
        store.abandon(b.claim());
        assertInstanceOf(New.class, store.begin("t1", "task.complete", "k-503", "alice", fp));

        // compaction after response retention, then key expiry
        // margins wide enough for a slower database round trip (MySQL on Docker Desktop: complete -> cleanup ~100 ms more)
        IdempotencyStore shortRetention = new IdempotencyStore(PG, TX, "node-c", Duration.ofSeconds(30), Duration.ofMillis(300), Duration.ofMillis(2000));
        New d = (New) shortRetention.begin("t1", "process.start", "k-ret", "alice", fp);
        run(c -> shortRetention.complete(c, d.claim(), 201, "{\"big\":true}".getBytes(), "root-r"));
        Thread.sleep(600);
        shortRetention.cleanup();
        Replay compact = (Replay) shortRetention.begin("t1", "process.start", "k-ret", "alice", fp);
        assertTrue(compact.compacted());
        assertEquals(null, compact.body());
        assertEquals("root-r", compact.resourceRef());
        Thread.sleep(1600);
        shortRetention.cleanup();
        assertInstanceOf(New.class, shortRetention.begin("t1", "process.start", "k-ret", "alice", fp));
    }

    @Test
    void invalidKeysAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> store.begin("t1", "process.start", "", fp));
        assertThrows(IllegalArgumentException.class, () -> store.begin("t1", "process.start", "x".repeat(129), fp));
        assertThrows(IllegalArgumentException.class, () -> store.begin("t1", "process.start", "naïve", fp));
    }
}
