// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * M-042 (decision 82): the per-process mapping is validated when it is read, an amount is integral minor units with an
 * ISO currency, a reference is bounded, and an amount sort is recognised in a multi-key sortBy.
 */
class TaskIndexTest {

    static Map<String, Object> m(Object... kv) {
        Map<String, Object> out = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            out.put((String) kv[i], kv[i + 1]);
        }
        return out;
    }

    @Test
    void theMappingIsValidatedWhenRead() {
        TaskIndex.Mapping ok = TaskIndex.Mapping.of(m("amount", "billAmountMinor", "currency", "billCurrency",
                "reference", "billNo"));
        assertEquals("billAmountMinor", ok.amount());
        assertEquals("INR", TaskIndex.Mapping.of(m("amount", "a", "currencyCode", "INR")).currencyCode());
        assertThrows(IllegalArgumentException.class, () -> TaskIndex.Mapping.of(null), "taskIndex object required");
        assertThrows(IllegalArgumentException.class, () -> TaskIndex.Mapping.of(m("amount", "a")), "amount needs currency");
        assertThrows(IllegalArgumentException.class, () -> TaskIndex.Mapping.of(m("amount", "a", "currency", "c",
                "currencyCode", "INR")), "one currency source");
        assertThrows(IllegalArgumentException.class, () -> TaskIndex.Mapping.of(m("currencyCode", "INR")), "nothing indexed");
        assertThrows(IllegalArgumentException.class, () -> TaskIndex.Mapping.of(m("amount", "a", "currencyCode", "inr")));
        assertThrows(IllegalArgumentException.class, () -> TaskIndex.Mapping.of(m("reference", "a b")), "variable name");
        assertThrows(IllegalArgumentException.class, () -> TaskIndex.Mapping.of(m("reference", "r", "sort", "x")),
                "unknown member");
    }

    @Test
    void valuesAreExtractedTyped() {
        TaskIndex.Mapping map = TaskIndex.Mapping.of(m("amount", "a", "currency", "c", "reference", "r"));
        Map<String, Object> vars = m("a", 12_500L, "c", "inr", "r", " BILL-7 ");
        assertEquals(new TaskIndex.Values(12_500L, "INR", "BILL-7"), map.extract(vars::get));
        assertEquals(new TaskIndex.Values(null, null, "BILL-7"), map.extract(m("r", "BILL-7")::get),
                "no amount: no currency either");
        assertThrows(IllegalStateException.class, () -> map.extract(m("a", 5L)::get), "an amount needs its currency");
        TaskIndex.Mapping fixed = TaskIndex.Mapping.of(m("amount", "a", "currencyCode", "USD"));
        assertEquals("USD", fixed.extract(m("a", 7)::get).currency());
    }

    @Test
    void anAmountIsIntegralMinorUnits() {
        assertEquals(5L, TaskIndex.amountOf("a", 5));
        assertEquals(5L, TaskIndex.amountOf("a", (short) 5));
        assertEquals(5L, TaskIndex.amountOf("a", new BigDecimal("5.00")));
        assertEquals(5L, TaskIndex.amountOf("a", BigInteger.valueOf(5)));
        assertEquals(-12L, TaskIndex.amountOf("a", " -12 "));
        assertNull(TaskIndex.amountOf("a", null));
        assertThrows(IllegalStateException.class, () -> TaskIndex.amountOf("a", new BigDecimal("5.5")));
        assertThrows(IllegalStateException.class, () -> TaskIndex.amountOf("a", "5.5"));
        assertThrows(IllegalStateException.class, () -> TaskIndex.amountOf("a", 5.0d), "no floating point money");
        assertThrows(IllegalStateException.class, () -> TaskIndex.amountOf("a", BigInteger.TWO.pow(70)));
    }

    @Test
    void currencyAndReferenceAreBounded() {
        assertEquals("EUR", TaskIndex.currencyOf("eur"));
        assertThrows(IllegalStateException.class, () -> TaskIndex.currencyOf("EURO"));
        assertNull(TaskIndex.referenceOf("   "));
        assertThrows(IllegalStateException.class, () -> TaskIndex.referenceOf("x".repeat(TaskIndex.MAX_REFERENCE + 1)));
    }

    @Test
    void anAmountSortIsFoundInAMultiKeySort() {
        assertTrue(TaskIndex.sortsByAmount("amount"));
        assertTrue(TaskIndex.sortsByAmount("created, amount"));
        assertFalse(TaskIndex.sortsByAmount("reference,created"));
        assertFalse(TaskIndex.sortsByAmount(null));
    }

    @Test
    void theRecorderRefusesARuleWithoutMapping() {
        TaskIndex.Recorder r = new TaskIndex.Recorder(Map.of());
        assertThrows(IllegalStateException.class, () -> r.record(null, "unknown"));
    }
}
