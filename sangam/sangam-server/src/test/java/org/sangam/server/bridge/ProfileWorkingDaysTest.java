// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sangam.config.locale.BusinessCalendar;
import org.sangam.packs.sim.WorkingDayCalendar;

/** SL-34: pack SLAs count the working days of the tenant's profile calendar (zone, weekend, holidays). */
class ProfileWorkingDaysTest {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Test
    void profileHolidayMovesAPackWorkingDayDeadline() {
        // Friday 2026-01-23, 10:00 IST; Monday 2026-01-26 (Republic Day) is a holiday in the tenant's profile only
        Instant from = ZonedDateTime.of(2026, 1, 23, 10, 0, 0, 0, IST).toInstant();
        BusinessCalendar gov = new BusinessCalendar("government", IST, Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
                Set.of(LocalDate.of(2026, 1, 26)), Set.of(), null);
        WorkingDayCalendar packs = new WorkingDayCalendar(ZoneId.of("UTC"),
                new ProfileWorkingDays(t -> "state-a".equals(t) ? gov : null));

        LocalDate withHoliday = packs.due("state-a", from, "1WD").atZone(IST).toLocalDate();
        LocalDate other = packs.due("state-b", from, "1WD").atZone(IST).toLocalDate();

        assertEquals(LocalDate.of(2026, 1, 27), withHoliday, "the profile holiday is skipped");
        assertEquals(LocalDate.of(2026, 1, 26), other, "a tenant without that holiday keeps Monday");
        assertTrue(!packs.isWorkingDay("state-a", ZonedDateTime.of(2026, 1, 26, 12, 0, 0, 0, IST).toInstant()));
    }

    @Test
    void profileWeekendAndExtraWorkingDayApply() {
        // a settlement calendar: only Sunday off, and Sunday 2027-03-28 declared a working day
        BusinessCalendar settlement = new BusinessCalendar("settlement", IST, Set.of(DayOfWeek.SUNDAY), Set.of(),
                Set.of(LocalDate.of(2027, 3, 28)), null);
        WorkingDayCalendar packs = new WorkingDayCalendar(IST, new ProfileWorkingDays(t -> settlement));
        Instant fri = ZonedDateTime.of(2027, 3, 26, 10, 0, 0, 0, IST).toInstant();
        assertEquals(LocalDate.of(2027, 3, 27), packs.due("t", fri, "1WD").atZone(IST).toLocalDate(), "Saturday works");
        assertEquals(LocalDate.of(2027, 3, 28), packs.due("t", fri, "2WD").atZone(IST).toLocalDate(), "extra Sunday");
    }

    @Test
    void unresolvedProfileKeepsTheDefaultCalendar() {
        WorkingDayCalendar packs = new WorkingDayCalendar(IST, new ProfileWorkingDays(t -> {
            throw new IllegalStateException("no profile");
        }));
        Instant fri = ZonedDateTime.of(2026, 1, 23, 10, 0, 0, 0, IST).toInstant();
        assertEquals(LocalDate.of(2026, 1, 26), packs.due("x", fri, "1WD").atZone(IST).toLocalDate());
        assertEquals(LocalDate.of(2026, 1, 26), packs.due(null, fri, "1WD").atZone(IST).toLocalDate());
    }
}
