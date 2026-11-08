// SPDX-License-Identifier: Apache-2.0
package org.sangam.resources.calendar;

import org.sangam.config.locale.BusinessCalendar;
import org.sangam.resources.support.ResourcesException;
import org.sangam.resources.support.Sql;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Opening hours of a resource (or a group of resources) in a zone, optionally bound to a business calendar from
 * sangam-config (C12 holidays and working days). Hours are per weekday as a list of {@code HH:mm-HH:mm} windows; a
 * window whose end is not after its start runs over midnight; {@code 00:00-24:00} is the whole day. Days with no entry
 * are closed; an empty map means open around the clock.
 */
public record AvailabilityCalendar(String calendarId, String name, ZoneId zone, String businessCalendarId,
                                   Map<DayOfWeek, List<Window>> hours, boolean closedOnHolidays) {

    public record Window(LocalTime from, LocalTime to, boolean toMidnight) {
        public static Window parse(String s) {
            String[] p = s.trim().split("-");
            if (p.length != 2) {
                throw ResourcesException.invalid("HOURS", "opening window must be HH:mm-HH:mm: " + s);
            }
            boolean mid = "24:00".equals(p[1].trim());
            return new Window(LocalTime.parse(p[0].trim()), mid ? LocalTime.MIDNIGHT : LocalTime.parse(p[1].trim()), mid);
        }

        @Override
        public String toString() {
            return from + "-" + (toMidnight ? "24:00" : to.toString());
        }
    }

    public AvailabilityCalendar {
        zone = zone == null ? ZoneId.of("UTC") : zone;
        Map<DayOfWeek, List<Window>> copy = new EnumMap<>(DayOfWeek.class);
        if (hours != null) {
            hours.forEach((d, w) -> copy.put(d, List.copyOf(w)));
        }
        hours = copy;
    }

    public boolean alwaysOpen() {
        return hours.isEmpty();
    }

    /** Parses {@code {zone, businessCalendarId, closedOnHolidays, hours: {MONDAY: ["09:00-17:00"], ...}}}. */
    public static AvailabilityCalendar fromMap(String id, Map<String, Object> m) {
        Map<DayOfWeek, List<Window>> h = new EnumMap<>(DayOfWeek.class);
        Map<String, Object> hm = Sql.asMap(m.get("hours"));
        for (Map.Entry<String, Object> e : hm.entrySet()) {
            List<Window> ws = new ArrayList<>();
            for (Object o : Sql.asList(e.getValue())) {
                ws.add(Window.parse(o.toString()));
            }
            String key = e.getKey().toUpperCase();
            if ("ALL".equals(key) || "DAILY".equals(key)) {
                for (DayOfWeek d : DayOfWeek.values()) {
                    h.put(d, ws);
                }
            } else {
                h.put(DayOfWeek.valueOf(key), ws);
            }
        }
        Object z = m.get("zone");
        return new AvailabilityCalendar(id, Sql.str(m.getOrDefault("name", id)), z == null ? ZoneId.of("UTC") : ZoneId.of(z.toString()),
                Sql.str(m.get("businessCalendarId")), h, Sql.bool(m.get("closedOnHolidays"), false));
    }

    public Map<String, Object> hoursJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        hours.forEach((d, ws) -> m.put(d.name(), ws.stream().map(Window::toString).toList()));
        return m;
    }

    /** Open intervals in {@code range}, before blackouts. */
    public List<Interval> openIntervals(Interval range, BusinessCalendar business) {
        if (alwaysOpen() && (business == null || !closedOnHolidays)) {
            return List.of(range);
        }
        List<Interval> out = new ArrayList<>();
        LocalDate day = range.from().atZone(zone).toLocalDate().minusDays(1);
        LocalDate last = range.to().atZone(zone).toLocalDate();
        for (; !day.isAfter(last); day = day.plusDays(1)) {
            if (closedOnHolidays && business != null && !business.isBusinessDay(day)) {
                continue;
            }
            List<Window> ws = alwaysOpen() ? List.of(new Window(LocalTime.MIDNIGHT, LocalTime.MIDNIGHT, true))
                    : hours.getOrDefault(day.getDayOfWeek(), List.of());
            for (Window w : ws) {
                ZonedDateTime s = day.atTime(w.from()).atZone(zone);
                ZonedDateTime e = w.toMidnight() || !w.to().isAfter(w.from())
                        ? day.plusDays(1).atTime(w.to()).atZone(zone)
                        : day.atTime(w.to()).atZone(zone);
                Instant from = s.toInstant().isBefore(range.from()) ? range.from() : s.toInstant();
                Instant to = e.toInstant().isAfter(range.to()) ? range.to() : e.toInstant();
                if (to.isAfter(from)) {
                    out.add(new Interval(from, to));
                }
            }
        }
        return Interval.union(out);
    }
}
