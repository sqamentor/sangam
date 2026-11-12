// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.sangam.sdk.SangamClient;
import org.sangam.sdk.error.StaleLeaseException;
import org.sangam.sdk.model.CompleteWorkRequest;
import org.sangam.sdk.model.ExternalWork;
import org.sangam.sdk.model.FetchAndLockRequest;
import org.sangam.sdk.model.FetchTopic;
import org.sangam.sdk.testing.SangamTestEngine;
import org.sangam.sdk.worker.SangamWorker;
import org.sangam.sdk.worker.TopicSubscription;

/**
 * T03 (SANGAM.md II §16, VI.3 rule 2): an old worker that resumes after its lease expired cannot change protected
 * state; its call gets 409 STALE_LEASE and the new lease holder completes.
 */
class ContractT03StaleWorkerTest {

    @Test
    void t03_staleCompleteIsRejectedAndTheNewHolderCompletes() throws Exception {
        try (SangamTestEngine engine = SangamTestEngine.start()) {
            String id = engine.addWork("pay", Map.of());
            CountDownLatch aStarted = new CountDownLatch(1);
            CountDownLatch releaseA = new CountDownLatch(1);
            CountDownLatch bStarted = new CountDownLatch(1);
            CountDownLatch releaseB = new CountDownLatch(1);
            AtomicLong aVersion = new AtomicLong();
            AtomicLong bVersion = new AtomicLong();
            List<ContractSupport.Log> logsA = ContractSupport.logs();
            SangamWorker a = ContractSupport.worker(engine, "worker-A", logsA).subscribe(TopicSubscription.topic("pay", ctx -> {
                aVersion.set(ctx.lockVersion());
                aStarted.countDown();
                releaseA.await();
                return Map.of("by", "A");
            }).lockDuration(Duration.ofMinutes(1)).build()).build().start();
            SangamWorker b = null;
            try {
                assertTrue(aStarted.await(10, TimeUnit.SECONDS));
                engine.advance(Duration.ofMinutes(2)); // A's lease expires (A is paused, as after a GC stop or partition)
                b = ContractSupport.worker(engine, "worker-B", ContractSupport.logs()).subscribe(TopicSubscription.topic("pay", ctx -> {
                    bVersion.set(ctx.lockVersion());
                    bStarted.countDown();
                    releaseB.await();
                    return Map.of("by", "B");
                }).lockDuration(Duration.ofMinutes(1)).build()).build().start();
                assertTrue(bStarted.await(10, TimeUnit.SECONDS), "re-offered to B after expiry");
                assertTrue(bVersion.get() > aVersion.get(), "B holds a newer lockVersion");

                releaseA.countDown(); // A resumes and reports its result
                ContractSupport.until(() -> ContractSupport.logged(logsA, "work.abandoned"), "A abandons");
                assertEquals("LOCKED", engine.state(id), "the stale result changed nothing");
                assertEquals(0, engine.completions(id));
                assertEquals(1, engine.callsOf("complete").size(), "A does not retry a 409 STALE_LEASE");

                releaseB.countDown();
                engine.awaitState(id, "COMPLETED", Duration.ofSeconds(10));
            } finally {
                releaseA.countDown();
                releaseB.countDown();
                a.close();
                if (b != null) b.close();
            }
            assertEquals(1, engine.completions(id));
            assertEquals(Map.of("by", "B"), engine.completedVariableMap(id));
        }
    }

    @Test
    void t03_staleLeaseSurfacesAsTheSdkException() {
        try (SangamTestEngine engine = SangamTestEngine.start()) {
            SangamClient c = engine.client();
            String id = engine.addWork("pay", Map.of());
            List<FetchTopic> topics = List.of(new FetchTopic("pay", 60_000L, null, null, null, null));
            ExternalWork first = c.work().fetchAndLock(new FetchAndLockRequest("A", topics, 1, 0L, null)).get(0);
            engine.advance(Duration.ofMinutes(2));
            ExternalWork second = c.work().fetchAndLock(new FetchAndLockRequest("B", topics, 1, 0L, null)).get(0);
            StaleLeaseException e = org.junit.jupiter.api.Assertions.assertThrows(StaleLeaseException.class,
                    () -> c.work().complete(id, new CompleteWorkRequest("A", first.lockVersion(), first.attemptId(),
                            Map.of(), null, null), "k"));
            assertEquals(409, e.status());
            assertEquals("STALE_LEASE", e.problem().code);
            assertEquals(false, e.isRetryable());
            c.work().complete(id, new CompleteWorkRequest("B", second.lockVersion(), second.attemptId(), Map.of(), null, null), "k2");
            assertEquals("COMPLETED", engine.state(id));
        }
    }
}
