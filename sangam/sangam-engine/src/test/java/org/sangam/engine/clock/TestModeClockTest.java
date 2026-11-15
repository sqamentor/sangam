// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.clock;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.time.BusinessClock;
import org.sangam.engine.SangamEngineSettings;

/** M-021: test-mode clock steering is H2-only, never in production, and every change is audited. */
class TestModeClockTest {

    private BusinessClock saved = BusinessClock.system();

    @AfterEach
    void restore() {
        OperatonClock.INSTANCE.reset();
        BusinessClock.install(saved);
    }

    @Test
    void theGuardRefusesEverythingButH2WithoutAProductionProfile() {
        assertDoesNotThrow(() -> TestModeClock.requireAllowed(false, false, Set.of("prod")), "off: nothing to check");
        assertDoesNotThrow(() -> TestModeClock.requireAllowed(true, true, Set.of("e2e")));
        assertThrows(IllegalStateException.class, () -> TestModeClock.requireAllowed(true, false, Set.of()));
        assertThrows(IllegalStateException.class, () -> TestModeClock.requireAllowed(true, true, Set.of("e2e", "Production")));
        assertThrows(IllegalStateException.class, () -> TestModeClock.forEngine(new SangamEngineSettings(), c -> { }));
    }

    @Test
    void closingGivesTheProcessWideBusinessClockBack() {
        BusinessClock before = BusinessClock.system();
        TestModeClock clock = new TestModeClock(OperatonClock.INSTANCE, c -> { });
        org.junit.jupiter.api.Assertions.assertNotSame(before, BusinessClock.system(), "the constructor replaces it");
        clock.close();
        org.junit.jupiter.api.Assertions.assertSame(before, BusinessClock.system(), "and close restores it");
        clock.close();
        org.junit.jupiter.api.Assertions.assertSame(before, BusinessClock.system(), "idempotent");

        // a clock installed by someone else after ours is not thrown away by our close
        TestModeClock second = new TestModeClock(OperatonClock.INSTANCE, c -> { });
        BusinessClock later = BusinessClock.of(java.time.Clock.systemUTC(), before::zone);
        BusinessClock.install(later);
        second.close();
        org.junit.jupiter.api.Assertions.assertSame(later, BusinessClock.system());
    }

    @Test
    void engineAndBusinessClockMoveTogetherAndEveryChangeIsAudited() {
        List<TestModeClock.Change> audit = new CopyOnWriteArrayList<>();
        TestModeClock clock = new TestModeClock(OperatonClock.INSTANCE, audit::add);
        Instant before = Instant.now();
        clock.advanceBy(Duration.ofDays(3), "tester", "SLA breach scenario");
        assertTrue(OperatonClock.INSTANCE.now().isAfter(before.plus(Duration.ofDays(3)).minusSeconds(5)));
        assertTrue(BusinessClock.system().clock().instant().isAfter(before.plus(Duration.ofDays(3)).minusSeconds(5)),
                "the business clock follows the engine clock");
        Instant past = Instant.parse("2026-03-29T00:30:00Z");
        clock.set(past, "tester", "DST");
        assertTrue(Math.abs(Duration.between(past, OperatonClock.INSTANCE.now()).toMillis()) < 5_000);
        clock.reset("tester", null);
        assertEquals(0, clock.offsetMs());
        assertEquals(List.of("ADVANCE", "SET", "RESET"), audit.stream().map(TestModeClock.Change::action).toList());
        assertEquals("tester", audit.get(0).actor());
        assertThrows(IllegalArgumentException.class, () -> clock.advanceBy(Duration.ofSeconds(-1), "t", null));
    }

    @Test
    void theCallersAuditRunsWithTheEngineAuditorAndARejectionUndoesTheChange() {
        List<String> engineLog = new CopyOnWriteArrayList<>();
        TestModeClock clock = new TestModeClock(OperatonClock.INSTANCE, c -> engineLog.add(c.action()));
        List<TestModeClock.Change> route = new CopyOnWriteArrayList<>();
        clock.advanceBy(Duration.ofHours(2), "admin", "r", route::add);
        assertEquals(1, route.size());
        assertEquals(List.of("ADVANCE"), engineLog);
        long kept = clock.offsetMs();
        assertThrows(IllegalStateException.class, () -> clock.reset("admin", null, c -> {
            throw new IllegalStateException("audit write failed");
        }));
        assertEquals(kept, clock.offsetMs(), "the route's audit failed: not applied");
        assertEquals(List.of("ADVANCE"), engineLog, "the engine auditor never saw the undone change");
        assertThrows(IllegalStateException.class, () -> TestModeClock.forEngine(new SangamEngineSettings(), true,
                Set.of(), c -> { }), "off in the settings: no clock");
        SangamEngineSettings on = new SangamEngineSettings();
        on.setSystemAuditScope("system:platform"); // decision 144: required at engine start
        on.enableTestMode(true, Set.of("e2e"));
        assertThrows(IllegalStateException.class, () -> TestModeClock.forEngine(on, false, Set.of("e2e"), c -> { }),
                "the host's own view says not H2");
        assertThrows(IllegalStateException.class, () -> TestModeClock.forEngine(on, true, Set.of("prod"), c -> { }),
                "the host's own profiles say production");
    }

    @Test
    void aChangeTheAuditorRejectsIsUndone() {
        TestModeClock clock = new TestModeClock(OperatonClock.INSTANCE, c -> {
            throw new IllegalStateException("audit down");
        });
        assertThrows(IllegalStateException.class, () -> clock.advanceBy(Duration.ofDays(1), "t", null));
        assertEquals(0, clock.offsetMs());
        assertTrue(Math.abs(Duration.between(Instant.now(), OperatonClock.INSTANCE.now()).toMillis()) < 5_000);
    }
}
