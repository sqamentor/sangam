// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.cmmn;

import org.operaton.bpm.engine.impl.cmmn.CaseControlRule;
import org.operaton.bpm.engine.impl.cmmn.execution.CmmnActivityExecution;
import org.operaton.bpm.engine.impl.cmmn.execution.CmmnExecution;

/**
 * PATCHES.md P-4 / FLOWABLE-INTO-OPERATON FC-57: repetition capped by {@code sangam:maxInstanceCount}.
 * <p>
 * Operaton 2.2.0 {@code PlanItemDefinitionActivityBehavior.evaluateRepetitionRule} reads the activity property
 * {@code ItemHandler.PROPERTY_REPETITION_RULE} (a {@link CaseControlRule}) and repeats while it returns {@code true}.
 * {@link PortsCmmnTransformListener} replaces that property with this decorator after the item handler has set it
 * (post transform listener), so no engine class is patched or subclassed.
 * <p>
 * The count of instances created so far is a local variable of the parent stage execution
 * ({@link #counterVariable}); the first instance counts as 1. A repetition is allowed only while the base rule holds
 * and the count is below the maximum; each allowed repetition increments the count.
 */
public final class MaxCountRepetitionRule implements CaseControlRule {

    /** Prefix of the parent-stage local variable holding the instance count of one plan item. */
    public static final String COUNTER_PREFIX = "_sangam_repetitions_";

    private final CaseControlRule base;
    private final int maxInstanceCount;

    public MaxCountRepetitionRule(CaseControlRule base, int maxInstanceCount) {
        if (maxInstanceCount < 1) {
            throw new IllegalArgumentException("sangam:maxInstanceCount must be at least 1, was " + maxInstanceCount);
        }
        this.base = base;
        this.maxInstanceCount = maxInstanceCount;
    }

    public static String counterVariable(String planItemId) {
        return COUNTER_PREFIX + planItemId;
    }

    public int maxInstanceCount() {
        return maxInstanceCount;
    }

    @Override
    public boolean evaluate(CmmnActivityExecution execution) {
        if (base != null && !base.evaluate(execution)) {
            return false;
        }
        CmmnExecution parent = ((CmmnExecution) execution).getParent();
        String var = counterVariable(execution.getActivity().getId());
        Object current = parent == null ? null : parent.getVariableLocal(var);
        int created = current instanceof Number n ? n.intValue() : 1;
        if (created >= maxInstanceCount) {
            return false;
        }
        if (parent != null) {
            parent.setVariableLocal(var, created + 1);
        }
        return true;
    }
}
