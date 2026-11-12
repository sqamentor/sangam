// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.catalogue;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.config.common.Finding;

/**
 * SL-122: a single-head bill sends {@code headOfAccount} and {@code amount} (integer minor units, SD-398 / PACKS4), not
 * {@code lines}; the type's classification allow-list, nil rule and amount limit ({@code limits.maxAmount}, minor units
 * as profilegen money.minor() writes profile money) apply to it, and a limit that is not an amount is an error.
 */
class DocumentCheckWithoutLinesTest {

    static final LocalDate AS_OF = LocalDate.of(2026, 11, 5);

    static DocumentTypeCatalogue catalogue(Object maxAmount) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("id", "CONT");
        t.put("formNumber", "C-1");
        t.put("sanction", "REFERENCE");
        t.put("nilAllowed", false);
        t.put("classification", Map.of("allow", List.of("2054-*")));
        t.put("limits", maxAmount == null ? Map.of() : Map.of("maxAmount", maxAmount));
        return new DocumentTypeCatalogue(List.of(t), List.of());
    }

    static List<String> errors(DocumentTypeCatalogue c, Map<String, Object> doc) {
        return Finding.errors(c.check("CONT", doc, AS_OF, null)).stream().map(Finding::code).toList();
    }

    @Test
    void topLevelAmountAndHeadOfAccountAreChecked() {
        DocumentTypeCatalogue c = catalogue(10_000_000L);   // Rs 1 lakh in paise
        assertEquals(List.of(), errors(c, Map.of("headOfAccount", "2054-00-001-01", "amount", 10_000_000L)));
        assertEquals(List.of("AMOUNT_ABOVE_LIMIT"), errors(c, Map.of("headOfAccount", "2054-00-001-01",
                "amount", "10000001")), "one paisa above the limit");
        assertEquals(List.of("CLASSIFICATION_NOT_ALLOWED"), errors(c, Map.of("headOfAccount", "2202-01-101-01",
                "amount", 5_000L)));
        assertEquals(List.of("NIL_NOT_ALLOWED"), errors(c, Map.of("headOfAccount", "2054-00-001-01", "amount", 0L)));
        assertEquals(List.of("CLASSIFICATION_NOT_ALLOWED"), errors(c, Map.of("amount", 5_000L)),
                "a restricted type and no head of account: not allowed, never skipped");
        assertEquals(List.of(), errors(c, Map.of()), "no amount and no head: nothing to check (as before)");
    }

    @Test
    void explicitLinesStillWin() {
        DocumentTypeCatalogue c = catalogue(10_000_000L);
        Map<String, Object> doc = Map.of("headOfAccount", "2202-01-101-01", "amount", 99_000_000L,
                "lines", List.of(Map.of("classification", "2054-00-001-01", "amount", 4_000_000L),
                        Map.of("classification", "2054-00-001-02", "amount", 5_000_000L)));
        assertEquals(List.of(), errors(c, doc));
    }

    @Test
    void aLimitThatIsNotAnAmountIsAnErrorNeverASkippedCheck() {
        assertEquals(List.of("LIMIT_INVALID"), errors(catalogue("10L"), Map.of("headOfAccount", "2054-00-001-01",
                "amount", 5_000L)));
        assertEquals(List.of(), errors(catalogue(null), Map.of("headOfAccount", "2054-00-001-01",
                "amount", 99_000_000_000L)), "no limit set: none applies");
    }
}
