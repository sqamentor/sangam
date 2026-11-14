// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Decision 211: Task.amount is MoneyAmount text in major units, computed exactly from the index's minor units. */
class TaskAmountTest {

    private static TaskPort.Task task() {
        return new TaskPort.Task("t-1", "Approve", null, "approve", "pi-1", "bill", null, null, null, null, null, "T1",
                null, null, null, null, null, "CREATED", 50, null, null);
    }

    @Test
    void minorUnitsBecomeMajorUnitsWithTheCurrencyScale() {
        assertEquals("123.45", task().withIndex(12345L, "INR", "B-1").amount());
        assertEquals("500", task().withIndex(500L, "JPY", null).amount(), "JPY has no fraction digits");
        assertEquals("1.234", task().withIndex(1234L, "KWD", null).amount(), "KWD has three");
        assertEquals("3.00", task().withIndex(300L, "USD", null).amount(), "exactly the currency's digits, never stripped");
        assertEquals("-0.05", task().withIndex(-5L, "INR", null).amount());
        assertEquals("0.00", task().withIndex(0L, "EUR", null).amount());
        assertEquals("92233720368547758.07", task().withIndex(Long.MAX_VALUE, "INR", null).amount(), "no binary rounding");
    }

    @Test
    void theIndexValuesAreCarriedAsIs() {
        TaskPort.Task t = task().withIndex(12345L, "INR", "B-1");
        assertEquals("INR", t.currency());
        assertEquals("B-1", t.reference());
        TaskPort.Task referenceOnly = task().withIndex(null, null, "B-0");
        assertNull(referenceOnly.amount());
        assertNull(referenceOnly.currency());
        assertEquals("B-0", referenceOnly.reference());
    }

    @Test
    void anAmountWithoutAValidCurrencyIsRefused() {
        assertThrows(IllegalStateException.class, () -> TaskPort.Task.moneyAmount(100L, null));
        assertThrows(IllegalStateException.class, () -> TaskPort.Task.moneyAmount(100L, " "));
        assertThrows(IllegalArgumentException.class, () -> TaskPort.Task.moneyAmount(100L, "XXX"),
                "a pseudo-currency has no minor unit");
        assertThrows(IllegalArgumentException.class, () -> TaskPort.Task.moneyAmount(100L, "ZZZ"));
    }
}
