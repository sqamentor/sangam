// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.replay;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

/**
 * Decision 354: a calendar-date input as a replay fixture carries it ({@code ReplayFixture.ValueType.LOCAL_DATE} with
 * its {@code zone}): the date, and the zone it belongs to, the business zone of the tenant whose engine took it
 * ({@code BusinessClock}; UTC for a shared or unknown tenant). A {@link LocalDate} has no zone of its own, so without
 * one the fixture could not say which day it was; the capture never drops it for that reason.
 *
 * @param date the date (never null)
 * @param zone the zone it belongs to (never null)
 */
public record LocalDateInZone(LocalDate date, ZoneId zone) {

    public LocalDateInZone {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(zone, "zone");
    }
}
