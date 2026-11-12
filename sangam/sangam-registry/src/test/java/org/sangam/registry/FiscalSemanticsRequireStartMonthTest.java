// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.registry.kernel.ValidityRule;
import org.sangam.registry.numbering.NumberSeriesDefinition;

/**
 * SL-125 (fail closed): a number series with a FISCAL_YEAR reset or a {@code {fy}} / {@code {fyStart}} token, and a
 * validity aligned to FISCAL_YEAR_END, state their {@code fiscalStartMonth}; none is assumed (the old default was 1,
 * a January year). Definitions without fiscal semantics need no month and store none.
 */
class FiscalSemanticsRequireStartMonthTest {

    static final ZoneId UTC = ZoneId.of("UTC");

    static Map<String, Object> series(String reset, String pattern, Object month) {
        Map<String, Object> m = new HashMap<>(Map.of("seriesKey", "TL", "reset", reset, "pattern", pattern));
        if (month != null) {
            m.put("fiscalStartMonth", month);
        }
        return m;
    }

    @Test
    void aFiscalSeriesWithoutStartMonthIsInvalid() {
        RegistryException reset = assertThrows(RegistryException.class,
                () -> NumberSeriesDefinition.from(series("FISCAL_YEAR", "{prefix}{seq:6}", null)));
        assertTrue(reset.getMessage().contains("fiscalStartMonth"), reset.getMessage());
        assertThrows(RegistryException.class, () -> NumberSeriesDefinition.from(series("NEVER", "{prefix}/{fy}/{seq:6}", null)));
        assertThrows(RegistryException.class, () -> NumberSeriesDefinition.from(series("YEARLY", "{fyStart}-{seq}", null)));
        assertThrows(RegistryException.class, () -> NumberSeriesDefinition.from(series("FISCAL_YEAR", "{seq}", 13)));
    }

    @Test
    void aFiscalSeriesUsesItsOwnStartMonth() {
        NumberSeriesDefinition d = NumberSeriesDefinition.from(series("FISCAL_YEAR", "{prefix}/{fy}/{seq:3}", "7"));
        assertEquals(7, d.fiscalStartMonth());
        assertEquals("FY2026", d.periodKey(LocalDate.of(2026, 7, 1)));
        assertEquals("FY2025", d.periodKey(LocalDate.of(2026, 6, 30)));
        assertEquals("/2026-27/001", d.format(1, null, LocalDate.of(2026, 11, 12), Map.of()));
        assertEquals(7, d.toMap().get("fiscalStartMonth"));
        assertEquals(7, NumberSeriesDefinition.from(d.toMap()).fiscalStartMonth()); // the stored form reads back
    }

    @Test
    void aSeriesWithoutFiscalSemanticsNeedsAndStoresNoMonth() {
        NumberSeriesDefinition d = NumberSeriesDefinition.from(series("YEARLY", "{prefix}/{yyyy}/{seq:4}", null));
        assertNull(d.fiscalStartMonth());
        assertFalse(d.toMap().containsKey("fiscalStartMonth"), d.toMap()::toString);
        assertEquals("2026", d.periodKey(LocalDate.of(2026, 11, 12)));
        assertEquals("/2026/0005", d.format(5, null, LocalDate.of(2026, 11, 12), Map.of()));
        assertThrows(RegistryException.class, () -> d.fiscalStartYear(LocalDate.of(2026, 11, 12)));
    }

    @Test
    void aFiscalYearEndValidityWithoutStartMonthIsInvalid() {
        RegistryException e = assertThrows(RegistryException.class,
                () -> ValidityRule.from(Map.of("period", "P1Y", "alignTo", "FISCAL_YEAR_END")));
        assertTrue(e.getMessage().contains("fiscalStartMonth"), e.getMessage());
        assertThrows(RegistryException.class,
                () -> ValidityRule.from(Map.of("period", "P1Y", "alignTo", "FISCAL_YEAR_END", "fiscalStartMonth", 0)));
    }

    @Test
    void aFiscalYearEndValidityEndsOnTheConfiguredYearEnd() {
        ValidityRule r = ValidityRule.from(Map.of("period", "P1Y", "alignTo", "FISCAL_YEAR_END", "fiscalStartMonth", 7));
        ValidityRule.Window w = r.first(LocalDate.of(2026, 11, 12), null, UTC);
        // P1Y from 2026-11-12 ends 2027-11-11, aligned to the July-June year end 2028-06-30 (exclusive 2028-07-01)
        assertEquals(Instant.parse("2028-07-01T00:00:00Z"), w.validTo());
        ValidityRule plain = ValidityRule.from(Map.of("period", "P1Y", "alignTo", "END_OF_MONTH"));
        assertNull(plain.fiscalStartMonth());
        assertFalse(plain.toMap().containsKey("fiscalStartMonth"), plain.toMap()::toString);
    }
}
