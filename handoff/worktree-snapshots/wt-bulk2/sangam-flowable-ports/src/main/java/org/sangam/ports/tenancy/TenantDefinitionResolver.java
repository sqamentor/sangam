// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.tenancy;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.repository.CaseDefinition;
import org.operaton.bpm.engine.repository.ProcessDefinition;

import java.util.Optional;

/**
 * Callee tenant handling (FLOWABLE-INTO-OPERATON FC-33; Flowable {@code fallbackToDefaultTenant}, {@code overrideProcessDefinitionTenantId},
 * {@code DefaultTenantProvider}). Operaton's {@code calledElementTenantId} is kept (better, B-6); what it lacks is the
 * fallback from a tenant to shared definitions. Resolution order: the tenant's own latest definition, then the shared
 * definition (no tenant), then (optional) a configured default tenant. Every fallback is checked by
 * {@link TenantAccess} (R19: a tenant may use shared definitions only if allowed).
 *
 * <p>BPMN use: {@code operaton:calledElementTenantId="${sangamTenants.callee('child', tenantId)}"} with this object
 * exposed as bean {@code sangamTenants}; product starts call {@link #processDefinition}.
 */
public class TenantDefinitionResolver {

    /** R19 hook owned by WS-SECURITY. */
    @FunctionalInterface
    public interface TenantAccess {
        boolean mayUse(String callerTenant, String definitionTenant);

        TenantAccess SHARED_ONLY = (caller, def) -> def == null || def.equals(caller);
    }

    private final ProcessEngine engine;
    private final String defaultTenant;
    private final TenantAccess access;

    public TenantDefinitionResolver(ProcessEngine engine, String defaultTenantOrNull, TenantAccess access) {
        this.engine = engine;
        this.defaultTenant = defaultTenantOrNull;
        this.access = access;
    }

    public ProcessDefinition processDefinition(String key, String tenant) {
        for (String t : candidates(tenant)) {
            var q = engine.getRepositoryService().createProcessDefinitionQuery().processDefinitionKey(key).latestVersion();
            if (t == null) {
                q.withoutTenantId();
            } else {
                q.tenantIdIn(t);
            }
            ProcessDefinition d = q.singleResult();
            if (d != null && access.mayUse(tenant, d.getTenantId())) {
                return d;
            }
        }
        throw new ProcessEngineException("no process definition " + key + " for tenant " + tenant + " (own, shared or default)");
    }

    public Optional<CaseDefinition> caseDefinition(String key, String tenant) {
        for (String t : candidates(tenant)) {
            var q = engine.getRepositoryService().createCaseDefinitionQuery().caseDefinitionKey(key).latestVersion();
            if (t == null) {
                q.withoutTenantId();
            } else {
                q.tenantIdIn(t);
            }
            CaseDefinition d = q.singleResult();
            if (d != null && access.mayUse(tenant, d.getTenantId())) {
                return Optional.of(d);
            }
        }
        return Optional.empty();
    }

    /** For {@code calledElementTenantId}: the tenant whose definition the call activity should use. */
    public String callee(String key, String tenant) {
        return processDefinition(key, tenant).getTenantId();
    }

    private String[] candidates(String tenant) {
        return defaultTenant == null ? new String[]{tenant, null} : new String[]{tenant, null, defaultTenant};
    }
}
