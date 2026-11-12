// SPDX-License-Identifier: Apache-2.0
package org.sangam.ledger;

import org.junit.jupiter.api.Test;
import org.sangam.config.locale.FiscalYearNotConfiguredException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * X1-08: one "explicit period, else current fiscal period" rule for the ledger connector, the billing connector and the
 * billing refund. {@code period} wins over the alias {@code fiscalYear}; missing, blank or {@code current} asks the
 * resolver, and only then; a null resolver refuses with FISCAL_YEAR_NOT_CONFIGURED (never a calendar year).
 */
class LedgerPeriodsTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 12);

    private static Map<String, Object> in(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void explicitTakesPeriodFirstThenTheFiscalYearAlias() {
        assertEquals("2025-26", LedgerPeriods.explicit(in("period", "2025-26", "fiscalYear", "2024-25")));
        assertEquals("2024-25", LedgerPeriods.explicit(in("fiscalYear", "2024-25")));
        assertEquals("2024-25", LedgerPeriods.explicit(in("period", " ", "fiscalYear", "2024-25")));
        assertEquals("2026", LedgerPeriods.explicit(in("fiscalYear", 2026)));
        assertNull(LedgerPeriods.explicit(in()));
        assertNull(LedgerPeriods.explicit(null));
        assertEquals(List.of("period", "fiscalYear"), LedgerPeriods.KEYS);
    }

    @Test
    void anExplicitPeriodNeverAsksTheResolver() {
        List<String> asked = new ArrayList<>();
        BiFunction<String, LocalDate, String> r = (t, d) -> {
            asked.add(t);
            return "2026-27";
        };
        assertEquals("2025-26", LedgerPeriods.periodOr(in("period", "2025-26"), "T", DAY, r));
        assertEquals("2024-25", LedgerPeriods.periodOr(in("fiscalYear", "2024-25"), "T", DAY, r));
        assertEquals("2025-26", LedgerPeriods.periodOr("2025-26", () -> {
            throw new AssertionError("asked");
        }));
        assertTrue(asked.isEmpty(), asked.toString());
    }

    @Test
    void missingBlankOrCurrentAsksTheResolverForTheTenantAndDay() {
        List<String> asked = new ArrayList<>();
        BiFunction<String, LocalDate, String> r = (t, d) -> {
            asked.add(t + "@" + d);
            return "2026-27";
        };
        for (Map<String, Object> m : List.of(in(), in("period", ""), in("period", "current"), in("period", "CURRENT"),
                in("fiscalYear", "current"), in("period", "current", "fiscalYear", "2024-25"))) {
            assertEquals("2026-27", LedgerPeriods.periodOr(m, "T", DAY, r), m.toString());
        }
        assertEquals(6, asked.size());
        assertTrue(asked.stream().allMatch("T@2026-11-12"::equals), asked.toString());
        assertEquals("2026-27", LedgerPeriods.periodOr((String) null, () -> "2026-27"));
        assertEquals("2026-27", LedgerPeriods.periodOr(" current ", () -> "2026-27"));
    }

    @Test
    void withoutAResolverOrAFiscalYearTheStepIsRefusedNeverACalendarYear() {
        assertThrows(FiscalYearNotConfiguredException.class, () -> LedgerPeriods.periodOr(in(), "T", DAY, null));
        assertThrows(FiscalYearNotConfiguredException.class, () -> LedgerPeriods.periodOr(in("period", "current"), "T", DAY,
                (t, d) -> {
                    throw FiscalYearNotConfiguredException.forSubject("profile T");
                }));
        assertEquals("2025-26", LedgerPeriods.periodOr(in("fiscalYear", "2025-26"), "T", DAY, null));
    }
}
