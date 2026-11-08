// SPDX-License-Identifier: Apache-2.0
package org.sangam.programme.delegate;

import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.sangam.programme.progress.VarianceService;

import java.util.List;

/**
 * Variance alerts for one programme and period (achievement against the pro-rata approved target). Inputs:
 * {@code programmeId}, {@code periodId}, optional {@code asOfDate}. Outputs: {@code varianceAlerts} (new alerts) and
 * {@code varianceCritical} (true when one of them is CRITICAL), for an escalation branch.
 */
public class VarianceDelegate extends DelegateSupport implements JavaDelegate {

    private Expression programmeId;
    private Expression periodId;
    private Expression asOfDate;

    @Override
    public void execute(DelegateExecution ex) {
        List<VarianceService.Alert> alerts = module().variance().checkVariance(connection(), tenant(ex), required(programmeId, ex, "programmeId"),
                required(periodId, ex, "periodId"), asOf(asOfDate, ex));
        ex.setVariable("varianceAlerts", alerts.size());
        ex.setVariable("varianceCritical", alerts.stream().anyMatch(a -> a.severity() == VarianceService.Severity.CRITICAL));
    }
}
