// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.time;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BusinessClockTest {

    /** 2026-04-01T19:00Z = 2026-04-02 00:30 at +05:30. */
    static final Clock AT = Clock.fixed(Instant.parse("2026-04-01T19:00:00Z"), ZoneOffset.UTC);

    @Test
    void todayIsTheDateInTheTenantZoneNotTheJvmOrUtcDate() {
        Map<String, ZoneId> zones = Map.of("EAST", ZoneId.of("Asia/Kolkata"), "ZERO", ZoneOffset.UTC);
        BusinessClock b = BusinessClock.of(AT, t -> t == null ? null : zones.get(t));
        assertEquals(LocalDate.of(2026, 4, 2), b.today("EAST"));
        assertEquals(LocalDate.of(2026, 4, 1), b.today("ZERO"));
        assertEquals(LocalDate.of(2026, 4, 1), b.today("UNKNOWN"), "unknown tenant falls back to UTC");
        assertEquals(LocalDate.of(2026, 4, 1), b.today(null));
    }

    @Test
    void clockZoneIsIgnoredOnlyTheInstantCounts() {
        Clock otherZone = Clock.fixed(AT.instant(), ZoneId.of("America/Los_Angeles"));
        assertEquals(LocalDate.of(2026, 4, 2), BusinessClock.fixedZone(otherZone, ZoneId.of("Asia/Kolkata")).today("t"));
        assertEquals(LocalDate.of(2026, 4, 1), BusinessClock.utc(otherZone).today("t"));
    }
}
