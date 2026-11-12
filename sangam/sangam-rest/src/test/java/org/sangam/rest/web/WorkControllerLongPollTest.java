// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.sangam.rest.adapter.operaton.FakeWorkPort;
import org.sangam.rest.adapter.operaton.TrackingWorkPort;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.WorkPort;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.WorkLongPoll;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.web.context.request.async.DeferredResult;

/**
 * M-053 at the controller: the first attempt runs in the request thread (work that is there, and errors, come back
 * exactly as before), an empty first attempt with a wait becomes an async response answered by the long-poll (a
 * wake-up answers at once), the answer is the same list, the per-node bound falls back to the blocking wait, and
 * work found for a request that is gone is released with its own fencing token.
 */
class WorkControllerLongPollTest {

    static final RequestContext CTX = new RequestContext("T1", "worker", Set.of("sangam-worker"), null, null, null, null,
            null, "API_KEY");

    static final PermissionPort ALLOW = new PermissionPort() {
        @Override
        public boolean has(RequestContext ctx, String permission) {
            return true;
        }

        @Override
        public Set<String> permissions(RequestContext ctx) {
            return Set.of();
        }

        @Override
        public boolean mayReplay(RequestContext ctx, String commandType, String resourceRef) {
            return true;
        }
    };

    final FakeWorkPort fake = new FakeWorkPort();
    WorkLongPoll poll;

