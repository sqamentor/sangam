// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

/** M-054: per-job-type bulkheads ({@link JobBulkheads}): limits, bounded wait, stop and validation. */
class JobBulkheadsTest {

    private static final String ASYNC = "async-continuation";

    @Test
    void aTypeWithoutALimitIsNeverCounted() {
        JobBulkheads b = new JobBulkheads(Map.of(ASYNC, 1), Duration.ZERO);
        for (int i = 0; i < 5; i++) {
            assertNotNull(b.acquire("timer-transition", () -> true));
        }
        assertNotNull(b.acquire(null, () -> true), "a job that no longer exists has no type");
        assertEquals(-1, b.available("timer-transition"));
        assertFalse(b.limits("timer-transition"));
        assertFalse(b.limits(null));
    }

    @Test
    void theLimitHoldsAndAReleasedPermitIsReusable() {
        JobBulkheads b = new JobBulkheads(Map.of(ASYNC, 2), Duration.ZERO);
        JobBulkheads.Permit p1 = b.acquire(ASYNC, () -> true);
        JobBulkheads.Permit p2 = b.acquire(ASYNC, () -> true);
        assertNotNull(p1);
        assertNotNull(p2);
        assertNull(b.acquire(ASYNC, () -> true), "third concurrent execution refused at once (wait 0)");
        p1.release();
        p1.release(); // idempotent: must not add a permit
        assertEquals(1, b.available(ASYNC));
        assertNotNull(b.acquire(ASYNC, () -> true));
        assertEquals(0, b.available(ASYNC));
    }

    @Test
    void aWaitingJobGetsThePermitWhenItIsReleasedInTime() throws Exception {
        JobBulkheads b = new JobBulkheads(Map.of(ASYNC, 1), Duration.ofSeconds(5));
        JobBulkheads.Permit held = b.acquire(ASYNC, () -> true);
        CompletableFuture<JobBulkheads.Permit> waiter = CompletableFuture.supplyAsync(() -> b.acquire(ASYNC, () -> true));
        Thread.sleep(100);
        assertFalse(waiter.isDone(), "waits while the type is full");
        held.release();
        assertNotNull(waiter.get(5, TimeUnit.SECONDS));
    }

    @Test
    void theWaitIsBounded() {
        JobBulkheads b = new JobBulkheads(Map.of(ASYNC, 1), Duration.ofMillis(300));
        assertNotNull(b.acquire(ASYNC, () -> true));
        long t0 = System.nanoTime();
        assertNull(b.acquire(ASYNC, () -> true));
        long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertTrue(waitedMs >= 250 && waitedMs < 3_000, "waited about the configured 300 ms: " + waitedMs);
    }

    @Test
    void aStoppingExecutorEndsTheWaitEarly() {
        JobBulkheads b = new JobBulkheads(Map.of(ASYNC, 1), Duration.ofMinutes(5));
        assertNotNull(b.acquire(ASYNC, () -> true));
        AtomicBoolean active = new AtomicBoolean(true);
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            active.set(false);
        });
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertNull(b.acquire(ASYNC, active::get)),
                "an inactive executor stops waiting within one slice, not after five minutes");
    }

    @Test
    void anInterruptedWaitReturnsNullAndKeepsTheFlag() {
        JobBulkheads b = new JobBulkheads(Map.of(ASYNC, 1), Duration.ofMinutes(5));
        assertNotNull(b.acquire(ASYNC, () -> true));
        Thread.currentThread().interrupt();
        try {
            assertNull(b.acquire(ASYNC, () -> true));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void invalidLimitsAndWaitsAreRefused() {
        Map<String, Integer> zero = Map.of(ASYNC, 0);
        assertThrows(IllegalArgumentException.class, () -> new JobBulkheads(zero, Duration.ZERO));
        Map<String, Integer> blank = Map.of(" ", 1);
        assertThrows(IllegalArgumentException.class, () -> new JobBulkheads(blank, Duration.ZERO));
        Map<String, Integer> nullLimit = new LinkedHashMap<>();
        nullLimit.put(ASYNC, null);
        assertThrows(IllegalArgumentException.class, () -> new JobBulkheads(nullLimit, Duration.ZERO));
        Map<String, Integer> ok = Map.of(ASYNC, 1);
        assertThrows(IllegalArgumentException.class, () -> new JobBulkheads(ok, Duration.ofMillis(-1)));
        assertThrows(IllegalArgumentException.class, () -> new JobBulkheads(ok, null));
        assertTrue(new JobBulkheads(null, Duration.ZERO).isEmpty());
        assertTrue(new JobBulkheads(Map.of(), Duration.ZERO).isEmpty());
    }
}
