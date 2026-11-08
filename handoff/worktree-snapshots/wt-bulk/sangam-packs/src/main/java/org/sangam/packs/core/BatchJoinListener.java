// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import java.util.List;

import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.ExecutionListener;
import org.operaton.bpm.engine.delegate.Expression;

import org.sangam.packs.port.PackRuntime;

/**
 * Start listener for items that join a batch by themselves (an application submitted on the portal joins its
 * recruitment campaign, a complaint joins a drive): registers the item in the {@link org.sangam.packs.port.BatchLedger}
 * round, so the campaign aggregates it without a multi-instance join (GP-08, GP-09). Fields {@code batchVar} (variable
 * holding the batch id, default {@code campaignId}), {@code itemVar} (default: the business key).
 */
public class BatchJoinListener implements ExecutionListener {

    private Expression batchVar;
    private Expression itemVar;

    @Override
    public void notify(DelegateExecution ex) {
        String batch = Vars.str(ex, Vars.field(batchVar, ex, "campaignId"));
        if (batch == null) {
            return;
        }
        String item = Vars.field(itemVar, ex) == null ? ex.getProcessBusinessKey() : Vars.str(ex, Vars.field(itemVar, ex));
        if (item == null) {
            item = ex.getProcessInstanceId();
        }
        int round = Vars.intVal(ex, "batchRound", 1);
        ex.setVariable("batchId", batch);
        ex.setVariable("batchRound", round);
        ex.setVariable("itemKey", item);
        PackRuntime.batches().open(Vars.tenant(ex), batch, round, List.of(item));
    }
}
