// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * M-053: the waiting half of the non-blocking worker long-poll. The old loop's schedule is kept (R1: the poll is
 * authoritative), a wake-up polls at once, the deadline answers an empty list, the per-node bound is enforced, the
 * request context is carried explicitly to the poll thread, and work found for a request that is gone is handed back.
 */
class WorkLongPollTest {

    static final WorkLongPoll.Settings FAST = new WorkLongPoll.Settings(10, 2, Duration.ofMillis(10), Duration.ofMillis(40));

    /** Captures the outcome of one waiter. */
    static final class Sink<T> implements WorkLongPoll.Outcome<T> {
        final CompletableFuture<List<T>> result = new CompletableFuture<>();
        final CompletableFuture<Throwable> error = new CompletableFuture<>();
        volatile boolean accept = true;

        @Override
        public boolean result(List<T> items) {
            return accept && result.complete(items);
        }

        @Override
        public boolean error(Throwable e) {
            return error.complete(e);
        }
    }

    @Test
    void pollsUntilWorkIsFoundAndAnswersIt() throws Exception {
        try (WorkLongPoll poll = new WorkLongPoll(FAST, List.of())) {
            AtomicInteger calls = new AtomicInteger();
            Sink<String> sink = new Sink<>();
            assertNotNull(poll.await(5_000, () -> calls.incrementAndGet() < 4 ? List.of() : List.of("w1"), sink, null));
            assertEquals(List.of("w1"), sink.result.get(5, TimeUnit.SECONDS));
            assertEquals(4, calls.get());
            assertEquals(0, poll.waiting(), "a finished waiter is removed");
        }
    }

