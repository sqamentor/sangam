// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.WorkPort;

/**
 * M-053: the console view of a long-poll ({@link TrackingWorkPort#prepare} once per HTTP request, then any number of
 * {@link TrackingWorkPort#attempt}s that do not wait) and the unchanged synchronous call, including R63 paused topics.
 */
class TrackingWorkPortLongPollTest {

    static final RequestContext CTX = new RequestContext("T1", "worker-principal", Set.of(), null, null, null, null,
            null, "API_KEY");

    static WorkPort.FetchAndLockRequest request(long waitMs, String... topics) {
        return new WorkPort.FetchAndLockRequest("w-1", java.util.Arrays.stream(topics)
                .map(t -> new WorkPort.TopicRequest(t, 60_000L, null, null, null, null)).toList(), 5, waitMs, null);
    }

    @Test
    void oneHttpRequestIsOneCallHoweverManyPollsItTakes() {
        FakeWorkPort fake = new FakeWorkPort();
        TrackingWorkPort tracking = new TrackingWorkPort(fake);
        TrackingWorkPort.Prepared p = tracking.prepare(CTX, request(30_000, "pay"));
        assertTrue(tracking.attempt(CTX, p, 0L).isEmpty());
        assertTrue(tracking.attempt(CTX, p, 0L).isEmpty());
        fake.queued.add(List.of(FakeWorkPort.work("e1", "pay", "T1")));
        assertEquals(1, tracking.attempt(CTX, p, 0L).size());

        TrackingWorkPort.Worker w = tracking.workers("T1").get(0);
        assertEquals(1, w.calls.get(), "the console counts the HTTP request, not the polls");
        assertEquals(1, w.activeLeases(), "the lease found by a poll is attributed to the worker");
        assertEquals(List.of("pay"), w.topics());
        // every poll asks the delegate for one attempt without waiting
        assertEquals(3, fake.fetches.size());
        fake.fetches.forEach(r -> assertEquals(0L, r.asyncResponseTimeoutMs()));
    }

    @Test
    void theSynchronousCallPassesTheRequestThroughUnchanged() {
        FakeWorkPort fake = new FakeWorkPort();
        TrackingWorkPort tracking = new TrackingWorkPort(fake);
        WorkPort.FetchAndLockRequest r = request(1_234, "pay");
        tracking.fetchAndLock(CTX, r);
        assertEquals(1, fake.fetches.size());
        assertTrue(fake.fetches.get(0) == r, "same request object, same wait, as before M-053");
    }

    @Test
    void pausedTopicsAreRemovedOnceAndAnAllPausedCallFetchesNothing() {
        FakeWorkPort fake = new FakeWorkPort();
        TrackingWorkPort tracking = new TrackingWorkPort(fake);
        tracking.pauseTopic("T1", "pay", true);

        TrackingWorkPort.Prepared some = tracking.prepare(CTX, request(30_000, "pay", "refund"));
        assertFalse(some.allPaused());
        assertEquals(List.of("refund"), some.filtered().topics().stream().map(WorkPort.TopicRequest::name).toList());

        TrackingWorkPort.Prepared all = tracking.prepare(CTX, request(30_000, "pay"));
        assertTrue(all.allPaused());
        assertEquals(1_000, all.pausedWaitMs(), "R63: an all-paused call answers after at most 1 s");
        long t0 = System.nanoTime();
        assertTrue(tracking.attempt(CTX, all, 0L).isEmpty());
        assertTrue(System.nanoTime() - t0 < 500_000_000L, "a long-poll attempt never waits");
        assertTrue(fake.fetches.isEmpty(), "nothing is fetched for paused topics");
    }

    @Test
    void anAllPausedSynchronousCallStillWaitsBeforeItsEmptyAnswer() {
        FakeWorkPort fake = new FakeWorkPort();
        TrackingWorkPort tracking = new TrackingWorkPort(fake);
        tracking.pauseTopic("T1", "pay", true);
        long t0 = System.nanoTime();
        assertTrue(tracking.fetchAndLock(CTX, request(80, "pay")).isEmpty());
        assertTrue(System.nanoTime() - t0 >= 70_000_000L, "the worker does not spin (min(1 s, its wait))");
        assertTrue(fake.fetches.isEmpty());
    }
}
