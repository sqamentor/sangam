// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.operaton.bpm.engine.runtime.ProcessInstance;

/**
 * Court stay / litigation hold on another case (GP-40): suspends ({@code op=SUSPEND}) or resumes ({@code op=RESUME})
 * every running instance with the business key in {@code targetBusinessKeyVar} (default {@code impugnedCaseKey}),
 * through Operaton's suspension API. Timers of a suspended instance do not fire; the statutory clock pause is recorded
 * with {@code SLA_PAUSED} / {@code SLA_RESUMED} and a legal hold marker on this case.
 */
public class SuspensionDelegate implements JavaDelegate {

    private Expression op;
    private Expression targetBusinessKeyVar;

    @Override
    public void execute(DelegateExecution ex) {
        String var = Vars.field(targetBusinessKeyVar, ex, "impugnedCaseKey");
        String bk = Vars.str(ex, var);
        boolean suspend = !"RESUME".equals(Vars.field(op, ex, "SUSPEND"));
        int n = 0;
        if (bk != null) {
            RuntimeService rs = ex.getProcessEngineServices().getRuntimeService();
            for (ProcessInstance pi : rs.createProcessInstanceQuery().processInstanceBusinessKey(bk).list()) {
                if (pi.getId().equals(ex.getProcessInstanceId())) {
                    continue;
                }
                if (suspend) {
                    rs.suspendProcessInstanceById(pi.getId());
                } else {
                    rs.activateProcessInstanceById(pi.getId());
                }
                n++;
            }
        }
        ex.setVariable("stayInForce", suspend);
        Vars.audit(ex, suspend ? "SLA_PAUSED" : "SLA_RESUMED", Vars.map("reason", "court stay", "target", bk,
                "instances", n, "order", Vars.str(ex, "stayOrderRef")));
        Vars.audit(ex, suspend ? "LEGAL_HOLD_SET" : "LEGAL_HOLD_RELEASED", Vars.map("target", bk));
    }
}
