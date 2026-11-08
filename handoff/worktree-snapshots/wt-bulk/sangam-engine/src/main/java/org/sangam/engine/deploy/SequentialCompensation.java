// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.deploy;

import java.util.Date;
import java.util.List;

import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.ExecutionListener;
import org.operaton.bpm.engine.impl.bpmn.behavior.CompensationEventActivityBehavior;
import org.operaton.bpm.engine.impl.bpmn.behavior.SubProcessActivityBehavior;
import org.operaton.bpm.engine.impl.bpmn.helper.CompensationUtil;
import org.operaton.bpm.engine.impl.bpmn.parser.CompensateEventDefinition;
import org.operaton.bpm.engine.impl.event.CompensationEventHandler;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.operaton.bpm.engine.impl.persistence.entity.EventSubscriptionEntity;
import org.operaton.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.operaton.bpm.engine.impl.pvm.delegate.ActivityExecution;
import org.operaton.bpm.engine.impl.pvm.delegate.CompositeActivityBehavior;
import org.operaton.bpm.engine.impl.pvm.process.ActivityImpl;
import org.operaton.bpm.engine.impl.pvm.runtime.PvmExecutionImpl;

/**
 * <b>SG-08 (a): compensation in reverse completion order, one handler at a time</b> (saga rule of the UP-02 pack:
 * "compensates the completed steps in reverse").
 *
 * <p>Operaton 2.2 ({@code CompensationUtil.throwCompensationEvent}) triggers <i>every</i> compensation subscription of
 * the scope in one go: wait-state handlers (user tasks, external tasks) all run in parallel, and the only ordering key
 * is the subscription's {@code created} timestamp, so steps completed in the same millisecond have no defined order.
 * Sangam fixes both through the parse / event-handler SPI (no engine patch):
 * <ul>
 *   <li>{@link OrderListener}: a built-in END listener on every compensable activity makes {@code created} strictly
 *       increasing within the scope, so it is a total completion order;</li>
 *   <li>{@link ThrowBehavior} (compensation intermediate throw and end events) and {@link SubProcessBehavior} plus
 *       {@link EventHandler} (an embedded sub-process compensated through its children) trigger only the most recently
 *       completed subscription; when its handler finishes ({@code compensationDone}), the next one is triggered, and
 *       the throw leaves after the last.</li>
 * </ul>
 */
public final class SequentialCompensation {

    private SequentialCompensation() {
    }

    /** The most recently completed subscription (latest {@code created}; ties cannot occur, see {@link OrderListener}). */
    static EventSubscriptionEntity newest(List<EventSubscriptionEntity> subs) {
        EventSubscriptionEntity best = null;
        for (EventSubscriptionEntity s : subs) {
            if (best == null || s.getCreated().after(best.getCreated())) {
                best = s;
            }
        }
        return best;
    }

    static void throwNewest(List<EventSubscriptionEntity> subs, ActivityExecution execution) {
        CompensationUtil.throwCompensationEvent(new java.util.ArrayList<>(List.of(newest(subs))), execution, false);
    }

    /** Replaces Operaton's behaviour of a compensation intermediate throw event or end event. */
    public static final class ThrowBehavior extends CompensationEventActivityBehavior {

        public ThrowBehavior(CompensateEventDefinition definition) {
            super(definition);
        }

        @Override
        public void execute(ActivityExecution execution) {
            List<EventSubscriptionEntity> subs = collectEventSubscriptions(execution);
            if (subs.isEmpty()) {
                leave(execution);
            } else {
                throwNewest(subs, execution);
            }
        }

        @Override
        public void signal(ActivityExecution execution, String signalName, Object signalData) {
            if (!((PvmExecutionImpl) execution).getNonEventScopeExecutions().isEmpty()) {
                execution.forceUpdate();
                return;
            }
            List<EventSubscriptionEntity> rest = collectEventSubscriptions(execution);
            if (rest.isEmpty()) {
                leave(execution);
            } else {
                throwNewest(rest, execution);
            }
        }
    }

    /** Embedded sub-process: when one inner handler is done, the next inner subscription is triggered. */
    public static final class SubProcessBehavior extends SubProcessActivityBehavior {

        @Override
        protected void signalCompensationDone(ActivityExecution execution) {
            if (((PvmExecutionImpl) execution).getNonEventScopeExecutions().isEmpty()
                    && execution instanceof ExecutionEntity e && !e.getCompensateEventSubscriptions().isEmpty()) {
                throwNewest(e.getCompensateEventSubscriptions(), execution);
                return;
            }
            super.signalCompensationDone(execution);
        }
    }

    /**
     * Replaces the {@code compensate} event handler only for a scope compensated through its children (Operaton
     * throws all inner subscriptions at once there); every other case is Operaton's handler unchanged.
     */
    public static final class EventHandler extends CompensationEventHandler {

        @Override
        public void handleEvent(EventSubscriptionEntity sub, Object payload, Object localPayload,
                                Object payloadToTriggeredScope, String businessKey, CommandContext commandContext) {
            ActivityImpl handler = sub.getActivity();
            if (handler == null || !handler.isScope() || handler.isCompensationHandler()) {
                super.handleEvent(sub, payload, localPayload, payloadToTriggeredScope, businessKey, commandContext);
                return;
            }
            sub.delete();
            String configuration = sub.getConfiguration();
            if (configuration == null) {
                throw new org.operaton.bpm.engine.ProcessEngineException(
                        "Compensating execution not set for compensate event subscription with id " + sub.getId());
            }
            ExecutionEntity compensating = commandContext.getExecutionManager().findExecutionById(configuration);
            compensating.setActive(true);
            if (compensating.getActivity().getActivityBehavior() instanceof CompositeActivityBehavior) {
                compensating.getParent().setActivityInstanceId(compensating.getActivityInstanceId());
            }
            List<EventSubscriptionEntity> inner = compensating.getCompensateEventSubscriptions();
            if (inner.isEmpty()) {
                CompensationUtil.throwCompensationEvent(inner, compensating, false);
            } else {
                throwNewest(inner, compensating);
            }
        }
    }

    /**
     * Built-in END listener of a compensable activity: the subscription just created for it gets a {@code created}
     * strictly after every other compensation subscription of its scope (same-millisecond completions keep their
     * order).
     */
    public static final class OrderListener implements ExecutionListener {

        public static final OrderListener INSTANCE = new OrderListener();

        @Override
        public void notify(DelegateExecution d) {
            if (!(d instanceof ExecutionEntity ex) || ex.getActivity() == null) {
                return;
            }
            ActivityImpl act = ex.getActivity();
            ActivityImpl handler = act.findCompensationHandler() != null ? act.findCompensationHandler() : act;
            PvmExecutionImpl scope = ex.findExecutionForFlowScope(act.getFlowScope());
            if (!(scope instanceof ExecutionEntity se)) {
                return;
            }
            List<EventSubscriptionEntity> subs = se.getCompensateEventSubscriptions();
            EventSubscriptionEntity mine = null;
            for (EventSubscriptionEntity s : subs) {
                if (s.getActivity() == handler || handler.getId().equals(s.getActivityId())) {
                    mine = s; // the last one for this activity is the one created by this completion
                }
            }
            if (mine == null) {
                return;
            }
            long max = Long.MIN_VALUE;
            for (EventSubscriptionEntity s : subs) {
                if (s != mine && s.getCreated() != null) {
                    max = Math.max(max, s.getCreated().getTime());
                }
            }
            if (mine.getCreated() == null || mine.getCreated().getTime() <= max) {
                mine.setCreated(new Date(max + 1));
            }
        }
    }
}
