// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.bpmn;

import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.impl.bpmn.behavior.BpmnActivityBehavior;
import org.operaton.bpm.engine.impl.bpmn.behavior.FlowNodeActivityBehavior;
import org.operaton.bpm.engine.impl.pvm.PvmActivity;
import org.operaton.bpm.engine.impl.pvm.delegate.ActivityExecution;
import org.operaton.bpm.engine.impl.pvm.process.ActivityImpl;
import org.operaton.bpm.engine.impl.pvm.process.ScopeImpl;
import org.operaton.bpm.engine.impl.pvm.runtime.PvmExecutionImpl;

/**
 * Terminate end event with Flowable's {@code terminateAll} / {@code terminateMultiInstance} (FLOWABLE-INTO-OPERATON FC-22), set by
 * {@link BpmnExtensionsParseListener} for {@code sangam:terminate="all"} or {@code "multiInstance"} on a terminate end
 * event. Operaton's own behaviour terminates only the enclosing scope.
 * <ul>
 *   <li>{@code all}: the whole process instance ends (all scopes, all concurrent paths), from any nesting depth.
 *       A parent process that called this one continues normally (Operaton's call-activity contract); terminating
 *       callers too is a product decision taken through the Sangam cancel API.</li>
 *   <li>{@code multiInstance}: the enclosing multi-instance body ends with all its instances and the flow continues
 *       after the multi-instance activity.</li>
 * </ul>
 */
public class TerminateExtendedBehavior extends FlowNodeActivityBehavior {

    public enum Mode { ALL, MULTI_INSTANCE }

    private final Mode mode;

    public TerminateExtendedBehavior(Mode mode) {
        this.mode = mode;
    }

    @Override
    public void execute(ActivityExecution execution) throws Exception {
        PvmExecutionImpl exec = (PvmExecutionImpl) execution;
        if (mode == Mode.ALL) {
            PvmExecutionImpl pi = exec.getProcessInstance();
            pi.deleteCascade("terminateAll end event " + execution.getActivity().getId());
            return;
        }
        ActivityImpl body = enclosingMiBody(execution.getActivity());
        PvmExecutionImpl bodyExec = (PvmExecutionImpl) execution.findExecutionForFlowScope(body);
        String bodyInstanceId = bodyExec.getActivity() == body ? bodyExec.getActivityInstanceId() : bodyExec.getParentActivityInstanceId();
        bodyExec.interrupt("terminateMultiInstance end event " + execution.getActivity().getId());
        bodyExec.setActivity(body);
        bodyExec.setActivityInstanceId(bodyInstanceId);
        new BpmnActivityBehavior().performDefaultOutgoingBehavior(bodyExec);
    }

    static ActivityImpl enclosingMiBody(PvmActivity activity) {
        ScopeImpl s = ((ActivityImpl) activity).getFlowScope();
        while (s instanceof ActivityImpl a) {
            if (a.getId().endsWith("#multiInstanceBody")) {
                return a;
            }
            s = a.getFlowScope();
        }
        throw new ProcessEngineException("terminate end event " + activity.getId() + " (multiInstance) is not inside a multi-instance activity");
    }
}