    WorkController controller(WorkPort work, WorkLongPoll longPoll, Duration max) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        if (longPoll != null) {
            beans.registerSingleton("poll", longPoll);
        }
        RestProperties props = new RestProperties();
        props.setMaxAsyncResponseTimeout(max);
        beans.registerSingleton("props", props);
        return new WorkController(work, ALLOW, beans.getBeanProvider(WorkLongPoll.class),
                beans.getBeanProvider(RestProperties.class));
    }

    static WorkPort.FetchAndLockRequest body(Long waitMs) {
        return new WorkPort.FetchAndLockRequest("w-1", List.of(new WorkPort.TopicRequest("pay", 60_000L, null, null,
                null, null)), 2, waitMs, null);
    }

    @AfterEach
    void close() {
        if (poll != null) {
            poll.close();
        }
    }

    static void await(BooleanSupplier c, String what) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!c.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("timed out waiting for: " + what);
            }
            Thread.sleep(5);
        }
    }

    @Test
    void workThatIsThereIsAnsweredFromTheRequestThread() {
        poll = new WorkLongPoll(WorkLongPoll.Settings.defaults(), List.of());
        WorkPort.ExternalWork e1 = FakeWorkPort.work("e1", "pay", "T1");
        fake.queued.add(List.of(e1));
        DeferredResult<List<WorkPort.ExternalWork>> r = controller(new TrackingWorkPort(fake), poll, Duration.ofSeconds(30))
                .fetchAndLock(CTX, body(30_000L), null, null);
        assertTrue(r.hasResult());
        assertEquals(List.of(e1), r.getResult());
        assertEquals(0, poll.waiting());
        assertEquals(0L, fake.fetches.get(0).asyncResponseTimeoutMs(), "the attempt itself never waits");
    }

    @Test
    void withoutAWaitTheRequestGoesStraightThroughAsBefore() {
        poll = new WorkLongPoll(WorkLongPoll.Settings.defaults(), List.of());
        WorkPort.FetchAndLockRequest b = body(null);
        DeferredResult<List<WorkPort.ExternalWork>> r = controller(fake, poll, Duration.ofSeconds(30))
                .fetchAndLock(CTX, b, null, null);
        assertEquals(List.of(), r.getResult());
        assertSame(b, fake.fetches.get(0));
    }

    @Test
    void anEmptyFirstAttemptWaitsWithoutTheRequestThreadAndAWakeUpAnswersIt() throws Exception {
        // a 5 s poll interval: only the wake-up (M-005) can answer within the assertion's time
        poll = new WorkLongPoll(new WorkLongPoll.Settings(10, 2, Duration.ofSeconds(5), Duration.ofSeconds(5)), List.of());
        TrackingWorkPort tracking = new TrackingWorkPort(fake);
        DeferredResult<List<WorkPort.ExternalWork>> r = controller(tracking, poll, Duration.ofSeconds(30))
                .fetchAndLock(CTX, body(30_000L), null, null);
        assertFalse(r.hasResult(), "the request thread returned; the request waits");
        assertEquals(1, poll.waiting());
        WorkPort.ExternalWork e1 = FakeWorkPort.work("e1", "pay", "T1");
        fake.queued.add(List.of(e1));
        poll.wakeUp();
        await(r::hasResult, "the wake-up answers the waiting request");
        assertEquals(List.of(e1), r.getResult());
        assertEquals(1, tracking.workers("T1").get(0).activeLeases());
        assertEquals(0, poll.waiting());
    }

    @Test
    void theDeadlineAnswersTheEmptyListAndTheWaitIsCappedByTheMaximum() throws Exception {
        poll = new WorkLongPoll(new WorkLongPoll.Settings(10, 2, Duration.ofMillis(20), Duration.ofMillis(50)), List.of());
        long t0 = System.nanoTime();
        // the worker asks for 60 s; the server caps it at 150 ms
        DeferredResult<List<WorkPort.ExternalWork>> r = controller(fake, poll, Duration.ofMillis(150))
                .fetchAndLock(CTX, body(60_000L), null, null);
        await(r::hasResult, "the capped deadline");
        assertEquals(List.of(), r.getResult());
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 3_000);
    }

    @Test
    void aFirstAttemptErrorIsThrownAsBeforeAndALaterOneIsTheAsyncError() throws Exception {
        poll = new WorkLongPoll(new WorkLongPoll.Settings(10, 2, Duration.ofMillis(10), Duration.ofMillis(20)), List.of());
        WorkController c = controller(fake, poll, Duration.ofSeconds(30));
        fake.failure = ApiProblem.unavailable("DATABASE_UNAVAILABLE", "pool exhausted", 2);
        assertThrows(ApiProblem.class, () -> c.fetchAndLock(CTX, body(30_000L), null, null));

        fake.failure = null;
        DeferredResult<List<WorkPort.ExternalWork>> r = c.fetchAndLock(CTX, body(30_000L), null, null);
        ApiProblem later = ApiProblem.conflict("STALE_LEASE", "lease moved on");
        fake.failure = later;
        await(r::hasResult, "the poll error");
        assertSame(later, r.getResult(), "handled by the same exception handlers as a synchronous throw");
    }

    @Test
    void aboveTheWaiterBoundTheRequestWaitsInItsThreadAsBefore() {
        poll = new WorkLongPoll(new WorkLongPoll.Settings(0, 1, Duration.ofMillis(10), Duration.ofMillis(20)), List.of());
        FakeWorkPort blockingFake = new FakeWorkPort();
        DeferredResult<List<WorkPort.ExternalWork>> r = controller(blockingFake, poll, Duration.ofSeconds(30))
                .fetchAndLock(CTX, body(30_000L), null, null);
        assertTrue(r.hasResult(), "answered from the request thread");
        assertEquals(2, blockingFake.fetches.size());
        long fallbackWait = blockingFake.fetches.get(1).asyncResponseTimeoutMs();
        assertTrue(fallbackWait > 0 && fallbackWait <= 30_000, "the delegate's own wait gets the time left: " + fallbackWait);
    }

    @Test
    void workLeasedAfterTheRequestEndedIsReleasedWithItsFencingToken() throws Exception {
        poll = new WorkLongPoll(new WorkLongPoll.Settings(10, 2, Duration.ofSeconds(5), Duration.ofSeconds(5)), List.of());
        DeferredResult<List<WorkPort.ExternalWork>> r = controller(fake, poll, Duration.ofSeconds(30))
                .fetchAndLock(CTX, body(30_000L), null, null);
        r.setResult(List.of());       // the request ended first (for example the async timeout answered it)
        fake.queued.add(List.of(FakeWorkPort.work("e9", "pay", "T1")));
        poll.wakeUp();
        await(() -> !fake.unlocked.isEmpty(), "the release");
        assertEquals(List.of("e9|w-1|3|att-e9"), fake.unlocked);
    }
}
