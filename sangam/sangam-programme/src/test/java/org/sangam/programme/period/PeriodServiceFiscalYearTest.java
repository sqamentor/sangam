// SPDX-License-Identifier: Apache-2.0
package org.sangam.programme.period;

import org.junit.jupiter.api.Test;
import org.sangam.programme.config.IndicatorDefinition.Frequency;

import java.time.LocalDate;
import java.time.Month;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * X1-03: PeriodService now takes the fiscal year from sangam-core FiscalYears. Property check against the pre-X1-03
 * code (copied verbatim below as the oracle) for every start month: the stored {@code fiscal_year} label, the year
 * start of every day of 1999..2031, and the periods of every frequency for start years 1990..2110 are unchanged.
 */
class PeriodServiceFiscalYearTest {

    /** Pre-X1-03 PeriodService.fiscalYearLabel (PeriodService.java:49-51 at 0e53f91b). */
    static String oldLabel(int startYear, Month startMonth) {
        return startMonth == Month.JANUARY ? "FY" + startYear : "FY" + startYear + "-" + String.format("%02d", (startYear + 1) % 100);
    }

    /** Pre-X1-03 PeriodService.fiscalYearStart (PeriodService.java:54-57 at 0e53f91b). */
    static LocalDate oldStart(LocalDate d, Month startMonth) {
        LocalDate s = LocalDate.of(d.getYear(), startMonth, 1);
        return d.isBefore(s) ? s.minusYears(1) : s;
    }

    @Test
    void labelsAndYearStartsMatchThePreviousCode() {
        for (Month m : Month.values()) {
            for (int y = 1900; y <= 2200; y++) {
                assertEquals(oldLabel(y, m), PeriodService.fiscalYearLabel(y, m), m + " " + y);
            }
            for (LocalDate d = LocalDate.of(1999, 1, 1); d.isBefore(LocalDate.of(2032, 1, 1)); d = d.plusDays(1)) {
                assertEquals(oldStart(d, m), PeriodService.fiscalYearStart(d, m), m + " " + d);
            }
        }
    }

    @Test
    void periodsOfEveryFrequencyKeepTheirBoundsAndLabels() {
        for (Month m : Month.values()) {
            for (int y = 1990; y <= 2110; y++) {
                LocalDate yStart = LocalDate.of(y, m, 1);
                LocalDate yEnd = yStart.plusYears(1).minusDays(1);
                for (Frequency f : Frequency.values()) {
                    List<PeriodService.Period> ps = PeriodService.compute(f, y, m, 10);
                    String where = f + " " + m + " " + y;
                    assertEquals(yStart, ps.get(0).startsOn(), where);
                    assertEquals(yEnd, ps.get(ps.size() - 1).endsOn(), where);
                    assertEquals(f.perYear(), ps.size(), where);
                    for (int i = 0; i < ps.size(); i++) {
                        PeriodService.Period p = ps.get(i);
                        assertEquals(oldLabel(y, m), p.fiscalYear(), where);
                        assertEquals(p.fiscalYear() + "/", p.label().substring(0, p.fiscalYear().length() + 1), where);
                        if (i > 0) {
                            assertEquals(ps.get(i - 1).endsOn().plusDays(1), p.startsOn(), where + " contiguous");
                        }
                    }
                }
            }
        }
    }

    @Test
    void anExplicitFirstStartDayEqualsTheMonthOnlyForms() {
        for (Month m : Month.values()) {
            for (int y = 1990; y <= 2110; y++) {
                assertEquals(PeriodService.fiscalYearLabel(y, m), PeriodService.fiscalYearLabel(y, m, 1), m + " " + y);
                for (Frequency f : Frequency.values()) {
                    assertEquals(PeriodService.compute(f, y, m, 7), PeriodService.compute(f, y, m, 1, 7), f + " " + m + " " + y);
                }
            }
            for (LocalDate d = LocalDate.of(1999, 1, 1); d.isBefore(LocalDate.of(2032, 1, 1)); d = d.plusDays(1)) {
                assertEquals(oldStart(d, m), PeriodService.fiscalYearStart(d, m, 1), m + " " + d);
            }
        }
    }

    @Test
    void aConfiguredStartDayMovesTheYearAndItsPeriods() {
        assertEquals(LocalDate.of(2025, 4, 15), PeriodService.fiscalYearStart(LocalDate.of(2026, 4, 14), Month.APRIL, 15));
        assertEquals(LocalDate.of(2026, 4, 15), PeriodService.fiscalYearStart(LocalDate.of(2026, 4, 15), Month.APRIL, 15));
        assertEquals("FY2026-27", PeriodService.fiscalYearLabel(2026, Month.JANUARY, 15), "15 Jan .. 14 Jan spans two years");
        List<PeriodService.Period> months = PeriodService.compute(Frequency.MONTHLY, 2026, Month.APRIL, 15, 0);
        assertEquals(12, months.size());
        assertEquals(LocalDate.of(2026, 4, 15), months.get(0).startsOn());
        assertEquals(LocalDate.of(2026, 5, 14), months.get(0).endsOn());
        assertEquals(LocalDate.of(2027, 4, 14), months.get(11).endsOn());
        assertEquals("MONTHLY:2026-04-15", months.get(0).id());
        for (Frequency f : Frequency.values()) {
            List<PeriodService.Period> ps = PeriodService.compute(f, 2026, Month.APRIL, 15, 0);
            assertEquals(f.perYear(), ps.size(), f.name());
            assertEquals(LocalDate.of(2026, 4, 15), ps.get(0).startsOn(), f.name());
            assertEquals(LocalDate.of(2027, 4, 14), ps.get(ps.size() - 1).endsOn(), f.name());
        }
    }

    @Test
    void knownValues() {
        assertEquals("FY2026-27", PeriodService.fiscalYearLabel(2026, Month.APRIL));
        assertEquals("FY2026", PeriodService.fiscalYearLabel(2026, Month.JANUARY));
        assertEquals("FY2099-00", PeriodService.fiscalYearLabel(2099, Month.JULY));
        assertEquals(LocalDate.of(2025, 4, 1), PeriodService.fiscalYearStart(LocalDate.of(2026, 3, 31), Month.APRIL));
        List<PeriodService.Period> q = PeriodService.compute(Frequency.QUARTERLY, 2026, Month.APRIL, 0);
        assertEquals("FY2026-27/Q1", q.get(0).label());
        assertEquals(LocalDate.of(2027, 3, 31), q.get(3).endsOn());
    }
}
