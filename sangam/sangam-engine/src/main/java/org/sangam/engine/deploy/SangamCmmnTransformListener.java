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
 *   <li><b>D-08</b>: Operaton 2.2 registers no handler for CMMN event listeners (timer/user listeners deploy but
 *       never fire). Any model with an event listener is rejected (R44) until WS-PORTS delivers FC-55.</li>
 *   <li><b>D-26</b>: exiting or terminating a blocking process task left the called BPMN process running. A case
 *       execution listener on exit / terminate / parentTerminate deletes the called process instance in the same
 *       command.</li>
 *   <li><b>D-25 support</b>: a human task's {@code complete} transition is recorded in the command state, so the
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
            throw new ProcessEngineException("D-08: CMMN event listener '" + planItem.getId() + "' is not executed by "
                    + "Operaton 2.2 (it would deploy and never fire); use a BPMN timer or a process task instead");
        }
    }

    @Override
    public void transformProcessTask(PlanItem planItem, ProcessTask processTask, CmmnActivity activity) {
        CaseExecutionListener cancelCalled = SangamCmmnTransformListener::cancelCalledProcess;
        activity.addBuiltInListener(CaseExecutionListener.EXIT, cancelCalled);
        activity.addBuiltInListener(CaseExecutionListener.TERMINATE, cancelCalled);
        activity.addBuiltInListener(CaseExecutionListener.PARENT_TERMINATE, cancelCalled);
    }

    @Override
    public void transformHumanTask(PlanItem planItem, HumanTask humanTask, CmmnActivity activity) {
        activity.addBuiltInListener(CaseExecutionListener.COMPLETE, (CaseExecutionListener) ce -> outcome(ce, "COMPLETED"));
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
                sub.deleteCascade("D-26: process task " + ce.getActivityId() + " exited or terminated");
            }
        }
    }
}
