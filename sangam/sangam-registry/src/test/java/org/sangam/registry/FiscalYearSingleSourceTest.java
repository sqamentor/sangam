// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry;

import org.junit.jupiter.api.Test;
import org.sangam.registry.kernel.ValidityRule;
import org.sangam.registry.numbering.NumberSeriesDefinition;

import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * X1-03: NumberSeriesDefinition and ValidityRule now take the fiscal year from sangam-core FiscalYears. Property check
 * against the pre-X1-03 code (copied verbatim below as the oracle) for every start month and every day of 1999..2031:
 * the {fy} and {fyStart} tokens, the FISCAL_YEAR counter period and the FISCAL_YEAR_END validity end are unchanged.
 */
class FiscalYearSingleSourceTest {

    /** Pre-X1-03 NumberSeriesDefinition.fiscalStartYear (NumberSeriesDefinition.java:116 at 0e53f91b). */
    static int oldFiscalStartYear(LocalDate d, int fsm) {
        return d.getMonthValue() >= fsm ? d.getYear() : d.getYear() - 1;
    }

    /** Pre-X1-03 NumberSeriesDefinition.fyLabel (NumberSeriesDefinition.java:91-94 at 0e53f91b). */
    static String oldFyLabel(LocalDate d, int fsm) {
        int fy = oldFiscalStartYear(d, fsm);
        return fsm == 1 ? Integer.toString(fy) : fy + "-" + String.format("%02d", (fy + 1) % 100);
    }

    /** Pre-X1-03 ValidityRule.align FISCAL_YEAR_END (ValidityRule.java:148-154 at 0e53f91b). */
    static LocalDate oldFiscalYearEnd(LocalDate lastDay, int fsm) {
        LocalDate fyStart = LocalDate.of(lastDay.getYear(), fsm, 1);
        if (lastDay.isBefore(fyStart)) {
            return fyStart.minusDays(1);
        }
        return fyStart.plusYears(1).minusDays(1);
    }

    @Test
    void numberSeriesTokensAndCounterPeriodsMatchThePreviousCode() {
        for (int fsm = 1; fsm <= 12; fsm++) {
            NumberSeriesDefinition d = new NumberSeriesDefinition("S", "{fy}|{fyStart}|{period}|{seq}", "", "GAP_FREE",
                    "FISCAL_YEAR", fsm, 1, 50, "NONE", null);
            for (LocalDate day = LocalDate.of(1999, 1, 1); day.isBefore(LocalDate.of(2032, 1, 1)); day = day.plusDays(1)) {
                int fy = oldFiscalStartYear(day, fsm);
                String where = fsm + " " + day;
                assertEquals(fy, d.fiscalStartYear(day), where);
                assertEquals("FY" + fy, d.periodKey(day), where);
                assertEquals(oldFyLabel(day, fsm) + "|" + fy + "|FY" + fy + "|7", d.format(7, null, day, Map.of()), where);
            }
        }
    }

    @Test
    void fiscalYearEndValidityMatchesThePreviousCode() {
        for (int fsm = 1; fsm <= 12; fsm++) {
            // one-day validity: the last day is the issue day itself, so the aligned end is the fiscal-year end of that day
            ValidityRule r = new ValidityRule("PERIOD", Period.ofDays(1), "FISCAL_YEAR_END", fsm, Period.ZERO, Period.ZERO,
                    Period.ofDays(1), null, "PREVIOUS_END", null);
            for (LocalDate day = LocalDate.of(1999, 1, 1); day.isBefore(LocalDate.of(2032, 1, 1)); day = day.plusDays(1)) {
                ValidityRule.Window w = r.first(day, null, ZoneOffset.UTC);
                assertEquals(oldFiscalYearEnd(day, fsm).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(), w.validTo(),
                        fsm + " " + day);
            }
        }
    }

    @Test
    void absentOrFirstStartDayIsByteIdenticalToThePreviousCode() {
        for (int fsm = 1; fsm <= 12; fsm++) {
            Map<String, Object> series = new HashMap<>(Map.of("seriesKey", "S", "pattern", "{fy}|{fyStart}|{period}|{seq}",
                    "reset", "FISCAL_YEAR", "fiscalStartMonth", fsm));
            NumberSeriesDefinition absent = NumberSeriesDefinition.from(series);
            assertNull(absent.fiscalStartDay());
            assertFalse(absent.toMap().containsKey("fiscalStartDay"), "an absent day is not written: " + absent.toMap());
            assertEquals(absent.toMap(), NumberSeriesDefinition.from(absent.toMap()).toMap(), "stored form unchanged");
            series.put("fiscalStartDay", 1);
            NumberSeriesDefinition first = NumberSeriesDefinition.from(series);
            assertEquals(1, first.toMap().get("fiscalStartDay"), "a stated day round-trips");
            assertEquals(1, NumberSeriesDefinition.from(first.toMap()).fiscalStartDay());
            ValidityRule rule = ValidityRule.from(Map.of("period", "P1D", "alignTo", "FISCAL_YEAR_END", "fiscalStartMonth", fsm));
            assertFalse(rule.toMap().containsKey("fiscalStartDay"), rule.toMap()::toString);
            ValidityRule ruleFirst = ValidityRule.from(Map.of("period", "P1D", "alignTo", "FISCAL_YEAR_END", "fiscalStartMonth", fsm,
                    "fiscalStartDay", 1));
            assertEquals(1, ValidityRule.from(ruleFirst.toMap()).fiscalStartDay());
            for (LocalDate day = LocalDate.of(1999, 1, 1); day.isBefore(LocalDate.of(2032, 1, 1)); day = day.plusDays(1)) {
                String where = fsm + " " + day;
                String expected = oldFyLabel(day, fsm) + "|" + oldFiscalStartYear(day, fsm) + "|FY" + oldFiscalStartYear(day, fsm) + "|7";
                assertEquals(expected, absent.format(7, null, day, Map.of()), where);
                assertEquals(expected, first.format(7, null, day, Map.of()), where);
                java.time.Instant end = oldFiscalYearEnd(day, fsm).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
                assertEquals(end, rule.first(day, null, ZoneOffset.UTC).validTo(), where);
                assertEquals(end, ruleFirst.first(day, null, ZoneOffset.UTC).validTo(), where);
            }
        }
    }

