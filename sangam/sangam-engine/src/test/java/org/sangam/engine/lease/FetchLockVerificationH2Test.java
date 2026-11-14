// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.lease;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.api.EngineAdapter.ExternalWork;
import org.sangam.core.api.StaleLeaseException;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.SangamEngineFactory;
import org.sangam.engine.SangamEngineSettings;

/**
 * Decision 147 (W4Q-02, M-106 D-19): verification of the 0.1.0 baseline fetch locking ({@code SANGAM_FETCH_LOCK}
 * per-topic row lock plus the {@code SANGAM_LEASE} compare-and-set, {@link SangamLeaseTable}) on H2: atomic claiming
 * under competing and overlapping fetches, exclusive ownership during a valid lease, renewal, expiry, worker-crash
 * recovery, rejection of stale-worker completion, rollback, retries, bounded batches and no starvation. Tenant
 * isolation of worker calls is covered at the REST boundary ({@code WorkerTenantIsolationH2Test} in sangam-server);
 * vendor evidence is {@code FetchLockVerificationIT} (PostgreSQL, MySQL, Oracle, SQL Server lanes). Written, NOT_RUN.
 *
 * <p>Fetch locking is not exactly-once: a worker that loses its lease may already have caused an external effect;
 * that stays protected by the work's {@code operationId} (idempotency), never by the lease.
 *
 * <p><b>Deferred optimisation, acceptance criteria (decision 147).</b> A {@code SKIP LOCKED} (or equivalent) fetch
 * goes behind a replaceable database adapter with the same claiming contract and is enabled per database and version
 * only when: (1) every test of this class and of {@code FetchLockVerificationIT} passes unchanged on that database and
 * version; (2) under the measured contention profile (at least 8 competing workers, 1,000 open items, one topic) no item
 * is delivered to two live leases and every item is delivered; (3) p95 fetch latency and throughput are measured
 * against the baseline on the same hardware and the adapter is better on both, with the numbers recorded; (4) a
 * crash-and-expiry run shows the same recovery as the baseline. Until all four hold, the baseline stays. No support claim
 * is made for a database whose lane has not run.
 */
class FetchLockVerificationH2Test {

    private static final String TOPIC = "verify";

