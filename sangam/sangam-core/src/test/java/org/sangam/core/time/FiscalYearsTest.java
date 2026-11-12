// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.time;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** X1-03: the single fiscal-year calculation (year of a date, start, end, start day, labels). */
class FiscalYearsTest {

    @Test
    void everyDayBelongsToExactlyTheYearThatContainsIt() {
        for (int month = 1; month <= 12; month++) {
            for (int day : new int[] {1, 2, 15, 28}) {
                for (LocalDate d = LocalDate.of(1999, 1, 1); d.isBefore(LocalDate.of(2032, 1, 1)); d = d.plusDays(1)) {
                    int y = FiscalYears.startYearOf(d, month, day);
                    LocalDate s = FiscalYears.start(y, month, day);
                    LocalDate e = FiscalYears.end(y, month, day);
                    assertFalse(d.isBefore(s), d + " " + month + "/" + day);
                    assertFalse(d.isAfter(e), d + " " + month + "/" + day);
                    assertEquals(e, FiscalYears.endOfYearContaining(d, month, day));
                    assertEquals(s.plusYears(1), e.plusDays(1), "years are contiguous");
                }
            }
        }
    }

    @Test
    void theStartDayMovesTheBoundary() {
        // April 15 start: April 14 is still in the previous year, April 15 opens the new one
        assertEquals(2025, FiscalYears.startYearOf(LocalDate.of(2026, 4, 14), 4, 15));
        assertEquals(2026, FiscalYears.startYearOf(LocalDate.of(2026, 4, 15), 4, 15));
        assertEquals(LocalDate.of(2027, 4, 14), FiscalYears.end(2026, 4, 15));
        // start day 1 is the month boundary
        assertEquals(2025, FiscalYears.startYearOf(LocalDate.of(2026, 3, 31), 4, 1));
        assertEquals(2026, FiscalYears.startYearOf(LocalDate.of(2026, 4, 1), 4, 1));
        // January 15: the year ends in the next calendar year, so START-END2 shows both years
        assertEquals(LocalDate.of(2027, 1, 14), FiscalYears.end(2026, 1, 15));
        assertEquals("2026-27", FiscalYears.label(2026, 1, 15, FiscalYears.LabelFormat.START_END2));
        assertEquals(2025, FiscalYears.startYearOf(LocalDate.of(2026, 1, 14), 1, 15));
        // leap day inside a year that starts on 28 February
        assertEquals(2028, FiscalYears.startYearOf(LocalDate.of(2028, 2, 29), 2, 28));
        assertEquals(LocalDate.of(2029, 2, 27), FiscalYears.end(2028, 2, 28));
    }

    @Test
    void labelsInEveryFormat() {
        assertEquals("2026-27", FiscalYears.label(2026, 4, 1, FiscalYears.LabelFormat.START_END2));
        assertEquals("2026", FiscalYears.label(2026, 1, 1, FiscalYears.LabelFormat.START_END2));
        assertEquals("2099-00", FiscalYears.label(2099, 7, 1, FiscalYears.LabelFormat.START_END2));
        assertEquals("2026", FiscalYears.label(2026, 10, 1, FiscalYears.LabelFormat.START));
        assertEquals("2027", FiscalYears.label(2026, 10, 1, FiscalYears.LabelFormat.END));
        assertEquals("2026", FiscalYears.label(2026, 1, 1, FiscalYears.LabelFormat.END));
        assertEquals("2026/2027", FiscalYears.label(2026, 7, 1, FiscalYears.LabelFormat.START_SLASH_END));
        for (FiscalYears.LabelFormat f : FiscalYears.LabelFormat.values()) {
            assertEquals(f, FiscalYears.LabelFormat.of(f.code()));
        }
        assertEquals("START-END2", FiscalYears.LabelFormat.START_END2.code());
        assertEquals("START/END", FiscalYears.LabelFormat.START_SLASH_END.code());
        assertThrows(IllegalArgumentException.class, () -> FiscalYears.LabelFormat.of("FY-STYLE"));
    }

    @Test
    void noStartIsAssumedOrRepaired() {
        for (int[] bad : new int[][] {{0, 1}, {13, 1}, {4, 0}, {4, 29}, {2, 30}}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> FiscalYears.startYearOf(LocalDate.of(2026, 5, 5), bad[0], bad[1]));
            assertTrue(e.getMessage().contains("month 1..12, day 1..28"), e.getMessage());
            assertThrows(IllegalArgumentException.class, () -> FiscalYears.start(2026, bad[0], bad[1]));
            assertThrows(IllegalArgumentException.class, () -> FiscalYears.label(2026, bad[0], bad[1], FiscalYears.LabelFormat.START));
        }
    }
}
