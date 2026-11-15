// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.deploy;

import java.util.ArrayList;
import java.util.List;

import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.ExecutionListener;
import org.operaton.bpm.engine.impl.bpmn.behavior.MultiInstanceActivityBehavior;
import org.operaton.bpm.engine.impl.context.Context;
import org.operaton.bpm.engine.impl.persistence.entity.EventSubscriptionEntity;
import org.operaton.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.operaton.bpm.engine.impl.pvm.process.ActivityImpl;
import org.operaton.bpm.engine.impl.pvm.process.ScopeImpl;
import org.operaton.bpm.engine.impl.pvm.runtime.PvmExecutionImpl;
import org.operaton.bpm.engine.variable.value.TypedValue;

/**
 * <b>Decision 148 (FIO D-27): a multi-instance compensation handler sees the instance it compensates.</b>
 *
 * <p><b>Root cause</b> (javap, Operaton 2.2.0). A compensation boundary event does not make its activity a scope
 * ({@code BpmnParse.parseBoundaryEvents}: only non-compensation boundary events call {@code setScope(true)}), so the
 * inner activity of a multi-instance body leaves through {@code AbstractBpmnActivityBehavior.doLeave}, which creates
 * the compensate subscription on the <i>body's</i> scope execution with no configuration. The instance's variables
 * ({@code loopCounter}, the element variable, locals) live on the instance execution (parallel: the concurrent child;
 * sequential: the body execution, overwritten by the next instance), which is removed. When compensation is thrown,
 * {@code CompensationUtil.throwCompensationEvent} creates a fresh empty execution for a subscription without
 * configuration, so every handler runs without its instance: the D-27 symptom (E16a #7, D-TENDER). For an inner
 * <i>sub-process</i>, {@code CompensationUtil.createEventScopeExecution} snapshots only the scope execution's own
 * locals; {@code loopCounter} is on its parent (the concurrent execution or the body execution) and is lost too.
 *
 * <p><b>A task that is a scope is not a sub-process.</b> Operaton makes every external task a scope
 * ({@code BpmnParse.parseExternalServiceTask} calls {@code setScope(true)}, javap 2.2.0), but only a sub-process (and
 * the multi-instance body) gets an event-scope snapshot when it ends. A scope <i>task</i> leaves through
 * {@code AbstractBpmnActivityBehavior.doLeave} exactly like a task that is no scope: its subscription is created on
 * the body's scope execution with no configuration, and its own scope execution (a child of the instance execution) is
 * removed. It therefore needs the snapshot of the first case below; the instance's variables are read from that scope
 * execution upwards. (This class used to leave every scope with a configuration-less subscription alone, so the
 * handlers of an external task, the D-TENDER shape itself, still ran without {@code loopCounter}.)
 *
 * <p><b>Fix</b> (built-in END listener on the inner activity of every multi-instance body, added by
 * {@link SangamBpmnParseListener}): when an instance completes (not when it is cancelled), the variables of the
 * instance, read from the ending execution up to the body's scope execution (the nearest execution wins), are kept
 * with the subscription that completion created, the way Operaton keeps a scope's snapshot:
 * <ul>
 *   <li>inner task (a scope or not, never a sub-process): a snapshot <i>event-scope</i> execution under the body's
 *       scope execution (activity = the inner activity, inactive, not concurrent, the instance's variables as local
 *       copies) becomes the
 *       subscription's configuration; Operaton's own {@code throwCompensationEvent} then reuses it as the compensating
 *       execution (moves it under the throwing execution, clears the event-scope flag), so the handler runs in the
 *       instance's context. While the body runs, the snapshot counts like Operaton's own event-scope executions of
 *       scope instances (inactive, in the inner activity) in {@code ParallelMultiInstanceActivityBehavior
 *       .allExecutionsEnded}; when the body ends, {@code createEventScopeExecution} moves it under the body's event
 *       scope with the subscription;</li>
 *   <li>inner sub-process: the variables the snapshot lacks are added to the event-scope execution Operaton created
 *       (existing values are never replaced). A sub-process whose subscription has no configuration (its own
 *       compensation handler, no compensable child) keeps Operaton's behaviour.</li>
 * </ul>
 * Only completed instances have a subscription, so only they are compensated, each exactly once (the subscription is
 * deleted when handled). Instances completed before this listener existed keep Operaton's behaviour (their variables
 * were removed and cannot be recovered). Compensation runs the modelled handler only; it never reverses a posted or
 * settled financial transaction by itself.
 */
