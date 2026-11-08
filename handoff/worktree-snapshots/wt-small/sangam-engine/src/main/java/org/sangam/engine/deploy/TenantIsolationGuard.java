// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.deploy;

import java.util.Objects;

import org.operaton.bpm.engine.delegate.DelegateCaseExecution;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.VariableScope;
import org.operaton.bpm.engine.impl.core.model.BaseCallableElement;
import org.operaton.bpm.engine.impl.core.variable.mapping.value.ParameterValueProvider;

/**
 * Tenant isolation for every element that resolves another definition by tenant (SL-13; SANGAM.md section 10 "Tenant A
 * cannot execute or inspect tenant B's definitions", AC-09, J-P2-E09 "cross-tenant referral is a message to the other
 * tenant's inbox, not shared rows"): BPMN call activities, BPMN business rule tasks, CMMN process, case and decision
 * tasks. Operaton resolves an explicit {@code calledElementTenantId} / {@code decisionRefTenantId} /
 * {@code processTenantId} / {@code caseTenantId} / {@code decisionTenantId} without any tenant check, so a model could
 * start a process, case or decision of another tenant. The guard admits only the caller's own tenant and shared
 * definitions (no tenant), which is exactly what the sanctioned resolver
 * ({@code org.sangam.ports.tenancy.TenantDefinitionResolver}, own then shared) returns.
 */
public final class TenantIsolationGuard implements ParameterValueProvider {

    private final ParameterValueProvider delegate;
    private final String element;
    /** Tenant of the definition that declares the element (its deployment's tenant); {@code null} when shared. */
    private final String ownerTenant;

    private TenantIsolationGuard(ParameterValueProvider delegate, String element, String ownerTenant) {
        this.delegate = delegate;
        this.element = element;
        this.ownerTenant = ownerTenant;
    }

    /**
     * Wraps the element's explicit tenant-id provider; elements without one resolve in the caller's tenant already.
     *
     * @param ownerTenant tenant of the declaring definition, known at parse time; it decides resolutions made outside
     *                    an execution (static analysis), where there is no caller tenant
     */
    public static void guard(BaseCallableElement callable, String element, String ownerTenant) {
        if (callable == null || callable.getTenantIdProvider() == null
                || callable.getTenantIdProvider() instanceof TenantIsolationGuard) {
            return;
        }
        callable.setTenantIdProvider(new TenantIsolationGuard(callable.getTenantIdProvider(), element, ownerTenant));
    }

    @Override
    public Object getValue(VariableScope scope) {
        Object value = delegate.getValue(scope);
        String target = value == null ? null : value.toString();
        if (target == null || target.isEmpty()) {
            return value; // shared definition
        }
        // Outside an execution (static analysis: RepositoryService#getStaticCalledProcessDefinitions, reachable through
        // /engine-rest) there is no caller. Operaton's own tenant check there applies only to an engine-authenticated
        // user, so without one another tenant's definition was returned (SL-45); the declaring definition's tenant
        // decides instead, and the static analysis of such a model is refused (fail closed).
        String caller = scope instanceof DelegateExecution || scope instanceof DelegateCaseExecution
                ? callerTenant(scope) : ownerTenant;
        if (!Objects.equals(target, caller)) {
            throw new TenantIsolationException(element, caller, target);
        }
        return value;
    }

    @Override
    public boolean isDynamic() {
        return delegate.isDynamic();
    }

    private static String callerTenant(VariableScope scope) {
        if (scope instanceof DelegateExecution e) {
            return e.getTenantId();
        }
        return ((DelegateCaseExecution) scope).getTenantId();
    }
}
