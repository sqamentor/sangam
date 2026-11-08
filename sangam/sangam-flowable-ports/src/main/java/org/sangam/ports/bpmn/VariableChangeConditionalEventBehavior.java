// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.bpmn;

import org.operaton.bpm.engine.impl.bpmn.behavior.IntermediateConditionalEventBehavior;
import org.operaton.bpm.engine.impl.bpmn.parser.ConditionalEventDefinition;
import org.operaton.bpm.engine.impl.pvm.delegate.ActivityExecution;

/**
 * Intermediate conditional catch event that is triggered only by a variable change (the event subscription), never by
 * evaluating the condition on entry: the semantics of Flowable's {@code variableListenerEventDefinition} (FW-12,
 * FLOWABLE-INTO-OPERATON FC-05). Leaving on a matching variable event is Operaton's own {@code leaveOnSatisfiedCondition}.
 */
public class VariableChangeConditionalEventBehavior extends IntermediateConditionalEventBehavior {

    public VariableChangeConditionalEventBehavior(ConditionalEventDefinition definition) {
        super(definition, false);
    }

    @Override
    public void execute(ActivityExecution execution) {
        // wait for the variable change
    }
}