public final class MultiInstanceCompensationContext implements ExecutionListener {

    public static final MultiInstanceCompensationContext INSTANCE = new MultiInstanceCompensationContext();

    private MultiInstanceCompensationContext() {
    }

    /** True for an activity whose completion can create a compensate subscription inside a multi-instance body. */
    static boolean applies(ActivityImpl inner) {
        ScopeImpl flowScope = inner.getFlowScope();
        return flowScope instanceof ActivityImpl body
                && body.getActivityBehavior() instanceof MultiInstanceActivityBehavior
                && (inner.findCompensationHandler() != null || inner.isSubProcessScope());
    }

    @Override
    public void notify(DelegateExecution d) {
        if (!(d instanceof ExecutionEntity ex) || ex.getActivity() == null || ex.isCanceled()) {
            return;
        }
        ActivityImpl act = ex.getActivity();
        if (!applies(act)) {
            return;
        }
        PvmExecutionImpl scope = ex.findExecutionForFlowScope(act.getFlowScope());
        if (!(scope instanceof ExecutionEntity body)) {
            return;
        }
        EventSubscriptionEntity sub = newestFor(body, act);
        if (sub == null) {
            return;
        }
        List<ExecutionEntity> chain = chain(ex, body);
        if (sub.getConfiguration() == null) {
            if (act.isSubProcessScope()) {
                return; // a sub-process with a configuration-less subscription: Operaton's behaviour unchanged
            }
            // a task, whether Operaton made it a scope (every external task) or not: no snapshot exists yet
            ExecutionEntity snapshot = body.createExecution();
            snapshot.setActivity(act);
            snapshot.setActive(false);
            snapshot.setConcurrent(false);
            snapshot.setEventScope(true);
            copyMissing(chain, snapshot);
            sub.setConfiguration(snapshot.getId());
        } else {
            ExecutionEntity eventScope = Context.getCommandContext().getExecutionManager()
                    .findExecutionById(sub.getConfiguration());
            if (eventScope != null && eventScope.isEventScope()) {
                copyMissing(chain, eventScope);
            }
        }
    }

    /** The compensate subscription this completion created: the last one of the activity's handler on the scope. */
    static EventSubscriptionEntity newestFor(ExecutionEntity body, ActivityImpl act) {
        ActivityImpl handler = act.findCompensationHandler() != null ? act.findCompensationHandler() : act;
        EventSubscriptionEntity newest = null;
        for (EventSubscriptionEntity s : body.getCompensateEventSubscriptions()) {
            if (s.getActivity() == handler || handler.getId().equals(s.getActivityId()) || s.getActivity() == act) {
                if (newest == null || s.getCreated() == null || newest.getCreated() == null
                        || !s.getCreated().before(newest.getCreated())) {
                    newest = s;
                }
            }
        }
        return newest;
    }

    /** The ending execution, its parents up to and including the body's scope execution (nearest first). */
    static List<ExecutionEntity> chain(ExecutionEntity from, ExecutionEntity body) {
        List<ExecutionEntity> out = new ArrayList<>();
        for (ExecutionEntity e = from; e != null; e = e.getParent()) {
            out.add(e);
            if (e == body) {
                return out;
            }
        }
        return List.of(from); // the body is not an ancestor: only the instance's own variables
    }

    /** Copies each variable the target lacks, nearest execution first, keeping its typed (serialized) form. */
    static void copyMissing(List<ExecutionEntity> chain, ExecutionEntity target) {
        for (ExecutionEntity e : chain) {
            for (String name : e.getVariableNamesLocal()) {
                if (!target.hasVariableLocal(name)) {
                    TypedValue v = e.getVariableLocalTyped(name, false);
                    target.setVariableLocal(name, v);
                }
            }
        }
    }
}
