// SPDX-License-Identifier: Apache-2.0
package org.sangam.programme.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Month;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.programme.ProgrammeException;

/**
 * SL-125 (fail closed): the programme configuration states the fiscal-year start month of its reporting periods; a
 * configuration without it (the old default was January) or with a month outside 1..12 is refused at publication.
 */
class ProgrammeConfigFiscalStartTest {

    @Test
    void aConfigurationWithoutFiscalYearStartMonthIsRefused() {
        ProgrammeException e = assertThrows(ProgrammeException.class, () -> ProgrammeConfig.parse(Map.of()));
        assertEquals(ProgrammeException.Code.INVALID_CONFIG, e.code());
        assertTrue(e.getMessage().contains("fiscalYearStartMonth"), e.getMessage());
        assertThrows(ProgrammeException.class, () -> ProgrammeConfig.parse(Map.of("fiscalYearStartMonth", "")));
        assertThrows(ProgrammeException.class, () -> ProgrammeConfig.parse(Map.of("fiscalYearStartMonth", 13)));
        assertThrows(ProgrammeException.class, () -> ProgrammeConfig.parse(Map.of("fiscalYearStartMonth", "April")));
    }

    @Test
    void theConfiguredStartMonthIsUsed() {
        assertEquals(Month.JULY, ProgrammeConfig.parse(Map.of("fiscalYearStartMonth", 7)).fiscalYearStartMonth());
        assertEquals(Month.OCTOBER, ProgrammeConfig.parse("{\"fiscalYearStartMonth\": \"10\"}").fiscalYearStartMonth());
    }
}
