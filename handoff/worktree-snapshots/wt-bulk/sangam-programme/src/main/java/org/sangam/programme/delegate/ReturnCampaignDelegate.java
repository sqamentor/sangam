// SPDX-License-Identifier: Apache-2.0
package org.sangam.programme.delegate;

import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.sangam.programme.ProgrammeModule;
import org.sangam.programme.config.ProgrammeConfig;
import org.sangam.programme.config.ReturnDefinition;
import org.sangam.programme.period.PeriodService;
import org.sangam.programme.returns.ReturnService;

import java.sql.Connection;
import java.time.LocalDate;

/**
 * GP-30 campaign start per period (typically on a timer start event): creates one DUE return per reporting office
 * under {@code rootNode}; with a {@code processKey} on the return definition one process instance per office is
 * started (GP-09). Inputs: {@code returnKey}, {@code rootNode}, optional {@code periodId}, {@code programmeId},
 * {@code asOfDate} and {@code periodOffset} (default -1: the period that just ended; 0: the current one). Missing
 * periods of the fiscal year are generated. Outputs: {@code campaignPeriodId}, {@code campaignOffices},
 * {@code campaignCreated}, {@code campaignProcesses}.
 */
public class ReturnCampaignDelegate extends DelegateSupport implements JavaDelegate {

    private Expression returnKey;
    private Expression rootNode;
    private Expression periodId;
    private Expression programmeId;
    private Expression asOfDate;
    private Expression periodOffset;

    @Override
    public void execute(DelegateExecution ex) {
        ProgrammeModule m = module();
        Connection c = connection();
        String t = tenant(ex);
        String key = required(returnKey, ex, "returnKey");
        String period = input(periodId, ex, "periodId", null);
        if (period == null) {
            ProgrammeConfig cfg = m.registry().config(c, t);
            ReturnDefinition def = m.registry().returnDefinition(c, t, key);
            LocalDate asOf = asOf(asOfDate, ex);
            int offset = Integer.parseInt(input(periodOffset, ex, "periodOffset", "-1"));
            LocalDate fy = PeriodService.fiscalYearStart(asOf, cfg.fiscalYearStartMonth());
            m.periods().generate(c, t, def.frequency(), fy.getYear(), cfg.fiscalYearStartMonth(), def.dueOffsetDays());
            m.periods().generate(c, t, def.frequency(), fy.getYear() - 1, cfg.fiscalYearStartMonth(), def.dueOffsetDays());
            PeriodService.Period p = m.periods().containing(c, t, def.frequency(), asOf)
                    .orElseThrow(() -> new ProcessEngineException("no period contains " + asOf));
            for (int i = 0; i > offset; i--) {
                PeriodService.Period cur = p;
                p = m.periods().previous(c, t, p).orElseThrow(() -> new ProcessEngineException("no period before " + cur.label()));
            }
            period = p.id();
        }
        ReturnService.CampaignResult r = m.returns().startCampaign(c, t, key, period, required(rootNode, ex, "rootNode"),
                input(programmeId, ex, "programmeId", null), actor(ex));
        ex.setVariable("campaignPeriodId", r.periodId());
        ex.setVariable("campaignOffices", r.offices());
        ex.setVariable("campaignCreated", r.created());
        ex.setVariable("campaignProcesses", r.processesStarted());
    }
}
