// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SL-117: the compliance schedule reads its tenants afresh on every cycle (a tick in which a task is due), so a failed
 * or empty read is retried on the next cycle; the empty-schedule WARN and metric are the wiring's
 * {@code ScheduleTenantsMonitor} (see {@code ScheduleTenantsMonitorTest}).
 */
class ComplianceSchedulerEmptyTest {

    @Test
    void theTenantListIsReadAfreshEveryCycle() {
        AtomicInteger reads = new AtomicInteger();
        List<List<String>> answers = new ArrayList<>(List.of(List.of(), List.of("RJ")));
        ComplianceScheduler s = new ComplianceScheduler(null, null, () -> {
            reads.incrementAndGet();
            return answers.remove(0);
        }, 500, 10_000);

        assertEquals(List.of(), s.cycleTenants());
        assertEquals(List.of("RJ"), s.cycleTenants(), "an empty or failed read is not cached");
        assertEquals(2, reads.get());
    }

    @Test
    void beforeStartEveryTaskIsDue() {
        ComplianceScheduler s = new ComplianceScheduler(null, null, List::of, 500, 10_000);
        assertTrue(s.anyDue(Instant.now()));
        assertEquals(List.of("deadlines", "retention", "ntp", "logs", "integrity"), ComplianceScheduler.TASKS);
    }
}
