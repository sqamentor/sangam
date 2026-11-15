// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.variable.Variables;
import org.operaton.bpm.engine.variable.value.TypedValue;

/** Decision 333: the local-date text format v1 and value type, strict in what they accept. */
class LocalDateTextTest {

    private static String code(Runnable r) {
        return assertThrows(LocalDateText.LocalDateFormatException.class, r::run).code();
    }

    @Test
    void formatV1IsIsoTextAndRoundTrips() {
        for (String s : List.of("2026-03-31", "0000-01-01", "0999-12-31", "9999-12-31", "2024-02-29")) {
            LocalDate d = LocalDateText.parse(s);
            assertEquals(s, LocalDateText.format(d), s);
            assertEquals(d, LocalDateText.exact(d));
            assertEquals(d, LocalDateText.exact(s));
        }
        assertEquals("sangam-local-date-v1", LocalDateValueSerializer.NAME, "the persisted type id is fixed");
    }

    @Test
    void anythingButStrictIsoDateTextIsRefused() {
        for (String bad : List.of("", " 2026-03-31", "2026-03-31 ", "2026-3-31", "26-03-31", "2026/03/31",
                "2026-03-31T00:00:00", "2026-03-31T00:00Z", "2026-03-31Z", "2026-03-31+05:30", "+2026-03-31",
                "-0001-01-01", "+10000-01-01", "2026-W13-2", "2026-090", "31-03-2026", "2026-02-30", "2026-13-01",
                "2026-00-10", "2025-02-29", "2026-04-31", "२०२६-०३-३१")) {
            assertEquals("LOCAL_DATE_INVALID", code(() -> LocalDateText.parse(bad)), bad);
        }
        assertEquals("LOCAL_DATE_NULL", code(() -> LocalDateText.parse(null)));
        assertEquals("LOCAL_DATE_NULL", code(() -> LocalDateText.format(null)));
        assertEquals("LOCAL_DATE_UNSUPPORTED", code(() -> LocalDateText.exact(20260331)));
        assertEquals("LOCAL_DATE_UNSUPPORTED", code(() -> LocalDateText.exact(new java.util.Date())));
    }

    @Test
    void yearsOutsideTheSortableRangeAreRefusedNeverWritten() {
        assertEquals("LOCAL_DATE_OUT_OF_RANGE", code(() -> LocalDateText.format(LocalDate.of(10000, 1, 1))));
        assertEquals("LOCAL_DATE_OUT_OF_RANGE", code(() -> LocalDateText.format(LocalDate.of(-1, 12, 31))));
        assertEquals("LOCAL_DATE_OUT_OF_RANGE", code(() -> LocalDateValueType.localDate(LocalDate.MAX)));
    }

    @Test
    void textOrderIsChronologicalOrderAcrossTheWholeRange() {
        List<LocalDate> days = new ArrayList<>(List.of(LocalDate.of(9999, 12, 31), LocalDate.of(2026, 3, 31),
                LocalDate.of(2026, 10, 1), LocalDate.of(999, 12, 31), LocalDate.of(1000, 1, 1), LocalDate.of(0, 1, 1),
                LocalDate.of(2026, 3, 9), LocalDate.of(2025, 12, 31)));
        List<String> byText = days.stream().map(LocalDateText::format).sorted().toList();
        List<String> byDate = days.stream().sorted(Comparator.naturalOrder()).map(LocalDateText::format).toList();
        assertEquals(byDate, byText);
    }

    @Test
    void valueTypeCreatesTypedValuesAndConvertsNothing() {
        TypedValue v = LocalDateValueType.LOCAL_DATE.createValue("2026-03-31", Map.of());
        assertEquals(LocalDateValueType.NAME, v.getType().getName());
        assertEquals(LocalDate.of(2026, 3, 31), v.getValue());
        assertTrue(LocalDateValueType.localDate(LocalDate.of(2026, 3, 31), true).isTransient());
        assertNull(LocalDateValueType.localDate(null).getValue());
        assertFalse(LocalDateValueType.LOCAL_DATE.canConvertFromTypedValue(Variables.stringValue("2026-03-31")),
                "a string variable is never turned into a date");
        assertFalse(LocalDateValueType.LOCAL_DATE.canConvertFromTypedValue(Variables.dateValue(new java.util.Date())));
        assertThrows(IllegalArgumentException.class,
                () -> LocalDateValueType.LOCAL_DATE.convertFromTypedValue(Variables.stringValue("2026-03-31")));
        assertEquals("LOCAL_DATE_INVALID", code(() -> LocalDateValueType.LOCAL_DATE.createValue("2026-03-31T00:00:00Z",
                Map.of())));
    }
}
