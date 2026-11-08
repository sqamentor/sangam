// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.history;

import org.operaton.bpm.engine.delegate.DelegateCaseExecution;
import org.operaton.bpm.engine.impl.cmmn.execution.CaseExecutionState;
import org.operaton.bpm.engine.impl.history.event.HistoricCaseActivityInstanceEventEntity;
import org.operaton.bpm.engine.impl.history.event.HistoryEvent;
import org.operaton.bpm.engine.impl.history.producer.CacheAwareCmmnHistoryEventProducer;
import org.sangam.engine.tx.CommandState;

/**
 * <b>SG-10 / FLOWABLE-INTO-OPERATON D-25 (Stage B E1): CMMN history records a completed item as "terminated".</b>
 *
 * <p>Root cause (Operaton 2.2): completing a plan item fires its {@code complete} sentries <i>during</i> its own
 * complete transition. When that cascade completes the enclosing stage (a milestone reached, {@code autoComplete}),
 * the stage ends its remaining children, which still include the item being completed, before that item's own end
 * history event is produced; the end event then reads the item's state as TERMINATED. The runtime outcome is right,
 * only the engine's {@code ACT_HI_CASEACTINST} row is wrong.
 *
 * <p>Fix at the source of the row: Operaton's CMMN history producer is replaced (public configuration
 * {@code cmmnHistoryEventProducer}) by this subclass. An end event whose item was completed in this command (recorded
 * by {@code SangamCmmnTransformListener} and the task-complete history event in {@link CommandState}) is written as
 * COMPLETED. A genuine termination (no completion recorded) stays TERMINATED.
 */
public class CompletionAwareCmmnHistoryEventProducer extends CacheAwareCmmnHistoryEventProducer {

    @Override
    public HistoryEvent createCaseActivityInstanceEndEvt(DelegateCaseExecution caseExecution) {
        HistoryEvent e = super.createCaseActivityInstanceEndEvt(caseExecution);
        if (e instanceof HistoricCaseActivityInstanceEventEntity h && h.isTerminated()) {
            CommandState s = CommandState.current();
            if (s != null && s.caseTaskOutcome(transitionKey(caseExecution.getId())) != null) {
                h.setCaseActivityInstanceState(CaseExecutionState.COMPLETED.getStateCode());
            }
        }
        return e;
    }

    /**
     * Key under which only the CMMN {@code complete} transition itself is recorded (a task-history "complete" event
     * is not enough: Operaton also emits one when a case termination deletes the task).
     */
    public static String transitionKey(String caseExecutionId) {
        return "complete-transition:" + caseExecutionId;
    }

    /** Called from the built-in {@code complete} case execution listener. */
    public static void recordCompleteTransition(String caseExecutionId) {
        CommandState s = CommandState.current();
        if (s != null) {
            s.caseTaskOutcome(transitionKey(caseExecutionId), "COMPLETED");
        }
    }
}
