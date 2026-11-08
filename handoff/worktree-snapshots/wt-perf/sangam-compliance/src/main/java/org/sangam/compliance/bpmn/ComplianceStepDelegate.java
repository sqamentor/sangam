// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.bpmn;

import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.JavaDelegate;

import java.util.Map;

/**
 * Operaton bridge for the compliance BPMN packs: {@code operaton:class="org.sangam.compliance.bpmn.ComplianceStepDelegate"}
 * with the field {@code step}. The tenant comes from {@code sangamTenantId} (set at start), else the engine tenant.
 */
public final class ComplianceStepDelegate implements JavaDelegate {

    private Expression step;

    public void setStep(Expression step) {
        this.step = step;
    }

    @Override
    public void execute(DelegateExecution execution) {
        String name = String.valueOf(step.getValue(execution));
        Object t = execution.getVariable("sangamTenantId");
        String tenant = t != null ? String.valueOf(t) : execution.getTenantId();
        Map<String, Object> out = ComplianceRuntime.steps().run(name, tenant, execution.getVariables());
        out.forEach(execution::setVariable);
    }
}
