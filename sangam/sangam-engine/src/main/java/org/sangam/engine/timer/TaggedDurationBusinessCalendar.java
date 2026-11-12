// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.timer;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Date;
import java.util.Map;

import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.impl.ProcessEngineLogger;
import org.operaton.bpm.engine.impl.calendar.DurationBusinessCalendar;
import org.sangam.core.engine.SangamClock;
import org.sangam.core.engine.WorkCalendar;

/**
 * Business calendar per timer (FLOWABLE-INTO-OPERATON FC-23, FLOWABLE-INTO-OPERATON D-11, R79). Operaton has one calendar per timer type; Sangam keeps the type
 * calendar and lets a duration expression name a {@link WorkCalendar}: {@code cal:<name>:<ISO duration>}, for
 * example {@code cal:mh-gov:P3D} (three working days of the Maharashtra government calendar). An unknown calendar name
 * fails the timer with a clear message.
 *
 * <p>SL-53: an untagged duration with a date part ({@code P1D}, {@code P1W}, {@code P1M}, {@code P1DT2H}) adds its
 * days, weeks, months and years on the calendar of the timer zone ({@code sangam.engine.timer-zone}) and its time part
 * as elapsed time, as Operaton does in the JVM zone (which is UTC, never the business zone). Elapsed durations
 * ({@code PT5M}) and other forms keep Operaton's evaluation.
 */
public class TaggedDurationBusinessCalendar extends DurationBusinessCalendar {

    private final Map<String, WorkCalendar> calendars;
    private final SangamClock clock;
    private final ZoneId zone;

    public TaggedDurationBusinessCalendar(Map<String, WorkCalendar> calendars, SangamClock clock, ZoneId zone) {
        this.calendars = calendars;
        this.clock = clock;
        this.zone = zone;
    }

    @Override
    public Date resolveDuedate(String duedate, Date startDate) {
        if (duedate != null && duedate.startsWith("cal:")) {
            int i = duedate.indexOf(':', 4);
            if (i < 0) {
                throw new ProcessEngineException("FLOWABLE-INTO-OPERATON FC-23: malformed calendar tag in '" + duedate + "' (cal:<name>:<duration>)");
            }
            String name = duedate.substring(4, i);
            WorkCalendar cal = calendars.get(name);
            if (cal == null) {
                throw new ProcessEngineException("FLOWABLE-INTO-OPERATON FC-23: unknown business calendar '" + name + "' in '" + duedate + "'");
            }
            CycleExpression d = CycleExpression.parse("R/" + duedate.substring(i + 1), zone);
            Instant start = startDate != null ? startDate.toInstant() : clock.now();
            return Date.from(cal.add(start, d.datePart(), d.timePart(), zone));
        }
        CycleExpression d = CycleExpression.duration(duedate);
        if (d != null && !d.datePart().isZero()) {
            // SL-53: days, weeks, months and years are calendar units of the timer zone (P1D across a DST change is
            // 23 or 25 hours there), the time part is elapsed; Operaton would add them in the JVM zone, which is UTC
            Instant start = startDate != null ? startDate.toInstant() : clock.now();
            try {
                return Date.from(start.atZone(zone).plus(d.datePart()).toInstant().plus(d.timePart()));
            } catch (RuntimeException e) {
                throw ProcessEngineLogger.UTIL_LOGGER.exceptionWhileResolvingDuedate(duedate, e);
            }
        }
        // elapsed durations (PT5M) are zone-free; other forms keep Operaton's evaluation
        return super.resolveDuedate(duedate, startDate);
    }
}
