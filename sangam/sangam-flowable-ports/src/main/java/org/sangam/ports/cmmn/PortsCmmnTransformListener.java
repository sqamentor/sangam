// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.cmmn;

import org.operaton.bpm.engine.delegate.CaseExecutionListener;
import org.operaton.bpm.engine.delegate.DelegateCaseExecution;
import org.operaton.bpm.engine.impl.cmmn.model.CmmnActivity;
import org.operaton.bpm.engine.impl.cmmn.transformer.CmmnTransformListener;
import org.operaton.bpm.model.cmmn.instance.CaseTask;
import org.operaton.bpm.model.cmmn.instance.DecisionTask;
import org.operaton.bpm.model.cmmn.instance.EventListener;
import org.operaton.bpm.model.cmmn.instance.HumanTask;
import org.operaton.bpm.model.cmmn.instance.Milestone;
import org.operaton.bpm.model.cmmn.instance.PlanItem;
import org.operaton.bpm.model.cmmn.instance.ProcessTask;
import org.operaton.bpm.model.cmmn.instance.Stage;
import org.operaton.bpm.model.cmmn.instance.Task;
import org.operaton.bpm.model.cmmn.instance.TimerEventListener;
import org.operaton.bpm.model.cmmn.instance.UserEventListener;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The ports' CMMN transform listener (route 1), registered next to WS-ENGINE's
 * {@code org.sangam.engine.deploy.SangamCmmnTransformListener} (which owns FLOWABLE-INTO-OPERATON D-08 rejection, FLOWABLE-INTO-OPERATON D-25 outcome capture and
 * FLOWABLE-INTO-OPERATON D-26 cancel-on-exit). This one carries:
 * <ul>
 *   <li>FLOWABLE-INTO-OPERATON FC-55: marks event listeners ({@code timer|user|generic}) and stores the timer expression for
 *       {@link SangamEventListenerActivityBehavior}. WS-ENGINE must run with {@code rejectCmmnEventListeners=false}
 *       when this port is enabled.</li>
 *   <li>FLOWABLE-INTO-OPERATON FC-56 (feasible part): plan-item lifecycle listeners, Flowable {@code PlanItemInstanceLifecycleListener}; every
 *       CMMN transition of every plan item is offered to the registered {@link PlanItemLifecycleListener}s. The
 *       extra Flowable states (async-active, wait_repetition, unavailable, completion-neutral, parent-completion rule)
 *       need a state-machine patch and are not implemented (PATCHES.md P-4, stage E).</li>
 * </ul>
 */
public class PortsCmmnTransformListener implements CmmnTransformListener {

    /** Flowable-style plan-item lifecycle callback. Runs inside the engine transaction. */
    @FunctionalInterface
    public interface PlanItemLifecycleListener {
        void onTransition(DelegateCaseExecution planItem, String transition);
    }

    private static final List<String> ALL_EVENTS = List.of(CaseExecutionListener.CREATE, CaseExecutionListener.ENABLE,
            CaseExecutionListener.DISABLE, CaseExecutionListener.RE_ENABLE, CaseExecutionListener.START,
            CaseExecutionListener.MANUAL_START, CaseExecutionListener.COMPLETE, CaseExecutionListener.RE_ACTIVATE,
            CaseExecutionListener.TERMINATE, CaseExecutionListener.EXIT, CaseExecutionListener.PARENT_TERMINATE,
            CaseExecutionListener.SUSPEND, CaseExecutionListener.RESUME, CaseExecutionListener.PARENT_SUSPEND,
            CaseExecutionListener.PARENT_RESUME, CaseExecutionListener.OCCUR);

    private final List<PlanItemLifecycleListener> lifecycleListeners = new CopyOnWriteArrayList<>();
    private final boolean alwaysAttach;

    /** @param alwaysAttach attach the dispatch listener even when no lifecycle listener is registered at deploy time */
    public PortsCmmnTransformListener(boolean alwaysAttach) {
        this.alwaysAttach = alwaysAttach;
    }

    public void addLifecycleListener(PlanItemLifecycleListener l) {
        lifecycleListeners.add(l);
    }

    @Override
    public void transformHumanTask(PlanItem planItem, HumanTask humanTask, CmmnActivity activity) {
        common(activity);
    }

    @Override
    public void transformProcessTask(PlanItem planItem, ProcessTask processTask, CmmnActivity activity) {
        common(activity);
    }

    @Override
    public void transformCaseTask(PlanItem planItem, CaseTask caseTask, CmmnActivity activity) {
        common(activity);
    }

    @Override
    public void transformDecisionTask(PlanItem planItem, DecisionTask decisionTask, CmmnActivity activity) {
        common(activity);
    }

    @Override
    public void transformTask(PlanItem planItem, Task task, CmmnActivity activity) {
        common(activity);
    }

    @Override
    public void transformStage(PlanItem planItem, Stage stage, CmmnActivity activity) {
        common(activity);
    }

    @Override
    public void transformMilestone(PlanItem planItem, Milestone milestone, CmmnActivity activity) {
        common(activity);
    }

    @Override
    public void transformEventListener(PlanItem planItem, EventListener eventListener, CmmnActivity activity) {
        if (eventListener instanceof TimerEventListener t) {
            activity.setProperty(SangamEventListenerActivityBehavior.PROPERTY_LISTENER_KIND, "timer");
            if (t.getTimerExpression() != null) {
                String text = t.getTimerExpression().getText() != null ? t.getTimerExpression().getText() : t.getTimerExpression().getBody();
                activity.setProperty(SangamEventListenerActivityBehavior.PROPERTY_TIMER_EXPRESSION, text);
            }
        } else if (eventListener instanceof UserEventListener) {
            activity.setProperty(SangamEventListenerActivityBehavior.PROPERTY_LISTENER_KIND, "user");
        } else {
            activity.setProperty(SangamEventListenerActivityBehavior.PROPERTY_LISTENER_KIND, "generic");
        }
        common(activity);
    }

    private void common(CmmnActivity activity) {
        if (lifecycleListeners.isEmpty() && !alwaysAttach) {
            return;
        }
        CaseExecutionListener dispatch = ce -> {
            for (PlanItemLifecycleListener l : lifecycleListeners) {
                l.onTransition(ce, ce.getEventName());
            }
        };
        for (String ev : ALL_EVENTS) {
            activity.addBuiltInListener(ev, dispatch);
        }
    }
}
