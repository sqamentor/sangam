// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.timer;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Date;
import java.util.Map;

import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.impl.calendar.DurationBusinessCalendar;
import org.sangam.core.engine.SangamClock;
import org.sangam.core.engine.WorkCalendar;

/**
 * Business calendar per timer (FC-23, D-11, R79). Operaton has one calendar per timer type; Sangam keeps the type
 * calendar and lets a duration expression name a {@link WorkCalendar}: {@code cal:<name>:<ISO duration>}, for
 * example {@code cal:mh-gov:P3D} (three working days of the Maharashtra government calendar). Untagged durations
 * keep Operaton's behaviour. An unknown calendar name fails the timer with a clear message.
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
                throw new ProcessEngineException("FC-23: malformed calendar tag in '" + duedate + "' (cal:<name>:<duration>)");
            }
            String name = duedate.substring(4, i);
            WorkCalendar cal = calendars.get(name);
            if (cal == null) {
                throw new ProcessEngineException("FC-23: unknown business calendar '" + name + "' in '" + duedate + "'");
            }
            CycleExpression d = CycleExpression.parse("R/" + duedate.substring(i + 1), zone);
            Instant start = startDate != null ? startDate.toInstant() : clock.now();
            return Date.from(cal.add(start, d.datePart(), d.timePart(), zone));
        }
        return super.resolveDuedate(duedate, startDate);
    }
}
