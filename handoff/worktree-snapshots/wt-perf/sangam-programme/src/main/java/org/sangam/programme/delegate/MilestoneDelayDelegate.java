// SPDX-License-Identifier: Apache-2.0
package org.sangam.programme.delegate;

import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.sangam.programme.ProgrammeModule;
import org.sangam.programme.milestone.MilestoneService;

import java.sql.Connection;
import java.util.List;

/**
 * Milestone delay detection (daily timer): marks overdue milestones DELAYED and raises MILESTONE_DELAY alerts.
 * Inputs (optional): {@code asOfDate}, {@code limit} (default 1000). Outputs: {@code delayedMilestones}.
 */
public class MilestoneDelayDelegate extends DelegateSupport implements JavaDelegate {

    private Expression asOfDate;
    private Expression limit;

    @Override
    public void execute(DelegateExecution ex) {
        ProgrammeModule m = module();
        Connection c = connection();
        String t = tenant(ex);
        List<MilestoneService.Milestone> delayed = m.milestones().detectDelays(c, t, asOf(asOfDate, ex),
                Integer.parseInt(input(limit, ex, "limit", "1000")));
        m.variance().raiseMilestoneDelays(c, t, delayed);
        ex.setVariable("delayedMilestones", delayed.size());
    }
}
