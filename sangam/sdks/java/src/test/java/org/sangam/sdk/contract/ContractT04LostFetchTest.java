// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.sangam.sdk.SangamClient;
import org.sangam.sdk.error.TransportException;
import org.sangam.sdk.model.ExternalWork;
import org.sangam.sdk.model.FetchAndLockRequest;
import org.sangam.sdk.model.FetchTopic;
import org.sangam.sdk.testing.SangamTestEngine;
import org.sangam.sdk.worker.SangamWorker;
import org.sangam.sdk.worker.TopicSubscription;

/**
 * T04 (SANGAM.md II §16, VI.3 rule 1; REST form of "the stream breaks while the worker still runs"): losing the
 * connection does not release acknowledged (LOCKED) work; only lease expiry does, and a lease holder that keeps
 * heart-beating is never re-offered.
 */
class ContractT04LostFetchTest {

    private static final List<FetchTopic> TOPICS = List.of(new FetchTopic("pay", 60_000L, null, null, null, null));

    @Test
    void t04_lockedWorkIsNotReofferedAfterALostFetchUntilItsLeaseExpires() {
        try (SangamTestEngine engine = SangamTestEngine.start()) {
            SangamClient c = engine.client();
            String id = engine.addWork("pay", Map.of());
            engine.loseNextResponse("fetch-and-lock");
            assertThrows(TransportException.class, () -> c.work().fetchAndLock(new FetchAndLockRequest("A", TOPICS, 1, 0L, null)));
            assertEquals("LOCKED", engine.state(id), "the grant happened; only its answer was lost");
            assertEquals(1L, engine.lockVersion(id));
            assertTrue(c.work().fetchAndLock(new FetchAndLockRequest("B", TOPICS, 1, 0L, null)).isEmpty(),
                    "no immediate re-dispatch of LOCKED work");
            assertTrue(c.work().fetchAndLock(new FetchAndLockRequest("A", TOPICS, 1, 0L, null)).isEmpty());
            engine.advance(Duration.ofMinutes(2));
            assertEquals("AVAILABLE", engine.state(id));
            assertEquals(2L, engine.lockVersion(id), "expiry ends the lease with lock_version + 1");
            ExternalWork again = c.work().fetchAndLock(new FetchAndLockRequest("B", TOPICS, 1, 0L, null)).get(0);
            assertEquals(3L, again.lockVersion());
            assertEquals(2, engine.grants(id));
        }
    }

    @Test
    void t04_heartbeatingHolderIsNeverReoffered() throws Exception {
        try (SangamTestEngine engine = SangamTestEngine.start()) {
            String id = engine.addWork("pay", Map.of());
            AtomicInteger runsA = new AtomicInteger();
            AtomicInteger runsB = new AtomicInteger();
            // lease 3 s, heartbeat every 1 s; the handler runs about 2.5 leases
            SangamWorker a = ContractSupport.worker(engine, "A", ContractSupport.logs()).subscribe(TopicSubscription.topic("pay", ctx -> {
                runsA.incrementAndGet();
                Thread.sleep(7_500);
                return Map.of("by", "A");
            }).lockDuration(Duration.ofSeconds(3)).build()).build().start();
            SangamWorker b = null;
            try {
                ContractSupport.until(() -> runsA.get() == 1, "A started");
                b = ContractSupport.worker(engine, "B", ContractSupport.logs()).subscribe(TopicSubscription.topic("pay", ctx -> {
                    runsB.incrementAndGet();
                    return Map.of("by", "B");
                }).lockDuration(Duration.ofSeconds(3)).build()).build().start();
                engine.awaitState(id, "COMPLETED", Duration.ofSeconds(20));
            } finally {
                a.close();
                if (b != null) b.close();
            }
            assertEquals(0, runsB.get(), "B never received the heart-beaten work");
            assertEquals(1, engine.grants(id));
            assertTrue(engine.callsOf("extend-lock").size() >= 2, "A heart-beat at lease/3");
            assertEquals(Map.of("by", "A"), engine.completedVariableMap(id));
        }
    }
}
