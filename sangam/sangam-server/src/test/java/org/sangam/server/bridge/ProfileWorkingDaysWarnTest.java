// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/** SL-104: a tenant whose profile calendar does not resolve is logged as a WARN once per tenant, never silently. */
class ProfileWorkingDaysWarnTest {

    @Test
    void aMissingProfileCalendarWarnsOncePerTenant() {
        List<String> warnings = new ArrayList<>();
        ProfileWorkingDays days = new ProfileWorkingDays(t -> {
            throw new IllegalStateException("no active profile for " + t);
        }, warnings::add);

        assertEquals(Optional.empty(), days.of("t-1"));
        assertEquals(Optional.empty(), days.of("t-1"));
        assertEquals(Optional.empty(), days.of("t-2"));

        assertEquals(2, warnings.size(), "once per tenant: " + warnings);
        assertTrue(warnings.get(0).contains("t-1") && warnings.get(0).contains("UTC"), warnings.get(0));
        assertTrue(warnings.get(1).contains("t-2"), warnings.get(1));
    }

    @Test
    void aBlankTenantIsNotWarnedAbout() {
        List<String> warnings = new ArrayList<>();
        ProfileWorkingDays days = new ProfileWorkingDays(t -> null, warnings::add);
        assertEquals(Optional.empty(), days.of(null));
        assertEquals(Optional.empty(), days.of(" "));
        assertTrue(warnings.isEmpty());
    }
}