// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.adhoc;

import java.util.List;

import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.impl.bpmn.behavior.SequentialMultiInstanceActivityBehavior;
import org.operaton.bpm.engine.impl.pvm.delegate.ActivityExecution;

/**
 * BPMN 2.0.2 §10.3.8 standard loop ({@code standardLoopCharacteristics}), patch-route item P-6 (PATCHES.md).
 *
 * <p>The loop runs as a loop body around the inner activity, built exactly like Operaton's sequential multi-instance
 * body ({@code <id>#multiInstanceBody}, type {@code multiInstanceBody}), so boundary events, activity-instance trees,
 * history and modification treat it as a sequential loop. Only the decision to run another iteration differs:
 * <ul>
 *   <li>{@code testBefore="false"} (default): the inner activity runs, then {@code loopCondition} is evaluated; the loop
 *       repeats while it is {@code true} (do-while).</li>
 *   <li>{@code testBefore="true"}: {@code loopCondition} is evaluated before each iteration, including the first
 *       (while); a false condition at entry leaves the activity without running it.</li>
 *   <li>{@code loopMaximum}: at most that many iterations; the loop ends when the maximum is reached even if the
 *       condition is still true.</li>
 * </ul>
 * {@code loopCounter} is the number of completed iterations when the condition is evaluated (0 before the first),
 * which is the BPMN "loopCounter" instance attribute. The condition must evaluate to a {@link Boolean}; anything else
 * (including null) fails the step (strict evaluation, SG-34), and a loop without a condition and without a maximum
 * is refused at deployment because it can never end.
 */
public class StandardLoopActivityBehavior extends SequentialMultiInstanceActivityBehavior {

    private final Expression loopCondition;
    private final boolean testBefore;
    private final Integer loopMaximum;

    public StandardLoopActivityBehavior(Expression loopCondition, boolean testBefore, Integer loopMaximum) {
        this.loopCondition = loopCondition;
        this.testBefore = testBefore;
        this.loopMaximum = loopMaximum;
    }

    @Override
    public void execute(ActivityExecution execution) throws Exception {
        prepareScope(execution, 0);
        setLoopVariable(execution, NUMBER_OF_ACTIVE_INSTANCES, 0);
        setLoopVariable(execution, LOOP_COUNTER, 0);
        if (mayRun(execution, 0)) {
            iterate(execution, 0);
        } else {
            leave(execution);
        }
    }

    @Override
    protected void createInstances(ActivityExecution execution, int nrOfInstances) {
        // execute() is overridden; Operaton's sequential path is not used for a standard loop
        throw new UnsupportedOperationException("standard loop instances are created by execute()");
    }

    @Override
    public void complete(ActivityExecution scopeExecution) {
        int done = getLoopVariable(scopeExecution, LOOP_COUNTER) + 1;
        setLoopVariable(scopeExecution, NUMBER_OF_COMPLETED_INSTANCES, done);
        setLoopVariable(scopeExecution, NUMBER_OF_ACTIVE_INSTANCES, 0);
        setLoopVariable(scopeExecution, LOOP_COUNTER, done);
        if (mayRun(scopeExecution, done)) {
            iterate(scopeExecution, done);
        } else {
            leave(scopeExecution);
        }
    }

    /** Whether iteration number {@code done} (0-based) may start. */
    private boolean mayRun(ActivityExecution execution, int done) {
        if (loopMaximum != null && done >= loopMaximum) {
            return false;
        }
        if (done == 0 && !testBefore) {
            return true;    // do-while: the first iteration always runs
        }
        return condition(execution);
    }

    private boolean condition(ActivityExecution execution) {
        if (loopCondition == null) {
            return true;    // bounded by loopMaximum (checked at deployment)
        }
        Object v = loopCondition.getValue(execution);
        if (v instanceof Boolean b) {
            return b;
        }
        throw new ProcessEngineException("SANGAM-LOOP-CONDITION: loopCondition of " + execution.getActivity().getId()
                + " evaluated to " + (v == null ? "null" : v.getClass().getSimpleName()) + ", expected a boolean");
    }

    private void iterate(ActivityExecution execution, int index) {
        setLoopVariable(execution, NUMBER_OF_INSTANCES, index + 1);
        setLoopVariable(execution, NUMBER_OF_ACTIVE_INSTANCES, 1);
        performInstance(execution, getInnerActivity(execution.getActivity()), index, List.of());
    }

    public Expression getLoopCondition() {
        return loopCondition;
    }

    public boolean isTestBefore() {
        return testBefore;
    }

    public Integer getLoopMaximum() {
        return loopMaximum;
    }
}
