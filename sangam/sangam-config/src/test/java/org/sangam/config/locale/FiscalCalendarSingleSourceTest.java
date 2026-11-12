// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.locale;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * X1-03: FiscalCalendar now takes its year, start, end and label from sangam-core FiscalYears. Property check against
 * the pre-X1-03 code (copied verbatim below as the oracle): every start month, start days 1/15/28, every label format,
 * every day of 1999..2031 and every start year of 1900..2200 give the same result.
 */
class FiscalCalendarSingleSourceTest {

    /** Pre-X1-03 FiscalCalendar.yearOf (FiscalCalendar.java:89-92 at 0e53f91b). */
    static int oldYearOf(LocalDate d, int startMonth, int startDay) {
        LocalDate startThisYear = LocalDate.of(d.getYear(), startMonth, startDay);
        return d.isBefore(startThisYear) ? d.getYear() - 1 : d.getYear();
    }

    /** Pre-X1-03 FiscalYear.label (FiscalCalendar.java:166-173 at 0e53f91b). */
    static String oldLabel(int startYear, int startMonth, int startDay, String labelFormat) {
        int end = LocalDate.of(startYear, startMonth, startDay).plusYears(1).minusDays(1).getYear();
        return switch (labelFormat) {
            case "START" -> Integer.toString(startYear);
            case "END" -> Integer.toString(end);
            case "START/END" -> startYear + "/" + end;
            default -> startYear == end ? Integer.toString(startYear) : startYear + "-" + String.format("%02d", end % 100);
        };
    }

    @Test
    void theLabelFormatsAreUnchanged() {
        assertEquals(Set.of("START-END2", "START", "END", "START/END"), FiscalCalendar.LABEL_FORMATS);
    }

    @Test
    void yearStartEndAndLabelMatchThePreviousCodeEverywhere() {
        for (String format : FiscalCalendar.LABEL_FORMATS) {
            for (int month = 1; month <= 12; month++) {
                for (int day : new int[] {1, 15, 28}) {
                    FiscalCalendar c = new FiscalCalendar(month, day, ZoneOffset.UTC, format);
                    for (int y = 1900; y <= 2200; y++) {
                        FiscalCalendar.FiscalYear fy = c.year(y);
                        String where = format + " " + month + "/" + day + " " + y;
                        assertEquals(oldLabel(y, month, day, format), fy.label(), where);
                        assertEquals(LocalDate.of(y, month, day), fy.start(), where);
                        assertEquals(LocalDate.of(y, month, day).plusYears(1).minusDays(1), fy.end(), where);
                    }
                    if (!"START-END2".equals(format)) {
                        continue;   // yearOf does not depend on the format: walk the days once per start
                    }
                    for (LocalDate d = LocalDate.of(1999, 1, 1); d.isBefore(LocalDate.of(2032, 1, 1)); d = d.plusDays(1)) {
                        assertEquals(oldYearOf(d, month, day), c.yearOf(d).startYear(), d + " " + month + "/" + day);
                    }
                }
            }
        }
    }

    @Test
    void aStartDayOtherThanTheFirstIsStillHonoured() {
        FiscalCalendar c = new FiscalCalendar(4, 15, ZoneOffset.UTC, "START-END2");
        assertEquals("2025-26", c.yearOf(LocalDate.of(2026, 4, 14)).label());
        assertEquals("2026-27", c.yearOf(LocalDate.of(2026, 4, 15)).label());
        assertEquals(LocalDate.of(2027, 4, 14), c.yearOf(LocalDate.of(2026, 4, 15)).end());
        assertEquals("2026-27", new FiscalCalendar(1, 15, ZoneOffset.UTC, "START-END2").year(2026).label());
        assertEquals("2026", new FiscalCalendar(1, 1, ZoneOffset.UTC, "START-END2").year(2026).label());
    }
}
