// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.delegate.DelegateTask;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.TaskListener;

/**
 * Binds an approval to the material payload hash (VI.4 rule 5, K4, AC-02).
 *
 * <p>On a task completion whose decision is in {@code approveValues}: computes the hash over {@code materialFields};
 * if the screen sent {@code presentedPayloadHash} (the hash of what the officer saw) and it differs, the completion is
 * refused with {@code 409 APPROVAL_INVALIDATED}; otherwise {@code approvedPayloadHash}, {@code approvedCaseVersion}
 * and {@code approvedBy} are stored and {@code APPROVAL_RECORDED} written. Mode {@code JOINT} (J-P1-E09) requires every
 * joint approval to carry the same hash as the first one.
 */
public class ApprovalBindingListener implements TaskListener {

    private Expression decisionVar;
    private Expression approveValues;
    private Expression mode;

    @Override
    public void notify(DelegateTask t) {
        Object decision = t.getVariable(Vars.field(decisionVar, t, "decision"));
        if (decision == null || !Vars.csv(Vars.field(approveValues, t, "approve")).contains(decision.toString())) {
            return;
        }
        TaskActor a = TaskActor.of(t);
        String hash = Vars.materialHash(t);
        String presented = Vars.str(t, "presentedPayloadHash");
        if (presented != null && !presented.equalsIgnoreCase(hash)) {
            Vars.auditTask(t, "APPROVAL_INVALIDATED", a.person(), a.seat(), null, a.authorityBasis(),
                    Vars.map("reason", "presented hash differs from the current case", "presented", presented,
                            "current", hash));
            throw new ProcessEngineException("409 APPROVAL_INVALIDATED: the case changed since it was shown");
        }
        boolean joint = "JOINT".equalsIgnoreCase(Vars.field(mode, t, "SINGLE"));
        String existing = Vars.str(t, Vars.APPROVED_HASH);
        if (joint && existing != null && !existing.equals(hash)) {
            throw new ProcessEngineException("409 APPROVAL_INVALIDATED: joint approvals must bind the same payload");
        }
        t.getExecution().setVariable(Vars.APPROVED_HASH, hash);
        t.getExecution().setVariable("approvedPayload", Vars.toJson(Vars.materialPayload(t)));
        t.getExecution().setVariable("approvalInvalidated", false);
        t.getExecution().setVariable("approvedCaseVersion", Vars.intVal(t, Vars.CASE_VERSION, 1));
        t.getExecution().setVariable("approvedBy", a.person());
        t.getExecution().setVariable("approvedBySeat", a.seat());
        t.getExecution().setVariable("approvedAt", java.util.Date.from(Vars.now()));
        Vars.auditTask(t, "APPROVAL_RECORDED", a.person(), a.seat(), a.actedFor() ? a.seat() : null,
                a.authorityBasis(), Vars.map("payloadHash", hash, "taskVersion",
                        Vars.intVal(t, Vars.CASE_VERSION, 1), "ruleVersion", Vars.str(t, "approvalRuleVersion"),
                        "mode", joint ? "JOINT" : "SINGLE"));
    }
}
