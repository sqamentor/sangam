// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.sql.Connection;
import java.util.Map;

import org.operaton.bpm.engine.ProcessEngine;
import org.sangam.compliance.ports.CompliancePorts;

/**
 * {@link CompliancePorts.WorkflowStarter} on the engine: starts the compliance pack's processes (rights requests,
 * breaches, incidents, lawful requests). The caller's transaction is the engine transaction
 * ({@code Transactions.of(engineAdapter)}), so the start commits with the compliance rows. A tenant-specific
 * definition is preferred; otherwise the shared ({@code tenantId = null}) pack definition is used.
 */
public final class ComplianceWorkflowStarter implements CompliancePorts.WorkflowStarter {

    private final ProcessEngine engine;

    public ComplianceWorkflowStarter(ProcessEngine engine) {
        this.engine = engine;
    }

    @Override
    public String start(Connection c, String tenantId, String processKey, String businessKey, Map<String, Object> variables) {
        boolean tenantDefinition = tenantId != null && engine.getRepositoryService().createProcessDefinitionQuery()
                .processDefinitionKey(processKey).tenantIdIn(tenantId).latestVersion().count() > 0;
        var builder = engine.getRuntimeService().createProcessInstanceByKey(processKey);
        builder = tenantDefinition ? builder.processDefinitionTenantId(tenantId) : builder.processDefinitionWithoutTenantId();
        return builder.businessKey(businessKey).setVariables(variables == null ? Map.of() : variables).execute().getId();
    }
}
