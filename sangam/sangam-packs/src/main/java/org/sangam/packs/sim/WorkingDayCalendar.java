// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.sim;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.sangam.packs.port.BusinessCalendar;
import org.sangam.packs.port.TenantWorkingDays;

/**
 * Working-day calendar: office hours 09:30-18:00 in the tenant zone (the engine still stores UTC, A-12). Which dates
 * are working days, and the zone, come from the tenant's own configuration when a {@link TenantWorkingDays} source
 * answers for the tenant (sangam-server: the profile business calendar, SL-34); otherwise Saturday and Sunday are off,
 * plus holidays added here, in the default zone (IST, which has no DST).
 */
public class WorkingDayCalendar implements BusinessCalendar {

    private static final Pattern SPEC = Pattern.compile("^(\\d+)\\s*(WD|D|H|WH|M|MIN)$");
    private static final LocalTime OPEN = LocalTime.of(9, 30);
    private static final LocalTime CLOSE = LocalTime.of(18, 0);

    private final Map<String, Set<LocalDate>> holidays = new ConcurrentHashMap<>();
    private final Map<String, ZoneId> zones = new ConcurrentHashMap<>();
    private final ZoneId defaultZone;
    private final TenantWorkingDays tenants;

    public WorkingDayCalendar() {
        this(ZoneId.of("Asia/Kolkata"));
    }

    public WorkingDayCalendar(ZoneId defaultZone) {
        this(defaultZone, TenantWorkingDays.NONE);
    }

    /** A calendar whose working days and zone come from {@code tenants} for every tenant it answers for. */
    public WorkingDayCalendar(ZoneId defaultZone, TenantWorkingDays tenants) {
        this.defaultZone = java.util.Objects.requireNonNull(defaultZone, "defaultZone");
        this.tenants = java.util.Objects.requireNonNull(tenants, "tenants");
    }

    public void addHoliday(String tenantId, LocalDate day) {
        holidays.computeIfAbsent(key(tenantId), k -> ConcurrentHashMap.newKeySet()).add(day);
    }

    public void setZone(String tenantId, ZoneId zone) {
        zones.put(key(tenantId), zone);
    }

    private static String key(String tenantId) {
        return tenantId == null ? "" : tenantId;
    }

    /** The tenant's rule, resolved once per calendar operation. */
    private TenantWorkingDays.Days rule(String tenantId) {
        return tenants.of(tenantId).orElseGet(() -> {
            Set<LocalDate> h = holidays.getOrDefault(key(tenantId), Set.of());
            return new TenantWorkingDays.Days(zones.getOrDefault(key(tenantId), defaultZone), d -> {
                DayOfWeek w = d.getDayOfWeek();
                return w != DayOfWeek.SATURDAY && w != DayOfWeek.SUNDAY && !h.contains(d);
            });
        });
    }

    @Override
    public boolean isWorkingDay(String tenantId, Instant at) {
        TenantWorkingDays.Days r = rule(tenantId);
        return r.working().test(at.atZone(r.zone()).toLocalDate());
    }

    @Override
    public Instant due(String tenantId, Instant from, String spec) {
        if (spec == null || spec.isBlank()) {
            throw new IllegalArgumentException("SLA spec is empty");
        }
        String s = spec.trim().toUpperCase(Locale.ROOT);
        if (s.startsWith("P")) {
            String datePart = s.contains("T") ? s.substring(0, s.indexOf('T')) : s;
            if (datePart.contains("Y") || datePart.contains("M") || datePart.contains("W")) {
                // calendar period (months, years, weeks) in the tenant zone, then any time part
                java.time.Period p = java.time.Period.parse(datePart);
                Instant base = from.atZone(rule(tenantId).zone()).plus(p).toInstant();
                return s.contains("T") ? base.plus(Duration.parse("P" + s.substring(s.indexOf('T')))) : base;
            }
            return from.plus(Duration.parse(s));
        }
        Matcher m = SPEC.matcher(s);
        if (!m.matches()) {
            throw new IllegalArgumentException("unknown SLA spec: " + spec);
        }
        long n = Long.parseLong(m.group(1));
        TenantWorkingDays.Days r = rule(tenantId);
        return switch (m.group(2)) {
            case "D" -> from.plus(Duration.ofDays(n));
            case "H" -> from.plus(Duration.ofHours(n));
            case "M", "MIN" -> from.plus(Duration.ofMinutes(n));
            case "WD" -> addWorkingDays(r, from.atZone(r.zone()), n).toInstant();
            case "WH" -> addWorkingMinutes(r, from.atZone(r.zone()), n * 60).toInstant();
            default -> throw new IllegalArgumentException(spec);
        };
    }

    private static ZonedDateTime addWorkingDays(TenantWorkingDays.Days r, ZonedDateTime start, long days) {
        LocalDate d = start.toLocalDate();
        long added = 0;
        while (added < days) {
            d = d.plusDays(1);
            if (r.working().test(d)) {
                added++;
            }
        }
        return ZonedDateTime.of(d, CLOSE, start.getZone());
    }

    private static ZonedDateTime addWorkingMinutes(TenantWorkingDays.Days r, ZonedDateTime start, long minutes) {
        ZonedDateTime t = start;
        long left = minutes;
        int guard = 0;
        while (left > 0 && guard++ < 100_000) {
            LocalDate d = t.toLocalDate();
            if (!r.working().test(d) || !t.toLocalTime().isBefore(CLOSE)) {
                t = ZonedDateTime.of(d.plusDays(1), OPEN, t.getZone());
                continue;
            }
            if (t.toLocalTime().isBefore(OPEN)) {
                t = ZonedDateTime.of(d, OPEN, t.getZone());
            }
            long avail = Duration.between(t.toLocalTime(), CLOSE).toMinutes();
            long use = Math.min(avail, left);
            t = t.plusMinutes(use);
            left -= use;
        }
        return t;
    }

    @Override
    public Duration workingTimeBetween(String tenantId, Instant from, Instant to) {
        if (!to.isAfter(from)) {
            return Duration.ZERO;
        }
        TenantWorkingDays.Days r = rule(tenantId);
        ZonedDateTime a = from.atZone(r.zone());
        ZonedDateTime b = to.atZone(r.zone());
        long minutes = 0;
        LocalDate d = a.toLocalDate();
        while (!d.isAfter(b.toLocalDate())) {
            if (r.working().test(d)) {
                LocalTime s = d.equals(a.toLocalDate()) ? max(a.toLocalTime(), OPEN) : OPEN;
                LocalTime e = d.equals(b.toLocalDate()) ? min(b.toLocalTime(), CLOSE) : CLOSE;
                if (e.isAfter(s)) {
                    minutes += Duration.between(s, e).toMinutes();
                }
            }
            d = d.plusDays(1);
        }
        return Duration.ofMinutes(minutes);
    }

    private static LocalTime max(LocalTime a, LocalTime b) {
        return a.isAfter(b) ? a : b;
    }

    private static LocalTime min(LocalTime a, LocalTime b) {
        return a.isBefore(b) ? a : b;
    }
}