    @Test
    void aConfiguredStartDayMovesTheFiscalBoundary() {
        NumberSeriesDefinition d = NumberSeriesDefinition.from(Map.of("seriesKey", "S", "pattern", "{fy}/{fyStart}/{seq}",
                "reset", "FISCAL_YEAR", "fiscalStartMonth", 4, "fiscalStartDay", 15));
        assertEquals(15, d.fiscalStartDay());
        assertEquals(15, d.toMap().get("fiscalStartDay"));
        assertEquals("2025-26/2025/1", d.format(1, null, LocalDate.of(2026, 4, 14), Map.of()));
        assertEquals("2026-27/2026/1", d.format(1, null, LocalDate.of(2026, 4, 15), Map.of()));
        assertEquals("FY2025", d.periodKey(LocalDate.of(2026, 4, 14)));
        assertEquals("FY2026", d.periodKey(LocalDate.of(2026, 4, 15)));
        // January 15: the year ends in the next calendar year, so {fy} shows both years
        NumberSeriesDefinition jan = NumberSeriesDefinition.from(Map.of("seriesKey", "S", "pattern", "{fy}/{seq}",
                "reset", "FISCAL_YEAR", "fiscalStartMonth", 1, "fiscalStartDay", 15));
        assertEquals("2026-27/1", jan.format(1, null, LocalDate.of(2026, 6, 1), Map.of()));
        assertEquals("2025-26/1", jan.format(1, null, LocalDate.of(2026, 1, 14), Map.of()));

        ValidityRule r = ValidityRule.from(Map.of("period", "P1D", "alignTo", "FISCAL_YEAR_END", "fiscalStartMonth", 4,
                "fiscalStartDay", 15));
        assertEquals(15, r.fiscalStartDay());
        assertEquals(15, ValidityRule.from(r.toMap()).fiscalStartDay());
        assertEquals(LocalDate.of(2026, 4, 15).atStartOfDay(ZoneOffset.UTC).toInstant(),
                r.first(LocalDate.of(2026, 4, 14), null, ZoneOffset.UTC).validTo(), "valid through 14 April");
        assertEquals(LocalDate.of(2027, 4, 15).atStartOfDay(ZoneOffset.UTC).toInstant(),
                r.first(LocalDate.of(2026, 4, 15), null, ZoneOffset.UTC).validTo(), "valid through 14 April next year");
    }

    @Test
    void aStartDayOutside1To28IsRefused() {
        for (int bad : new int[] {0, 29, 31, -1}) {
            assertThrows(RegistryException.class, () -> NumberSeriesDefinition.from(Map.of("seriesKey", "S", "pattern", "{seq}",
                    "reset", "FISCAL_YEAR", "fiscalStartMonth", 4, "fiscalStartDay", bad)), "series day " + bad);
            assertThrows(RegistryException.class, () -> ValidityRule.from(Map.of("period", "P1Y", "alignTo", "FISCAL_YEAR_END",
                    "fiscalStartMonth", 4, "fiscalStartDay", bad)), "validity day " + bad);
        }
    }

    @Test
    void knownValues() {
        NumberSeriesDefinition april = new NumberSeriesDefinition("S", "{fy}/{seq}", "", "GAP_FREE", "FISCAL_YEAR", 4, 1, 50,
                "NONE", null);
        assertEquals("2026-27/1", april.format(1, null, LocalDate.of(2026, 4, 1), Map.of()));
        assertEquals("2025-26/1", april.format(1, null, LocalDate.of(2026, 3, 31), Map.of()));
        NumberSeriesDefinition january = new NumberSeriesDefinition("S", "{fy}/{seq}", "", "GAP_FREE", "FISCAL_YEAR", 1, 1, 50,
                "NONE", null);
        assertEquals("2026/1", january.format(1, null, LocalDate.of(2026, 12, 31), Map.of()));
        assertEquals("FY2026", january.periodKey(LocalDate.of(2026, 1, 1)));
    }
}
