package org.sangam.spike.gova.support;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Product-layer business-day calendar (GP-06): Saturday, Sunday and listed gazetted holidays are not working days.
 * "P&lt;n&gt;D" means n working days at the same time of day; everything else is not handled here.
 * SYNTHETIC holiday list for the spike.
 */
public final class BizCal {

    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Pattern DAYS = Pattern.compile("P(\\d+)D");

    private final Set<LocalDate> holidays;

    public BizCal(Set<LocalDate> holidays) {
        this.holidays = Set.copyOf(holidays);
    }

    public boolean isWorkingDay(LocalDate d) {
        return d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY && !holidays.contains(d);
    }

    public Date addWorkingDays(Date start, int days) {
        ZonedDateTime t = start.toInstant().atZone(IST);
        int left = days;
        while (left > 0) {
            t = t.plusDays(1);
            if (isWorkingDay(t.toLocalDate())) {
                left--;
            }
        }
        return Date.from(t.toInstant());
    }

    /** Working-day count from an ISO "PnD"; -1 when the text is not of that form. */
    public static int workingDays(String iso) {
        Matcher m = DAYS.matcher(iso == null ? "" : iso.trim());
        return m.matches() ? Integer.parseInt(m.group(1)) : -1;
    }
}
