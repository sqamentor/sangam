// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.engine;

import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneId;

/**
 * A named business calendar (FC-23, D-11, R79): working days, holidays, office hours. Timer expressions select one
 * with the prefix {@code cal:<name>:}, for example {@code cal:mh-gov:P3D}. Implementations come from packs or the
 * tenant configuration; the engine module registers them by name.
 */
@FunctionalInterface
public interface WorkCalendar {

    /** Adds a calendar period and a time duration to {@code start}, counting only working time. */
    Instant add(Instant start, Period period, Duration duration, ZoneId zone);

    /** Plain calendar arithmetic in {@code zone} (every day counts). */
    WorkCalendar PLAIN = (start, period, duration, zone) -> start.atZone(zone).plus(period).toInstant().plus(duration);
}