    @Test
    void aWakeUpPollsAtOnceInsteadOfWaitingForTheAuthoritativePoll() throws Exception {
        // a 2 s poll interval: only the wake-up can answer within the assertion's 1 s
        WorkLongPoll.Settings slow = new WorkLongPoll.Settings(10, 2, Duration.ofSeconds(2), Duration.ofSeconds(2));
        try (WorkLongPoll poll = new WorkLongPoll(slow, List.of())) {
            AtomicBoolean ready = new AtomicBoolean();
            Sink<String> sink = new Sink<>();
            poll.await(30_000, () -> ready.get() ? List.of("w1") : List.of(), sink, null);
            ready.set(true);
            long t0 = System.nanoTime();
            poll.wakeUp();
            assertEquals(List.of("w1"), sink.result.get(1, TimeUnit.SECONDS));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 1_000);
        }
    }

    @Test
    void theDeadlineAnswersAnEmptyListAfterALastAttempt() throws Exception {
        try (WorkLongPoll poll = new WorkLongPoll(FAST, List.of())) {
            AtomicInteger calls = new AtomicInteger();
            Sink<String> sink = new Sink<>();
            long t0 = System.nanoTime();
            poll.await(120, () -> {
                calls.incrementAndGet();
                return List.of();
            }, sink, null);
            assertEquals(List.of(), sink.result.get(5, TimeUnit.SECONDS));
            long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            assertTrue(took >= 100, "not before the deadline: " + took);
            assertTrue(calls.get() >= 3, "polled on the schedule up to the deadline: " + calls.get());
        }
    }

    @Test
    void theWaiterBoundPerNodeIsEnforced() throws Exception {
        WorkLongPoll.Settings one = new WorkLongPoll.Settings(1, 1, Duration.ofMillis(10), Duration.ofMillis(20));
        try (WorkLongPoll poll = new WorkLongPoll(one, List.of())) {
            AtomicBoolean ready = new AtomicBoolean();
            Sink<String> first = new Sink<>();
            assertNotNull(poll.await(5_000, () -> ready.get() ? List.of("w") : List.of(), first, null));
            assertNull(poll.await(5_000, List::of, new Sink<>(), null), "above the bound the caller waits itself");
            assertEquals(1, poll.waiting());
            ready.set(true);
            first.result.get(5, TimeUnit.SECONDS);
            assertEquals(0, poll.waiting());
            Sink<String> next = new Sink<>();
            assertNotNull(poll.await(50, List::of, next, null), "the slot is free again");
            next.result.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void aPollErrorIsTheRequestsError() throws Exception {
        try (WorkLongPoll poll = new WorkLongPoll(FAST, List.of())) {
            IllegalStateException boom = new IllegalStateException("409 STALE_LEASE");
            Sink<String> sink = new Sink<>();
            poll.await(5_000, () -> {
                throw boom;
            }, sink, null);
            assertSame(boom, sink.error.get(5, TimeUnit.SECONDS));
            assertEquals(0, poll.waiting());
        }
    }

    @Test
    void theRequestContextIsCapturedOnTheRequestThreadAndBoundAroundEveryPoll() throws Exception {
        ThreadLocal<String> tenant = new ThreadLocal<>();
        WorkLongPoll.ContextCarrier carrier = () -> {
            String captured = tenant.get();
            return new WorkLongPoll.ContextCarrier.Scope() {
                @Override
                public <T> T call(Supplier<T> work) {
                    String previous = tenant.get();
                    tenant.set(captured);
                    try {
                        return work.get();
                    } finally {
                        tenant.set(previous);
                    }
                }
            };
        };
        try (WorkLongPoll poll = new WorkLongPoll(FAST, List.of(carrier))) {
            AtomicReference<String> seenTenant = new AtomicReference<>();
            AtomicReference<String> seenMdc = new AtomicReference<>();
            AtomicReference<Thread> pollThread = new AtomicReference<>();
            tenant.set("T1");
            MDC.put("tenant", "T1");
            Sink<String> sink = new Sink<>();
            try {
                poll.await(5_000, () -> {
                    seenTenant.set(tenant.get());
                    seenMdc.set(MDC.get("tenant"));
                    pollThread.set(Thread.currentThread());
                    return List.of("w");
                }, sink, null);
            } finally {
                tenant.remove();
                MDC.remove("tenant");
            }
            sink.result.get(5, TimeUnit.SECONDS);
            assertEquals("T1", seenTenant.get());
            assertEquals("T1", seenMdc.get());
            assertTrue(pollThread.get() != Thread.currentThread(), "the poll ran on a poll thread");
            // a later waiter captured nothing: its polls see none of the first request's state
            AtomicReference<String> laterTenant = new AtomicReference<>("unset");
            AtomicReference<String> laterMdc = new AtomicReference<>("unset");
            Sink<String> probe = new Sink<>();
            poll.await(5_000, () -> {
                laterTenant.set(tenant.get());
                laterMdc.set(MDC.get("tenant"));
                return List.of("x");
            }, probe, null);
            probe.result.get(5, TimeUnit.SECONDS);
            assertNull(laterTenant.get(), "no tenant leaks from one request's poll to another's");
            assertNull(laterMdc.get(), "no MDC leaks from one request's poll to another's");
        }
    }

    @Test
    void workFoundAfterTheRequestEndedIsHandedBack() throws Exception {
        try (WorkLongPoll poll = new WorkLongPoll(FAST, List.of())) {
            CountDownLatch polling = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CompletableFuture<List<String>> handedBack = new CompletableFuture<>();
            Sink<String> sink = new Sink<>();
            WorkLongPoll.Waiter w = poll.await(5_000, () -> {
                polling.countDown();
                await(release);
                return List.of("w1");
            }, sink, handedBack::complete);
            assertTrue(polling.await(5, TimeUnit.SECONDS));
            w.cancel();   // the request ended (client gone) while the poll was leasing work
            release.countDown();
            assertEquals(List.of("w1"), handedBack.get(5, TimeUnit.SECONDS));
            assertTrue(!sink.result.isDone(), "no outcome after cancel");
        }
    }

    @Test
    void anOutcomeThatCannotBeDeliveredIsHandedBack() throws Exception {
        try (WorkLongPoll poll = new WorkLongPoll(FAST, List.of())) {
            CompletableFuture<List<String>> handedBack = new CompletableFuture<>();
            Sink<String> sink = new Sink<>();
            sink.accept = false;    // e.g. the async request already timed out
            poll.await(5_000, () -> List.of("w1"), sink, handedBack::complete);
            assertEquals(List.of("w1"), handedBack.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void onePollPerWaiterAtATimeAndOneMoreAfterAWakeUpDuringIt() throws Exception {
        WorkLongPoll.Settings slow = new WorkLongPoll.Settings(10, 4, Duration.ofSeconds(5), Duration.ofSeconds(5));
        try (WorkLongPoll poll = new WorkLongPoll(slow, List.of())) {
            AtomicInteger running = new AtomicInteger();
            AtomicInteger maxRunning = new AtomicInteger();
            AtomicInteger calls = new AtomicInteger();
            CountDownLatch inFirst = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Sink<String> sink = new Sink<>();
            poll.await(30_000, () -> {
                int n = calls.incrementAndGet();
                maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
                try {
                    if (n == 1) {
                        inFirst.countDown();
                        await(release);
                        return List.of();
                    }
                    return List.of("w");
                } finally {
                    running.decrementAndGet();
                }
            }, sink, null);
            poll.wakeUp();                  // starts poll 1 at once (the timer is 5 s)
            assertTrue(inFirst.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 20; i++) {
                poll.wakeUp();              // all collapse into one follow-up poll
            }
            release.countDown();
            assertEquals(List.of("w"), sink.result.get(5, TimeUnit.SECONDS));
            assertEquals(1, maxRunning.get(), "never two polls of one waiter at once");
            assertEquals(2, calls.get(), "exactly one follow-up poll");
        }
    }

    @Test
    void closingAnswersEveryWaiterWithAnEmptyList() throws Exception {
        WorkLongPoll poll = new WorkLongPoll(FAST, List.of());
        Sink<String> sink = new Sink<>();
        poll.await(30_000, List::of, sink, null);
        poll.close();
        assertEquals(List.of(), sink.result.get(5, TimeUnit.SECONDS));
        assertNull(poll.await(1_000, List::of, new Sink<>(), null), "a closed long-poll takes no waiter");
    }

    @Test
    void settingsAreValidatedAndDefaultToTheOldSchedule() {
        WorkLongPoll.Settings d = WorkLongPoll.Settings.defaults();
        assertEquals(Duration.ofMillis(100), d.firstInterval());
        assertEquals(Duration.ofSeconds(1), d.maxInterval(), "R1: the 1 s poll stays authoritative");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new WorkLongPoll.Settings(1, 0, Duration.ofMillis(10), Duration.ofMillis(10)));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new WorkLongPoll.Settings(1, 1, Duration.ofMillis(20), Duration.ofMillis(10)));
    }

    static void await(CountDownLatch l) {
        try {
            l.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
