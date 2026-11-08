// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.clock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

/**
 * SL-37 follow-up: the D-10 one-engine-per-JVM guard must free an engine's slot when that engine closes, exactly once,
 * whoever calls release and however often. Before the fix the count was a bare counter that the Spring server never
 * decremented, and a global {@code unregister()} could decrement another engine's slot.
 */
class OperatonClockRegistrationTest {

    @Test
    void aReleasedSlotLetsTheNextEngineRegisterAndReleaseIsIdempotent() {
        int baseline = OperatonClock.liveEngines();
        OperatonClock.Registration first = OperatonClock.register(true);
        assertEquals(baseline + 1, OperatonClock.liveEngines());
        assertThrows(IllegalStateException.class, () -> OperatonClock.register(false), "second live engine refused");
        assertEquals(baseline + 1, OperatonClock.liveEngines(), "a refused registration takes no slot");
        first.release();
        first.release();
        first.close();
        assertEquals(baseline, OperatonClock.liveEngines(), "releasing twice frees exactly one slot");
        if (baseline == 0) {
            OperatonClock.register(false).release(); // sequential engines need no flag
        }
    }

    @Test
    void concurrentReleaseOfOneSlotFreesItOnce() throws Exception {
        int baseline = OperatonClock.liveEngines();
        OperatonClock.Registration other = OperatonClock.register(true);
        OperatonClock.Registration slot = OperatonClock.register(true);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> done = new ArrayList<>();
            for (int i = 0; i < 64; i++) {
                done.add(pool.submit(() -> {
                    go.await();
                    slot.release();
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> f : done) {
                f.get();
            }
            assertTrue(slot.released());
            assertEquals(baseline + 1, OperatonClock.liveEngines(), "only this slot is freed; the other engine keeps its own");
        } finally {
            pool.shutdownNow();
            other.release();
        }
        assertEquals(baseline, OperatonClock.liveEngines());
    }
}
