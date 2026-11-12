// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/** Daily retention inside the channels poll loop (M-071): spacing after success and after failure. Written, not run (Wave 3). */
class ChannelsSchedulerSpacingTest {

    static final class MovableClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

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
    void runsOncePerPeriodAndRetriesAFailureSooner() throws Exception {
        MovableClock clock = new MovableClock();
        ChannelsScheduler.Spaced spaced = new ChannelsScheduler.Spaced(Duration.ofDays(1), Duration.ofHours(1), clock);
        AtomicInteger runs = new AtomicInteger();
        ChannelsScheduler.Task t = spaced.wrap("retention:t", runs::incrementAndGet);
        t.run();
        t.run();
        assertEquals(1, runs.get(), "the second tick on the same day does nothing");
        clock.now = clock.now.plus(Duration.ofHours(23));
        t.run();
        assertEquals(1, runs.get());
        clock.now = clock.now.plus(Duration.ofHours(1));
        t.run();
        assertEquals(2, runs.get(), "due again after one period");

        AtomicInteger attempts = new AtomicInteger();
        ChannelsScheduler.Task failing = spaced.wrap("retention:u", () -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("database down");
        });
        assertThrows(IllegalStateException.class, failing::run, "the failure reaches the tick, which logs it");
        failing.run();
        assertEquals(1, attempts.get(), "not repeated on every tick");
        clock.now = clock.now.plus(Duration.ofHours(1));
        assertThrows(IllegalStateException.class, failing::run);
        assertEquals(2, attempts.get(), "retried after the retry interval, not after a whole day");

        AtomicInteger other = new AtomicInteger();
        spaced.wrap("retention:v", other::incrementAndGet).run();
        assertEquals(1, other.get(), "keys are independent");
        assertThrows(IllegalArgumentException.class, () -> new ChannelsScheduler.Spaced(Duration.ZERO, Duration.ofHours(1), clock));
    }
}
