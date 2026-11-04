// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.timer;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An ISO 8601 repeating interval {@code R[n]/[start]/<period>[/end]} (and an optional calendar tag prefix
 * {@code cal:<name>:}), evaluated with {@code java.time} instead of Operaton's {@code DurationHelper}, which adds
 * periods with a {@code GregorianCalendar} in the JVM zone and stores the start as local time without an offset
 * (D-01 root-cause candidates). Semantics (R31, A-12):
 *
 * <ul>
 *   <li><b>Elapsed cadence</b> (the period has no Y/M/W/D part, e.g. {@code PT1H}): slot k = start + k × period,
 *       computed on the UTC timeline. A DST fold or gap changes nothing: one firing per elapsed hour (fixes the E8
 *       autumn-fold runaway).</li>
 *   <li><b>Calendar cadence</b> (the period has a date part, e.g. {@code P1D}, {@code P1M}): slot k = (start in the
 *       cadence zone) + k × period, always <b>anchored on the original start</b>, so {@code P1M} from the 31st gives
 *       31 Jan, 28 Feb, 31 Mar (D-31), never drifting to the 28th. A local time that falls into a DST gap moves forward
 *       by the gap length; a local time in a fold takes the earlier offset ({@code ZonedDateTime} rules).</li>
 *   <li>A start without an offset is local time in the zone Operaton wrote it in (the JVM default zone it caches at
 *       class load, D-02); a start with an offset or {@code Z} is absolute.</li>
 *   <li>{@code Rn}: n firings in total (D-29: Operaton fires n + 1 times for {@code R3/<start>/P1M}); the count is
 *       kept by the timer guard per series. {@code R} alone repeats without bound.</li>
 * </ul>
 */
public final class CycleExpression {

    private static final Pattern PERIOD = Pattern.compile(
            "P(?:(\\d+)Y)?(?:(\\d+)M)?(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+(?:\\.\\d+)?)S)?)?");

    private final Integer times;
    private final Instant start;
    private final Period datePart;
    private final Duration timePart;
    private final Instant end;
    private final String calendarName;

    private CycleExpression(Integer times, Instant start, Period datePart, Duration timePart, Instant end,
                            String calendarName) {
        this.times = times;
        this.start = start;
        this.datePart = datePart;
        this.timePart = timePart;
        this.end = end;
        this.calendarName = calendarName;
    }

    /** True if the expression is an ISO repeating interval this class handles ({@code R...}, optionally tagged). */
    public static boolean isRepeating(String expr) {
        return expr != null && stripTag(expr.trim())[1].startsWith("R");
    }

    /**
     * Parses {@code expr}.
     *
     * @param localZone zone of a start written without an offset (Operaton's JVM default zone)
     */
    public static CycleExpression parse(String expr, ZoneId localZone) {
        String[] tagged = stripTag(expr.trim());
        String body = tagged[1];
        String[] parts = body.split("/");
        if (parts.length < 2 || !parts[0].startsWith("R")) {
            throw new IllegalArgumentException("not an ISO 8601 repeating interval: " + expr);
        }
        Integer times = parts[0].length() > 1 ? Integer.valueOf(parts[0].substring(1)) : null;
        Instant start = null;
        String periodText;
        Instant end = null;
        if (parts.length == 2) {
            periodText = parts[1];
        } else if (parts[1].startsWith("P")) {
            periodText = parts[1];
            end = instant(parts[2], localZone);
        } else {
            start = instant(parts[1], localZone);
            periodText = parts[2];
            if (parts.length > 3) {
                end = instant(parts[3], localZone);
            }
        }
        Matcher m = PERIOD.matcher(periodText);
        if (!m.matches() || periodText.equals("P") || periodText.endsWith("T")) {
            throw new IllegalArgumentException("invalid ISO 8601 period in " + expr);
        }
        int y = group(m, 1);
        int mo = group(m, 2);
        int w = group(m, 3);
        int d = group(m, 4);
        long h = group(m, 5);
        long mi = group(m, 6);
        double s = m.group(7) == null ? 0 : Double.parseDouble(m.group(7));
        Period datePart = Period.of(y, mo, w * 7 + d);
        Duration timePart = Duration.ofHours(h).plusMinutes(mi).plusMillis(Math.round(s * 1000));
        if (datePart.isZero() && timePart.isZero()) {
            throw new IllegalArgumentException("zero period in " + expr);
        }
        return new CycleExpression(times, start, datePart, timePart, end, tagged[0]);
    }

