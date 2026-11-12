// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.config.common.ConfigException;

/**
 * SL-125 (owner 2026-11-12, fail closed core-wide): no fiscal year is ever assumed. An absent {@code locale.fiscal} is
 * "not configured" (never a 1 January calendar year); inside a present one {@code startMonth}, {@code startDay} and
 * {@code label} are required; the locale still builds without it, so money, zone and calendars keep working, and only
 * {@code fiscal()} refuses, naming the profile and the path.
 */
class FiscalCalendarTest {

    static final ZoneId UTC = ZoneId.of("UTC");

    static Map<String, Object> fiscal(Object month, Object day, Object label) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (month != null) {
            m.put("startMonth", month);
        }
        if (day != null) {
            m.put("startDay", day);
        }
        if (label != null) {
            m.put("label", label);
        }
        return m;
    }

    @Test
    void anAbsentOrEmptyFiscalMapIsNotConfiguredNeverACalendarYear() {
        assertTrue(FiscalCalendar.from(null, UTC).isEmpty());
        assertTrue(FiscalCalendar.from(Map.of(), UTC).isEmpty());
    }

    @Test
    void startMonthStartDayAndLabelAreRequired() {
        IllegalArgumentException m = assertThrows(IllegalArgumentException.class,
                () -> FiscalCalendar.from(fiscal(null, 1, "START"), UTC));
        assertTrue(m.getMessage().contains("locale.fiscal.startMonth"), m.getMessage());
        IllegalArgumentException d = assertThrows(IllegalArgumentException.class,
                () -> FiscalCalendar.from(fiscal(7, null, "END"), UTC));
        assertTrue(d.getMessage().contains("locale.fiscal.startDay"), d.getMessage());
        IllegalArgumentException l = assertThrows(IllegalArgumentException.class,
                () -> FiscalCalendar.from(fiscal(7, 1, null), UTC));
        assertTrue(l.getMessage().contains("locale.fiscal.label"), l.getMessage());
        assertThrows(IllegalArgumentException.class, () -> FiscalCalendar.from(fiscal(7, 1, "FY-STYLE"), UTC));
        assertThrows(IllegalArgumentException.class, () -> FiscalCalendar.from(fiscal("July", 1, "END"), UTC));
        assertThrows(IllegalArgumentException.class, () -> new FiscalCalendar(7, 1, UTC, null));
    }

    @Test
    void aCompleteFiscalMapIsUsedAsWritten() {
        FiscalCalendar c = FiscalCalendar.from(fiscal(10, 1, "END"), ZoneId.of("America/New_York")).orElseThrow();
        assertEquals(10, c.startMonth());
        assertEquals(1, c.startDay());
        assertEquals(ZoneId.of("America/New_York"), c.zone());
        assertEquals(0, c.adjustmentPeriods());
        assertEquals("2027", c.yearOf(LocalDate.of(2026, 11, 12)).label());
        assertEquals("2026", c.yearOf(LocalDate.of(2026, 9, 30)).label());
        FiscalCalendar april = FiscalCalendar.from(fiscal("4", "1", "START-END2"), UTC).orElseThrow();
        assertEquals("2026-27", april.yearOf(LocalDate.of(2026, 11, 12)).label());
    }

    @Test
    void aLocaleWithoutFiscalBuildsAndRefusesOnlyTheFiscalOperation() {
        Map<String, Object> locale = Map.of("zone", "America/New_York", "currency", "USD", "units", Map.of("K", 1000));
        LocaleSettings s = LocaleSettings.from(locale, "profile US (INTL@1>US@1#abc)");
        assertFalse(s.hasFiscal());
        assertNull(s.fiscalProblem());
        // money, zone and calendars are unaffected
        assertEquals(ZoneId.of("America/New_York"), s.zone());
        assertEquals(0, new BigDecimal("12000").compareTo(s.money().parse("12K")));
        assertEquals(ZoneId.of("America/New_York"), s.calendar("x").zone());
        FiscalYearNotConfiguredException e = assertThrows(FiscalYearNotConfiguredException.class, s::fiscal);
        assertEquals(FiscalYearNotConfiguredException.CODE, e.code());
        assertTrue(e.getMessage().contains("profile US"), e.getMessage());
        assertTrue(e.getMessage().contains("locale.fiscal"), e.getMessage());
        assertTrue(e instanceof ConfigException);
    }

    @Test
    void anIncompleteFiscalMapStillBuildsTheLocaleButFiscalNamesTheMissingPart() {
        LocaleSettings s = LocaleSettings.from(Map.of("currency", "EUR", "fiscal", fiscal(7, null, "END")), "profile X");
        assertFalse(s.hasFiscal());
        FiscalYearNotConfiguredException e = assertThrows(FiscalYearNotConfiguredException.class, s::fiscal);
        assertTrue(e.getMessage().contains("locale.fiscal.startDay"), e.getMessage());
        assertTrue(e.getMessage().contains("profile X"), e.getMessage());
    }

    @Test
    void aCompleteFiscalMapIsAvailable() {
        LocaleSettings s = LocaleSettings.from(Map.of("zone", "Africa/Nairobi", "fiscal", fiscal(7, 1, "END")));
        assertTrue(s.hasFiscal());
        assertEquals(7, s.fiscal().startMonth());
        assertEquals(ZoneId.of("Africa/Nairobi"), s.fiscal().zone());
    }
}
