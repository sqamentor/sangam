// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.deploy;

import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.delegate.CaseExecutionListener;
import org.operaton.bpm.engine.delegate.DelegateCaseExecution;
import org.operaton.bpm.engine.impl.cmmn.entity.runtime.CaseExecutionEntity;
import org.operaton.bpm.engine.impl.cmmn.model.CmmnActivity;
import org.operaton.bpm.engine.impl.cmmn.transformer.CmmnTransformListener;
import org.operaton.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.operaton.bpm.model.cmmn.instance.EventListener;
import org.operaton.bpm.model.cmmn.instance.HumanTask;
import org.operaton.bpm.model.cmmn.instance.PlanItem;
import org.operaton.bpm.model.cmmn.instance.ProcessTask;
import org.sangam.engine.SangamEngineSettings;
import org.sangam.engine.tx.CommandState;

/**
 * CMMN deploy-time rules and engine fixes through the transform SPI (no engine patch):
 * <ul>
 *   <li><b>FLOWABLE-INTO-OPERATON D-08</b>: Operaton 2.2 registers no handler for CMMN event listeners (timer/user listeners deploy but
 *       never fire). Any model with an event listener is rejected (R44) until WS-PORTS delivers FLOWABLE-INTO-OPERATON FC-55.</li>
 *   <li><b>FLOWABLE-INTO-OPERATON D-26</b>: exiting or terminating a blocking process task left the called BPMN process running. A case
 *       execution listener on exit / terminate / parentTerminate deletes the called process instance in the same
 *       command.</li>
 *   <li><b>FLOWABLE-INTO-OPERATON D-25 support</b>: a human task's {@code complete} transition is recorded in the command state, so the
 *       audit projection can label it COMPLETED even when the history row says "terminated".</li>
 * </ul>
 */
public class SangamCmmnTransformListener implements CmmnTransformListener {

    private final SangamEngineSettings settings;

    public SangamCmmnTransformListener(SangamEngineSettings settings) {
        this.settings = settings;
    }

    @Override
    public void transformEventListener(PlanItem planItem, EventListener eventListener, CmmnActivity activity) {
        if (settings.isRejectCmmnEventListeners()) {
            throw new ProcessEngineException("FLOWABLE-INTO-OPERATON D-08: CMMN event listener '" + planItem.getId() + "' is not executed by "
                    + "Operaton 2.2 (it would deploy and never fire); use a BPMN timer or a process task instead");
        }
    }

    @Override
    public void transformProcessTask(PlanItem planItem, ProcessTask processTask, CmmnActivity activity) {
        guardTenant(activity, "processTask " + planItem.getId());
        CaseExecutionListener cancelCalled = SangamCmmnTransformListener::cancelCalledProcess;
        activity.addBuiltInListener(CaseExecutionListener.EXIT, cancelCalled);
        activity.addBuiltInListener(CaseExecutionListener.TERMINATE, cancelCalled);
        activity.addBuiltInListener(CaseExecutionListener.PARENT_TERMINATE, cancelCalled);
        recordCompletion(activity);
    }

    @Override
    public void transformCaseTask(PlanItem planItem, org.operaton.bpm.model.cmmn.instance.CaseTask caseTask,
                                  CmmnActivity activity) {
        guardTenant(activity, "caseTask " + planItem.getId());
        recordCompletion(activity);
    }

    @Override
    public void transformDecisionTask(PlanItem planItem, org.operaton.bpm.model.cmmn.instance.DecisionTask decisionTask,
                                      CmmnActivity activity) {
        guardTenant(activity, "decisionTask " + planItem.getId());
    }

    /** SL-13: a CMMN task resolves only the caller's tenant or shared definitions ({@link TenantIsolationGuard}). */
    private static void guardTenant(CmmnActivity activity, String element) {
        if (activity.getActivityBehavior() instanceof org.operaton.bpm.engine.impl.cmmn.behavior.CallingTaskActivityBehavior b) {
            TenantIsolationGuard.guard(b.getCallableElement(), element, activity.getCaseDefinition()
                    instanceof org.operaton.bpm.engine.impl.cmmn.entity.repository.CaseDefinitionEntity d ? d.getTenantId() : null);
        }
    }

    @Override
    public void transformStage(PlanItem planItem, org.operaton.bpm.model.cmmn.instance.Stage stage,
                               CmmnActivity activity) {
        recordCompletion(activity);
    }

    /** SG-10: a plan item's complete transition is recorded so its history end row is COMPLETED (see the producer). */
    private static void recordCompletion(CmmnActivity activity) {
        activity.addBuiltInListener(CaseExecutionListener.COMPLETE, (CaseExecutionListener) ce ->
                org.sangam.engine.history.CompletionAwareCmmnHistoryEventProducer.recordCompleteTransition(ce.getId()));
    }

    @Override
    public void transformHumanTask(PlanItem planItem, HumanTask humanTask, CmmnActivity activity) {
        activity.addBuiltInListener(CaseExecutionListener.COMPLETE, (CaseExecutionListener) ce -> outcome(ce, "COMPLETED"));
        recordCompletion(activity);
        activity.addBuiltInListener(CaseExecutionListener.EXIT, (CaseExecutionListener) ce -> outcome(ce, "EXITED"));
    }

    private static void outcome(DelegateCaseExecution ce, String outcome) {
        CommandState s = CommandState.current();
        if (s != null) {
            s.caseTaskOutcome(ce.getId(), outcome);
        }
    }

    private static void cancelCalledProcess(DelegateCaseExecution execution) {
        if (execution instanceof CaseExecutionEntity ce) {
            ExecutionEntity sub = ce.getSubProcessInstance();
            if (sub != null && !sub.isEnded()) {
                sub.deleteCascade("FLOWABLE-INTO-OPERATON D-26: process task " + ce.getActivityId() + " exited or terminated");
            }
        }
    }
}
