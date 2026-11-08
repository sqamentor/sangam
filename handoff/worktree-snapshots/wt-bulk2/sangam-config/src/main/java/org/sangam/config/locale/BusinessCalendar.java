// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.locale;

import org.sangam.config.common.EffectiveDated;
import org.sangam.config.common.Tree;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A holiday / business-day calendar from configuration: weekend days, holidays, extra working days (e.g. a
 * settlement system's year-end special working days) and named daily cut-off times with next-business-day roll.
 *
 * <p>Profile shape: {@code {zone, weekend: [SATURDAY, SUNDAY], holidays: [2026-01-26, ...],
 * workingDays: [2027-03-31], cutoffs: {paymentFile: "15:00"}}}. Several calendars may be named in a profile
 * (e.g. the government calendar and a settlement-system calendar); each is one instance of this class.
 */
public final class BusinessCalendar {

    private final String id;
    private final ZoneId zone;
    private final Set<DayOfWeek> weekend;
    private final Set<LocalDate> holidays;
    private final Set<LocalDate> extraWorkingDays;
    private final Map<String, Object> cutoffs;

    public BusinessCalendar(String id, ZoneId zone, Set<DayOfWeek> weekend, Set<LocalDate> holidays,
                            Set<LocalDate> extraWorkingDays, Map<String, Object> cutoffs) {
        this.id = id;
        this.zone = zone == null ? ZoneId.of("UTC") : zone;
        this.weekend = Set.copyOf(weekend == null ? Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) : weekend);
        this.holidays = Set.copyOf(holidays == null ? Set.of() : holidays);
        this.extraWorkingDays = Set.copyOf(extraWorkingDays == null ? Set.of() : extraWorkingDays);
        this.cutoffs = cutoffs == null ? Map.of() : Map.copyOf(cutoffs);
    }

    public static BusinessCalendar from(String id, Map<String, Object> m, ZoneId defaultZone) {
        Set<DayOfWeek> wk = new HashSet<>();
        Object w = m.get("weekend");
        if (w == null) {
            wk.add(DayOfWeek.SATURDAY);
            wk.add(DayOfWeek.SUNDAY);
        } else {
            for (String s : Tree.strings(w)) {
                wk.add(DayOfWeek.valueOf(s.toUpperCase()));
            }
        }
        Object z = m.get("zone");
        return new BusinessCalendar(id, z == null ? defaultZone : ZoneId.of(z.toString()), wk, dates(m.get("holidays")),
                dates(m.get("workingDays")), Tree.map(m.get("cutoffs")));
    }

    private static Set<LocalDate> dates(Object v) {
        Set<LocalDate> out = new HashSet<>();
        if (v instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> mm) {
                    LocalDate d = EffectiveDated.date(((Map<?, ?>) mm).get("date"));
                    if (d != null) {
                        out.add(d);
                    }
                } else {
                    LocalDate d = EffectiveDated.date(o);
                    if (d != null) {
                        out.add(d);
                    }
                }
            }
        }
        return out;
    }

    public String id() {
        return id;
    }

    public ZoneId zone() {
        return zone;
    }

    public boolean isBusinessDay(LocalDate d) {
        if (extraWorkingDays.contains(d)) {
            return true;
        }
        return !weekend.contains(d.getDayOfWeek()) && !holidays.contains(d);
    }

    public LocalDate nextBusinessDay(LocalDate d) {
        LocalDate x = d.plusDays(1);
        while (!isBusinessDay(x)) {
            x = x.plusDays(1);
        }
        return x;
    }

    /** Adds n business days (n &gt;= 0); n = 0 returns the date itself if it is a business day, else the next one. */
    public LocalDate addBusinessDays(LocalDate d, int n) {
        LocalDate x = d;
        if (!isBusinessDay(x)) {
            x = nextBusinessDay(x);
        }
        for (int i = 0; i < n; i++) {
            x = nextBusinessDay(x);
        }
        return x;
    }

    public LocalTime cutoff(String name, LocalTime def) {
        Object v = cutoffs.get(name);
        return v == null ? def : LocalTime.parse(v.toString().length() == 4 ? "0" + v : v.toString());
    }

    /**
     * The business day on which a submission at {@code at} is processed under the named cut-off: the same day if it
     * is a business day and the local time is before the cut-off, otherwise the next business day.
     */
    public LocalDate processingDay(Instant at, String cutoffName, LocalTime def) {
        ZonedDateTime z = at.atZone(zone);
        LocalDate d = z.toLocalDate();
        LocalTime cut = cutoff(cutoffName, def);
        if (isBusinessDay(d) && z.toLocalTime().isBefore(cut)) {
            return d;
        }
        return nextBusinessDay(d);
    }

    /** The instant of the named cut-off on the processing day of {@code at}. */
    public Instant nextCutoffInstant(Instant at, String cutoffName, LocalTime def) {
        LocalDate d = processingDay(at, cutoffName, def);
        return LocalDateTime.of(d, cutoff(cutoffName, def)).atZone(zone).toInstant();
    }
}
