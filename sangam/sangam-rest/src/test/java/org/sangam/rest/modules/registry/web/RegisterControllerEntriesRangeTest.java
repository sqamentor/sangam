// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.registry.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.sangam.rest.port.ApiProblem;

/**
 * Code scanning java/tainted-arithmetic (#686): {@code GET .../books/{bookId}/entries?from=&count=} added the caller's
 * {@code from} to the count without a bound, so a huge {@code from} wrapped the upper entry number to a negative value.
 * A {@code from} outside the entry numbers is now 400 VALIDATION_FAILED before the addition and before any read.
 */
class RegisterControllerEntriesRangeTest {

    /** No service and no transaction support: a refused range must not reach either. */
    private final RegisterController controller = new RegisterController(null, null);

    private static void assertRefused(ApiProblem p) {
        assertEquals(400, p.status());
        assertEquals("VALIDATION_FAILED", p.code());
        assertEquals("from", p.extensions().get("field"));
    }

    @Test
    void aFromThatWouldOverflowIsRefusedBeforeAnyRead() {
        assertRefused(assertThrows(ApiProblem.class, () -> controller.entries("book-1", Long.MAX_VALUE, 100)));
        assertRefused(assertThrows(ApiProblem.class, () -> controller.entries("book-1", Long.MAX_VALUE - 998, 1000)));
        assertRefused(assertThrows(ApiProblem.class, () -> controller.entries("book-1", Long.MAX_VALUE, Long.MAX_VALUE)));
    }

    @Test
    void aFromBelowTheFirstEntryNumberIsRefused() {
        assertRefused(assertThrows(ApiProblem.class, () -> controller.entries("book-1", 0, 100)));
        assertRefused(assertThrows(ApiProblem.class, () -> controller.entries("book-1", -5, 100)));
        assertRefused(assertThrows(ApiProblem.class, () -> controller.entries("book-1", Long.MIN_VALUE, 100)));
    }

    @Test
    void theRangeIsFromPlusTheBoundedCountMinusOne() {
        assertEquals(100, RegisterController.lastEntry(1, 100));
        assertEquals(1000, RegisterController.lastEntry(1, 5000), "count is capped at 1000");
        assertEquals(7, RegisterController.lastEntry(7, 0), "count is at least 1");
        assertEquals(7, RegisterController.lastEntry(7, -3));
        assertEquals(Long.MAX_VALUE, RegisterController.lastEntry(Long.MAX_VALUE, 1), "the largest range that fits");
        assertEquals(Long.MAX_VALUE, RegisterController.lastEntry(Long.MAX_VALUE - 999, 1000));
    }
}
