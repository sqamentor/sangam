// SPDX-License-Identifier: Apache-2.0
package org.sangam.programme.delegate;

import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.sangam.programme.ProgrammeModule;
import org.sangam.programme.returns.ReturnService;

import java.sql.Connection;
import java.time.LocalDate;
import java.util.List;

/**
 * Defaulter lists and reminders (C15), typically on a daily timer: sends the scheduled reminders and raises DEFAULTER
 * alerts for overdue returns. Inputs (optional): {@code returnKey}, {@code periodId}, {@code asOfDate}, {@code limit}
 * (default 500). Outputs: {@code remindersSent}, {@code defaulterCount}, {@code defaulterOrgUnits} (comma list, first
 * 100).
 */
public class DefaulterReminderDelegate extends DelegateSupport implements JavaDelegate {

    private Expression returnKey;
    private Expression periodId;
    private Expression asOfDate;
    private Expression limit;

    @Override
    public void execute(DelegateExecution ex) {
        ProgrammeModule m = module();
        Connection c = connection();
        String t = tenant(ex);
        LocalDate asOf = asOf(asOfDate, ex);
        int max = Integer.parseInt(input(limit, ex, "limit", "500"));
        int sent = m.returns().sendReminders(c, t, asOf, max);
        List<ReturnService.ReturnRow> defaulters = m.returns().defaulters(c, t, input(returnKey, ex, "returnKey", null),
                input(periodId, ex, "periodId", null), asOf, max);
        m.variance().raiseDefaulters(c, t, defaulters, asOf);
        ex.setVariable("remindersSent", sent);
        ex.setVariable("defaulterCount", defaulters.size());
        ex.setVariable("defaulterOrgUnits", String.join(",", defaulters.stream().limit(100).map(ReturnService.ReturnRow::orgUnit).toList()));
    }
}
