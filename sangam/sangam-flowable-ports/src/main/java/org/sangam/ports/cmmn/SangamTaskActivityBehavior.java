// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.cmmn;

import org.operaton.bpm.engine.impl.cmmn.behavior.TaskActivityBehavior;
import org.operaton.bpm.engine.impl.cmmn.execution.CmmnActivityExecution;

/**
 * PATCHES.md P-5: Operaton 2.2.0 {@code TaskActivityBehavior.performStart} completes a plain CMMN {@code task} at
 * once. A task that carries {@code sangam:topic} is a case external task: it stays ACTIVE until its worker completes
 * it through {@link CaseExternalTaskService#complete}. Every other plain task keeps Operaton's behaviour.
 */
public class SangamTaskActivityBehavior extends TaskActivityBehavior {

    @Override
    protected void performStart(CmmnActivityExecution execution) {
        if (execution.getActivity().getProperty(CaseExternalTaskService.PROPERTY_TOPIC) != null) {
            return;
        }
        super.performStart(execution);
    }
}
