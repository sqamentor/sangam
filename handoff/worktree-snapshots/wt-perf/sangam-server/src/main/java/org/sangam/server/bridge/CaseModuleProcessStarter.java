// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;

import org.operaton.bpm.engine.RuntimeService;
import org.sangam.casefile.ports.CaseFilePorts;
import org.sangam.programme.ports.ProgrammePorts;

/**
 * The case module's {@code ProcessStarter} and the programme module's {@code CampaignStarter} on the engine
 * {@link RuntimeService}. The modules call it inside their engine transaction ({@code EngineTx}), so the start joins
 * that command and the product row and the process instance commit together (Q1). The tenant's own definition is
 * preferred; a shared (tenant-less) definition is used when the tenant has none.
 */
public final class CaseModuleProcessStarter implements CaseFilePorts.ProcessStarter, ProgrammePorts.CampaignStarter {

    private final RuntimeService runtime;
    private final org.operaton.bpm.engine.RepositoryService repository;

    public CaseModuleProcessStarter(RuntimeService runtime, org.operaton.bpm.engine.RepositoryService repository) {
        this.runtime = runtime;
        this.repository = repository;
    }

    @Override
    public String start(Connection c, String tenantId, String processKey, String businessKey, Map<String, Object> variables) {
        if (processKey == null || processKey.isBlank()) {
            return null;
        }
        Map<String, Object> vars = new LinkedHashMap<>(variables == null ? Map.of() : variables);
        vars.putIfAbsent("sangamTenantId", tenantId);
        vars.putIfAbsent("sangamBusinessKey", businessKey);
        boolean tenantOwned = tenantId != null && repository.createProcessDefinitionQuery().processDefinitionKey(processKey)
                .tenantIdIn(tenantId).latestVersion().count() > 0;
        var builder = runtime.createProcessInstanceByKey(processKey).businessKey(businessKey).setVariables(vars);
        builder = tenantOwned ? builder.processDefinitionTenantId(tenantId) : builder.processDefinitionWithoutTenantId();
        return builder.execute().getId();
    }
}
