// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.timer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

/** A-12 / D-01 / D-31 arithmetic, no engine. */
class CycleExpressionTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    @Test
    void hourlyCycleAcrossAutumnFoldFiresOncePerElapsedHour() {
        // E8 window: 2026-10-25, 03:00 CEST -> 02:00 CET at 01:00Z; start written as local time without offset
        CycleExpression c = CycleExpression.parse("R/2026-10-25T00:00:00/PT1H", BERLIN);
        Instant t = Instant.parse("2026-10-24T22:30:00Z");
        Instant prev = null;
        for (int i = 0; i < 6; i++) {
            Instant next = c.slotAfter(t, t, BERLIN);
            if (prev != null) {
                assertEquals(Duration.ofHours(1), Duration.between(prev, next));
                assertNotEquals(c.intervalOf(prev, BERLIN), c.intervalOf(next, BERLIN));
            }
            prev = next;
            t = next;
        }
    }

    @Test
    void monthlyCycleIsAnchoredOnTheOriginalStart() {
        CycleExpression c = CycleExpression.parse("R/2026-01-31T10:00:00Z/P1M", ZoneId.of("UTC"));
        Instant feb = c.slotAfter(Instant.parse("2026-01-31T10:00:00Z"), null, ZoneId.of("UTC"));
        Instant mar = c.slotAfter(feb, null, ZoneId.of("UTC"));
        assertEquals(Instant.parse("2026-02-28T10:00:00Z"), feb);
        assertEquals(Instant.parse("2026-03-31T10:00:00Z"), mar); // not the 28th (D-31)
    }

    @Test
    void calendarTagIsParsed() {
        CycleExpression c = CycleExpression.parse("cal:mh-gov:R3/PT10M", ZoneId.of("UTC"));
        assertEquals("mh-gov", c.calendarName());
        assertEquals(3, c.times());
    }

    /** SL-53: the plain durations the duration calendar evaluates on the timer-zone calendar; anything else is not one. */
    @Test
    void plainDurationsAreRecognisedAndNothingElse() {
        assertEquals(java.time.Period.ofDays(1), CycleExpression.duration("P1D").datePart());
        assertEquals(java.time.Period.ofDays(14), CycleExpression.duration(" P2W ").datePart());
        assertEquals(java.time.Period.of(1, 2, 3), CycleExpression.duration("P1Y2M3D").datePart());
        CycleExpression mixed = CycleExpression.duration("P1DT2H30M");
        assertEquals(java.time.Period.ofDays(1), mixed.datePart());
        assertEquals(Duration.ofMinutes(150), mixed.timePart());
        assertEquals(Duration.ofMinutes(5), CycleExpression.duration("PT5M").timePart());
        for (String notOne : java.util.Arrays.asList("P0D", "PT0S", "P", "PT", "-P1D", "P1.5D", "R/PT1H", "R3/P1D",
                "2026-01-01T00:00:00/P1D", "P1D/2026-01-02T00:00:00", "cal:mh-gov:P3D", "2026-01-01T00:00:00", "", null)) {
            assertEquals(null, CycleExpression.duration(notOne), String.valueOf(notOne));
        }
    }

    /** SL-53: a date part is added on the calendar of the zone (25 h across the Berlin autumn fold), not as 24 h. */
    @Test
    void dayAcrossTheAutumnFoldIsTwentyFiveHoursInBerlin() {
        CycleExpression day = CycleExpression.duration("P1D");
        Instant start = Instant.parse("2026-10-24T10:00:00Z"); // 12:00 CEST
        assertEquals(Instant.parse("2026-10-25T11:00:00Z"), start.atZone(BERLIN).plus(day.datePart()).toInstant());
        assertEquals(Instant.parse("2026-10-25T10:00:00Z"),
                start.atZone(java.time.ZoneOffset.UTC).plus(day.datePart()).toInstant());
    }
}
