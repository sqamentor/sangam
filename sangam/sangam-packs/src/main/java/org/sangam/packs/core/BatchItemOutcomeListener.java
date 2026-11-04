// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import java.util.HashMap;
import java.util.Map;

import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.ExecutionListener;
import org.operaton.bpm.engine.delegate.Expression;

import org.sangam.packs.port.PackRuntime;

/**
 * End-event listener of a per-item child process (GP-09 pattern): records the item's terminal outcome in the
 * {@link org.sangam.packs.port.BatchLedger} in the child's own transaction. Fields: {@code outcome} (SUCCESS, FAILED,
 * REJECTED, ...), {@code reasonVar} (variable with the reason code), {@code dataVars} (comma list of variables stored
 * as item data, for example scores for the merit list GP-26), {@code roundVar} (variable holding the round to record
 * in, default {@code batchRound}; the score round for GP-47).
 */
public class BatchItemOutcomeListener implements ExecutionListener {

    private Expression outcome;
    private Expression reasonVar;
    private Expression dataVars;
    private Expression roundVar;

    @Override
    public void notify(DelegateExecution ex) {
        String batchId = Vars.str(ex, "batchId");
        String item = Vars.str(ex, "itemKey");
        if (batchId == null || item == null) {
            return; // started standalone, not as part of a batch
        }
        Map<String, Object> data = new HashMap<>();
        for (String n : Vars.csv(Vars.field(dataVars, ex))) {
            data.put(n, Vars.canonical(ex.getVariable(n)));
        }
        String rv = Vars.field(reasonVar, ex, "opReasonCode");
        PackRuntime.batches().record(Vars.tenant(ex), batchId,
                Vars.intVal(ex, Vars.field(roundVar, ex, "batchRound"), Vars.intVal(ex, "batchRound", 1)), item,
                Vars.field(outcome, ex, "SUCCESS"), Vars.str(ex, rv), data);
    }
}