    private static final String MODEL = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="t">
              <process id="fl" isExecutable="true">
                <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="x"/>
                <serviceTask id="x" operaton:type="external" operaton:topic="verify"/>
                <sequenceFlow id="f2" sourceRef="x" targetRef="e"/><endEvent id="e"/>
              </process>
            </definitions>
            """;

    private OperatonEngineAdapter a;
    private Instant now;

    @BeforeEach
    void start() {
        SangamEngineSettings s = new SangamEngineSettings();
        s.setSystemAuditScope("system:platform"); // decision 144: required at engine start
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true");
        s.setApplyEnvironment(false);
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:fl" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
                + ";LOCK_TIMEOUT=10000"); // competing fetches wait on SANGAM_FETCH_LOCK instead of timing out
        a =SangamEngineFactory.build(ds, s, "h2");
        now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        a.setClock(now);
        a.deploy("t1", "fl-" + UUID.randomUUID(), Map.of("fl.bpmn", MODEL.getBytes(StandardCharsets.UTF_8)));
    }

    @AfterEach
    void stop() {
        if (a != null) {
            a.close();
        }
    }

    private List<String> startInstances(int n) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ids.add(a.startProcess("t1", "fl", "FL-" + i + "-" + UUID.randomUUID(), Map.of()));
        }
        return ids;
    }

    private void advance(Duration d) {
        now = now.plus(d);
        a.setClock(now);
    }

    @Test
    void competingWorkersClaimEveryItemExactlyOnce() throws Exception {
        startInstances(20);
        int workers = 4;
        CyclicBarrier barrier = new CyclicBarrier(workers);
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        Map<String, String> owner = new ConcurrentHashMap<>();
        List<String> duplicates = java.util.Collections.synchronizedList(new ArrayList<>());
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int w = 0; w < workers; w++) {
                String worker = "w" + w;
                results.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS); // overlapping fetches of one topic
                    int got = 0;
                    for (int round = 0; round < 10; round++) {
                        for (ExternalWork item : a.fetchAndLock(worker, TOPIC, 3, Duration.ofMinutes(5))) {
                            assertTrue(item.lockVersion() > 0);
                            if (owner.putIfAbsent(item.id(), worker) != null) {
                                duplicates.add(item.id());
                            }
                            got++;
                        }
                    }
                    return got;
                }));
            }
            int total = 0;
            for (Future<Integer> f : results) {
                int got = f.get(60, TimeUnit.SECONDS);
                assertTrue(got <= 30, "a fetch never returns more than maxItems");
                total += got;
            }
            assertEquals(List.of(), duplicates, "no item is held by two live leases");
            assertEquals(20, total, "every item delivered, none twice, none starved");
            assertEquals(20, owner.size());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void batchesAreBoundedAndRepeatedFetchesDrainTheTopic() {
        startInstances(7);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            List<ExternalWork> batch = a.fetchAndLock("w1", TOPIC, 3, Duration.ofMinutes(5));
            assertTrue(batch.size() <= 3);
            batch.forEach(w -> assertTrue(seen.add(w.id()), "an item under a valid lease is not offered again"));
        }
        assertEquals(7, seen.size());
        assertEquals(List.of(), a.fetchAndLock("w2", TOPIC, 3, Duration.ofMinutes(5)), "nothing left for another worker");
    }

    @Test
    void renewalKeepsOwnershipAndExpiryHandsTheItemOnWithAStaleCompletionRejected() {
        startInstances(1);
        ExternalWork first = a.fetchAndLock("w1", TOPIC, 1, Duration.ofMinutes(1)).getFirst();
        a.extendLock(first.id(), "w1", first.lockVersion(), Duration.ofMinutes(10));
        advance(Duration.ofMinutes(5));
        assertEquals(List.of(), a.fetchAndLock("w2", TOPIC, 1, Duration.ofMinutes(1)), "renewed: still exclusively w1's");
        assertEquals(StaleLeaseException.Reason.NOT_OWNER, assertThrows(StaleLeaseException.class,
                () -> a.completeExternal(first.id(), "w2", first.lockVersion(), Map.of())).reason());

        advance(Duration.ofMinutes(6)); // past the renewed expiry: w1 crashed and never came back
        assertEquals(StaleLeaseException.Reason.EXPIRED, assertThrows(StaleLeaseException.class,
                () -> a.extendLock(first.id(), "w1", first.lockVersion(), Duration.ofMinutes(1))).reason());
        ExternalWork second = a.fetchAndLock("w2", TOPIC, 1, Duration.ofMinutes(1)).getFirst();
        assertEquals(first.id(), second.id(), "crash recovery: the expired item is offered again");
        assertTrue(second.lockVersion() > first.lockVersion(), "a new fencing token");
        assertTrue(!second.attemptId().equals(first.attemptId()));
        assertEquals(first.operationId(), second.operationId(), "the operation identity survives the re-offer");

        StaleLeaseException late = assertThrows(StaleLeaseException.class,
                () -> a.completeExternal(first.id(), "w1", first.lockVersion(), Map.of("late", true)));
        assertTrue(late.reason() == StaleLeaseException.Reason.NOT_OWNER
                || late.reason() == StaleLeaseException.Reason.STALE_VERSION, "the stale worker is rejected: " + late);
        a.completeExternal(second.id(), "w2", second.lockVersion(), Map.of("done", true));
        assertEquals(List.of(), a.fetchAndLock("w3", TOPIC, 1, Duration.ofMinutes(1)));
        assertEquals(StaleLeaseException.Reason.NO_LEASE, assertThrows(StaleLeaseException.class,
                () -> a.completeExternal(second.id(), "w2", second.lockVersion(), Map.of())).reason(),
                "a repeat after completion changes nothing");
    }

    @Test
    void aRolledBackLeasedTransactionLeavesTheLeaseAndTheEngineUnchanged() {
        String pid = startInstances(1).getFirst();
        ExternalWork w = a.fetchAndLock("w1", TOPIC, 1, Duration.ofMinutes(5)).getFirst();
        assertThrows(IllegalStateException.class, () -> a.inLeasedTransaction(w.id(), "w1", w.lockVersion(), conn -> {
            throw new IllegalStateException("product work failed");
        }));
        assertEquals(List.of(), a.fetchAndLock("w2", TOPIC, 1, Duration.ofMinutes(5)), "still leased to w1");
        a.completeExternal(w.id(), "w1", w.lockVersion(), Map.of());
        assertTrue(a.getInstance(pid).ended(), "the same lease completes after the rollback");
    }

    @Test
    void retriesKeepTheItemAvailableUntilTheyRunOut() {
        startInstances(1);
        ExternalWork w = a.fetchAndLock("w1", TOPIC, 1, Duration.ofMinutes(5)).getFirst();
        a.failExternal(w.id(), "w1", w.lockVersion(), "transient", 1, Duration.ZERO);
        assertEquals(StaleLeaseException.Reason.STALE_VERSION, assertThrows(StaleLeaseException.class,
                () -> a.completeExternal(w.id(), "w1", w.lockVersion(), Map.of())).reason(),
                "the failure ended the lease and moved the version");
        ExternalWork again = a.fetchAndLock("w2", TOPIC, 1, Duration.ofMinutes(5)).getFirst();
        assertEquals(w.id(), again.id());
        assertEquals(1, again.retries());
        assertTrue(again.lockVersion() > w.lockVersion());
        a.failExternal(again.id(), "w2", again.lockVersion(), "permanent", 0, Duration.ZERO);
        assertEquals(List.of(), a.fetchAndLock("w3", TOPIC, 1, Duration.ofMinutes(5)), "no retries left: not offered");
    }
}
