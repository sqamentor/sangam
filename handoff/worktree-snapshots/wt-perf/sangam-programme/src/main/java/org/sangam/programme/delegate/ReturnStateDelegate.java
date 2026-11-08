// SPDX-License-Identifier: Apache-2.0
package org.sangam.programme.delegate;

import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.sangam.programme.ProgrammeModule;
import org.sangam.programme.returns.ReturnService;
import org.sangam.programme.returns.ValidationEngine;

import java.sql.Connection;

/**
 * Moves one return through its lifecycle from a process (the per-office process of GP-09). Field {@code op}: SUBMIT,
 * VALIDATE, ACCEPT, RETURN, FREEZE or REOPEN. Inputs: {@code returnId}, {@code actor} (else the authenticated user),
 * {@code reason} (RETURN, REOPEN). Outputs: {@code returnState}, {@code returnApprovalLevel}, {@code returnAdvanced}
 * (false when SUBMIT/VALIDATE found blocking errors) and {@code returnErrorCount} / {@code returnWarningCount}, so a
 * gateway can route back to the data-entry task.
 */
public class ReturnStateDelegate extends DelegateSupport implements JavaDelegate {

    private Expression op;
    private Expression returnId;
    private Expression reason;

    @Override
    public void execute(DelegateExecution ex) {
        ProgrammeModule m = module();
        Connection c = connection();
        String t = tenant(ex);
        String id = required(returnId, ex, "returnId");
        String operation = input(op, ex, "returnOp", null);
        if (operation == null) {
            throw new ProcessEngineException("field 'op' is required");
        }
        String actor = actor(ex);
        ReturnService rs = m.returns();
        ReturnService.ReturnRow row;
        boolean advanced = true;
        long errors = 0;
        long warnings = 0;
        switch (operation.toUpperCase()) {
            case "SUBMIT" -> {
                ReturnService.Outcome o = rs.submit(c, t, id, rs.get(c, t, id).rowVersion(), actor);
                row = o.returnRow();
                advanced = o.advanced();
                errors = o.findings().stream().filter(f -> f.severity() == ValidationEngine.Severity.ERROR).count();
                warnings = o.findings().size() - errors;
            }
            case "VALIDATE" -> {
                ReturnService.Outcome o = rs.validate(c, t, id, actor);
                row = o.returnRow();
                advanced = o.advanced();
                errors = o.findings().stream().filter(f -> f.severity() == ValidationEngine.Severity.ERROR).count();
                warnings = o.findings().size() - errors;
            }
            case "ACCEPT" -> row = rs.accept(c, t, id, actor);
            case "RETURN" -> row = rs.returnForCorrection(c, t, id, required(reason, ex, "reason"), actor);
            case "FREEZE" -> row = rs.freeze(c, t, id, actor);
            case "REOPEN" -> row = rs.reopen(c, t, id, required(reason, ex, "reason"), actor);
            default -> throw new ProcessEngineException("unknown op " + operation);
        }
        ex.setVariable("returnState", row.state().name());
        ex.setVariable("returnApprovalLevel", row.approvalLevel());
        ex.setVariable("returnAdvanced", advanced);
        ex.setVariable("returnErrorCount", (int) errors);
        ex.setVariable("returnWarningCount", (int) warnings);
    }
}
