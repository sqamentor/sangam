// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.bpmn;

import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.impl.bpmn.behavior.BpmnActivityBehavior;
import org.operaton.bpm.engine.impl.bpmn.behavior.UserTaskActivityBehavior;
import org.operaton.bpm.engine.impl.pvm.delegate.ActivityBehavior;
import org.operaton.bpm.engine.impl.pvm.delegate.ActivityExecution;
import org.operaton.bpm.engine.impl.pvm.delegate.SignallableActivityBehavior;
import org.sangam.ports.history.EngineEventBus;

import java.util.Map;

/**
 * Skip expressions (FC-13, Flowable {@code flowable:skipExpression} + {@code SkipExpressionUtil}). An activity with
 * {@code sangam:skipExpression="${...}"} is left through its outgoing flows without doing its work when
 * <ol>
 *   <li>skipping is enabled for the instance: process variable {@code _SANGAM_SKIP_EXPRESSION_ENABLED == true}
 *       (Flowable's {@code _FLOWABLE_SKIP_EXPRESSION_ENABLED} is accepted too), or the engine option
 *       {@code skipExpressionsAlwaysEnabled}; and</li>
 *   <li>the expression evaluates to {@code true}.</li>
 * </ol>
 * Each skip is published as engine event {@code activity.skip} (R42 audit). User tasks use a
 * {@link UserTaskActivityBehavior} subclass, so Operaton's migration and task code still recognise them; other
 * supported tasks use a delegating wrapper. Sub-processes, call activities, gateways, events and external tasks are
 * not wrappable and are rejected by the validator (wrapping scope/MI behaviours "needs care", gap list FC-13).
 */
public final class SkipExpressionSupport {

    public static final String ENABLE_VAR = "_SANGAM_SKIP_EXPRESSION_ENABLED";
    public static final String FLOWABLE_ENABLE_VAR = "_FLOWABLE_SKIP_EXPRESSION_ENABLED";

    private SkipExpressionSupport() {
    }

    static boolean shouldSkip(ActivityExecution execution, Expression skip, boolean alwaysEnabled, EngineEventBus bus) {
        boolean enabled = alwaysEnabled || Boolean.TRUE.equals(execution.getVariable(ENABLE_VAR))
                || Boolean.TRUE.equals(execution.getVariable(FLOWABLE_ENABLE_VAR));
        if (!enabled) {
            return false;
        }
        Object v = skip.getValue(execution);
        if (v != null && !(v instanceof Boolean)) {
            throw new ProcessEngineException("skipExpression of " + execution.getActivity().getId() + " must be boolean, got " + v);
        }
        boolean result = Boolean.TRUE.equals(v);
        if (result && bus != null && bus.hasListeners()) {
            bus.dispatch(new EngineEventBus.EngineEvent("activity.skip", execution.getTenantId(), execution.getProcessInstanceId(),
                    null, execution.getActivity().getId(), null, Map.of("expression", skip.getExpressionText())));
        }
        return result;
    }

    /** User task with a skip expression; remains a {@link UserTaskActivityBehavior}. */
    public static class SkippableUserTask extends UserTaskActivityBehavior {
        private final Expression skip;
        private final boolean alwaysEnabled;
        private final EngineEventBus bus;

        public SkippableUserTask(UserTaskActivityBehavior original, Expression skip, boolean alwaysEnabled, EngineEventBus bus) {
            super(original.getTaskDecorator());
            this.skip = skip;
            this.alwaysEnabled = alwaysEnabled;
            this.bus = bus;
        }

        @Override
        public void performExecution(ActivityExecution execution) {
            if (shouldSkip(execution, skip, alwaysEnabled, bus)) {
                leave(execution);
            } else {
                super.performExecution(execution);
            }
        }
    }

    /** Delegating wrapper for service/send/script/business-rule/receive/manual/plain tasks. */
    public static class SkippableActivity implements SignallableActivityBehavior {
        private final ActivityBehavior inner;
        private final Expression skip;
        private final boolean alwaysEnabled;
        private final EngineEventBus bus;
        private final BpmnActivityBehavior bpmn = new BpmnActivityBehavior();

        public SkippableActivity(ActivityBehavior inner, Expression skip, boolean alwaysEnabled, EngineEventBus bus) {
            this.inner = inner;
            this.skip = skip;
            this.alwaysEnabled = alwaysEnabled;
            this.bus = bus;
        }

        public ActivityBehavior inner() {
            return inner;
        }

        @Override
        public void execute(ActivityExecution execution) throws Exception {
            if (shouldSkip(execution, skip, alwaysEnabled, bus)) {
                bpmn.performDefaultOutgoingBehavior(execution);
            } else {
                inner.execute(execution);
            }
        }

        @Override
        public void signal(ActivityExecution execution, String signalEvent, Object signalData) throws Exception {
            if (inner instanceof SignallableActivityBehavior s) {
                s.signal(execution, signalEvent, signalData);
            } else {
                throw new ProcessEngineException("activity " + execution.getActivity().getId() + " cannot be signalled");
            }
        }
    }
}
