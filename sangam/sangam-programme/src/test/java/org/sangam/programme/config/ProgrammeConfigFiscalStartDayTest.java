// SPDX-License-Identifier: Apache-2.0
package org.sangam.programme.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Month;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sangam.programme.ProgrammeException;

/** X1-03 (owner decision 2026-11-12): the optional fiscalStartDay of the programme configuration, 1..28, default 1. */
class ProgrammeConfigFiscalStartDayTest {

    @Test
    void anAbsentStartDayIsTheFirst() {
        assertEquals(1, ProgrammeConfig.parse(Map.of("fiscalYearStartMonth", 4)).fiscalStartDay());
        assertEquals(1, ProgrammeConfig.parse(Map.of("fiscalYearStartMonth", 4, "fiscalStartDay", "")).fiscalStartDay());
        assertEquals(1, new ProgrammeConfig(Month.APRIL, Set.of(), Map.of(), BigDecimal.TEN, BigDecimal.ONE, "MILESTONES",
                Map.of(), Map.of()).fiscalStartDay(), "the 8-argument constructor keeps the 1st");
    }

    @Test
    void aStatedStartDayIsReadFromThePublishedJson() {
        assertEquals(15, ProgrammeConfig.parse(Map.of("fiscalYearStartMonth", 4, "fiscalStartDay", 15)).fiscalStartDay());
        assertEquals(28, ProgrammeConfig.parse("{\"fiscalYearStartMonth\": 2, \"fiscalStartDay\": \"28\"}").fiscalStartDay());
        assertEquals(1, ProgrammeConfig.parse("{\"fiscalYearStartMonth\": 7, \"fiscalStartDay\": 1}").fiscalStartDay());
    }

    @Test
    void aStartDayOutside1To28IsRefused() {
        for (Object bad : new Object[] {0, 29, 31, -3, "x", "15th"}) {
            ProgrammeException e = assertThrows(ProgrammeException.class,
                    () -> ProgrammeConfig.parse(Map.of("fiscalYearStartMonth", 4, "fiscalStartDay", bad)), String.valueOf(bad));
            assertEquals(ProgrammeException.Code.INVALID_CONFIG, e.code());
            assertTrue(e.getMessage().contains("fiscalStartDay"), e.getMessage());
        }
        assertThrows(ProgrammeException.class, () -> new ProgrammeConfig(Month.APRIL, Set.of(), Map.of(), BigDecimal.TEN,
                BigDecimal.ONE, "MILESTONES", Map.of(), Map.of(), 29));
    }
}
