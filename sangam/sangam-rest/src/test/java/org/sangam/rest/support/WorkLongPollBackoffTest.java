// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.support;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * X1-07: the one back-off rule of every worker wait. {@link WorkLongPoll.Settings#nextIntervalMillis} reproduces the
 * old schedule of both adapters' in-thread loops ({@code 100, 200, 400, 800 ms, then every 1 s}) with the defaults, and
 * follows other settings.
 */
class WorkLongPollBackoffTest {

    static List<Long> schedule(WorkLongPoll.Settings s, int steps) {
        List<Long> out = new ArrayList<>();
        long sleep = s.firstInterval().toMillis();
        for (int i = 0; i < steps; i++) {
            out.add(sleep);
            sleep = s.nextIntervalMillis(sleep);
        }
        return out;
    }

    @Test
    void theDefaultsAreTheOldAdapterSchedule() {
        assertEquals(List.of(100L, 200L, 400L, 800L, 1000L, 1000L), schedule(WorkLongPoll.Settings.defaults(), 6));
    }

    @Test
    void otherSettingsChangeTheScheduleInOnePlace() {
        WorkLongPoll.Settings s = new WorkLongPoll.Settings(10, 1, Duration.ofMillis(50), Duration.ofMillis(300));
        assertEquals(List.of(50L, 100L, 200L, 300L, 300L), schedule(s, 5));
    }

    @Test
    void theLongPollExposesItsSettings() {
        WorkLongPoll.Settings s = WorkLongPoll.Settings.defaults();
        try (WorkLongPoll poll = new WorkLongPoll(s, List.of())) {
            assertEquals(s, poll.settings());
        }
    }
}
