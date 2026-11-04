// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.deploy;

import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.ExecutionListener;

/**
 * Runtime half of the GP-09 / D-23 rule: when a multi-instance inner activity starts, {@code nrOfInstances} (set by
 * the multi-instance body from the evaluated cardinality or collection) must not exceed the configured maximum.
 * Above it the command fails with a clear message (a job turns into an incident), instead of the n-squared completion
 * measured in E16a (1,732 of 5,000 done in 180 s).
 */
public final class MultiInstanceSizeGuard implements ExecutionListener {

    private final int max;

    public MultiInstanceSizeGuard(int max) {
        this.max = max;
    }

    @Override
    public void notify(DelegateExecution execution) {
        Object n = execution.getVariable("nrOfInstances");
        if (n instanceof Number num && num.longValue() > max) {
            throw new ProcessEngineException("GP-09: multi-instance '" + execution.getCurrentActivityId() + "' would create "
                    + num + " instances (> " + max + "); start one process instance per item and aggregate instead");
        }
    }
}
