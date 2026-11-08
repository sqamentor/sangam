// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.gov;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.Set;

import org.sangam.core.engine.WorkCalendar;

/**
 * The spike's product-layer business-day calendar (GP-06): Saturday, Sunday and listed holidays are not working days.
 * SYNTHETIC holiday list. Also a Sangam {@link WorkCalendar} (FC-23), so a timer can name it with
 * {@code cal:<name>:P3D}: the period's days count as working days, the time part is added as is.
 */
final class GovACal implements WorkCalendar {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final Set<LocalDate> holidays;

    GovACal(Set<LocalDate> holidays) {
        this.holidays = Set.copyOf(holidays);
    }

    boolean isWorkingDay(LocalDate d) {
        return d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY && !holidays.contains(d);
    }

    Date addWorkingDays(Date start, int days) {
        return Date.from(addWorkingDays(start.toInstant(), days));
    }

    private Instant addWorkingDays(Instant start, int days) {
        ZonedDateTime t = start.atZone(IST);
        int left = days;
        while (left > 0) {
            t = t.plusDays(1);
            if (isWorkingDay(t.toLocalDate())) {
                left--;
            }
        }
        return t.toInstant();
    }

    @Override
    public Instant add(Instant start, Period period, Duration duration, ZoneId zone) {
        Instant t = start.atZone(IST).plusYears(period.getYears()).plusMonths(period.getMonths()).toInstant();
        return addWorkingDays(t, period.getDays()).plus(duration);
    }
}
