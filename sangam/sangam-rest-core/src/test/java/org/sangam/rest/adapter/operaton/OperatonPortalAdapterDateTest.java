// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/**
 * Decision 333: a portal date variable ({@code sangamSlaDate}, {@code sangamAppealDeadline}) written as a typed
 * {@code LocalDate} reads as the start of that day in UTC, exactly as the same date written as ISO text always did.
 */
class OperatonPortalAdapterDateTest {

    @Test
    void aTypedLocalDateAndItsIsoTextGiveTheSameInstant() {
        Instant want = Instant.parse("2026-03-31T00:00:00Z");
        assertEquals(want, OperatonPortalAdapter.instant(LocalDate.of(2026, 3, 31)), "typed date (was null)");
        assertEquals(want, OperatonPortalAdapter.instant("2026-03-31"), "string date, as before");
        assertEquals(Instant.parse("2026-03-31T18:30:00Z"), OperatonPortalAdapter.instant("2026-03-31T18:30:00Z"));
        assertNull(OperatonPortalAdapter.instant("not a date"));
        assertNull(OperatonPortalAdapter.instant(null));
    }
}
