// SPDX-License-Identifier: Apache-2.0
package org.sangam.programme.delegate;

import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.sangam.programme.ProgrammeModule;
import org.sangam.programme.rollup.RollupService;

import java.sql.Connection;

/**
 * Consolidation up the hierarchy for one programme and period from {@code rootNode}: indicator rollup, financial
 * rollup and (unless {@code refreshDashboard=false}) the dashboard snapshots of the subtree. Inputs:
 * {@code programmeId}, {@code periodId} (or {@code campaignPeriodId}), {@code rootNode}. Outputs: {@code rollupNodes},
 * {@code rollupRows}, {@code dashboardNodes}.
 */
public class RollupDelegate extends DelegateSupport implements JavaDelegate {

    private Expression programmeId;
    private Expression periodId;
    private Expression rootNode;
    private Expression refreshDashboard;

    @Override
    public void execute(DelegateExecution ex) {
        ProgrammeModule m = module();
        Connection c = connection();
        String t = tenant(ex);
        String prog = required(programmeId, ex, "programmeId");
        String period = input(periodId, ex, "periodId", input(null, ex, "campaignPeriodId", null));
        if (period == null) {
            throw new org.operaton.bpm.engine.ProcessEngineException("missing input 'periodId'");
        }
        String root = required(rootNode, ex, "rootNode");
        RollupService.RollupResult r = m.rollup().rollup(c, t, prog, period, root);
        m.progress().rollupFinancial(c, t, prog, period, root);
        int dash = 0;
        if (Boolean.parseBoolean(input(refreshDashboard, ex, "refreshDashboard", "true"))) {
            dash = m.dashboard().refreshTree(c, t, prog, period, root);
        }
        ex.setVariable("rollupNodes", r.nodes());
        ex.setVariable("rollupRows", r.rows());
        ex.setVariable("dashboardNodes", dash);
    }
}
