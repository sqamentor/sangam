// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.gov;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Date;

import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.JavaDelegate;

import org.sangam.packs.core.Vars;

/**
 * Hearing series with dates set by an outside forum (GP-51: court via LIMBS, tribunal, appellate authority). The next
 * date arrives as the variable {@code nextHearingDate} (ISO date, from the message {@code sangam.hearingDateSet} or
 * the LIMBS query); this sets {@code hearingAt} (10:30 IST on that date), {@code hearingReminderAt} (three days
 * earlier, for the brief / para-wise comments / counter-affidavit due) and increments {@code hearingCount}.
 */
public class HearingScheduleDelegate implements JavaDelegate {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Override
    public void execute(DelegateExecution ex) {
        String d = Vars.str(ex, "nextHearingDate", Vars.str(ex, "limbsNextHearing"));
        LocalDate date = d == null ? Vars.today().plusDays(30) : LocalDate.parse(d.substring(0, 10));
        Instant at = date.atTime(LocalTime.of(10, 30)).atZone(IST).toInstant();
        Instant reminder = at.minusSeconds(3 * 24 * 3600L);
        if (reminder.isBefore(Vars.now())) {
            reminder = Vars.now().plusSeconds(60);
        }
        ex.setVariable("hearingAt", Date.from(at));
        ex.setVariable("hearingReminderAt", Date.from(reminder));
        ex.setVariable("hearingCount", Vars.intVal(ex, "hearingCount", 0) + 1);
        ex.setVariable("nextHearingDate", null);
        Vars.audit(ex, "HEARING_SCHEDULED", Vars.map("date", date.toString(), "count",
                Vars.intVal(ex, "hearingCount", 1)));
    }
}
