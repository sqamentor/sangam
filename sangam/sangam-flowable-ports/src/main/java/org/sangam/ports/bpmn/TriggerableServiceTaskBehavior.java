// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.bpmn;

import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.operaton.bpm.engine.impl.bpmn.behavior.TaskActivityBehavior;
import org.operaton.bpm.engine.impl.pvm.delegate.ActivityExecution;
import org.operaton.bpm.engine.impl.util.ReflectUtil;

/**
 * Triggerable service task (FC-12, Flowable {@code flowable:triggerable="true"} + {@code TriggerableJavaDelegate}).
 * The delegate's {@code execute} runs, then the activity <em>waits</em>; {@code RuntimeService.signal(executionId,
 * signalName, signalData, variables)} (or the Sangam API) triggers it: a {@link Trigger} delegate gets
 * {@code trigger(..)}, then the activity leaves. Set by {@link BpmnExtensionsParseListener} on a service task with
 * {@code sangam:triggerable="true"}. The usual Sangam rule still applies: remote I/O belongs in external work (R9);
 * triggerable tasks are for "fire a request, wait for a callback" with the request written to the outbox.
 */
public class TriggerableServiceTaskBehavior extends TaskActivityBehavior {

    /** Optional callback for the trigger, Flowable's {@code TriggerableJavaDelegate.trigger}. */
    public interface Trigger extends JavaDelegate {
        void trigger(DelegateExecution execution, String signalName, Object signalData) throws Exception;
    }

    private final String className;
    private final Expression delegateExpression;

    public TriggerableServiceTaskBehavior(String className, Expression delegateExpression) {
        if ((className == null) == (delegateExpression == null)) {
            throw new IllegalArgumentException("triggerable service task needs exactly one of class or delegateExpression");
        }
        this.className = className;
        this.delegateExpression = delegateExpression;
    }

    @Override
    public void performExecution(ActivityExecution execution) throws Exception {
        delegate(execution).execute(execution);
        // wait state: no leave until signalled
    }

    @Override
    public void signal(ActivityExecution execution, String signalName, Object signalData) throws Exception {
        JavaDelegate d = delegate(execution);
        if (d instanceof Trigger t) {
            t.trigger(execution, signalName, signalData);
        }
        leave(execution);
    }

    private JavaDelegate delegate(ActivityExecution execution) {
        Object o = className != null ? ReflectUtil.instantiate(className) : delegateExpression.getValue(execution);
        if (o instanceof JavaDelegate d) {
            return d;
        }
        throw new ProcessEngineException("triggerable service task " + execution.getActivity().getId()
                + ": delegate " + (o == null ? "null" : o.getClass().getName()) + " is not a JavaDelegate");
    }
}
