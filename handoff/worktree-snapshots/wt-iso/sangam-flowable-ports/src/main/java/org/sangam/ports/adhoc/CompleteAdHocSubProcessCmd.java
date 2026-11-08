// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.adhoc;

import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.impl.interceptor.Command;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.operaton.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.operaton.bpm.engine.impl.pvm.PvmActivity;

/**
 * Completes one ad-hoc sub-process instance (Flowable {@code RuntimeService.completeAdhocSubProcess}).
 * {@code cancelRemaining=false} fails when inner work is still active.
 */
public class CompleteAdHocSubProcessCmd implements Command<Void> {

    private final String processInstanceId;
    private final String adHocActivityInstanceId;
    private final boolean cancelRemaining;

    public CompleteAdHocSubProcessCmd(String processInstanceId, String adHocActivityInstanceId, boolean cancelRemaining) {
        if (processInstanceId == null || adHocActivityInstanceId == null) {
            throw new IllegalArgumentException("processInstanceId and adHocActivityInstanceId are required");
        }
        this.processInstanceId = processInstanceId;
        this.adHocActivityInstanceId = adHocActivityInstanceId;
        this.cancelRemaining = cancelRemaining;
    }

    @Override
    public Void execute(CommandContext commandContext) {
        ExecutionEntity scope = findScopeExecution(commandContext, processInstanceId, adHocActivityInstanceId);
        PvmActivity adHoc = AdHocSupport.adHocActivityOf(scope);
        ((AdHocSubProcessActivityBehavior) adHoc.getActivityBehavior()).completeScope(scope, cancelRemaining);
        return null;
    }

    /** The scope execution of the ad-hoc activity instance. */
    static ExecutionEntity findScopeExecution(CommandContext cc, String processInstanceId, String adHocActivityInstanceId) {
        for (ExecutionEntity e : cc.getExecutionManager().findExecutionsByProcessInstanceId(processInstanceId)) {
            if (!e.isScope() || e.getActivity() == null) {
                continue;
            }
            PvmActivity a = e.getActivity();
            if (AdHocSupport.isAdHoc(a) && adHocActivityInstanceId.equals(e.getActivityInstanceId())) {
                return e;
            }
            if (!AdHocSupport.isAdHoc(a) && adHocActivityInstanceId.equals(e.getParentActivityInstanceId())
                    && a.getFlowScope() instanceof PvmActivity fs && AdHocSupport.isAdHoc(fs)) {
                return e;
            }
        }
        throw new ProcessEngineException("no active ad-hoc sub-process instance " + adHocActivityInstanceId
                + " in process instance " + processInstanceId);
    }
}
