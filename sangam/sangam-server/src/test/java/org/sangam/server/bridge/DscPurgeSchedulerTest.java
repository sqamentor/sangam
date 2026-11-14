// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

/** Decision 165: the DSC purge runs on the leader only, once per interval, with before = now, and never throws. */
class DscPurgeSchedulerTest {

    static final Instant T0 = Instant.parse("2026-11-13T02:00:00Z");

    final List<Instant> purged = new ArrayList<>();
    final List<String> released = new ArrayList<>();
    final AtomicLong fencing = new AtomicLong(-1);

    final DscPurgeScheduler.Leader leader = new DscPurgeScheduler.Leader() {
        @Override
        public long tryAcquire(String name) {
            assertEquals(DscPurgeScheduler.LEADER, name);
            return fencing.get();
        }

        @Override
        public void release(String name) {
            released.add(name);
        }
    };

    static final class MovableClock extends Clock {
        Instant now = T0;

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void onlyTheLeaderPurgesOncePerIntervalWithBeforeNow() {
        MovableClock clock = new MovableClock();
        DscPurgeScheduler s = new DscPurgeScheduler(before -> {
            purged.add(before);
            return 3;
        }, leader, Duration.ofMinutes(15), clock);

        assertEquals(0, s.tick(), "not the leader");
        assertTrue(purged.isEmpty());

        fencing.set(7);
        assertEquals(3, s.tick());
        assertEquals(List.of(T0), purged, "entries expired before now");

        clock.now = T0.plus(Duration.ofMinutes(14));
        assertEquals(0, s.tick(), "not due yet");
        clock.now = T0.plus(Duration.ofMinutes(15));
        assertEquals(3, s.tick());
        assertEquals(List.of(T0, T0.plus(Duration.ofMinutes(15))), purged);

        s.stop();
        assertEquals(List.of(DscPurgeScheduler.LEADER), released, "the lease is released on stop");
    }

    @Test
    void aFailingPurgeIsLoggedNotThrownAndTheIntervalDefaultsToFifteenMinutes() {
        fencing.set(1);
        DscPurgeScheduler s = new DscPurgeScheduler(before -> {
            throw new IllegalStateException("database down");
        }, leader, null, new MovableClock());
        assertEquals(0, s.tick());
        assertEquals(Duration.ofMinutes(15), s.interval());
    }
}