    private static String[] stripTag(String expr) {
        if (expr.startsWith("cal:")) {
            int i = expr.indexOf(':', 4);
            if (i > 4) {
                return new String[]{expr.substring(4, i), expr.substring(i + 1)};
            }
        }
        return new String[]{null, expr};
    }

    private static int group(Matcher m, int g) {
        return m.group(g) == null ? 0 : Integer.parseInt(m.group(g));
    }

    static Instant instant(String text, ZoneId localZone) {
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return ZonedDateTime.parse(text).toInstant();
            } catch (DateTimeParseException e2) {
                return LocalDateTime.parse(text).atZone(localZone).toInstant();
            }
        }
    }

    /** Elapsed cadence: the period is a fixed duration on the UTC timeline. */
    public boolean isElapsed() {
        return datePart.isZero();
    }

    /** Fixed period length for elapsed cadence; for calendar cadence, the nominal length (days × 24 h + time part). */
    public long nominalPeriodMillis() {
        long days = datePart.toTotalMonths() * 30L + datePart.getDays();
        return Duration.ofDays(days).plus(timePart).toMillis();
    }

    public Period datePart() {
        return datePart;
    }

    public Duration timePart() {
        return timePart;
    }

    public Integer times() {
        return times;
    }

    public Instant start() {
        return start;
    }

    public String calendarName() {
        return calendarName;
    }

    /**
     * The first slot strictly after {@code after}, or null when the schedule has ended ({@code end} reached).
     *
     * @param anchorIfNoStart the start to use when the expression has none (the timer's creation time)
     * @param cadenceZone     zone of calendar-cadence arithmetic
     */
    public Instant slotAfter(Instant after, Instant anchorIfNoStart, ZoneId cadenceZone) {
        Instant s = start != null ? start : anchorIfNoStart;
        Instant next;
        if (isElapsed()) {
            long p = timePart.toMillis();
            long delta = after.toEpochMilli() - s.toEpochMilli();
            long k = delta < 0 ? 0 : Math.floorDiv(delta, p) + 1;
            next = s.plusMillis(k * p);
            if (!next.isAfter(after)) {
                next = next.plusMillis(p);
            }
        } else {
            ZonedDateTime z0 = s.atZone(cadenceZone);
            // estimate k from the nominal length, then correct; anchored on the original start (D-31)
            long nominal = Math.max(1, nominalPeriodMillis());
            long k = Math.max(0, Math.floorDiv(after.toEpochMilli() - s.toEpochMilli(), nominal) - 1);
            next = slot(z0, k);
            while (!next.isAfter(after)) {
                k++;
                next = slot(z0, k);
            }
        }
        if (end != null && next.isAfter(end)) {
            return null;
        }
        return next;
    }

    private Instant slot(ZonedDateTime z0, long k) {
        Period p = Period.of(Math.multiplyExact(datePart.getYears(), (int) k),
                Math.multiplyExact(datePart.getMonths(), (int) k), Math.multiplyExact(datePart.getDays(), (int) k));
        return z0.plus(p).toInstant().plus(timePart.multipliedBy(k));
    }

    /**
     * The guard interval of a firing due at {@code due} (A-12 {@code lastFiredInterval}): for elapsed cadence the
     * index of the period on the UTC grid; for calendar cadence the local wall-clock minute in the cadence zone, so
     * the repeated local hour of an autumn fold maps to the same interval and fires once.
     */
    public long intervalOf(Instant due, ZoneId cadenceZone) {
        if (isElapsed()) {
            return Math.floorDiv(due.toEpochMilli(), Math.max(1, timePart.toMillis()));
        }
        return due.atZone(cadenceZone).toLocalDateTime().toEpochSecond(java.time.ZoneOffset.UTC) / 60;
    }
}
